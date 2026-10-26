package com.safari.practice.controller;

import com.safari.practice.domain.Payout;
import com.safari.practice.exception.WalletException;
import com.safari.practice.service.IdempotencyService;
import com.safari.practice.service.PayoutService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/mpesa/v1/payouts")
@RequiredArgsConstructor
public class PayoutController {

    private final PayoutService      payoutService;
    private final IdempotencyService idempotencyService;

    // ── 5.4 POST /mpesa/v1/payouts ────────────────────────────────────────
    @PostMapping
    public ResponseEntity<Object> submitPayout(
            @RequestHeader("Idempotency-Key") String idemKey,
            @Valid @RequestBody PayoutRequest body) {

        if (idemKey == null || idemKey.isBlank() || idemKey.length() > 64) {
            throw WalletException.validationFailed("Idempotency-Key must be 1..64 characters");
        }

        return idempotencyService.execute(idemKey, "payout", body, () -> {
            // Phase 1: reserve + persist (fast DB tx, returns 202 immediately)
            Payout p = payoutService.accept(body.wallet_id(), body.bank_account(), body.amount());

            // Phase 2: call bank asynchronously (off the request thread)
            payoutService.submitToBank(p.getPayoutId());

            return ResponseEntity
                    .status(HttpStatus.ACCEPTED)
                    .body((Object) Map.of(
                            "payout_id", p.getPayoutId(),
                            "status",    p.getStatus()
                    ));
        });
    }

    // ── GET /mpesa/v1/payouts/{id} ────────────────────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<Object> getPayout(@PathVariable String id) {
        Payout p = payoutService.getPayout(id);
        var resp = Map.of(
                "payout_id",  p.getPayoutId(),
                "wallet_id",  p.getWalletId(),
                "amount",     p.getAmount(),
                "status",     p.getStatus(),
                "bank_ref",   p.getBankRef() != null ? p.getBankRef() : "",
                "conflict",   p.isConflict(),
                "updated_at", p.getUpdatedAt().toString()
        );
        return ResponseEntity.ok(resp);
    }

    // ── DTO ───────────────────────────────────────────────────────────────
    public record PayoutRequest(
            @NotBlank String wallet_id,
            @NotBlank String bank_account,
            @Min(1) @Max(5_000_000) long amount
    ) {}
}
