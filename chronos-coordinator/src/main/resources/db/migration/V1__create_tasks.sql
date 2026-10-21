-- ============================================================================
-- Chronos Core - Flyway Migration V1: Task Persistence Schema
-- ============================================================================
-- Establishes the authoritative durable persistence table for Task entities.
-- Concurrency: Optimistic locking via the version column.
-- Idempotency: Enforced by a partial unique index on (tenant_id, idempotency_key).
-- ============================================================================

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

-- ============================================================================
-- Idempotency Strategy
-- ============================================================================
-- Partial unique index ensures that within a tenant, duplicate submissions
-- with the same non-null idempotency key are rejected by PostgreSQL.
-- Multiple tasks with NULL idempotency keys are freely permitted.
CREATE UNIQUE INDEX uq_chronos_tasks_tenant_idempotency
    ON chronos_tasks (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- ============================================================================
-- Query & Operational Indexes
-- ============================================================================
-- Index on status for operational state filtering and lifecycle metrics
CREATE INDEX idx_chronos_tasks_status ON chronos_tasks (status);

-- Index on priority for prioritization analysis
CREATE INDEX idx_chronos_tasks_priority ON chronos_tasks (priority);

-- Index on scheduled_at for time-based scheduling queries
CREATE INDEX idx_chronos_tasks_scheduled_at ON chronos_tasks (scheduled_at);

-- Index on tenant_id for tenant-partitioned retrieval and quota enforcement
CREATE INDEX idx_chronos_tasks_tenant_id ON chronos_tasks (tenant_id);

-- Index on created_at for time-range filtering and historical analysis
CREATE INDEX idx_chronos_tasks_created_at ON chronos_tasks (created_at);

-- Index on deadline to support deadline-exceeded detection and alerting
CREATE INDEX idx_chronos_tasks_deadline ON chronos_tasks (deadline);

-- ============================================================================
-- Scheduler Queue Acquisition Index
-- ============================================================================
-- Partial index targeting only ready QUEUED tasks, ordered by priority,
-- scheduled_at, and created_at to facilitate future FOR UPDATE SKIP LOCKED
-- task queue polling.
CREATE INDEX idx_chronos_tasks_queued
    ON chronos_tasks (priority, scheduled_at, created_at)
    WHERE status = 'QUEUED';
