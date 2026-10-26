# FAILURE_MODES.md

## POST /mpesa/v1/p2p/transfers (5.1)

| Failure mode | Prevention |
|---|---|
| Two concurrent transfers from W001 overdraw it | Pessimistic row lock (`SELECT FOR UPDATE`) on wallet rows inside a single DB transaction; balance check is inside the same lock scope |
| Debit without credit (partial transfer) | Single atomic DB transaction; both wallet UPDATE + transfer INSERT commit together or not at all |
| Deadlock between W001→W002 and W002→W001 | Locks acquired in consistent alphabetical order of wallet_id across all instances |
| Duplicate transfer on network retry | DB-backed idempotency key: INSERT-on-conflict in `REQUIRES_NEW` transaction; second request replays cached response |
| Transfer to/from a frozen wallet | Status checked inside the pessimistic lock; freeze sets status before any in-flight transfer can re-read it |
| Negative available | DB CHECK constraint `balance >= reserved`; application also checks `available >= amount` before debiting |
| SAME_WALLET transfer | Validated before any lock is taken; returns 422 SAME_WALLET |

## POST /mpesa/v1/p2p/transfers/{id}/reversal (5.2)

| Failure mode | Prevention |
|---|---|
| Two concurrent reversals both succeed | Pessimistic lock on the transfer row; only one UPDATE sees status=COMPLETED and proceeds; second returns 409 |
| Reversal of an already-reversed transfer | Transfer status checked inside the lock; REVERSED → 409 ALREADY_REVERSED |
| Receiver wallet frozen blocks credit | Spec says credited wallet may be FROZEN; only debited (original to_wallet) must be ACTIVE and checked |
| Insufficient funds in the original receiver | available checked inside the lock; 422 INSUFFICIENT_FUNDS if not enough |

## GET/PUT /mpesa/v1/wallets/{id} (5.3)

| Failure mode | Prevention |
|---|---|
| Stale read of available (balance − reserved out of sync) | Single consistent read of both columns in one SELECT; DB constraints guarantee `available >= 0` at all times |
| Freeze takes effect after a debit it should have blocked | Freeze uses pessimistic write lock; any concurrent transfer that hasn't committed yet will see the updated status when it reads inside its own lock |
| Freeze response > 2 s | Row-level lock is short-held; Tomcat thread pool and HikariCP timeouts provide hard bounds |

## POST /mpesa/v1/payouts (5.4)

| Failure mode | Prevention |
|---|---|
| Payout accepted but bank called twice | Payout row inserted once (idempotency key); bank call is async, single submitToBank dispatched once per payout |
| Bank timeout leaves payout in limbo | Recovery scheduler polls GET /bank/payouts/{id} every 30 s; safe across instance kill |
| Reservation not released on FAILED | FAILED callback or REJECTED response triggers `releaseReservation` inside DB transaction |
| Balance goes negative on SUCCEEDED | `commitPayout` decrements balance and reserved atomically; DB constraint enforces `balance >= 0` |
| 202 response > 500 ms | Bank call is async (off the request thread); response returned immediately after DB reservation |
| Double-debit on duplicate callback | Pessimistic lock on payout row; `isFinal()` check prevents applying a callback twice |
| Instance 8081 killed mid-payout | Recovery scheduler on 8082 picks up PENDING payouts and resolves them |
| Thread starvation from slow bank calls | Dedicated `payoutExecutor` thread pool (separate from Tomcat threads) |

## POST /mpesa/v1/callbacks/bank (5.5)

| Failure mode | Prevention |
|---|---|
| Duplicate callbacks applied twice | Pessimistic lock on payout row; `isFinal()` guard idempotently skips re-application |
| Callback arrives before submit call returns | `applyCallback` works regardless of whether submit has returned; idempotency key and payout row are committed before bank is called |
| Conflicting status (FAILED after SUCCEEDED) | Final status is kept; `conflict:true` is set on the payout; 200 returned to bank |
| Unknown payout_id | 404 PAYOUT_NOT_FOUND returned; bank retries |
| Response > 2 s | No slow operations inside the callback path; one DB SELECT FOR UPDATE + one UPDATE |
