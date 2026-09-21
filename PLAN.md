# FulfillOps development notes

Implementation notes for the stock, order and shipment workflows. Setup instructions and diagrams are
in the README.

## Development checklist

- [x] 1. Scaffold: backend, frontend, docker-compose, Flyway baseline, CI (backend tests with Testcontainers + frontend tests)
- [x] 2. Auth and roles: SALES, WAREHOUSE, SUPERVISOR; JWT; method-level authorization; seeded users
- [x] 3. Catalog and inventory: products, one warehouse, on-hand / reserved / available, append-only `inventory_movements` ledger
- [x] 4. Orders: editor with FormArray line items, explicit state machine, idempotent submission
- [x] 5. Reservation correctness: atomic conditional UPDATE, release on cancel, concurrency test (10 in stock, 2x7) repeated in a loop
- [x] 6. Fulfillment: picking + packing screens, supervisor stock-exception queue, shipment timeline
- [x] 7. Carrier integration: simulated carrier container (fail / timeout / succeed-then-drop), outbox with retries, carrier idempotency key, lost-response integration test
- [x] 8. UI polish: searchable/sortable order table, inventory dashboard, movement history, responsive layout, loading/empty/error states, realistic seed script
- [x] 9. Playwright e2e demo: 2 concurrent 7-of-10 orders -> one exception; carrier failure -> recovery with one shipment
- [ ] 10. Delivery: production Dockerfiles, AWS deploy config and README with setup instructions and architecture diagrams

Returns and multiple warehouses are outside the current scope.

## Local development

```bash
cd ~/projects/fulfillops
mise install                      # JDK 21 (see mise.toml)
docker compose up -d postgres carrier
(cd backend && ./mvnw verify)     # needs Docker for Testcontainers
(cd frontend && npm ci && npm test)
```

## Architecture

### Layout

```
backend/        Spring Boot 3.5, Java 21, Maven (wrapper)
frontend/       Angular (standalone components, signals, Material)
carrier-sim/    simulated carrier HTTP service (single-file Java, raw sockets)
e2e/            Playwright
deploy/         AWS deploy config
docker-compose.yml
```

### Backend modules (Spring Modulith, verified by `ModularityTests`)

Base package `com.fulfillops`. Each module exposes only its top-level package (API: services,
DTOs, events). Sub-packages (`internal`) are hidden from other modules.

| Module        | Owns                                                        | May depend on          |
|---------------|-------------------------------------------------------------|------------------------|
| `shared`      | security (users, JWT), outbox/job runner, error handling     | none                   |
| `catalog`     | products                                                     | shared                 |
| `inventory`   | warehouse, stock levels, movement ledger, reserve/release    | shared, catalog        |
| `orders`      | orders, lines, state machine, idempotent submission          | shared, catalog, inventory |
| `fulfillment` | picking, packing, shipments, carrier client, timeline        | shared, orders, inventory  |

No cycles: `orders` never calls `fulfillment`; fulfillment drives the PICKING/PACKED/SHIPPED
transitions through the orders API.

### Order state machine

```
DRAFT -> SUBMITTED -> RESERVED -> PICKING -> PACKED -> SHIPPED
             |            \           \
             v             +-----------+--> CANCELLED (releases reservation)
       STOCK_EXCEPTION --(retry)--> RESERVED
             \--> CANCELLED
DRAFT -> CANCELLED
```

Transitions live in one enum-based table (`OrderStatus.canTransitionTo`), and every change goes
through `Order.transitionTo`, which throws `InvalidTransitionException` (HTTP 409). The status
update also carries a JPA `@Version` so two concurrent transitions on the same order cannot both win.

### Inventory and the reservation strategy

- `stock_levels(product_id, warehouse_id, on_hand, reserved, available GENERATED AS (on_hand - reserved))`
  with `CHECK (reserved >= 0 AND reserved <= on_hand)`.
- Every change writes an `inventory_movements` row (RECEIPT, ADJUSTMENT, RESERVE, RELEASE, SHIP)
  in the same transaction. The table is append-only: a trigger rejects UPDATE/DELETE.
- **Reservation = a conditional UPDATE**:
  `UPDATE stock_levels SET reserved = reserved + :q WHERE product_id = :p AND on_hand - reserved >= :q`.
  Zero rows updated means insufficient stock. Postgres takes the row lock, and under READ COMMITTED a
  blocked second updater re-checks the WHERE against the committed row, so two 7-unit
  reservations against 10 units cannot both succeed. Multi-line orders lock rows in product-id
  order (no deadlocks) and run in one transaction, so it is all-or-nothing.
  Why not `SELECT ... FOR UPDATE`: it also works, but it needs a read, an application-side check and a write,
  holding the lock across a round trip. The conditional UPDATE is one statement and the CHECK
  constraint rejects invalid quantities. See the README and ADR 0001 for the locking comparison.

### Background work: transactional outbox + DB job polling

- `outbox_jobs(id, type, payload jsonb, status, attempts, max_attempts, next_attempt_at, last_error, ...)`.
  Rows are inserted in the same transaction as the business change.
- `JobRunner` polls with `SELECT ... FOR UPDATE SKIP LOCKED`, claims a batch, runs the handler, and
  marks it DONE, or schedules a retry with exponential backoff, or marks it FAILED (dead) after max_attempts.
- Job types: `RESERVE_ORDER` (also tried synchronously right after submit), `CREATE_SHIPMENT`.

### Carrier integration

- `carrier-sim` endpoints: `POST /shipments` (requires `Idempotency-Key`), `GET /shipments`,
  `POST /admin/faults {mode, times}` with modes `FAIL`, `TIMEOUT`, `DROP_AFTER_SUCCESS`, and `POST /admin/reset`.
- Packing creates a `shipments` row (PENDING, `carrier_idempotency_key` = shipment UUID) plus a
  `CREATE_SHIPMENT` job in one transaction. The job calls the carrier with that same key, so a retry after a
  lost response gets the original shipment back, never a second one.

### Auth

Stateless JWT (HS256, Spring OAuth2 resource server, `NimbusJwtEncoder`). `POST /api/auth/login`.
Roles: `SALES`, `WAREHOUSE`, `SUPERVISOR`. `@PreAuthorize` sits on module service methods.

## Implementation details

- Spring Boot 3.5.x and Spring Modulith 1.4.x are compatible release lines.
- JDK 21 is pinned via `mise.toml`.
- Local ports: Postgres 5452 (compose, overridable via POSTGRES_PORT), backend 8080, frontend 4200 (proxies /api).
- Tests share one Testcontainers Postgres per JVM (`TestcontainersConfiguration`); test logs are WARN (logback-test.xml).
- Frontend unit tests: Angular's Vitest runner (`npm test -- --watch=false`), jsdom, so no browser is needed.
- Auth: demo users (`sales`, `warehouse`, `supervisor`, password `fulfill123`) are seeded by `DemoUserSeeder`
  (disable with SEED_DEMO_USERS=false). JWT carries a `roles` claim and maps to `ROLE_*` authorities.
  Frontend keeps the session in a signal (persisted to localStorage) and logs out on any 401.
- Backend integration tests extend `IntegrationTest` (MockMvc + real login; `bearer("sales")`).
- Inventory writes: `inventory.internal.StockLedger` is the only writer of stock_levels (JdbcTemplate, guarded
  UPDATE ... RETURNING + ledger insert). Order-driven ops (reserve/release/ship) are Propagation.MANDATORY.
  Ledger is append-only via trigger (UPDATE/DELETE/TRUNCATE rejected); tests never clean tables, they create
  uniquely-SKU'd products instead.
- Frontend is zoneless (Angular 21 default). In browser automation, wait for a control's `ng-pristine`/`ng-dirty`
  class before typing into a freshly opened dialog, or the first CD pass overwrites the typed value.
- Orders: `OrderService` (module API) never touches entity mutators directly; `orders.internal.OrderFacts` is the
  bridge so the state machine can't be bypassed. All transitions lock the order row (`findByIdForUpdate`).
  Reads after writes go through `getInternal`, which opens an explicit read transaction.
  Calling `saveAndFlush` on a managed Order can blank new history rows during merge; `orders.flush()` avoids this.
- Submission = tx1 (order SUBMITTED + RESERVE_ORDER outbox job) then inline `ReservationService.reserve` (tx2,
  plus tx3 for STOCK_EXCEPTION). Idempotency: unique `orders.idempotency_key` + request fingerprint; replays
  return 200 + `Idempotent-Replayed: true`; key reuse with a different body is 422.
- Job runner: lease-based claim (`FOR UPDATE SKIP LOCKED` + `locked_until`), backoff with jitter, FAILED after
  max_attempts, settle fenced on `locked_by`. Tests set `fulfillops.jobs.poll-enabled=false` and call `JobRunner.drain()`.
- order_lines unique constraints are DEFERRABLE INITIALLY DEFERRED (Hibernate inserts before orphan deletes).
- Reservation correctness: decision + evidence in `docs/adr/0001-reservation-locking.md`. Concurrency suite is
  `ReservationConcurrencyTest` (real HTTP via `HttpIntegrationTest`, RANDOM_PORT). `scripts/stress-reservations.sh N`
  loops it in fresh JVMs; verified 10/10 runs (500 races). Mutation check: naive check-then-act fails 48/50.
- Fulfillment: one pick list per order (unique order_id); start/pack call `OrderService.transitionForFulfillment`
  inside the fulfillment tx. Packing writes shipment (PENDING, `carrier_idempotency_key = fo-shp-<uuid>`) +
  events + CREATE_SHIPMENT outbox job in one tx. Stock is consumed (SHIP movement) only when the carrier
  confirms. JobRunner defers (does not fail) job types with no handler on this node.
- Carrier: `carrier-sim/` (raw-socket Java, no deps; fault queue via /admin/faults). Backend `CarrierClient`
  classifies IO errors/timeouts/5xx/429 as transient (retry) and other 4xx as permanent. `CreateShipmentJobHandler`:
  read (tx) -> HTTP call (no tx) -> lock + BOOKED + order SHIPPED + stock SHIP (tx). Gives up after max_attempts
  -> shipment FAILED; supervisor `POST /api/shipments/{id}/retry` requeues with the same key.
  `CarrierIntegrationTest` builds the sim image via Testcontainers; mutation check (per-attempt keys) fails it
  with "expected 1 but was 2" carrier bookings.
- UI polish: dashboard (pipeline, attention tiles, low stock, activity), audit log (`GET /api/orders/activity`),
  order filters synced to URL query params, card list for orders under 600px. `scripts/seed.mjs` seeds a realistic
  Maritime distributor via the API (idempotent; needs carrier-sim + poller to book shipments). No horizontal
  page overflow at 390px on dashboard/orders/inventory/editor (checked with Playwright).
- E2E: `e2e/tests/demo.spec.ts` (serial): two browser sessions submit 7-of-10 simultaneously -> one RESERVED, one
  STOCK_EXCEPTION (visible in supervisor queue); then pick/pack with carrier FAIL + DROP_AFTER_SUCCESS -> one
  booking at the carrier, order SHIPPED, stock 3/0. Runs against a live stack (BASE_URL, CARRIER_URL).
  The demo passed 9 consecutive local runs. CI runs it against the containerised stack.
