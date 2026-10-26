package com.safari.practice.service;

import com.safari.practice.domain.LedgerEntry;
import com.safari.practice.domain.Transfer;
import com.safari.practice.domain.Wallet;
import com.safari.practice.exception.WalletException;
import com.safari.practice.repository.LedgerEntryRepository;
import com.safari.practice.repository.TransferRepository;
import com.safari.practice.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private final WalletRepository      walletRepo;
    private final TransferRepository    transferRepo;
    private final LedgerEntryRepository ledgerRepo;

    /**
     * Moves {@code amount} santim from {@code fromId} to {@code toId}.
     * Uses READ_COMMITTED + pessimistic row locks in consistent ID order
     * (lower ID first) to be deadlock-free across two instances.
     *
     * The entire operation is a single atomic DB transaction:
     * no observer ever sees debit without credit.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Transfer transfer(String fromId, String toId, long amount, String reference) {

        // ── Validation ──────────────────────────────────────────────────
        if (amount < 1 || amount > 5_000_000) {
            throw WalletException.validationFailed("amount must be 1..5,000,000");
        }
        if (reference == null || reference.isBlank() || reference.length() > 64) {
            throw WalletException.validationFailed("reference must be 1..64 characters");
        }
        if (fromId.equals(toId)) {
            throw WalletException.sameWallet(fromId);
        }

        // ── Acquire locks in consistent order → deadlock-free ───────────
        Wallet first, second;
        boolean fromIsFirst = fromId.compareTo(toId) < 0;

        if (fromIsFirst) {
            first  = lockWallet(fromId);
            second = lockWallet(toId);
        } else {
            first  = lockWallet(toId);
            second = lockWallet(fromId);
        }

        Wallet sender   = fromIsFirst ? first  : second;
        Wallet receiver = fromIsFirst ? second : first;

        // ── Business checks ─────────────────────────────────────────────
        if (!sender.isActive()) {
            throw WalletException.walletNotActive(fromId);
        }
        if (!receiver.isActive()) {
            throw WalletException.walletNotActive(toId);
        }
        if (sender.available() < amount) {
            throw WalletException.insufficientFunds(fromId, sender.available(), amount);
        }

        // ── Apply the transfer ──────────────────────────────────────────
        sender.setBalance(sender.getBalance() - amount);
        receiver.setBalance(receiver.getBalance() + amount);

        String transferId = "T-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();

        Transfer tx = new Transfer();
        tx.setTransferId(transferId);
        tx.setFromWallet(fromId);
        tx.setToWallet(toId);
        tx.setAmount(amount);
        tx.setReference(reference);
        tx.setStatus("COMPLETED");
        tx.setCreatedAt(Instant.now());

        walletRepo.save(sender);
        walletRepo.save(receiver);
        transferRepo.save(tx);

        // ── Ledger entries ──────────────────────────────────────────────
        writeLedger(fromId, "TRANSFER_OUT", -amount, sender.getBalance(), transferId);
        writeLedger(toId,   "TRANSFER_IN",   amount, receiver.getBalance(), transferId);

        log.info("Transfer {} completed: {} -> {} amount={}", transferId, fromId, toId, amount);
        return tx;
    }

    /**
     * Reverses a completed transfer. Locks the transfer row first to block
     * concurrent reversal attempts; exactly one wins.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Transfer reverse(String transferId, String reason) {

        Transfer tx = transferRepo.findByIdForUpdate(transferId)
                .orElseThrow(() -> WalletException.transferNotFound(transferId));

        if ("REVERSED".equals(tx.getStatus())) {
            throw WalletException.alreadyReversed(transferId);
        }
        if (!"COMPLETED".equals(tx.getStatus())) {
            throw WalletException.notReversible(transferId);
        }

        // Debit the original receiver, credit the original sender.
        // original to_wallet must be ACTIVE; original from_wallet may be FROZEN.
        Wallet debited, credited;
        boolean toIsFirst = tx.getToWallet().compareTo(tx.getFromWallet()) < 0;
        if (toIsFirst) {
            debited  = lockWallet(tx.getToWallet());
            credited = lockWallet(tx.getFromWallet());
        } else {
            credited = lockWallet(tx.getFromWallet());
            debited  = lockWallet(tx.getToWallet());
        }

        if (!debited.isActive()) {
            throw WalletException.walletNotActive(tx.getToWallet());
        }
        if (debited.available() < tx.getAmount()) {
            throw WalletException.insufficientFunds(tx.getToWallet(), debited.available(), tx.getAmount());
        }

        debited.setBalance(debited.getBalance() - tx.getAmount());
        credited.setBalance(credited.getBalance() + tx.getAmount());

        String reversalId = "R-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        tx.setStatus("REVERSED");
        tx.setReversalId(reversalId);
        tx.setReversedAt(Instant.now());

        walletRepo.save(debited);
        walletRepo.save(credited);
        transferRepo.save(tx);

        writeLedger(tx.getToWallet(),   "TRANSFER_OUT", -tx.getAmount(), debited.getBalance(),  reversalId);
        writeLedger(tx.getFromWallet(), "TRANSFER_IN",   tx.getAmount(), credited.getBalance(), reversalId);

        log.info("Transfer {} reversed ({})", transferId, reversalId);
        return tx;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Wallet lockWallet(String id) {
        return walletRepo.findByIdForUpdate(id)
                .orElseThrow(() -> WalletException.walletNotFound(id));
    }

    private void writeLedger(String walletId, String type, long amount, long balanceAfter, String ref) {
        LedgerEntry e = new LedgerEntry();
        e.setEntryId("E-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase());
        e.setWalletId(walletId);
        e.setType(type);
        e.setAmount(amount);
        e.setBalanceAfter(balanceAfter);
        e.setReference(ref);
        e.setCreatedAt(Instant.now());
        ledgerRepo.save(e);
    }
}
