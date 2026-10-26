package com.safari.practice.controller;

import com.safari.practice.domain.Wallet;
import com.safari.practice.service.WalletService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/mpesa/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    // ── 5.3 GET /mpesa/v1/wallets/{id} ───────────────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<Object> getWallet(@PathVariable String id) {
        Wallet w = walletService.getWallet(id);
        return ResponseEntity.ok(Map.of(
                "wallet_id", w.getWalletId(),
                "status",    w.getStatus(),
                "balance",   w.getBalance(),
                "reserved",  w.getReserved(),
                "available", w.available()
        ));
    }

    // ── 5.3 PUT /mpesa/v1/wallets/{id}/status ────────────────────────────
    @PutMapping("/{id}/status")
    public ResponseEntity<Object> setStatus(
            @PathVariable String id,
            @Valid @RequestBody StatusRequest body) {

        Wallet w = walletService.setStatus(id, body.status());
        return ResponseEntity.ok(Map.of(
                "wallet_id",  w.getWalletId(),
                "status",     w.getStatus(),
                "changed_at", w.getUpdatedAt().toString()
        ));
    }

    // ── DTO ───────────────────────────────────────────────────────────────
    public record StatusRequest(
            @NotBlank @Pattern(regexp = "ACTIVE|FROZEN") String status,
            String reason
    ) {}
}
