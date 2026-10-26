package com.safari.practice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safari.practice.exception.WalletException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Database-backed idempotency layer (safe across two instances).
 *
 * Strategy:
 *  1. INSERT INTO idempotency_keys ... ON CONFLICT (idem_key) DO NOTHING.
 *  2. If 1 row affected:
 *       Caller owns this request and proceeds to execute action.
 *       On completion, UPDATE status to DONE and save response.
 *  3. If 0 rows affected:
 *       Key already claimed. Query row:
 *       - IN_PROGRESS -> 409 REQUEST_IN_PROGRESS
 *       - Hash mismatch -> 409 IDEMPOTENCY_CONFLICT
 *       - DONE -> Replay cached response
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper;

    public ResponseEntity<Object> execute(
            String key,
            String endpoint,
            Object body,
            Supplier<ResponseEntity<Object>> action) {

        String hash = sha256(toJson(body));

        // Atomic insert: returns 1 if this request claimed the key, 0 if conflict
        int inserted = jdbcTemplate.update(
                "INSERT INTO idempotency_keys (idem_key, endpoint, body_hash, status, created_at, expires_at) " +
                "VALUES (?, ?, ?, 'IN_PROGRESS', NOW(), NOW() + INTERVAL '24 hours') " +
                "ON CONFLICT (idem_key) DO NOTHING",
                key, endpoint, hash
        );

        if (inserted == 1) {
            return executeAndStore(key, action);
        }

        // Another request claimed this key
        Map<String, Object> row;
        try {
            row = jdbcTemplate.queryForMap(
                    "SELECT status, body_hash, response_status, response_body FROM idempotency_keys WHERE idem_key = ?",
                    key
            );
        } catch (EmptyResultDataAccessException e) {
            // Very rare race if key expired/deleted: treat as in progress
            throw WalletException.requestInProgress(key);
        }

        String existingHash = (String) row.get("body_hash");
        if (existingHash != null && !existingHash.equals(hash)) {
            throw WalletException.idempotencyConflict(key);
        }

        String status = (String) row.get("status");
        if ("IN_PROGRESS".equals(status)) {
            throw WalletException.requestInProgress(key);
        }

        // DONE: Replay cached response
        int respStatus = ((Number) row.get("response_status")).intValue();
        String respBody = (String) row.get("response_body");
        return replay(respStatus, respBody);
    }

    private ResponseEntity<Object> executeAndStore(
            String key, Supplier<ResponseEntity<Object>> action) {
        try {
            ResponseEntity<Object> result = action.get();
            markDone(key, result.getStatusCode().value(), toJson(result.getBody()));
            return result;
        } catch (WalletException ex) {
            markDone(key, ex.getHttpStatus().value(),
                    toJson(new ErrorBody(ex.getErrorCode(), ex.getMessage())));
            throw ex;
        } catch (Exception ex) {
            deleteKey(key);
            throw ex;
        }
    }

    private void markDone(String key, int status, String body) {
        jdbcTemplate.update(
                "UPDATE idempotency_keys SET status = 'DONE', response_status = ?, response_body = ? WHERE idem_key = ?",
                status, body, key
        );
    }

    private void deleteKey(String key) {
        jdbcTemplate.update("DELETE FROM idempotency_keys WHERE idem_key = ?", key);
    }

    private ResponseEntity<Object> replay(int status, String bodyJson) {
        try {
            Object body = mapper.readValue(bodyJson, Object.class);
            return ResponseEntity.status(status).body(body);
        } catch (Exception e) {
            log.error("Failed to deserialise cached idempotency body", e);
            throw WalletException.serviceUnavailable();
        }
    }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); }
        catch (Exception e) { return obj == null ? "null" : obj.toString(); }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    record ErrorBody(String error, String message) {}
}
