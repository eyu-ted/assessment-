package com.safari.practice.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /mpesa/internal/reset
 * Deletes all data and restores seed (spec section II).
 * Used by end-to-end tests before every scenario.
 */
@RestController
@RequestMapping("/mpesa/internal")
@RequiredArgsConstructor
public class InternalController {

    private final JdbcTemplate jdbc;

    @PostMapping("/reset")
    public ResponseEntity<String> reset() {
        // Delete in FK-safe order
        jdbc.execute("DELETE FROM ledger_entries");
        jdbc.execute("DELETE FROM idempotency_keys");
        jdbc.execute("DELETE FROM payouts");
        jdbc.execute("DELETE FROM transfers");
        jdbc.execute("DELETE FROM wallets");

        // Re-seed W001–W100 ACTIVE
        jdbc.execute("""
            INSERT INTO wallets (wallet_id, status, is_merchant, balance, reserved)
            SELECT 'W' || LPAD(i::TEXT, 3, '0'), 'ACTIVE', FALSE, 1000000, 0
            FROM generate_series(1,100) AS i
        """);

        // W101–W105 FROZEN
        jdbc.execute("""
            INSERT INTO wallets (wallet_id, status, is_merchant, balance, reserved)
            SELECT 'W' || LPAD(i::TEXT, 3, '0'), 'FROZEN', FALSE, 1000000, 0
            FROM generate_series(101,105) AS i
        """);

        // M001 merchant
        jdbc.execute("""
            INSERT INTO wallets (wallet_id, status, is_merchant, balance, reserved)
            VALUES ('M001', 'ACTIVE', TRUE, 50000000, 0)
        """);

        return ResponseEntity.ok("reset complete");
    }
}
