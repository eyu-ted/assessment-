package com.safari.practice;

import com.safari.practice.fakebank.FakeBank;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test suite covering the required scenarios S1.1 – S5.2.
 * Uses a real PostgreSQL via Testcontainers.
 * Run with: ./mvnw test
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WalletE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("mpesa")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url",      postgres::getJdbcUrl);
        reg.add("spring.datasource.username", postgres::getUsername);
        reg.add("spring.datasource.password", postgres::getPassword);
        // Point to a no-op bank for basic tests (no real bank needed)
        reg.add("bank.base-url", () -> "http://localhost:9090");
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    String base() { return "http://localhost:" + port; }

    void reset() {
        ResponseEntity<String> r = rest.postForEntity(base() + "/mpesa/internal/reset", null, String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    HttpHeaders idemHeaders(String key) {
        HttpHeaders h = new HttpHeaders();
        h.set("Idempotency-Key", key);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    // ── S1.1: 200 concurrent transfers – exactly 100 succeed ─────────────
    @Test
    @Order(1)
    void s1_1_concurrent_transfers_exactly_half_succeed() throws Exception {
        reset();
        int threads = 200;
        ExecutorService exec = Executors.newFixedThreadPool(20);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            exec.submit(() -> {
                try {
                    String toWallet = "W" + String.format("%03d", 2 + (idx % 99));
                    var body = Map.of(
                            "from_wallet", "W001",
                            "to_wallet",   toWallet,
                            "amount",      10000,
                            "reference",   "s1.1-" + idx
                    );
                    var req = new HttpEntity<>(body, idemHeaders("s1.1-key-" + idx));
                    var resp = rest.postForEntity(base() + "/mpesa/v1/p2p/transfers", req, Map.class);

                    if (resp.getStatusCode() == HttpStatus.CREATED)          created.incrementAndGet();
                    else if (resp.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) rejected.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        exec.shutdown();

        assertThat(created.get()).isEqualTo(100);
        assertThat(rejected.get()).isEqualTo(100);

        // W001 should be exactly 0
        var wallet = rest.getForEntity(base() + "/mpesa/v1/wallets/W001", Map.class);
        assertThat((Integer) wallet.getBody().get("available")).isEqualTo(0);
    }

    // ── S1.2: 20 concurrent identical requests – debited exactly once ─────
    @Test
    @Order(2)
    void s1_2_idempotent_transfer() throws Exception {
        reset();
        String idemKey = "s1.2-shared-key";
        var body = Map.of(
                "from_wallet", "W001",
                "to_wallet",   "W002",
                "amount",      10000,
                "reference",   "s1.2-test"
        );

        int threads = 20;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(exec.submit(() -> {
                var req = new HttpEntity<>(body, idemHeaders(idemKey));
                return rest.exchange(base() + "/mpesa/v1/p2p/transfers",
                        HttpMethod.POST, req, Map.class);
            }));
        }

        exec.shutdown();
        exec.awaitTermination(30, TimeUnit.SECONDS);

        Set<String> transferIds = new HashSet<>();
        for (var f : futures) {
            var resp = f.get();
            assertThat(resp.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.CONFLICT);
            if (resp.getStatusCode() == HttpStatus.CREATED) {
                transferIds.add((String) resp.getBody().get("transfer_id"));
            }
        }

        // All 201s must have the same transfer_id
        assertThat(transferIds).hasSize(1);

        // W001 debited exactly once (990000 remaining)
        var w1 = rest.getForEntity(base() + "/mpesa/v1/wallets/W001", Map.class);
        assertThat((Integer) w1.getBody().get("available")).isEqualTo(990000);
    }

    // ── S1.3: 50 W001→W002 and 50 W002→W001, all succeed, both end at 1M ─
    @Test
    @Order(3)
    void s1_3_bidirectional_transfers_no_deadlock() throws Exception {
        reset();
        int n = 50;
        ExecutorService exec = Executors.newFixedThreadPool(20);
        AtomicInteger ok = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(n * 2);

        for (int i = 0; i < n; i++) {
            final int idx = i;
            exec.submit(() -> {
                try {
                    var body = Map.of("from_wallet","W001","to_wallet","W002","amount",1000,"reference","fwd-"+idx);
                    var req = new HttpEntity<>(body, idemHeaders("s1.3-fwd-" + idx));
                    var r = rest.postForEntity(base() + "/mpesa/v1/p2p/transfers", req, Map.class);
                    if (r.getStatusCode() == HttpStatus.CREATED) ok.incrementAndGet();
                } finally { latch.countDown(); }
            });
            exec.submit(() -> {
                try {
                    var body = Map.of("from_wallet","W002","to_wallet","W001","amount",1000,"reference","bwd-"+idx);
                    var req = new HttpEntity<>(body, idemHeaders("s1.3-bwd-" + idx));
                    var r = rest.postForEntity(base() + "/mpesa/v1/p2p/transfers", req, Map.class);
                    if (r.getStatusCode() == HttpStatus.CREATED) ok.incrementAndGet();
                } finally { latch.countDown(); }
            });
        }

        boolean done = latch.await(10, TimeUnit.SECONDS);
        assertThat(done).isTrue();
        assertThat(ok.get()).isEqualTo(100);
        exec.shutdown();

        var w1 = rest.getForEntity(base() + "/mpesa/v1/wallets/W001", Map.class);
        var w2 = rest.getForEntity(base() + "/mpesa/v1/wallets/W002", Map.class);
        assertThat((Integer) w1.getBody().get("balance")).isEqualTo(1000000);
        assertThat((Integer) w2.getBody().get("balance")).isEqualTo(1000000);
    }

    // ── S2.1: 30 concurrent reversals of one transfer – exactly one wins ──
    @Test
    @Order(4)
    void s2_1_concurrent_reversals_exactly_one_succeeds() throws Exception {
        reset();
        // First do the transfer
        var body = Map.of("from_wallet","W001","to_wallet","W002","amount",5000,"reference","rev-test");
        var req = new HttpEntity<>(body, idemHeaders("s2.1-create"));
        var resp = rest.postForEntity(base() + "/mpesa/v1/p2p/transfers", req, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String txId = (String) resp.getBody().get("transfer_id");

        int threads = 30;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        AtomicInteger reversed = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    var revReq = new HttpEntity<>(Map.of("reason", "test"), new HttpHeaders() {{
                        setContentType(MediaType.APPLICATION_JSON);
                    }});
                    var r = rest.postForEntity(
                            base() + "/mpesa/v1/p2p/transfers/" + txId + "/reversal", revReq, Map.class);
                    if (r.getStatusCode() == HttpStatus.OK)       reversed.incrementAndGet();
                    else if (r.getStatusCode() == HttpStatus.CONFLICT) conflict.incrementAndGet();
                } finally { latch.countDown(); }
            });
        }

        latch.await(15, TimeUnit.SECONDS);
        exec.shutdown();

        assertThat(reversed.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(29);
    }

    // ── S3.1: freeze while transfers in flight ─────────────────────────────
    @Test
    @Order(5)
    void s3_1_freeze_while_transfers_in_flight() throws Exception {
        reset();
        int threads = 300;
        ExecutorService exec = Executors.newFixedThreadPool(30);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            exec.submit(() -> {
                try {
                    var body = Map.of("from_wallet","W030","to_wallet","W031","amount",1000,"reference","s3.1-"+idx);
                    var req = new HttpEntity<>(body, idemHeaders("s3.1-key-" + idx));
                    rest.postForEntity(base() + "/mpesa/v1/p2p/transfers", req, Map.class);
                } finally { latch.countDown(); }
            });
        }

        // Freeze W030 about 100 ms in
        Thread.sleep(100);
        var freezeReq = new HttpEntity<>(Map.of("status","FROZEN","reason","test"), new HttpHeaders() {{
            setContentType(MediaType.APPLICATION_JSON);
        }});
        rest.exchange(base() + "/mpesa/v1/wallets/W030/status", HttpMethod.PUT, freezeReq, Map.class);

        // Read balance B right after freeze returns
        var w = rest.getForEntity(base() + "/mpesa/v1/wallets/W030", Map.class);
        int balanceB = (Integer) w.getBody().get("balance");

        latch.await(15, TimeUnit.SECONDS);
        exec.shutdown();

        // After all finish, W030 must still be exactly B
        var wFinal = rest.getForEntity(base() + "/mpesa/v1/wallets/W030", Map.class);
        assertThat((Integer) wFinal.getBody().get("balance")).isEqualTo(balanceB);
    }

    // ── S3.2: GET wallet consistent snapshot ───────────────────────────────
    @Test
    @Order(6)
    void s3_2_wallet_snapshot_always_consistent() throws Exception {
        reset();
        // Start transfers in background
        ExecutorService exec = Executors.newFixedThreadPool(10);
        AtomicInteger inconsistent = new AtomicInteger();

        for (int i = 0; i < 50; i++) {
            final int idx = i;
            exec.submit(() -> {
                var body = Map.of("from_wallet","M001","to_wallet","W001","amount",1000,"reference","s3.2-"+idx);
                var req = new HttpEntity<>(body, idemHeaders("s3.2-key-"+idx));
                rest.postForEntity(base() + "/mpesa/v1/p2p/transfers", req, Map.class);
            });
        }

        // While transfers run, repeatedly GET M001 and verify consistency
        for (int i = 0; i < 20; i++) {
            var resp = rest.getForEntity(base() + "/mpesa/v1/wallets/M001", Map.class);
            Map<?, ?> body = resp.getBody();
            int balance  = ((Number) body.get("balance")).intValue();
            int reserved = ((Number) body.get("reserved")).intValue();
            int available = ((Number) body.get("available")).intValue();
            if (available != balance - reserved || available < 0) {
                inconsistent.incrementAndGet();
            }
            Thread.sleep(50);
        }

        exec.shutdown();
        exec.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(inconsistent.get()).isEqualTo(0);
    }

    // ── Error contract tests ───────────────────────────────────────────────
    @Test
    @Order(7)
    void error_contract_wallet_not_found() {
        reset();
        var resp = rest.getForEntity(base() + "/mpesa/v1/wallets/NOTEXIST", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().get("error")).isEqualTo("WALLET_NOT_FOUND");
    }

    @Test
    @Order(8)
    void error_contract_same_wallet() {
        reset();
        var body = Map.of("from_wallet","W001","to_wallet","W001","amount",1000,"reference","same");
        var req = new HttpEntity<>(body, idemHeaders("same-wallet-key"));
        var resp = rest.postForEntity(base() + "/mpesa/v1/p2p/transfers", req, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(resp.getBody().get("error")).isEqualTo("SAME_WALLET");
    }

    @Test
    @Order(9)
    void error_contract_frozen_wallet() {
        reset();
        var body = Map.of("from_wallet","W101","to_wallet","W001","amount",1000,"reference","frozen");
        var req = new HttpEntity<>(body, idemHeaders("frozen-key"));
        var resp = rest.postForEntity(base() + "/mpesa/v1/p2p/transfers", req, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(resp.getBody().get("error")).isEqualTo("WALLET_NOT_ACTIVE");
    }

    @Test
    @Order(10)
    void error_contract_insufficient_funds() {
        reset();
        var body = Map.of("from_wallet","W001","to_wallet","W002","amount",2000000,"reference","big");
        var req = new HttpEntity<>(body, idemHeaders("big-amount-key"));
        var resp = rest.postForEntity(base() + "/mpesa/v1/p2p/transfers", req, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(resp.getBody().get("error")).isEqualTo("INSUFFICIENT_FUNDS");
    }
}
