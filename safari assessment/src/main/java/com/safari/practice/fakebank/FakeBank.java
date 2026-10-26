package com.safari.practice.fakebank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fake bank implementing section IV of the assessment spec.
 * Runs on port 9090 as a completely separate Spring Boot application.
 *
 * Behaviour (configurable via properties):
 *  - 2-6 s latency on POST /bank/payouts
 *  - ~80% ACCEPTED, ~8% REJECTED, ~12% 504 hang (60 s)
 *  - ~5% of accepted eventually FAIL
 *  - Callbacks sent 1-10 s after decision, 30% arrive 2-3 times concurrently
 *  - GET /bank/payouts/{id} takes 1-3 s
 *  - Exposes GET /bank/admin/total for reconciliation in tests
 */
@Slf4j
@SpringBootApplication
@EnableAsync
@EnableScheduling
@RestController
@RequiredArgsConstructor
public class FakeBank {

    // ── Configuration ──────────────────────────────────────────────────────
    @Value("${bank.accepted-rate:0.80}")  double acceptedRate;
    @Value("${bank.rejected-rate:0.08}")  double rejectedRate;
    @Value("${bank.noresp-rate:0.12}")    double noRespRate;
    @Value("${bank.noresp-paid-rate:0.50}") double noRespPaidRate;
    @Value("${bank.fail-rate:0.05}")      double failRate;
    @Value("${bank.dup-callback-rate:0.30}") double dupCallbackRate;
    @Value("${bank.callback-url:http://localhost:8081/mpesa/v1/callbacks/bank}") String callbackUrl1;
    @Value("${bank.callback-url2:http://localhost:8082/mpesa/v1/callbacks/bank}") String callbackUrl2;

    // ── State ──────────────────────────────────────────────────────────────
    record PayoutRecord(String payoutId, long amount, String bankRef,
                        String finalStatus, boolean responded) {}

    private final Map<String, PayoutRecord> store     = new ConcurrentHashMap<>();
    private final AtomicLong               totalPaid  = new AtomicLong(0);
    private final SecureRandom             rng        = new SecureRandom();
    private final ScheduledExecutorService scheduler  = Executors.newScheduledThreadPool(10);

    public static void main(String[] args) {
        System.setProperty("server.port", "9090");
        System.setProperty("spring.autoconfigure.exclude",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration");
        SpringApplication.run(FakeBank.class, args);
    }

    // ── POST /bank/payouts ─────────────────────────────────────────────────
    @PostMapping("/bank/payouts")
    public ResponseEntity<Object> submitPayout(@Valid @RequestBody PayoutReq req) throws InterruptedException {
        String id = req.payout_id;

        // Idempotent by payout_id
        if (store.containsKey(id)) {
            PayoutRecord existing = store.get(id);
            if (existing.amount() != req.amount) {
                return ResponseEntity.ok(Map.of("result", "REJECTED", "reason", "AMOUNT_MISMATCH"));
            }
            if (existing.responded()) {
                return ResponseEntity.ok(Map.of("result", "ACCEPTED", "bank_ref", existing.bankRef()));
            }
        }

        // Simulate latency 2-6 s
        sleep(2000 + rng.nextInt(4000));

        double r = rng.nextDouble();

        if (r < rejectedRate) {
            // ── REJECTED (8%) ──────────────────────────────────────────
            String bankRef = "BR-" + UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase();
            store.put(id, new PayoutRecord(id, req.amount, bankRef, "REJECTED", true));
            return ResponseEntity.ok(Map.of("result", "REJECTED", "reason", "BENEFICIARY_ACCOUNT_INVALID"));
        }

        if (r < rejectedRate + noRespRate) {
            // ── NO RESPONSE (12%) – hang 60 s then 504 ────────────────
            boolean bankDidProcess = rng.nextDouble() < noRespPaidRate;
            String bankRef = "BR-" + UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase();
            if (bankDidProcess) {
                boolean willFail = rng.nextDouble() < failRate;
                String finalStatus = willFail ? "FAILED" : "SUCCEEDED";
                store.put(id, new PayoutRecord(id, req.amount, bankRef, finalStatus, false));
                scheduleCallback(id, bankRef, finalStatus, 1000 + rng.nextInt(9000));
            } else {
                store.put(id, new PayoutRecord(id, req.amount, bankRef, "FAILED", false));
            }
            sleep(60000);
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();
        }

        // ── ACCEPTED (80%) ─────────────────────────────────────────────
        String bankRef = "BR-" + UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase();
        boolean willFail = rng.nextDouble() < failRate;
        String finalStatus = willFail ? "FAILED" : "SUCCEEDED";
        store.put(id, new PayoutRecord(id, req.amount, bankRef, finalStatus, true));

        // Schedule callback 1-10 s from now
        scheduleCallback(id, bankRef, finalStatus, 1000 + rng.nextInt(9000));

        return ResponseEntity.ok(Map.of("result", "ACCEPTED", "bank_ref", bankRef));
    }

    // ── GET /bank/payouts/{id} ─────────────────────────────────────────────
    @GetMapping("/bank/payouts/{id}")
    public ResponseEntity<Object> getPayoutStatus(@PathVariable String id) throws InterruptedException {
        sleep(1000 + rng.nextInt(2000));
        PayoutRecord rec = store.get(id);
        if (rec == null) {
            return ResponseEntity.ok(Map.of("status", "NOT_FOUND"));
        }
        String status = switch (rec.finalStatus()) {
            case "SUCCEEDED" -> "SUCCEEDED";
            case "FAILED"    -> "FAILED";
            case "REJECTED"  -> "REJECTED";
            default          -> "IN_PROGRESS";
        };
        return ResponseEntity.ok(Map.of("status", status));
    }

    // ── GET /bank/admin/total ──────────────────────────────────────────────
    @GetMapping("/bank/admin/total")
    public ResponseEntity<Object> adminTotal() {
        return ResponseEntity.ok(Map.of("total_paid", totalPaid.get()));
    }

    // ── Admin: configure rates on the fly ─────────────────────────────────
    @PutMapping("/bank/admin/config")
    public ResponseEntity<Object> configure(@RequestBody Map<String, Double> cfg) {
        if (cfg.containsKey("accepted_rate"))    acceptedRate    = cfg.get("accepted_rate");
        if (cfg.containsKey("rejected_rate"))    rejectedRate    = cfg.get("rejected_rate");
        if (cfg.containsKey("noresp_rate"))      noRespRate      = cfg.get("noresp_rate");
        if (cfg.containsKey("fail_rate"))        failRate        = cfg.get("fail_rate");
        if (cfg.containsKey("dup_callback_rate")) dupCallbackRate = cfg.get("dup_callback_rate");
        return ResponseEntity.ok("config updated");
    }

    // ── Callback sender ────────────────────────────────────────────────────
    private void scheduleCallback(String payoutId, String bankRef, String status, long delayMs) {
        scheduler.schedule(() -> sendCallback(payoutId, bankRef, status), delayMs, TimeUnit.MILLISECONDS);
    }

    private void sendCallback(String payoutId, String bankRef, String status) {
        if ("SUCCEEDED".equals(status)) {
            PayoutRecord rec = store.get(payoutId);
            if (rec != null) totalPaid.addAndGet(rec.amount());
        }

        var body = Map.of(
                "event_id",   "EV-" + UUID.randomUUID(),
                "payout_id",  payoutId,
                "bank_ref",   bankRef != null ? bankRef : "",
                "status",     status
        );

        // Determine how many copies to send (30% chance of 2-3 duplicates)
        int copies = rng.nextDouble() < dupCallbackRate ? 2 + rng.nextInt(2) : 1;
        WebClient client = WebClient.create();

        // Alternate between the two instances
        List<String> urls = List.of(callbackUrl1, callbackUrl2);
        for (int i = 0; i < copies; i++) {
            final String url = urls.get(i % urls.size());
            // Retry up to 5 times every 3 s if no 2xx within 2 s
            sendWithRetry(client, url, body, 5);
        }
    }

    private void sendWithRetry(WebClient client, String url, Object body, int retries) {
        for (int attempt = 0; attempt < retries; attempt++) {
            try {
                Integer status = client.post().uri(url).bodyValue(body)
                        .retrieve().toBodilessEntity()
                        .map(r -> r.getStatusCode().value())
                        .timeout(Duration.ofSeconds(2))
                        .onErrorReturn(-1)
                        .block();
                if (status != null && status >= 200 && status < 300) return;
            } catch (Exception ignored) {}
            if (attempt < retries - 1) {
                try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
        log.warn("Callback delivery exhausted retries for payout at {}", url);
    }

    private void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    // ── DTOs ──────────────────────────────────────────────────────────────
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PayoutReq {
        @NotBlank String payout_id;
        @NotBlank String bank_account;
        long amount;
    }
}
