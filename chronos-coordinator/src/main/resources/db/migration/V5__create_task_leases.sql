-- ============================================================================
-- Chronos Core - Flyway Migration V5: Task Execution Leases Schema
-- ============================================================================
-- Establishes the durable execution-lease tracking table for active tasks.
-- Distinguishes active worker execution from orphaned tasks following worker crash
-- or network partition.
--
-- Architectural Guarantees:
-- 1. Distinct from Redis distributed scheduling lock (which guarantees dispatch exclusivity).
-- 2. Tied to specific physical execution attempt and worker node.
-- 3. Enables recovery scheduler to detect expired leases and orphaned executions
--    using FOR UPDATE SKIP LOCKED without loading all tasks into memory.
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

-- Operational indexes for recovery polling and worker failure eviction
CREATE INDEX idx_chronos_leases_expires_at ON chronos_task_leases (lease_expires_at);
CREATE INDEX idx_chronos_leases_worker_id ON chronos_task_leases (worker_id);
CREATE INDEX idx_chronos_leases_attempt_id ON chronos_task_leases (attempt_id);
