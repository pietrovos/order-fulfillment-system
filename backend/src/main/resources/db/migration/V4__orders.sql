-- ------------------------------------------------------------------ orders
CREATE SEQUENCE order_number_seq START 1001;

CREATE TABLE orders (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_number         VARCHAR(16)  NOT NULL UNIQUE
                         DEFAULT ('SO-' || lpad(nextval('order_number_seq')::text, 6, '0')),
    status               VARCHAR(20)  NOT NULL
                         CHECK (status IN ('DRAFT', 'SUBMITTED', 'RESERVED', 'PICKING', 'PACKED', 'SHIPPED',
                                           'CANCELLED', 'STOCK_EXCEPTION')),
    customer_name        VARCHAR(200) NOT NULL,
    customer_email       VARCHAR(200),
    shipping_address     TEXT         NOT NULL,
    notes                TEXT,
    total_amount         NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    -- Client-supplied key; a retried or double-clicked submission maps to the same order.
    idempotency_key      VARCHAR(64)  UNIQUE,
    request_fingerprint  VARCHAR(64),
    exception_reason     TEXT,
    cancel_reason        TEXT,
    created_by           VARCHAR(64)  NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    submitted_at         TIMESTAMPTZ,
    version              BIGINT       NOT NULL DEFAULT 0,
    CHECK ((idempotency_key IS NULL) = (request_fingerprint IS NULL))
);

CREATE INDEX orders_status_idx ON orders (status, created_at DESC);
CREATE INDEX orders_created_idx ON orders (created_at DESC);

CREATE TABLE order_lines (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id     BIGINT        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    line_no      INT           NOT NULL CHECK (line_no > 0),
    product_id   BIGINT        NOT NULL REFERENCES products (id),
    -- Snapshots: an order shows what was sold, even if the catalog changes later.
    sku          VARCHAR(32)   NOT NULL,
    product_name VARCHAR(200)  NOT NULL,
    quantity     INT           NOT NULL CHECK (quantity > 0),
    unit_price   NUMERIC(12,2) NOT NULL CHECK (unit_price >= 0),
    -- Deferred: replacing a draft's lines deletes and re-inserts within one transaction, and the ORM
    -- may issue the inserts first. Uniqueness is still guaranteed at commit.
    CONSTRAINT order_lines_line_no_unique UNIQUE (order_id, line_no) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT order_lines_product_unique UNIQUE (order_id, product_id) DEFERRABLE INITIALLY DEFERRED
);

-- Generalise the append-only guard so its message names whichever table it protects.
CREATE OR REPLACE FUNCTION reject_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only (% rejected)', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

-- Audit trail of every status change (append-only, same trigger as the stock ledger).
CREATE TABLE order_status_history (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id    BIGINT      NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    from_status VARCHAR(20),
    to_status   VARCHAR(20) NOT NULL,
    actor       VARCHAR(64) NOT NULL,
    note        TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX order_status_history_order_idx ON order_status_history (order_id, id);

CREATE TRIGGER order_status_history_append_only
    BEFORE UPDATE OR DELETE ON order_status_history
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

-- ------------------------------------------------------------------ job leases
-- A claimed job is leased, not held under a lock, so no DB transaction stays open while a
-- handler does slow work (e.g. an HTTP call). An expired lease makes the job claimable again.
ALTER TABLE outbox_jobs ADD COLUMN locked_until TIMESTAMPTZ;
ALTER TABLE outbox_jobs ADD COLUMN locked_by VARCHAR(64);
