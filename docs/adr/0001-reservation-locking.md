# ADR 0001: Reserve stock with a conditional UPDATE

Status: accepted

## Context

Two sales reps submit orders for the same SKU at the same moment. There are 10 units, and each order wants 7.
Exactly one may succeed. The other order must end up in `STOCK_EXCEPTION`, with no negative
`available` quantity or reservation exceeding the 10 units on hand.

With a check-then-act implementation, both transactions can read `available = 10` and decide that 7
units fit before either writes. READ COMMITTED isolation does not prevent this race.

## Options

| Option | How | Cost |
|---|---|---|
| A. `SELECT ... FOR UPDATE`, check in Java, `UPDATE` | Lock the row, read, decide, write | Two statements and an app-side decision while the lock is held; easy for a later change to "optimise" the lock away |
| B. Conditional `UPDATE ... WHERE on_hand - reserved >= :qty RETURNING ...` | Check and write are one statement | Needs a follow-up read to explain why it failed (only on the failure path) |
| C. SERIALIZABLE isolation | Let Postgres abort conflicting transactions | Callers must retry on serialization failures; aborts are noisy under contention |
| D. Optimistic `@Version` on stock rows | Retry on version conflict | Hot SKUs livelock under contention; retry logic everywhere |

## Decision

Use option B. `StockLedger.reserve` runs:

```sql
UPDATE stock_levels
   SET reserved = reserved + :qty, updated_at = now()
 WHERE product_id = :p AND warehouse_id = :w
   AND on_hand - reserved >= :qty
RETURNING on_hand, reserved;
```

Under READ COMMITTED, the UPDATE takes a row lock. A second transaction that
targets the same row blocks until the first commits, and then Postgres *re-evaluates the WHERE clause
against the newly committed row version* (EvalPlanQual) before applying its change. With 10 on hand, the first
reservation leaves 3 available, the re-check `3 >= 7` is false, zero rows are updated, and the service
reports insufficient stock. There is no window between check and write.

Supporting rules:

- Every line of an order is reserved in one transaction. The first shortfall
  throws `InsufficientStockException`, which rolls back the lines already reserved. A second
  transaction then records `STOCK_EXCEPTION` with the reason.
- Lines are merged per product and locked in ascending product-id order, so two orders
  touching {A, B} and {B, A} always acquire locks in the same sequence.
- `CHECK (reserved <= on_hand)`, `CHECK (reserved >= 0)` and `CHECK (on_hand >= 0)`
  live in the schema, so even a buggy future code path or a manual SQL fix cannot over-reserve.
- The same transaction appends an `inventory_movements` row with the deltas and resulting
  position. A trigger makes the ledger append-only, and summing it reproduces the current position.

## Evidence

`ReservationConcurrencyTest` (real HTTP, real Postgres via Testcontainers):

- `twoConcurrentOrdersForSevenOfTenUnits`: the 7-of-10 race, repeated 50 times per run.
- `aBlockedReservationReEvaluatesAgainstTheCommittedRow`: a deterministic version. A raw connection holds
  the row lock; the test waits until `pg_stat_activity` shows the HTTP request blocked on it, commits, and
  asserts the request then fails with "available 3".
- `oppositeLineOrderNeverDeadlocks`: 12 concurrent two-line orders listing products in opposite order.
- `randomisedLoadKeepsTheBooksBalanced`: 40 concurrent customers, random lines, random cancels. Reserved
  stock equals what reserving orders hold, and the ledger replays to the position.

`scripts/stress-reservations.sh 10` runs the suite ten times in fresh JVMs and containers (500 races).

The tests can catch the bug: swapping in a naive "read available, then unguarded UPDATE" made 48 of 50 races
fail. The CHECK constraint turned the double-booking into an error instead of silent corruption.

## Consequences

- Reservation latency is one round trip per product line, and contention cost is a short row lock.
- The failure path needs one extra read (`available`) to build a useful message.
- Cancellation releases with the mirror statement (`... WHERE reserved >= :qty`); shipping consumes
  `on_hand` and `reserved` together.
