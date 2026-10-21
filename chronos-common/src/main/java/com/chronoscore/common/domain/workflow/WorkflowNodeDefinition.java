package com.chronoscore.common.domain.workflow;

import com.chronoscore.common.enums.TaskPriority;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable definition of an individual node in a workflow DAG.
 */
public final class WorkflowNodeDefinition {

    private final String nodeId;
    private final String taskType;
    private final String payload;
    private final TaskPriority priority;
    private final Duration timeout;
    private final int maxAttempts;

    private WorkflowNodeDefinition(Builder builder) {
        this.nodeId = Objects.requireNonNull(builder.nodeId, "nodeId must not be null");
        this.taskType = Objects.requireNonNull(builder.taskType, "taskType must not be null");
        this.payload = builder.payload != null ? builder.payload : "{}";
        this.priority = builder.priority != null ? builder.priority : TaskPriority.NORMAL;
        this.timeout = builder.timeout;
        this.maxAttempts = builder.maxAttempts > 0 ? builder.maxAttempts : 3;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getTaskType() {
        return taskType;
    }

    public String getPayload() {
        return payload;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WorkflowNodeDefinition that = (WorkflowNodeDefinition) o;
        return maxAttempts == that.maxAttempts &&
                Objects.equals(nodeId, that.nodeId) &&
                Objects.equals(taskType, that.taskType) &&
                Objects.equals(payload, that.payload) &&
                priority == that.priority &&
                Objects.equals(timeout, that.timeout);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId, taskType, payload, priority, timeout, maxAttempts);
    }

    @Override
    public String toString() {
        return "WorkflowNodeDefinition{" +
                "nodeId='" + nodeId + '\'' +
                ", taskType='" + taskType + '\'' +
                ", priority=" + priority +
                ", maxAttempts=" + maxAttempts +
                '}';
    }

    public static final class Builder {
        private String nodeId;
        private String taskType;
        private String payload;
        private TaskPriority priority;
        private Duration timeout;
        private int maxAttempts = 3;

        private Builder() {
        }

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder taskType(String taskType) {
            this.taskType = taskType;
            return this;
        }

        public Builder payload(String payload) {
            this.payload = payload;
            return this;
        }

        public Builder priority(TaskPriority priority) {
            this.priority = priority;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public WorkflowNodeDefinition build() {
            return new WorkflowNodeDefinition(this);
        }
    }
}
