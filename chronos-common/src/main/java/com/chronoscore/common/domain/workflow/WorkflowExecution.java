package com.chronoscore.common.domain.workflow;

import com.chronoscore.common.enums.WorkflowStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable entity representing an active or completed workflow execution instance.
 */
public final class WorkflowExecution {

    private final UUID executionId;
    private final UUID workflowId;
    private final UUID versionId;
    private final WorkflowStatus status;
    private final String tenantId;
    private final Instant createdAt;
    private final Instant startedAt;
    private final Instant completedAt;
    private final long version;

    private WorkflowExecution(Builder builder) {
        this.executionId = builder.executionId != null ? builder.executionId : UUID.randomUUID();
        this.workflowId = Objects.requireNonNull(builder.workflowId, "workflowId must not be null");
        this.versionId = Objects.requireNonNull(builder.versionId, "versionId must not be null");
        this.status = builder.status != null ? builder.status : WorkflowStatus.PENDING;
        this.tenantId = Objects.requireNonNull(builder.tenantId, "tenantId must not be null");
        this.createdAt = builder.createdAt != null ? builder.createdAt : Instant.now();
        this.startedAt = builder.startedAt;
        this.completedAt = builder.completedAt;
        this.version = Math.max(builder.version, 0L);
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public WorkflowStatus getStatus() {
        return status;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public long getVersion() {
        return version;
    }

    public Builder toBuilder() {
        return new Builder()
                .executionId(executionId)
                .workflowId(workflowId)
                .versionId(versionId)
                .status(status)
                .tenantId(tenantId)
                .createdAt(createdAt)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .version(version);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WorkflowExecution that = (WorkflowExecution) o;
        return Objects.equals(executionId, that.executionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(executionId);
    }

    @Override
    public String toString() {
        return "WorkflowExecution{" +
                "executionId=" + executionId +
                ", workflowId=" + workflowId +
                ", versionId=" + versionId +
                ", status=" + status +
                ", version=" + version +
                '}';
    }

    public static final class Builder {
        private UUID executionId;
        private UUID workflowId;
        private UUID versionId;
        private WorkflowStatus status = WorkflowStatus.PENDING;
        private String tenantId;
        private Instant createdAt;
        private Instant startedAt;
        private Instant completedAt;
        private long version = 0L;

        private Builder() {
        }

        public Builder executionId(UUID executionId) {
            this.executionId = executionId;
            return this;
        }

        public Builder workflowId(UUID workflowId) {
            this.workflowId = workflowId;
            return this;
        }

        public Builder versionId(UUID versionId) {
            this.versionId = versionId;
            return this;
        }

        public Builder status(WorkflowStatus status) {
            this.status = status;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        public Builder completedAt(Instant completedAt) {
            this.completedAt = completedAt;
            return this;
        }

        public Builder version(long version) {
            this.version = version;
            return this;
        }

        public WorkflowExecution build() {
            return new WorkflowExecution(this);
        }
    }
}
