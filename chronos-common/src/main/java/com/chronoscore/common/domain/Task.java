package com.chronoscore.common.domain;

import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.common.statemachine.TaskStateMachine;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Core domain entity representing a task submitted to Chronos Core.
 * <p>
 * Key Architectural Guarantees:
 * <ul>
 *   <li><b>Idempotency</b>: {@code idempotencyKey} protects against duplicate submissions under network retries.</li>
 *   <li><b>Optimistic Concurrency &amp; Fencing</b>: {@code version} is incremented on every state mutation to detect stale updates.</li>
 *   <li><b>Deterministic Lifecycle</b>: All status transitions are validated against {@link TaskStateMachine}.</li>
 * </ul>
 */
public final class Task {

    private final UUID taskId;
    private final String taskType;
    private final String payload;
    private final TaskPriority priority;
    private final TaskStatus status;
    private final String tenantId;
    private final String idempotencyKey;
    private final String concurrencyKey;
    private final Instant createdAt;
    private final Instant scheduledAt;
    private final Instant deadline;
    private final Duration timeout;
    private final int maxAttempts;
    private final int currentAttempt;
    private final long version;

    private Task(Builder builder) {
        this.taskId = Objects.requireNonNull(builder.taskId, "taskId must not be null");
        this.taskType = Objects.requireNonNull(builder.taskType, "taskType must not be null");
        this.payload = Objects.requireNonNull(builder.payload, "payload must not be null");
        this.priority = builder.priority != null ? builder.priority : TaskPriority.NORMAL;
        this.status = builder.status != null ? builder.status : TaskStatus.CREATED;
        this.tenantId = Objects.requireNonNull(builder.tenantId, "tenantId must not be null");
        this.idempotencyKey = builder.idempotencyKey;
        this.concurrencyKey = builder.concurrencyKey;
        this.createdAt = builder.createdAt != null ? builder.createdAt : Instant.now();
        this.scheduledAt = builder.scheduledAt;
        this.deadline = builder.deadline;
        this.timeout = builder.timeout;
        this.maxAttempts = builder.maxAttempts > 0 ? builder.maxAttempts : 3;
        this.currentAttempt = Math.max(builder.currentAttempt, 0);
        this.version = Math.max(builder.version, 0L);
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID getTaskId() {
        return taskId;
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

    public TaskStatus getStatus() {
        return status;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getConcurrencyKey() {
        return concurrencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public int getCurrentAttempt() {
        return currentAttempt;
    }

    public long getVersion() {
        return version;
    }

    /**
     * Creates an updated Task instance with a new status, validating the state transition
     * and incrementing the optimistic concurrency version.
     *
     * @param newStatus target status.
     * @return updated Task with incremented version.
     */
    public Task withStatus(TaskStatus newStatus) {
        TaskStateMachine.transition(this.taskId, this.status, newStatus);
        return toBuilder()
                .status(newStatus)
                .version(this.version + 1)
                .build();
    }

    /**
     * Creates an updated Task instance reflecting a new execution attempt.
     *
     * @return updated Task with currentAttempt incremented and version bumped.
     */
    public Task withIncrementedAttempt() {
        return toBuilder()
                .currentAttempt(this.currentAttempt + 1)
                .version(this.version + 1)
                .build();
    }

    /**
     * Creates a builder pre-populated with all properties of this Task.
     */
    public Builder toBuilder() {
        return new Builder()
                .taskId(this.taskId)
                .taskType(this.taskType)
                .payload(this.payload)
                .priority(this.priority)
                .status(this.status)
                .tenantId(this.tenantId)
                .idempotencyKey(this.idempotencyKey)
                .concurrencyKey(this.concurrencyKey)
                .createdAt(this.createdAt)
                .scheduledAt(this.scheduledAt)
                .deadline(this.deadline)
                .timeout(this.timeout)
                .maxAttempts(this.maxAttempts)
                .currentAttempt(this.currentAttempt)
                .version(this.version);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Task task = (Task) o;
        return Objects.equals(taskId, task.taskId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId);
    }

    @Override
    public String toString() {
        return "Task{" +
                "taskId=" + taskId +
                ", taskType='" + taskType + '\'' +
                ", priority=" + priority +
                ", status=" + status +
                ", tenantId='" + tenantId + '\'' +
                ", currentAttempt=" + currentAttempt +
                "/" + maxAttempts +
                ", version=" + version +
                '}';
    }

    public static final class Builder {
        private UUID taskId;
        private String taskType;
        private String payload;
        private TaskPriority priority = TaskPriority.NORMAL;
        private TaskStatus status = TaskStatus.CREATED;
        private String tenantId;
        private String idempotencyKey;
        private String concurrencyKey;
        private Instant createdAt;
        private Instant scheduledAt;
        private Instant deadline;
        private Duration timeout;
        private int maxAttempts = 3;
        private int currentAttempt = 0;
        private long version = 0L;

        private Builder() {
        }

        public Builder taskId(UUID taskId) {
            this.taskId = taskId;
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

        public Builder status(TaskStatus status) {
            this.status = status;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder concurrencyKey(String concurrencyKey) {
            this.concurrencyKey = concurrencyKey;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder scheduledAt(Instant scheduledAt) {
            this.scheduledAt = scheduledAt;
            return this;
        }

        public Builder deadline(Instant deadline) {
            this.deadline = deadline;
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

        public Builder currentAttempt(int currentAttempt) {
            this.currentAttempt = currentAttempt;
            return this;
        }

        public Builder version(long version) {
            this.version = version;
            return this;
        }

        public Task build() {
            return new Task(this);
        }
    }
}
