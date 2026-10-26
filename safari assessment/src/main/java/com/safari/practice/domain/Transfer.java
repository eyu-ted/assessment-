package com.safari.practice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "transfers")
@Getter
@Setter
@NoArgsConstructor
public class Transfer {

    @Id
    @Column(name = "transfer_id", length = 30)
    private String transferId;

    @Column(name = "from_wallet", length = 20, nullable = false)
    private String fromWallet;

    @Column(name = "to_wallet", length = 20, nullable = false)
    private String toWallet;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "reference", length = 64, nullable = false)
    private String reference;

    @Column(name = "status", length = 15, nullable = false)
    private String status;   // COMPLETED | REVERSED

    @Column(name = "reversal_id", length = 30)
    private String reversalId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "reversed_at")
    private Instant reversedAt;
}
