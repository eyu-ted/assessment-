package com.safari.practice.controller;

import com.safari.practice.domain.Transfer;
import com.safari.practice.domain.Wallet;
import com.safari.practice.exception.WalletException;
import com.safari.practice.repository.WalletRepository;
import com.safari.practice.service.IdempotencyService;
import com.safari.practice.service.TransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/mpesa/v1/p2p/transfers")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService    transferService;
    private final IdempotencyService idempotencyService;
    private final WalletRepository   walletRepository;

    // ── 5.1 POST /mpesa/v1/p2p/transfers ──────────────────────────────────
    @PostMapping
    public ResponseEntity<Object> transfer(
            @RequestHeader("Idempotency-Key") String idemKey,
            @Valid @RequestBody TransferRequest body) {

        if (idemKey == null || idemKey.isBlank() || idemKey.length() > 64) {
            throw WalletException.validationFailed("Idempotency-Key must be 1..64 characters");
        }

        return idempotencyService.execute(idemKey, "transfer", body, () -> {
            Transfer tx = transferService.transfer(
                    body.from_wallet(), body.to_wallet(), body.amount(), body.reference());

            long fromAvailable = walletRepository.findById(tx.getFromWallet())
                    .map(Wallet::available).orElse(0L);

            var response = Map.of(
                    "transfer_id",         tx.getTransferId(),
                    "status",              tx.getStatus(),
                    "from_available_after", fromAvailable,
                    "created_at",          tx.getCreatedAt().toString()
            );
            return ResponseEntity.status(HttpStatus.CREATED).body((Object) response);
        });
    }

    // ── 5.2 POST /mpesa/v1/p2p/transfers/{id}/reversal ────────────────────
    @PostMapping("/{id}/reversal")
    public ResponseEntity<Object> reverse(
            @PathVariable String id,
            @Valid @RequestBody ReversalRequest body) {

        Transfer tx = transferService.reverse(id, body.reason());
        var response = Map.of(
                "transfer_id", tx.getTransferId(),
                "reversal_id", tx.getReversalId(),
                "status",      tx.getStatus(),
                "amount",      tx.getAmount()
        );
        return ResponseEntity.ok(response);
    }

    // ── DTOs ───────────────────────────────────────────────────────────────
    public record TransferRequest(
            @NotBlank String from_wallet,
            @NotBlank String to_wallet,
            @Min(1) @Max(5_000_000) long amount,
            @NotBlank @Size(min = 1, max = 64) String reference
    ) {}

    public record ReversalRequest(
            @NotBlank String reason
    ) {}
}
