-- ============================================================================
-- Chronos Core - Flyway Migration V3: Task Audit Log Schema
-- ============================================================================
-- Establishes the append-only audit trail for task lifecycle state transitions.
-- Every status transition recorded through the transactional persistence service
-- records a permanent, immutable record here.
-- ============================================================================

CREATE TABLE chronos_task_audit_log (
    audit_id        UUID                     NOT NULL,
    task_id         UUID                     NOT NULL,
    from_status     VARCHAR(50),
    to_status       VARCHAR(50)              NOT NULL,
    attempt_number  INTEGER                  NOT NULL DEFAULT 0,
    reason          TEXT,
    metadata        TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_chronos_task_audit_log PRIMARY KEY (audit_id),
    CONSTRAINT fk_task_audit_task FOREIGN KEY (task_id)
        REFERENCES chronos_tasks (task_id) ON DELETE CASCADE
);

-- Indexes for audit retrieval by task and chronological ordering
CREATE INDEX idx_chronos_audit_task_id ON chronos_task_audit_log (task_id);
CREATE INDEX idx_chronos_audit_created_at ON chronos_task_audit_log (created_at);
