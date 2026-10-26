package com.safari.practice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "ledger_entries")
@Getter
@Setter
@NoArgsConstructor
public class LedgerEntry {

    @Id
    @Column(name = "entry_id", length = 30)
    private String entryId;

    @Column(name = "wallet_id", length = 20, nullable = false)
    private String walletId;

    /** TRANSFER_IN | TRANSFER_OUT | PAYOUT */
    @Column(name = "type", length = 20, nullable = false)
    private String type;

    /** Positive for credits, negative for debits (in santim). */
    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "reference", length = 30, nullable = false)
    private String reference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
