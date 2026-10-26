package com.safari.practice.repository;

import com.safari.practice.domain.Transfer;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TransferRepository extends JpaRepository<Transfer, String> {

    /** Lock the transfer row to prevent concurrent reversals. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transfer t WHERE t.transferId = :id")
    Optional<Transfer> findByIdForUpdate(@Param("id") String id);
}
