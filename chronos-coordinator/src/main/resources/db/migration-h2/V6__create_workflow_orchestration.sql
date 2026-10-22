-- ============================================================================
-- Chronos Core - Flyway Migration V6: Workflow & DAG Orchestration Schema (H2)
-- ============================================================================
-- H2-compatible schema for integration tests.
-- ============================================================================

-- 1. Reusable Workflow Definitions
CREATE TABLE chronos_workflows (
    workflow_id     UUID                     NOT NULL,
    name            VARCHAR(128)             NOT NULL,
    description     TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_chronos_workflows PRIMARY KEY (workflow_id),
    CONSTRAINT uq_chronos_workflows_name UNIQUE (name)
);

-- 2. Versioned DAG Snapshots
CREATE TABLE chronos_workflow_versions (
    version_id      UUID                     NOT NULL,
    workflow_id     UUID                     NOT NULL,
    version         INTEGER                  NOT NULL,
    status          VARCHAR(32)              NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_chronos_workflow_versions PRIMARY KEY (version_id),
    CONSTRAINT fk_wf_versions_workflow FOREIGN KEY (workflow_id)
        REFERENCES chronos_workflows (workflow_id) ON DELETE CASCADE,
    CONSTRAINT uq_chronos_workflow_version UNIQUE (workflow_id, version),
    CONSTRAINT chk_wf_version_number CHECK (version >= 1)
);

-- 3. Workflow DAG Nodes (Definition)
CREATE TABLE chronos_workflow_nodes (
    version_id      UUID                     NOT NULL,
    node_id         VARCHAR(128)             NOT NULL,
    task_type       VARCHAR(128)             NOT NULL,
    payload         TEXT                     NOT NULL,
    priority        VARCHAR(32)              NOT NULL DEFAULT 'NORMAL',
    timeout_ms      BIGINT,
    max_attempts    INTEGER                  NOT NULL DEFAULT 3,

    CONSTRAINT pk_chronos_workflow_nodes PRIMARY KEY (version_id, node_id),
    CONSTRAINT fk_wf_nodes_version FOREIGN KEY (version_id)
        REFERENCES chronos_workflow_versions (version_id) ON DELETE CASCADE,
    CONSTRAINT chk_wf_nodes_max_attempts CHECK (max_attempts >= 1)
);

-- 4. Workflow DAG Directed Dependency Edges
CREATE TABLE chronos_workflow_edges (
    version_id      UUID                     NOT NULL,
    from_node_id    VARCHAR(128)             NOT NULL,
    to_node_id      VARCHAR(128)             NOT NULL,

    CONSTRAINT pk_chronos_workflow_edges PRIMARY KEY (version_id, from_node_id, to_node_id),
    CONSTRAINT fk_wf_edges_version FOREIGN KEY (version_id)
        REFERENCES chronos_workflow_versions (version_id) ON DELETE CASCADE
);

-- 5. Workflow Execution Instances
CREATE TABLE chronos_workflow_executions (
    execution_id    UUID                     NOT NULL,
    workflow_id     UUID                     NOT NULL,
    version_id      UUID                     NOT NULL,
    status          VARCHAR(32)              NOT NULL,
    tenant_id       VARCHAR(128)             NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at      TIMESTAMP WITH TIME ZONE,
    completed_at    TIMESTAMP WITH TIME ZONE,
    version         BIGINT                   NOT NULL DEFAULT 0,

    CONSTRAINT pk_chronos_workflow_executions PRIMARY KEY (execution_id),
    CONSTRAINT fk_wf_executions_workflow FOREIGN KEY (workflow_id)
        REFERENCES chronos_workflows (workflow_id),
    CONSTRAINT fk_wf_executions_version FOREIGN KEY (version_id)
        REFERENCES chronos_workflow_versions (version_id),
    CONSTRAINT chk_wf_execution_version CHECK (version >= 0)
);

-- 6. Workflow Execution Node States
CREATE TABLE chronos_workflow_execution_nodes (
    execution_id    UUID                     NOT NULL,
    node_id         VARCHAR(128)             NOT NULL,
    task_id         UUID,
    status          VARCHAR(32)              NOT NULL,
    started_at      TIMESTAMP WITH TIME ZONE,
    completed_at    TIMESTAMP WITH TIME ZONE,
    error_code      VARCHAR(128),
    error_message   TEXT,
    version         BIGINT                   NOT NULL DEFAULT 0,

    CONSTRAINT pk_chronos_workflow_execution_nodes PRIMARY KEY (execution_id, node_id),
    CONSTRAINT fk_wf_exec_nodes_execution FOREIGN KEY (execution_id)
        REFERENCES chronos_workflow_executions (execution_id) ON DELETE CASCADE,
    CONSTRAINT fk_wf_exec_nodes_task FOREIGN KEY (task_id)
        REFERENCES chronos_tasks (task_id) ON DELETE SET NULL,
    CONSTRAINT chk_wf_exec_node_version CHECK (version >= 0)
);

-- 7. Workflow Audit Trail
CREATE TABLE chronos_workflow_audit_log (
    audit_id        UUID                     NOT NULL,
    execution_id    UUID                     NOT NULL,
    from_status     VARCHAR(32),
    to_status       VARCHAR(32)              NOT NULL,
    reason          TEXT,
    metadata        TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_chronos_workflow_audit_log PRIMARY KEY (audit_id),
    CONSTRAINT fk_wf_audit_execution FOREIGN KEY (execution_id)
        REFERENCES chronos_workflow_executions (execution_id) ON DELETE CASCADE
);

-- Indexes for performance and relationship lookups
CREATE INDEX idx_chronos_wf_versions_workflow ON chronos_workflow_versions (workflow_id);
CREATE INDEX idx_chronos_wf_nodes_version ON chronos_workflow_nodes (version_id);
CREATE INDEX idx_chronos_wf_edges_version ON chronos_workflow_edges (version_id);
CREATE INDEX idx_chronos_wf_edges_from ON chronos_workflow_edges (version_id, from_node_id);
CREATE INDEX idx_chronos_wf_edges_to ON chronos_workflow_edges (version_id, to_node_id);
CREATE INDEX idx_chronos_wf_exec_workflow ON chronos_workflow_executions (workflow_id);
CREATE INDEX idx_chronos_wf_exec_status ON chronos_workflow_executions (status);
CREATE INDEX idx_chronos_wf_exec_tenant ON chronos_workflow_executions (tenant_id);
CREATE INDEX idx_chronos_wf_exec_nodes_task ON chronos_workflow_execution_nodes (task_id);
CREATE INDEX idx_chronos_wf_audit_exec ON chronos_workflow_audit_log (execution_id);
