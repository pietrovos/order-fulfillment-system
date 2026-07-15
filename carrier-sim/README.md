# SIMSHIP carrier simulator

A small, intentionally unreliable carrier API used by FulfillOps for local development and tests. Plain
Java 21 with raw sockets and no dependencies, so it can fail below the HTTP layer.

| Endpoint | Purpose |
|---|---|
| `POST /shipments` | Book a shipment. Requires `Idempotency-Key`. `201` new, `200` + `Idempotent-Replayed: true` for a known key |
| `GET /shipments?idempotencyKey=` | Inspect bookings (tests assert exactly one per key) |
| `POST /admin/faults` `{"mode":"…","times":n}` | Queue faults for the next *n* bookings (FIFO) |
| `POST /admin/reset` | Forget bookings and queued faults |
| `GET /admin/stats` | Request, booking and replay counters |

Fault modes:

- `FAIL`: respond `503`, book nothing.
- `TIMEOUT`: accept the request and never answer (hangs for `TIMEOUT_MS`, default 30 s); book nothing.
- `DROP_AFTER_SUCCESS`: book the shipment, then reset the TCP connection without sending a response.
  The client cannot tell whether the booking happened. Retrying with the same idempotency key returns
  the existing booking.
- `SUCCEED`: normal behaviour (also the default when no fault is queued).

```bash
docker compose up -d carrier
curl -s -XPOST localhost:8091/admin/faults -d '{"mode":"DROP_AFTER_SUCCESS","times":1}'
```
