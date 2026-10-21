-- ============================================================================
-- Chronos Core - Flyway Migration V7: Advanced Platform Capabilities Schema (H2)
-- ============================================================================
-- H2-compatible schema for integration tests.
-- ============================================================================

-- 1. Tenant Execution Policies
CREATE TABLE chronos_tenant_policies (
    tenant_id               VARCHAR(128)             NOT NULL,
    max_concurrent_tasks    INTEGER                  NOT NULL DEFAULT 100,
    max_queued_tasks        INTEGER                  NOT NULL DEFAULT 10000,
    tasks_per_second        DOUBLE                   NOT NULL DEFAULT 100.0,
    burst_capacity          INTEGER                  NOT NULL DEFAULT 200,
    max_workflow_executions INTEGER                  NOT NULL DEFAULT 50,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_chronos_tenant_policies PRIMARY KEY (tenant_id),
    CONSTRAINT chk_tp_max_concurrent CHECK (max_concurrent_tasks > 0),
    CONSTRAINT chk_tp_max_queued CHECK (max_queued_tasks > 0),
    CONSTRAINT chk_tp_rate CHECK (tasks_per_second > 0),
    CONSTRAINT chk_tp_burst CHECK (burst_capacity >= 1),
    CONSTRAINT chk_tp_max_wf CHECK (max_workflow_executions > 0)
);

-- 2. Concurrency Key column on chronos_tasks
ALTER TABLE chronos_tasks ADD COLUMN concurrency_key VARCHAR(255);

-- H2 compatible index without partial WHERE clause
CREATE INDEX idx_chronos_tasks_concurrency_key
    ON chronos_tasks (tenant_id, concurrency_key);
