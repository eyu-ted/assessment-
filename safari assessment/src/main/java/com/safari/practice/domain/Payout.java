package com.safari.practice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "payouts")
@Getter
@Setter
@NoArgsConstructor
public class Payout {

    @Id
    @Column(name = "payout_id", length = 30)
    private String payoutId;

    @Column(name = "wallet_id", length = 20, nullable = false)
    private String walletId;

    @Column(name = "bank_account", length = 50, nullable = false)
    private String bankAccount;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "status", length = 15, nullable = false)
    private String status;   // PENDING | SUCCEEDED | FAILED

    @Column(name = "bank_ref", length = 100)
    private String bankRef;

    @Column(name = "conflict", nullable = false)
    private boolean conflict;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() { this.updatedAt = Instant.now(); }

    public boolean isFinal() {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status);
    }
}
