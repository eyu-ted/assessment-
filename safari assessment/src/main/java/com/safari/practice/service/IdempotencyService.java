package com.safari.practice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safari.practice.domain.IdempotencyKey;
import com.safari.practice.exception.WalletException;
import com.safari.practice.repository.IdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Database-backed idempotency layer (safe across two instances).
 *
 * Strategy:
 *  1. Try INSERT the key with status=IN_PROGRESS in its own transaction.
 *  2. If INSERT succeeds   → we own this request; execute the supplier.
 *  3. If INSERT fails (PK) → key already exists; read it:
 *       a. DONE            → replay cached response
 *       b. IN_PROGRESS     → 409 REQUEST_IN_PROGRESS
 *       c. Body hash diff  → 409 IDEMPOTENCY_CONFLICT
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyKeyRepository repo;
    private final ObjectMapper             mapper;

    /**
     * @param key      the Idempotency-Key header value
     * @param endpoint short endpoint name for logging
     * @param body     raw request body (used for hash)
     * @param action   the supplier that executes and returns the ResponseEntity
     */
    public ResponseEntity<Object> execute(
            String key,
            String endpoint,
            Object body,
            Supplier<ResponseEntity<Object>> action) {

        String hash = sha256(toJson(body));

        // Step 1: try to claim the key
        Optional<IdempotencyKey> existing = tryInsert(key, endpoint, hash);

        if (existing.isEmpty()) {
            // We own the request – execute
            return executeAndStore(key, hash, action);
        }

        IdempotencyKey record = existing.get();

        // Body mismatch → 409 IDEMPOTENCY_CONFLICT
        if (!record.getBodyHash().equals(hash)) {
            throw WalletException.idempotencyConflict(key);
        }

        // Still in progress → 409 REQUEST_IN_PROGRESS
        if ("IN_PROGRESS".equals(record.getStatus())) {
            throw WalletException.requestInProgress(key);
        }

        // Done → replay
        return replay(record);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<IdempotencyKey> tryInsert(String key, String endpoint, String hash) {
        try {
            IdempotencyKey ik = new IdempotencyKey();
            ik.setIdemKey(key);
            ik.setEndpoint(endpoint);
            ik.setBodyHash(hash);
            ik.setStatus("IN_PROGRESS");
            repo.saveAndFlush(ik);
            return Optional.empty(); // we inserted, we own it
        } catch (DataIntegrityViolationException e) {
            return repo.findById(key); // someone else owns it
        }
    }

    private ResponseEntity<Object> executeAndStore(
            String key, String bodyHash, Supplier<ResponseEntity<Object>> action) {
        try {
            ResponseEntity<Object> result = action.get();
            markDone(key, result.getStatusCode().value(), toJson(result.getBody()));
            return result;
        } catch (WalletException ex) {
            // Business errors are also cached so replays return the same code
            markDone(key, ex.getHttpStatus().value(),
                    toJson(new ErrorBody(ex.getErrorCode(), ex.getMessage())));
            throw ex;
        } catch (Exception ex) {
            // Unexpected – don't cache; delete the key so client can retry
            deleteKey(key);
            throw ex;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDone(String key, int status, String body) {
        repo.findById(key).ifPresent(ik -> {
            ik.setStatus("DONE");
            ik.setResponseStatus(status);
            ik.setResponseBody(body);
            repo.save(ik);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteKey(String key) {
        repo.deleteById(key);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Object> replay(IdempotencyKey record) {
        try {
            Object body = mapper.readValue(record.getResponseBody(), Object.class);
            return ResponseEntity.status(record.getResponseStatus()).body(body);
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
