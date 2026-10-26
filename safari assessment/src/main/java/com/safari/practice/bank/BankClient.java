package com.safari.practice.bank;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;

/**
 * HTTP client for the external (fake) bank.
 *
 * Decision: 8-second timeout as specified. A no-response after 8 s is
 * returned as empty – outcome is UNKNOWN, not failure. Recovery scheduler
 * polls GET /bank/payouts/{id} to reconcile unknown payouts.
 */
@Slf4j
@Component
public class BankClient {

    private final WebClient    client;
    private final long         timeoutSeconds;

    public BankClient(
            @Value("${bank.base-url}") String baseUrl,
            @Value("${bank.timeout-seconds:8}") long timeoutSeconds) {
        this.client         = WebClient.builder().baseUrl(baseUrl).build();
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * POST /bank/payouts – blocking call up to {@code timeoutSeconds}.
     *
     * @return Optional.empty() on timeout (unknown outcome)
     */
    public Optional<BankPayoutResponse> submitPayout(String payoutId, String bankAccount, long amount) {
        BankPayoutRequest req = new BankPayoutRequest();
        req.setPayout_id(payoutId);
        req.setBank_account(bankAccount);
        req.setAmount(amount);

        try {
            BankPayoutResponse resp = client.post()
                    .uri("/bank/payouts")
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(BankPayoutResponse.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();
            return Optional.ofNullable(resp);
        } catch (Exception ex) {
            log.warn("Bank payout {} timed out or errored: {}", payoutId, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * GET /bank/payouts/{payoutId} – used during recovery.
     *
     * @return Optional.empty() if bank returns NOT_FOUND or times out.
     */
    public Optional<BankPayoutResponse> getPayoutStatus(String payoutId) {
        try {
            BankPayoutResponse resp = client.get()
                    .uri("/bank/payouts/{id}", payoutId)
                    .retrieve()
                    .bodyToMono(BankPayoutResponse.class)
                    .timeout(Duration.ofSeconds(4))
                    .onErrorResume(e -> Mono.empty())
                    .block();
            return Optional.ofNullable(resp);
        } catch (Exception ex) {
            log.warn("GET /bank/payouts/{} failed: {}", payoutId, ex.getMessage());
            return Optional.empty();
        }
    }
}
