package com.safari.practice.repository;

import com.safari.practice.domain.Payout;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PayoutRepository extends JpaRepository<Payout, String> {

    /** Lock the row to apply callback idempotently. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payout p WHERE p.payoutId = :id")
    Optional<Payout> findByIdForUpdate(@Param("id") String id);

    /** Recovery query: find pending payouts so either instance can reconcile. */
    @Query("SELECT p FROM Payout p WHERE p.status = 'PENDING'")
    List<Payout> findAllPending();
}
