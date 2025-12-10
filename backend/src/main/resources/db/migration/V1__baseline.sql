-- Baseline: shared infrastructure used by every module.

-- Transactional outbox / job queue. Rows are written in the same transaction as the
-- business change and processed by a poller using FOR UPDATE SKIP LOCKED.
CREATE TABLE outbox_jobs (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type            VARCHAR(64)  NOT NULL,
    aggregate_id    VARCHAR(64)  NOT NULL,
    payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    attempts        INT          NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts    INT          NOT NULL DEFAULT 8 CHECK (max_attempts > 0),
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);

-- The poller only ever scans due, pending work.
CREATE INDEX outbox_jobs_due_idx ON outbox_jobs (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX outbox_jobs_aggregate_idx ON outbox_jobs (type, aggregate_id);
