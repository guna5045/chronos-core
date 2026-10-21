package com.chronoscore.coordinator.persistence.model;

import com.chronoscore.common.enums.TaskStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable entity representing a durable audit log record for a task state transition.
 * <p>
 * The audit log is strictly append-only, preserving an immutable trace of task lifecycle
 * transitions for operational analysis, root-cause triage, and cluster monitoring.
 */
public final class TaskAuditRecord {

    private final UUID auditId;
    private final UUID taskId;
    private final TaskStatus fromStatus;
    private final TaskStatus toStatus;
    private final int attemptNumber;
    private final String reason;
    private final String metadata;
    private final Instant createdAt;

    private TaskAuditRecord(Builder builder) {
        this.auditId = builder.auditId != null ? builder.auditId : UUID.randomUUID();
        this.taskId = Objects.requireNonNull(builder.taskId, "taskId must not be null");
        this.fromStatus = builder.fromStatus;
        this.toStatus = Objects.requireNonNull(builder.toStatus, "toStatus must not be null");
        this.attemptNumber = Math.max(builder.attemptNumber, 0);
        this.reason = builder.reason;
        this.metadata = builder.metadata;
        this.createdAt = builder.createdAt != null ? builder.createdAt : Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID getAuditId() {
        return auditId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public TaskStatus getFromStatus() {
        return fromStatus;
    }

    public TaskStatus getToStatus() {
        return toStatus;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public String getReason() {
        return reason;
    }

    public String getMetadata() {
        return metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TaskAuditRecord that = (TaskAuditRecord) o;
        return Objects.equals(auditId, that.auditId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(auditId);
    }

    @Override
    public String toString() {
        return "TaskAuditRecord{" +
                "auditId=" + auditId +
                ", taskId=" + taskId +
                ", fromStatus=" + fromStatus +
                ", toStatus=" + toStatus +
                ", attemptNumber=" + attemptNumber +
                ", reason='" + reason + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }

    public static final class Builder {
        private UUID auditId;
        private UUID taskId;
        private TaskStatus fromStatus;
        private TaskStatus toStatus;
        private int attemptNumber = 0;
        private String reason;
        private String metadata;
        private Instant createdAt;

        private Builder() {
        }

        public Builder auditId(UUID auditId) {
            this.auditId = auditId;
            return this;
        }

        public Builder taskId(UUID taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder fromStatus(TaskStatus fromStatus) {
            this.fromStatus = fromStatus;
            return this;
        }

        public Builder toStatus(TaskStatus toStatus) {
            this.toStatus = toStatus;
            return this;
        }

        public Builder attemptNumber(int attemptNumber) {
            this.attemptNumber = attemptNumber;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder metadata(String metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public TaskAuditRecord build() {
            return new TaskAuditRecord(this);
        }
    }
}
