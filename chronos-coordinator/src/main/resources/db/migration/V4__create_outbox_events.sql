-- ============================================================================
-- Chronos Core - Flyway Migration V4: Transactional Outbox Events Schema
-- ============================================================================
-- Establishes the durable transactional outbox table.
-- State mutations and corresponding outbox event records are committed
-- atomically within the same database transaction, ensuring at-least-once
-- publication guarantees without dual-write race conditions.
-- ============================================================================

CREATE TABLE chronos_outbox_events (
    event_id        UUID                     NOT NULL,
    aggregate_type  VARCHAR(100)             NOT NULL,
    aggregate_id    VARCHAR(255)             NOT NULL,
    event_type      VARCHAR(100)             NOT NULL,
    payload         TEXT                     NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at    TIMESTAMP WITH TIME ZONE,
    attempt_count   INTEGER                  NOT NULL DEFAULT 0,

    CONSTRAINT pk_chronos_outbox_events PRIMARY KEY (event_id),
    CONSTRAINT chk_outbox_attempt_count CHECK (attempt_count >= 0)
);

-- Partial index targeting only pending (unpublished) outbox events ordered by creation time
CREATE INDEX idx_chronos_outbox_pending
    ON chronos_outbox_events (created_at)
    WHERE published_at IS NULL;

-- Index for aggregate-level event trace retrieval
CREATE INDEX idx_chronos_outbox_aggregate
    ON chronos_outbox_events (aggregate_type, aggregate_id);
