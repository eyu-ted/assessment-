# DESIGN.md

## Decisions

| Open question | Choice made |
|---|---|
| Same-key concurrent request | Return `409 REQUEST_IN_PROGRESS` |
| Idempotency mechanism | DB INSERT-on-conflict: row inserted with `status=IN_PROGRESS` in its own `REQUIRES_NEW` transaction; marked `DONE` with cached HTTP response after completion |
| Isolation level | `READ_COMMITTED` for all write paths (transfers, payouts, callbacks). PostgreSQL's row-level pessimistic locking (`SELECT … FOR UPDATE`) provides the serialisation needed without the cost of SERIALIZABLE |
| Amounts | Integers in **santim** (Long) throughout. No BigDecimal, no float |
| Payout recovery across instance kill | Scheduled `@Scheduled(fixedDelay=30s)` on both instances. Queries `findAllPending()` and polls `GET /bank/payouts/{id}` for each. Idempotent because the callback application path is protected by a pessimistic lock |

---

## Q1 · Every wait (where a thread can block)

| Location | Max wait | Bound |
|---|---|---|
| `WalletRepository.findByIdForUpdate` (transfer) | Until row lock released | Bounded by the other transfer's DB transaction duration; Tomcat max=50 threads so at most 50 concurrent holders |
| `WalletRepository.findByIdForUpdate` (freeze) | Same as above | Row lock; bounded by active transfers finishing or failing |
| `HikariCP.getConnection()` | `hikari.connection-timeout=2000 ms` | Hard cap; throws if exceeded |
| `BankClient.submitPayout` WebClient call | `bank.timeout-seconds=8` | Configured; returns `Optional.empty()` on timeout – outcome is UNKNOWN, not failure |
| `BankClient.getPayoutStatus` (recovery) | 4 s | Configured in client |
| `PayoutService.submitToBank` async thread pool | Queue depth 200 tasks | Dedicated `payoutExecutor` pool (10 core/30 max); never blocks Tomcat threads |
| `@Scheduled` recovery scan | Each payout: up to 4 s bank call | Runs on scheduler thread; does not hold Tomcat or connection-pool threads |

---

## Q2 · Database restart while a payout is in flight

Scenario: reservation has been written to DB, bank call is in progress, PostgreSQL restarts.

1. **Reservation written** — `wallets.reserved` was incremented in a committed transaction before the bank call started. That row is on disk.
2. **PostgreSQL restarts** — JDBC connections are dropped. HikariCP detects this (connection-timeout=2 s) and throws for any new attempt. The bank call thread continues (it is not waiting on DB).
3. **Bank call returns (ACCEPTED or timeout)** — `submitToBank` tries to call `finalisePayout`, which needs a DB connection. PostgreSQL is still restarting → HikariCP throws `SQLTransientConnectionException` → `finalisePayout` fails → payout remains `PENDING` in DB.
4. **PostgreSQL recovers** — HikariCP reconnects automatically.
5. **Recovery scheduler fires** (runs on both instances every 30 s) — it reads all `PENDING` payouts and calls `GET /bank/payouts/{id}` for each. If the bank reports `SUCCEEDED` or `FAILED`, `finalisePayout` is called and the payout is closed out correctly.
6. **Money is safe** — the reservation kept `available = balance − reserved` from going negative at any point. Either the payout finalises as SUCCEEDED (balance decremented + reservation released) or as FAILED (reservation released only).

---

## Q3 · What was left out

| Item | How I would build it | Hard part |
|---|---|---|
| **S4.1–S4.3** (payout e2e tests) | Start the `FakeBank` process in tests via `ProcessBuilder` or embed it in a separate module; configure rates via `PUT /bank/admin/config` before each test | The 60-second hang in the fake bank makes S4.3 slow; need to make the hang duration configurable |
| **5.6 B2C Disbursements** | Persist batch + rows to DB; process rows in a bounded `ExecutorService`; each row is a mini-transfer using the same lock-ordering pattern. Use a DB-level `INSERT OR IGNORE` on `(batch_id, row_id)` for idempotency per row | The hard part is ensuring 5.1 stays under 300 ms during a 5,000-row batch (M001 is the source wallet). Solution: lock M001 only once per row, not for the whole batch |
| **5.7 Statement cursor stability** | Already implemented. Hard part is the snapshot guarantee: new entries written during pagination must not appear mid-walk. The `(created_at DESC, entry_id DESC)` cursor gives a consistent cut-point |
| **S7.1, S7.2** (statement e2e tests) | Write entries in a background loop; walk pages in another thread; assert no duplicates and chain integrity | No additional architectural complexity |
| **S4.2** (p95 < 300 ms during payouts) | Separate connection pools / executor for payout workers; transfer path must not wait on payout locks. Achieved by the two-phase design: transfers lock wallet rows, payouts lock payout rows – they don't compete for the same rows |




---

## Overall System Architecture & Design

### What this system does
This service is the core ledger and wallet engine behind M-PESA. It has two primary responsibilities:
1. Move money between customer wallets (P2P transfers).
2. Pay out funds to an external bank, handle slow or dropped bank responses, and process asynchronous bank webhooks safely.

It runs across **two instances** against one shared **PostgreSQL** database, ensuring that no race condition, crash, or duplicate request can ever duplicate money or allow a negative balance.

---

### Core Principles & Real-World Examples

#### 1. Deadlock-Free Concurrent Transfers
* **The challenge:** Alice (`W001`) sends 500 santim to Bob (`W002`), while at the exact same millisecond Bob sends 200 santim to Alice from another instance. Without ordering, Instance A locks Alice and waits for Bob, while Instance B locks Bob and waits for Alice — deadlocking the database.
* **Our solution:** Strict **lexicographical row-locking**. Every transfer sorts wallet IDs alphabetically before acquiring locks (`MIN(from, to)` then `MAX(from, to)` via `SELECT FOR UPDATE`). Both instances are forced to acquire locks in the exact same sequence (`W001` then `W002`), eliminating deadlocks completely. Database check constraints (`balance >= 0`) physically prevent overdrafts.

#### 2. Two-Phase Bank Payouts
* **The challenge:** Alice withdraws 1,000 santim to her bank account. The external bank is slow (taking 6–10 seconds, or timing out). Holding HTTP request threads and DB connections open while waiting for the bank would exhaust server capacity.
* **Our solution:**
  1. **Phase 1 (Instant Reservation):** Within < 100 ms, Alice's `reserved` balance is incremented by 1,000 santim and `202 ACCEPTED` is returned. Alice’s spendable balance (`balance - reserved`) drops immediately so she cannot double-spend that money.
  2. **Phase 2 (Async Dispatch):** The HTTP call to the bank is dispatched on a dedicated background thread pool (`payoutExecutor`). When the bank sends a webhook callback:
     - If `SUCCEEDED`: finalize payout, deduct balance, and release reservation.
     - If `FAILED`: release reservation so Alice gets her 1,000 santim back immediately.

#### 3. Out-of-Order Callbacks & Crash Recovery
* **The challenge:** The bank's callback webhook might arrive twice, out-of-order, or the server might crash before the bank finishes.
* **Our solution:**
  - **Pessimistic callback lock:** The callback endpoint locks the payout row using `SELECT ... FOR UPDATE`. If the payout is already in a terminal state (`SUCCEEDED` or `FAILED`), duplicate callbacks are ignored safely.
  - **Auto-Recovery Scheduler:** A background task runs every 30 seconds (`@Scheduled`) to find stuck `PENDING` payouts and polls the bank (`GET /bank/payouts/{id}`) for status, guaranteeing eventual consistency.

#### 4. Safe Idempotency on Retries
* **The challenge:** A client sends a transfer, but network drops right as the server finishes. The client retries with the same request.
* **Our solution:** Every mutation requires an `Idempotency-Key` stored in PostgreSQL with `INSERT ... ON CONFLICT`:
  - If another request with the same key is still running: returns `409 REQUEST_IN_PROGRESS`.
  - Once completed: the final response status and JSON body are cached. When the client retries, the cached response is replayed without moving money twice.

#### 5. Immutable Double-Entry Ledger
Wallets store current balance snapshots. Every debit or credit appends an immutable record to `ledger_entries` with a running `balance_after`. This provides an auditable, tampering-proof transaction log.
