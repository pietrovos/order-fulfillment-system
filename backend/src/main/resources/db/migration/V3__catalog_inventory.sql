-- ---------------------------------------------------------------- catalog
CREATE TABLE products (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sku         VARCHAR(32)   NOT NULL UNIQUE CHECK (sku ~ '^[A-Z0-9][A-Z0-9-]*$'),
    name        VARCHAR(200)  NOT NULL,
    description TEXT,
    unit_price  NUMERIC(12,2) NOT NULL CHECK (unit_price >= 0),
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    version     BIGINT        NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- -------------------------------------------------------------- inventory
CREATE TABLE warehouses (
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(16)  NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL
);

-- Single warehouse for now; the schema is keyed by warehouse so a second one is additive.
INSERT INTO warehouses (code, name) VALUES ('MAIN', 'Main distribution centre');

-- Current position per product. The invariants live in the database, not only in Java:
-- nothing can make reserved negative or reserve more than is physically on hand.
CREATE TABLE stock_levels (
    product_id    BIGINT  NOT NULL REFERENCES products (id),
    warehouse_id  BIGINT  NOT NULL REFERENCES warehouses (id),
    on_hand       INT     NOT NULL DEFAULT 0 CHECK (on_hand >= 0),
    reserved      INT     NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    available     INT     GENERATED ALWAYS AS (on_hand - reserved) STORED,
    reorder_point INT     NOT NULL DEFAULT 0 CHECK (reorder_point >= 0),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (product_id, warehouse_id),
    CONSTRAINT stock_reserved_within_on_hand CHECK (reserved <= on_hand)
);

-- Append-only ledger: every change to stock_levels writes exactly one row here in the same
-- transaction, recording both deltas and the resulting position.
CREATE TABLE inventory_movements (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id      BIGINT      NOT NULL REFERENCES products (id),
    warehouse_id    BIGINT      NOT NULL REFERENCES warehouses (id),
    type            VARCHAR(16) NOT NULL
                    CHECK (type IN ('RECEIPT', 'ADJUSTMENT', 'RESERVE', 'RELEASE', 'SHIP')),
    on_hand_delta   INT         NOT NULL,
    reserved_delta  INT         NOT NULL,
    on_hand_after   INT         NOT NULL CHECK (on_hand_after >= 0),
    reserved_after  INT         NOT NULL CHECK (reserved_after >= 0),
    reference_type  VARCHAR(16) NOT NULL CHECK (reference_type IN ('MANUAL', 'ORDER')),
    reference_id    VARCHAR(64),
    reason          TEXT,
    actor           VARCHAR(64) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (on_hand_delta <> 0 OR reserved_delta <> 0)
);

CREATE INDEX inventory_movements_product_idx ON inventory_movements (product_id, id DESC);
CREATE INDEX inventory_movements_reference_idx ON inventory_movements (reference_type, reference_id);

CREATE FUNCTION reject_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'inventory_movements is append-only (% rejected)', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER inventory_movements_append_only
    BEFORE UPDATE OR DELETE ON inventory_movements
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER inventory_movements_no_truncate
    BEFORE TRUNCATE ON inventory_movements
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();
