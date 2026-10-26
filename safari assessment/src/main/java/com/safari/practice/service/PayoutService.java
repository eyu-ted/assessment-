package com.safari.practice.service;

import com.safari.practice.bank.BankClient;
import com.safari.practice.bank.BankPayoutResponse;
import com.safari.practice.domain.LedgerEntry;
import com.safari.practice.domain.Payout;
import com.safari.practice.domain.Wallet;
import com.safari.practice.exception.WalletException;
import com.safari.practice.repository.LedgerEntryRepository;
import com.safari.practice.repository.PayoutRepository;
import com.safari.practice.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutService {

    private final WalletRepository      walletRepo;
    private final PayoutRepository      payoutRepo;
    private final LedgerEntryRepository ledgerRepo;
    private final BankClient            bankClient;

    // ── 5.4 Accept payout ─────────────────────────────────────────────────

    /**
     * Phase 1 (DB transaction): validate, reserve funds, persist payout as PENDING.
     * Returns immediately (well within 500 ms p95).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Payout accept(String walletId, String bankAccount, long amount) {

        if (amount < 1 || amount > 5_000_000) {
            throw WalletException.validationFailed("amount must be 1..5,000,000");
        }

        Wallet w = walletRepo.findByIdForUpdate(walletId)
                .orElseThrow(() -> WalletException.walletNotFound(walletId));

        if (!w.isActive()) throw WalletException.walletNotActive(walletId);
        if (w.available() < amount) throw WalletException.insufficientFunds(walletId, w.available(), amount);

        // Reserve: lower available, keep balance
        w.setReserved(w.getReserved() + amount);
        walletRepo.save(w);

        String payoutId = "P-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();

        Payout p = new Payout();
        p.setPayoutId(payoutId);
        p.setWalletId(walletId);
        p.setBankAccount(bankAccount);
        p.setAmount(amount);
        p.setStatus("PENDING");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        payoutRepo.save(p);

        log.info("Payout {} accepted (amount={}, wallet={})", payoutId, amount, walletId);
        return p;
    }

    /**
     * Phase 2 (async, off the request thread): call the bank and finalise.
     * Annotated @Async so it runs in a separate thread pool,
     * keeping the Tomcat request thread free.
     */
    @Async("payoutExecutor")
    public void submitToBank(String payoutId) {
        Optional<BankPayoutResponse> resp = Optional.empty();
        try {
            Payout p = payoutRepo.findById(payoutId).orElse(null);
            if (p == null || p.isFinal()) return;

            resp = bankClient.submitPayout(payoutId, p.getBankAccount(), p.getAmount());
        } catch (Exception ex) {
            log.error("Error submitting payout {} to bank", payoutId, ex);
        }

        if (resp.isPresent()) {
            String result = resp.get().getResult();
            if ("ACCEPTED".equals(result)) {
                log.info("Bank accepted payout {}, waiting for callback", payoutId);
                // Callback will finalise; recovery scheduler is the safety net
            } else if ("REJECTED".equals(result)) {
                finalisePayout(payoutId, "FAILED", null, false);
            }
        } else {
            // Timeout / unknown – recovery scheduler polls GET /bank/payouts/{id}
            log.warn("Payout {} outcome unknown after bank timeout", payoutId);
        }
    }

    // ── 5.5 Apply callback ────────────────────────────────────────────────

    /**
     * Applies a bank callback exactly once regardless of duplicates / ordering.
     * Uses a pessimistic row lock so concurrent callbacks serialise.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void applyCallback(String payoutId, String status, String bankRef) {
        Payout p = payoutRepo.findByIdForUpdate(payoutId)
                .orElseThrow(() -> WalletException.payoutNotFound(payoutId));

        if (p.isFinal()) {
            // Spec: contradicting a final status returns 200 and sets conflict:true
            if (!p.getStatus().equals(status)) {
                p.setConflict(true);
                payoutRepo.save(p);
            }
            return;
        }

        applyFinalStatus(p, status, bankRef);
    }

    // ── Recovery scheduler ────────────────────────────────────────────────

    /**
     * Runs every 30 s on both instances. Polls the bank for any PENDING payout
     * that is still unresolved. Safe to run concurrently: the pessimistic lock
     * in applyCallback prevents double-finalisation.
     */
    @Scheduled(fixedDelayString = "${payout.recovery-interval-ms:30000}")
    public void recoverPendingPayouts() {
        List<Payout> pending = payoutRepo.findAllPending();
        if (pending.isEmpty()) return;
        log.info("Recovery: {} pending payouts", pending.size());
        for (Payout p : pending) {
            try {
                bankClient.getPayoutStatus(p.getPayoutId()).ifPresent(resp -> {
                    String s = resp.getStatus();
                    if ("SUCCEEDED".equals(s)) {
                        finalisePayout(p.getPayoutId(), "SUCCEEDED", resp.getBank_ref(), false);
                    } else if ("FAILED".equals(s) || "REJECTED".equals(s)) {
                        finalisePayout(p.getPayoutId(), "FAILED", null, false);
                    }
                    // IN_PROGRESS or NOT_FOUND → keep waiting
                });
            } catch (Exception ex) {
                log.warn("Recovery failed for payout {}: {}", p.getPayoutId(), ex.getMessage());
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Finalise a payout with a new transaction so callback handler can call this
     * inside its own transaction too.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void finalisePayout(String payoutId, String status, String bankRef, boolean conflict) {
        payoutRepo.findByIdForUpdate(payoutId).ifPresent(p -> {
            if (p.isFinal()) return; // already done by another thread
            applyFinalStatus(p, status, bankRef);
            if (conflict) p.setConflict(true);
        });
    }

    private void applyFinalStatus(Payout p, String status, String bankRef) {
        p.setStatus(status);
        if (bankRef != null) p.setBankRef(bankRef);
        p.setUpdatedAt(Instant.now());
        payoutRepo.save(p);

        if ("SUCCEEDED".equals(status)) {
            // Lower balance AND release reservation in one UPDATE
            walletRepo.commitPayout(p.getWalletId(), p.getAmount());
            writeLedger(p.getWalletId(), "PAYOUT", -p.getAmount(), p.getPayoutId());
            log.info("Payout {} SUCCEEDED", p.getPayoutId());
        } else {
            // Release reservation only
            walletRepo.releaseReservation(p.getWalletId(), p.getAmount());
            log.info("Payout {} FAILED – reservation released", p.getPayoutId());
        }
    }

    private void writeLedger(String walletId, String type, long amount, String ref) {
        // We need the post-commit balance; read it back
        Wallet w = walletRepo.findById(walletId).orElse(null);
        if (w == null) return;

        LedgerEntry e = new LedgerEntry();
        e.setEntryId("E-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase());
        e.setWalletId(walletId);
        e.setType(type);
        e.setAmount(amount);
        e.setBalanceAfter(w.getBalance());
        e.setReference(ref);
        e.setCreatedAt(Instant.now());
        ledgerRepo.save(e);
    }

    @Transactional(readOnly = true)
    public Payout getPayout(String id) {
        return payoutRepo.findById(id)
                .orElseThrow(() -> WalletException.payoutNotFound(id));
    }
}
