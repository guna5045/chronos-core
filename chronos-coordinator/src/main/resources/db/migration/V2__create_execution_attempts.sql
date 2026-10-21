-- ============================================================================
-- Chronos Core - Flyway Migration V2: Execution Attempts Schema
-- ============================================================================
-- Establishes the durable execution-attempt history table.
-- Execution attempts preserve the complete history of physical execution tries
-- across worker nodes, enabling operational visibility and failure triage.
-- ============================================================================

CREATE TABLE chronos_execution_attempts (
    attempt_id       UUID                     NOT NULL,
    task_id          UUID                     NOT NULL,
    worker_id        VARCHAR(255)             NOT NULL,
    attempt_number   INTEGER                  NOT NULL,
    status           VARCHAR(50)              NOT NULL,
    started_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at     TIMESTAMP WITH TIME ZONE,
    error_code       VARCHAR(100),
    error_message    TEXT,

    CONSTRAINT pk_chronos_execution_attempts PRIMARY KEY (attempt_id),
    CONSTRAINT fk_execution_attempts_task FOREIGN KEY (task_id)
        REFERENCES chronos_tasks (task_id) ON DELETE CASCADE,
    CONSTRAINT uq_execution_attempts_task_attempt UNIQUE (task_id, attempt_number),
    CONSTRAINT chk_execution_attempts_attempt_number CHECK (attempt_number >= 1)
);

-- Indexes to support execution history lookups and worker activity auditing
CREATE INDEX idx_chronos_attempts_task_id ON chronos_execution_attempts (task_id);
CREATE INDEX idx_chronos_attempts_worker_id ON chronos_execution_attempts (worker_id);
CREATE INDEX idx_chronos_attempts_status ON chronos_execution_attempts (status);
CREATE INDEX idx_chronos_attempts_started_at ON chronos_execution_attempts (started_at);
