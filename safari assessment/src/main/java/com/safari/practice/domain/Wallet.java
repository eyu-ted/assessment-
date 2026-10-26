package com.safari.practice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "wallets")
@Getter
@Setter
@NoArgsConstructor
public class Wallet {

    @Id
    @Column(name = "wallet_id", length = 20)
    private String walletId;

    @Column(name = "status", length = 10, nullable = false)
    private String status;   // ACTIVE | FROZEN

    @Column(name = "is_merchant", nullable = false)
    private boolean merchant;

    /**
     * Total balance in santim (never negative).
     * balance = available + reserved
     */
    @Column(name = "balance", nullable = false)
    private long balance;

    /**
     * Amount reserved for pending payouts.
     * available = balance - reserved  (always >= 0).
     */
    @Column(name = "reserved", nullable = false)
    private long reserved;

    @Version
    @Column(name = "version")
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() { this.updatedAt = Instant.now(); }

    public long available() {
        return balance - reserved;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
