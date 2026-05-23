-- ------------------------------------------------------------- picking
CREATE TABLE pick_lists (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- One pick list per order: two pickers pressing "start" at once cannot both win.
    order_id     BIGINT      NOT NULL UNIQUE REFERENCES orders (id),
    order_number VARCHAR(16) NOT NULL,
    status       VARCHAR(16) NOT NULL CHECK (status IN ('OPEN', 'COMPLETED')),
    picker       VARCHAR(64) NOT NULL,
    started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    version      BIGINT      NOT NULL DEFAULT 0,
    CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

CREATE TABLE pick_list_lines (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pick_list_id BIGINT       NOT NULL REFERENCES pick_lists (id) ON DELETE CASCADE,
    line_no      INT          NOT NULL,
    product_id   BIGINT       NOT NULL,
    sku          VARCHAR(32)  NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    quantity     INT          NOT NULL CHECK (quantity > 0),
    picked       BOOLEAN      NOT NULL DEFAULT FALSE,
    picked_by    VARCHAR(64),
    picked_at    TIMESTAMPTZ,
    UNIQUE (pick_list_id, line_no)
);

-- ------------------------------------------------------------- shipments
CREATE TABLE shipments (
    id                      UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id                BIGINT        NOT NULL UNIQUE REFERENCES orders (id),
    order_number            VARCHAR(16)   NOT NULL,
    status                  VARCHAR(16)   NOT NULL CHECK (status IN ('PENDING', 'BOOKED', 'FAILED')),
    carrier                 VARCHAR(32)   NOT NULL,
    -- Sent on every booking attempt. The carrier deduplicates on it, so a retry after a lost response
    -- returns the shipment it already created instead of creating a second one.
    carrier_idempotency_key VARCHAR(64)   NOT NULL UNIQUE,
    carrier_shipment_id     VARCHAR(64)   UNIQUE,
    tracking_number         VARCHAR(64),
    parcels                 INT           NOT NULL CHECK (parcels > 0),
    weight_kg               NUMERIC(8,2)  NOT NULL CHECK (weight_kg > 0),
    ship_to_name            VARCHAR(200)  NOT NULL,
    ship_to_address         TEXT          NOT NULL,
    booking_attempts        INT           NOT NULL DEFAULT 0,
    last_error              TEXT,
    packed_by               VARCHAR(64)   NOT NULL,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    booked_at               TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                 BIGINT        NOT NULL DEFAULT 0,
    CHECK ((status = 'BOOKED') = (carrier_shipment_id IS NOT NULL AND booked_at IS NOT NULL))
);

CREATE INDEX shipments_status_idx ON shipments (status, created_at DESC);

CREATE TABLE shipment_events (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shipment_id UUID        NOT NULL REFERENCES shipments (id),
    type        VARCHAR(32) NOT NULL,
    detail      TEXT,
    actor       VARCHAR(64) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX shipment_events_shipment_idx ON shipment_events (shipment_id, id);

CREATE TRIGGER shipment_events_append_only
    BEFORE UPDATE OR DELETE ON shipment_events
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
