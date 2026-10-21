package com.chronoscore.common.domain;

import com.chronoscore.common.enums.ExecutionAttemptStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing an individual physical execution attempt of a Task.
 * <p>
 * A {@link Task} is the logical entity across multiple retries, while an
 * {@code ExecutionAttempt} tracks a single attempt performed on a specific worker node.
 */
public final class ExecutionAttempt {

    private final UUID attemptId;
    private final UUID taskId;
    private final String workerId;
    private final int attemptNumber;
    private final ExecutionAttemptStatus status;
    private final Instant startedAt;
    private final Instant completedAt;
    private final String errorCode;
    private final String errorMessage;

    private ExecutionAttempt(Builder builder) {
        this.attemptId = Objects.requireNonNull(builder.attemptId, "attemptId must not be null");
        this.taskId = Objects.requireNonNull(builder.taskId, "taskId must not be null");
        this.workerId = Objects.requireNonNull(builder.workerId, "workerId must not be null");
        this.attemptNumber = builder.attemptNumber > 0 ? builder.attemptNumber : 1;
        this.status = builder.status != null ? builder.status : ExecutionAttemptStatus.PENDING;
        this.startedAt = builder.startedAt != null ? builder.startedAt : Instant.now();
        this.completedAt = builder.completedAt;
        this.errorCode = builder.errorCode;
        this.errorMessage = builder.errorMessage;
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public ExecutionAttemptStatus getStatus() {
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

    /**
     * Creates an updated attempt marked as SUCCEEDED.
     *
     * @param completedAt timestamp of completion.
     * @return completed ExecutionAttempt.
     */
    public ExecutionAttempt withSuccess(Instant completedAt) {
        return toBuilder()
                .status(ExecutionAttemptStatus.SUCCEEDED)
                .completedAt(completedAt != null ? completedAt : Instant.now())
                .build();
    }

    /**
     * Creates an updated attempt marked as FAILED.
     *
     * @param errorCode    failure code or classification.
     * @param errorMessage descriptive failure reason.
     * @param completedAt  timestamp of failure.
     * @return failed ExecutionAttempt.
     */
    public ExecutionAttempt withFailure(String errorCode, String errorMessage, Instant completedAt) {
        return toBuilder()
                .status(ExecutionAttemptStatus.FAILED)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .completedAt(completedAt != null ? completedAt : Instant.now())
                .build();
    }

    /**
     * Creates an updated attempt marked as CANCELLED.
     *
     * @param reason      cancellation reason.
     * @param completedAt timestamp of cancellation.
     * @return cancelled ExecutionAttempt.
     */
    public ExecutionAttempt withCancellation(String reason, Instant completedAt) {
        return toBuilder()
                .status(ExecutionAttemptStatus.CANCELLED)
                .errorMessage(reason)
                .completedAt(completedAt != null ? completedAt : Instant.now())
                .build();
    }

    public Builder toBuilder() {
        return new Builder()
                .attemptId(this.attemptId)
                .taskId(this.taskId)
                .workerId(this.workerId)
                .attemptNumber(this.attemptNumber)
                .status(this.status)
                .startedAt(this.startedAt)
                .completedAt(this.completedAt)
                .errorCode(this.errorCode)
                .errorMessage(this.errorMessage);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExecutionAttempt that = (ExecutionAttempt) o;
        return Objects.equals(attemptId, that.attemptId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(attemptId);
    }

    @Override
    public String toString() {
        return "ExecutionAttempt{" +
                "attemptId=" + attemptId +
                ", taskId=" + taskId +
                ", workerId='" + workerId + '\'' +
                ", attemptNumber=" + attemptNumber +
                ", status=" + status +
                ", startedAt=" + startedAt +
                ", completedAt=" + completedAt +
                '}';
    }

    public static final class Builder {
        private UUID attemptId;
        private UUID taskId;
        private String workerId;
        private int attemptNumber = 1;
        private ExecutionAttemptStatus status = ExecutionAttemptStatus.PENDING;
        private Instant startedAt;
        private Instant completedAt;
        private String errorCode;
        private String errorMessage;

        private Builder() {
        }

        public Builder attemptId(UUID attemptId) {
            this.attemptId = attemptId;
            return this;
        }

        public Builder taskId(UUID taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder workerId(String workerId) {
            this.workerId = workerId;
            return this;
        }

        public Builder attemptNumber(int attemptNumber) {
            this.attemptNumber = attemptNumber;
            return this;
        }

        public Builder status(ExecutionAttemptStatus status) {
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

        public ExecutionAttempt build() {
            return new ExecutionAttempt(this);
        }
    }
}
