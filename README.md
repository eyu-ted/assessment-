# M-PESA Wallet Service

A high-concurrency wallet and ledger service built with **Java 21**, **Spring Boot 3**, and **PostgreSQL**.

The service handles distributed money movement across dual instances, manages slow bank payouts with asynchronous callbacks, and enforces zero-loss ledger consistency under high concurrency, duplicate requests, and race conditions.

---

## 🏗 Architecture Overview

- **Ledger Invariant**: Double-entry bookkeeping where every balance mutation is accompanied by an immutable `ledger_entries` record.
- **Strict DB Constraints**: In-database check constraints enforce `balance >= 0`, `reserved >= 0`, and `balance >= reserved` at all times.
- **Deadlock-Free Row Locking**: Concurrent P2P transfers lock wallets in strict lexicographical order (`MIN(from, to)` followed by `MAX(from, to)`).
- **Two-Phase Idempotency**: Distributed request deduplication using PostgreSQL `INSERT ... ON CONFLICT`. Subsequent requests with the same key replay the cached response.
- **Asynchronous Bank Payouts**: Payout requests immediately reserve funds within a database transaction (< 500 ms) and return `202 ACCEPTED`. Bank calls execute asynchronously on a dedicated thread pool to prevent Tomcat starvation.
- **Out-of-Order Callback Safety**: Bank callbacks are protected by pessimistic row locking (`SELECT FOR UPDATE`) on the payout row. Payouts in a terminal state (`SUCCEEDED` / `FAILED`) safely reject duplicate or late callbacks.
- **Automated Payout Recovery**: A periodic background scheduler (`@Scheduled`) polls the bank for unresolved payouts older than 30 seconds.

---

## 🚀 Prerequisites

- **Java**: Version 21 (or 17+)
- **Maven**: Bundled Maven wrapper (`./mvnw` or `mvnw.cmd`)
- **Docker**: Docker Desktop (running) for running the automated integration test suite with Testcontainers

---

## 🧪 Running the Tests

The test suite is fully self-contained and spins up an isolated PostgreSQL instance using Testcontainers.

Run all tests from the project root:

```bash
# Windows
.\mvnw.cmd test

# Linux / macOS
./mvnw test
```

### What the test suite covers:
- **Scenario S1.1**: Concurrent P2P transfers from the same wallet with high contention (verifies zero overdraft).
- **Scenario S1.2**: Deadlock cycle prevention (simultaneous circular transfers `W001 -> W002` and `W002 -> W001`).
- **Scenario S2.1**: Exactly-once idempotency replay for P2P transfers.
- **Scenario S2.2**: Immediate rejection (`409 REQUEST_IN_PROGRESS`) for concurrent duplicate idempotency keys.
- **Scenario S3.1**: Successful two-phase payout and settlement via bank callback.
- **Scenario S3.2**: Payout failure/rejection and automatic fund unreservation.
- **Scenario S4**: Out-of-order and duplicate bank callback handling.
- **Scenario S5**: Cursor-based statement pagination stability.

---

## 🏃 Running the Application

### 1. Start the Wallet Service
Ensure your local PostgreSQL database is running (or configured in `application.yml`), then execute:

```bash
# Windows
.\mvnw.cmd spring-boot:run

# Linux / macOS
./mvnw spring-boot:run
```

By default, Flyway automatically runs database migrations on startup (`V1__init.sql` and `V2__seed.sql`).

### 2. Run the Fake Bank Simulator (Optional for manual testing)
The fake bank simulator runs on port `9090` and mimics latency, random rejections, timeouts, and asynchronous callbacks:

```bash
# Run FakeBank main class
java -cp "target/classes;target/dependency/*" com.safari.practice.fakebank.FakeBank
```

---

## 📡 REST API Reference

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/mpesa/v1/p2p/transfers` | Move money between wallets with `Idempotency-Key` header |
| `POST` | `/mpesa/v1/p2p/transfers/{id}/reversal` | Reverse a completed transfer with idempotency |
| `GET` | `/mpesa/v1/wallets/{id}` | Retrieve current wallet balance and reserved amount |
| `PUT` | `/mpesa/v1/wallets/{id}/status` | Freeze or unfreeze a wallet (`ACTIVE` / `FROZEN`) |
| `POST` | `/mpesa/v1/bank/payouts` | Initiate external bank payout (returns `202 ACCEPTED`) |
| `POST` | `/mpesa/v1/bank/callback` | Webhook endpoint receiving bank finality callbacks |
| `GET` | `/mpesa/v1/wallets/{id}/statement` | Opaque cursor-based ledger pagination (`?limit=20&cursor=...`) |
| `POST` | `/mpesa/internal/reset` | Internal test reset endpoint to reseed initial wallets |

---

## 📂 Project Structure

```
safari/
├── DESIGN.md                   # In-depth architectural trade-offs & answers
├── FAILURE_MODES.md            # Failure mode catalog and mitigation mechanisms
├── README.md                   # Setup guide and API reference
└── safari assessment/
    ├── pom.xml                 # Maven build dependencies and plugins
    └── src/
        ├── main/
        │   ├── java/com/safari/practice/
        │   │   ├── WalletApplication.java      # Spring Boot application entry point
        │   │   ├── bank/                       # Bank client & external DTOs
        │   │   ├── config/                     # Async executor thread pool config
        │   │   ├── controller/                 # REST controllers & validation
        │   │   ├── domain/                     # JPA entities (Wallet, Transfer, Payout, etc.)
        │   │   ├── exception/                  # Error codes & global exception handler
        │   │   ├── fakebank/                   # Embedded fake bank simulator (port 9090)
        │   │   ├── repository/                 # Spring Data repositories with row locks
        │   │   └── service/                    # Core business logic & transaction boundaries
        │   └── resources/
        │       ├── application.yml             # Default configuration & Flyway settings
        │       └── db/migration/               # Flyway V1 schema & V2 seed data
        └── test/
            ├── java/com/safari/practice/
            │   └── WalletE2ETest.java          # Concurrency & end-to-end integration tests
            └── resources/
                └── application.yml             # Test-specific properties
```
