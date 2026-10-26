package com.safari.practice.controller;

import com.safari.practice.service.PayoutService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/mpesa/v1/callbacks")
@RequiredArgsConstructor
public class CallbackController {

    private final PayoutService payoutService;

    // ── 5.5 POST /mpesa/v1/callbacks/bank ────────────────────────────────
    @PostMapping("/bank")
    public ResponseEntity<Void> bankCallback(@Valid @RequestBody CallbackRequest body) {
        // Fast path: lock payout row, apply status exactly once
        payoutService.applyCallback(body.payout_id(), body.status(), body.bank_ref());
        return ResponseEntity.ok().build();
    }

    // ── DTO ───────────────────────────────────────────────────────────────
    public record CallbackRequest(
            @NotBlank String event_id,
            @NotBlank String payout_id,
            String         bank_ref,
            @NotBlank String status
    ) {}
}
