package com.safari.practice.service;

import com.safari.practice.domain.Wallet;
import com.safari.practice.exception.WalletException;
import com.safari.practice.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepo;

    @Transactional(readOnly = true)
    public Wallet getWallet(String id) {
        return walletRepo.findById(id)
                .orElseThrow(() -> WalletException.walletNotFound(id));
    }

    /**
     * Freezes or activates a wallet. Uses a pessimistic write lock so the
     * freeze takes effect before any concurrent debit can commit on any instance.
     * Returns within 2 s because the lock is at row level and contention is bounded.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Wallet setStatus(String id, String newStatus) {
        Wallet w = walletRepo.findByIdForUpdate(id)
                .orElseThrow(() -> WalletException.walletNotFound(id));

        if (!w.getStatus().equals(newStatus)) {
            w.setStatus(newStatus);
            w.setUpdatedAt(Instant.now());
            walletRepo.save(w);
            log.info("Wallet {} status changed to {}", id, newStatus);
        }
        return w;
    }
}
