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
