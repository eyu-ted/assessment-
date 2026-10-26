package com.safari.practice.repository;

import com.safari.practice.domain.LedgerEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, String> {

    /**
     * Cursor-based pagination: return entries older than the cursor snapshot timestamp,
     * ordered newest-first. Stable snapshot via (created_at, entry_id) composite cursor.
     */
    @Query("""
            SELECT e FROM LedgerEntry e
             WHERE e.walletId = :walletId
               AND (e.createdAt < :cursorTs
                   OR (e.createdAt = :cursorTs AND e.entryId < :cursorId))
             ORDER BY e.createdAt DESC, e.entryId DESC
            """)
    List<LedgerEntry> findPage(
            @Param("walletId") String walletId,
            @Param("cursorTs") Instant cursorTs,
            @Param("cursorId") String cursorId,
            Pageable pageable);

    @Query("""
            SELECT e FROM LedgerEntry e
             WHERE e.walletId = :walletId
             ORDER BY e.createdAt DESC, e.entryId DESC
            """)
    List<LedgerEntry> findFirstPage(@Param("walletId") String walletId, Pageable pageable);
}
