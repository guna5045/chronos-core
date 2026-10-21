package com.chronoscore.common.domain.workflow;

import com.chronoscore.common.enums.WorkflowNodeStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable entity tracking the execution state of an individual workflow node
 * within a specific workflow execution instance.
 */
public final class WorkflowExecutionNode {

    private final UUID executionId;
    private final String nodeId;
    private final UUID taskId;
    private final WorkflowNodeStatus status;
    private final Instant startedAt;
    private final Instant completedAt;
    private final String errorCode;
    private final String errorMessage;
    private final long version;

    private WorkflowExecutionNode(Builder builder) {
        this.executionId = Objects.requireNonNull(builder.executionId, "executionId must not be null");
        this.nodeId = Objects.requireNonNull(builder.nodeId, "nodeId must not be null");
        this.taskId = builder.taskId;
        this.status = builder.status != null ? builder.status : WorkflowNodeStatus.PENDING;
        this.startedAt = builder.startedAt;
        this.completedAt = builder.completedAt;
        this.errorCode = builder.errorCode;
        this.errorMessage = builder.errorMessage;
        this.version = Math.max(builder.version, 0L);
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public WorkflowNodeStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public long getVersion() {
        return version;
    }

    public Builder toBuilder() {
        return new Builder()
                .executionId(executionId)
                .nodeId(nodeId)
                .taskId(taskId)
                .status(status)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .version(version);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WorkflowExecutionNode that = (WorkflowExecutionNode) o;
        return Objects.equals(executionId, that.executionId) && Objects.equals(nodeId, that.nodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(executionId, nodeId);
    }

    @Override
    public String toString() {
        return "WorkflowExecutionNode{" +
                "executionId=" + executionId +
                ", nodeId='" + nodeId + '\'' +
                ", taskId=" + taskId +
                ", status=" + status +
                ", version=" + version +
                '}';
    }

    public static final class Builder {
        private UUID executionId;
        private String nodeId;
        private UUID taskId;
        private WorkflowNodeStatus status = WorkflowNodeStatus.PENDING;
        private Instant startedAt;
        private Instant completedAt;
        private String errorCode;
        private String errorMessage;
        private long version = 0L;

        private Builder() {
        }

        public Builder executionId(UUID executionId) {
            this.executionId = executionId;
            return this;
        }

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder taskId(UUID taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder status(WorkflowNodeStatus status) {
            this.status = status;
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

        public Builder errorCode(String errorCode) {
            this.errorCode = errorCode;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder version(long version) {
            this.version = version;
            return this;
        }

        public WorkflowExecutionNode build() {
            return new WorkflowExecutionNode(this);
        }
    }
}
