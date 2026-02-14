# FulfillOps development notes

Implementation notes for the stock, order and shipment workflows. Setup instructions and diagrams are
in the README.

## Development checklist

- [x] 1. Scaffold: backend, frontend, docker-compose, Flyway baseline, CI (backend tests with Testcontainers + frontend tests)
- [x] 2. Auth and roles: SALES, WAREHOUSE, SUPERVISOR; JWT; method-level authorization; seeded users
- [x] 3. Catalog and inventory: products, one warehouse, on-hand / reserved / available, append-only `inventory_movements` ledger
- [ ] 4. Orders: editor with FormArray line items, explicit state machine, idempotent submission
- [ ] 5. Reservation correctness: atomic conditional UPDATE, release on cancel, concurrency test (10 in stock, 2x7) repeated in a loop
- [ ] 6. Fulfillment: picking + packing screens, supervisor stock-exception queue, shipment timeline
- [ ] 7. Carrier integration: simulated carrier container (fail / timeout / succeed-then-drop), outbox with retries, carrier idempotency key, lost-response integration test
- [ ] 8. UI polish: searchable/sortable order table, inventory dashboard, movement history, responsive layout, loading/empty/error states, realistic seed script
- [ ] 9. Playwright e2e demo: 2 concurrent 7-of-10 orders -> one exception; carrier failure -> recovery with one shipment
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
