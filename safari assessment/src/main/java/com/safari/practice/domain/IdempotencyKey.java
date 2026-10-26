package com.safari.practice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "idempotency_keys")
@Getter
@Setter
@NoArgsConstructor
public class IdempotencyKey {

    @Id
    @Column(name = "idem_key", length = 64)
    private String idemKey;

    @Column(name = "endpoint", length = 50, nullable = false)
    private String endpoint;

    @Column(name = "body_hash", length = 64, nullable = false)
    private String bodyHash;

    /**
     * IN_PROGRESS while the first request is executing; DONE once it completes.
     */
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt = Instant.now().plusSeconds(86400);
}
