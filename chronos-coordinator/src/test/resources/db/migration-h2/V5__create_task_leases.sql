-- ============================================================================
-- Chronos Core - Flyway Migration V5: Task Execution Leases Schema (H2)
-- ============================================================================
-- H2-compatible schema for integration tests.
-- ============================================================================

CREATE TABLE chronos_task_leases (
    task_id             UUID                     NOT NULL,
    attempt_id          UUID                     NOT NULL,
    worker_id           VARCHAR(255)             NOT NULL,
    attempt_number      INTEGER                  NOT NULL,
    lease_acquired_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_expires_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    leader_epoch        BIGINT                   NOT NULL DEFAULT 0,
    version             BIGINT                   NOT NULL DEFAULT 0,

    CONSTRAINT pk_chronos_task_leases PRIMARY KEY (task_id),
    CONSTRAINT fk_task_leases_task FOREIGN KEY (task_id)
        REFERENCES chronos_tasks (task_id) ON DELETE CASCADE,
    CONSTRAINT fk_task_leases_attempt FOREIGN KEY (attempt_id)
        REFERENCES chronos_execution_attempts (attempt_id) ON DELETE CASCADE,
    CONSTRAINT chk_task_leases_attempt_number CHECK (attempt_number >= 1),
    CONSTRAINT chk_task_leases_version CHECK (version >= 0)
);

CREATE INDEX idx_chronos_leases_expires_at ON chronos_task_leases (lease_expires_at);
CREATE INDEX idx_chronos_leases_worker_id ON chronos_task_leases (worker_id);
CREATE INDEX idx_chronos_leases_attempt_id ON chronos_task_leases (attempt_id);
