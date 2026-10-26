package com.safari.practice.repository;

import com.safari.practice.domain.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, String> {

    /** Pessimistic write lock – used in transfer and payout paths. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.walletId = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") String id);

    @Modifying
    @Query(value = """
            UPDATE wallets
               SET balance  = balance  - :amount,
                   reserved = reserved + :amount,
                   updated_at = NOW()
             WHERE wallet_id = :id
               AND status   = 'ACTIVE'
               AND (balance - reserved) >= :amount
            """, nativeQuery = true)
    int reserveFunds(@Param("id") String walletId, @Param("amount") long amount);

    @Modifying
    @Query(value = """
            UPDATE wallets
               SET balance  = balance  - :amount,
                   reserved = reserved - :amount,
                   updated_at = NOW()
             WHERE wallet_id = :id
            """, nativeQuery = true)
    int commitPayout(@Param("id") String walletId, @Param("amount") long amount);

    @Modifying
    @Query(value = """
            UPDATE wallets
               SET reserved = reserved - :amount,
                   updated_at = NOW()
             WHERE wallet_id = :id
            """, nativeQuery = true)
    int releaseReservation(@Param("id") String walletId, @Param("amount") long amount);
}
