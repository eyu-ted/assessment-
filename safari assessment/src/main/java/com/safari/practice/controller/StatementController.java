package com.safari.practice.controller;

import com.safari.practice.domain.LedgerEntry;
import com.safari.practice.exception.WalletException;
import com.safari.practice.repository.LedgerEntryRepository;
import com.safari.practice.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 5.7 GET /mpesa/v1/wallets/{id}/statement (optional)
 *
 * Cursor-based pagination, newest-first, stable snapshot.
 * Cursor encodes (created_at ISO, entry_id) as Base64.
 */
@RestController
@RequestMapping("/mpesa/v1/wallets")
@RequiredArgsConstructor
public class StatementController {

    private final LedgerEntryRepository ledgerRepo;
    private final WalletRepository      walletRepo;

    @GetMapping("/{id}/statement")
    public ResponseEntity<Object> statement(
            @PathVariable String id,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor) {

        if (!walletRepo.existsById(id)) {
            throw WalletException.walletNotFound(id);
        }
        if (limit < 1 || limit > 200) limit = 50;

        List<LedgerEntry> entries;
        if (cursor == null || cursor.isBlank()) {
            entries = ledgerRepo.findFirstPage(id, PageRequest.of(0, limit + 1));
        } else {
            String[] parts = decodeCursor(cursor);
            entries = ledgerRepo.findPage(id, Instant.parse(parts[0]), parts[1],
                    PageRequest.of(0, limit + 1));
        }

        boolean hasMore = entries.size() > limit;
        List<LedgerEntry> page = hasMore ? entries.subList(0, limit) : entries;

        String nextCursor = null;
        if (hasMore) {
            LedgerEntry last = page.get(page.size() - 1);
            nextCursor = encodeCursor(last.getCreatedAt().toString(), last.getEntryId());
        }

        List<Map<String, Object>> entryList = page.stream().map(e -> Map.<String, Object>of(
                "entry_id",     e.getEntryId(),
                "type",         e.getType(),
                "amount",       e.getAmount(),
                "balance_after", e.getBalanceAfter(),
                "reference",    e.getReference(),
                "created_at",   e.getCreatedAt().toString()
        )).collect(Collectors.toList());

        Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("wallet_id",   id);
        resp.put("entries",     entryList);
        resp.put("next_cursor", nextCursor);
        return ResponseEntity.ok(resp);
    }

    private static String encodeCursor(String ts, String entryId) {
        String raw = ts + "|" + entryId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String[] decodeCursor(String cursor) {
        String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        return raw.split("\\|", 2);
    }
}
