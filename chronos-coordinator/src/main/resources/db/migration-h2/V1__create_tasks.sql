-- H2-compatible schema for integration tests
CREATE TABLE chronos_tasks (
    task_id          UUID                     NOT NULL,
    task_type        VARCHAR(255)             NOT NULL,
    payload          TEXT                     NOT NULL,
    priority         VARCHAR(50)              NOT NULL,
    status           VARCHAR(50)              NOT NULL,
    tenant_id        VARCHAR(255)             NOT NULL,
    idempotency_key  VARCHAR(255),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    scheduled_at     TIMESTAMP WITH TIME ZONE,
    deadline         TIMESTAMP WITH TIME ZONE,
    timeout_ms       BIGINT,
    max_attempts     INTEGER                  NOT NULL,
    current_attempt  INTEGER                  NOT NULL DEFAULT 0,
    version          BIGINT                   NOT NULL DEFAULT 0,

    CONSTRAINT pk_chronos_tasks PRIMARY KEY (task_id),
    CONSTRAINT chk_tasks_max_attempts CHECK (max_attempts > 0),
    CONSTRAINT chk_tasks_current_attempt CHECK (current_attempt >= 0),
    CONSTRAINT chk_tasks_version CHECK (version >= 0),
    CONSTRAINT chk_tasks_timeout CHECK (timeout_ms IS NULL OR timeout_ms >= 0)
);

CREATE UNIQUE INDEX uq_chronos_tasks_tenant_idempotency
    ON chronos_tasks (tenant_id, idempotency_key);

CREATE INDEX idx_chronos_tasks_status ON chronos_tasks (status);
CREATE INDEX idx_chronos_tasks_priority ON chronos_tasks (priority);
CREATE INDEX idx_chronos_tasks_scheduled_at ON chronos_tasks (scheduled_at);
CREATE INDEX idx_chronos_tasks_tenant_id ON chronos_tasks (tenant_id);
CREATE INDEX idx_chronos_tasks_created_at ON chronos_tasks (created_at);
CREATE INDEX idx_chronos_tasks_deadline ON chronos_tasks (deadline);
CREATE INDEX idx_chronos_tasks_queued ON chronos_tasks (priority, scheduled_at, created_at);
