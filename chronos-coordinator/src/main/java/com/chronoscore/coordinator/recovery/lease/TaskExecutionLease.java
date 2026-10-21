package com.chronoscore.coordinator.recovery.lease;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable execution lease representing active worker ownership of a task attempt.
 * <p>
 * Architectural Guarantees:
 * <ul>
 *   <li><b>Ownership Tracking</b>: Binds an active execution attempt to a specific worker node.</li>
 *   <li><b>Lease Expiration</b>: If heartbeats or lease renewals cease, the lease expires,
 *       allowing the recovery engine to reclaim the orphaned task.</li>
 *   <li><b>Distinct from Distributed Locks</b>: Distributed scheduling locks protect dispatch exclusivity;
 *       execution leases track active physical execution duration.</li>
 * </ul>
 */
public final class TaskExecutionLease {

    private final UUID taskId;
    private final UUID attemptId;
    private final String workerId;
    private final int attemptNumber;
    private final Instant leaseAcquiredAt;
    private final Instant leaseExpiresAt;
    private final long leaderEpoch;
    private final long version;

    private TaskExecutionLease(Builder builder) {
        this.taskId = Objects.requireNonNull(builder.taskId, "taskId must not be null");
        this.attemptId = Objects.requireNonNull(builder.attemptId, "attemptId must not be null");
        this.workerId = Objects.requireNonNull(builder.workerId, "workerId must not be null");
        this.attemptNumber = builder.attemptNumber > 0 ? builder.attemptNumber : 1;
        this.leaseAcquiredAt = builder.leaseAcquiredAt != null ? builder.leaseAcquiredAt : Instant.now();
        this.leaseExpiresAt = Objects.requireNonNull(builder.leaseExpiresAt, "leaseExpiresAt must not be null");
        this.leaderEpoch = builder.leaderEpoch >= 0 ? builder.leaderEpoch : 0;
        this.version = Math.max(builder.version, 0L);
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID getTaskId() {
        return taskId;
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public Instant getLeaseAcquiredAt() {
        return leaseAcquiredAt;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public long getLeaderEpoch() {
        return leaderEpoch;
    }

    public long getVersion() {
        return version;
    }

    public boolean isExpired(Instant asOf) {
        Instant checkInstant = asOf != null ? asOf : Instant.now();
        return leaseExpiresAt.isBefore(checkInstant) || leaseExpiresAt.equals(checkInstant);
    }

    public TaskExecutionLease withExtendedLease(Duration extension, Instant asOf) {
        Instant base = asOf != null ? asOf : Instant.now();
        return toBuilder()
                .leaseExpiresAt(base.plus(extension))
                .version(this.version + 1)
                .build();
    }

    public Builder toBuilder() {
        return new Builder()
                .taskId(this.taskId)
                .attemptId(this.attemptId)
                .workerId(this.workerId)
                .attemptNumber(this.attemptNumber)
                .leaseAcquiredAt(this.leaseAcquiredAt)
                .leaseExpiresAt(this.leaseExpiresAt)
                .leaderEpoch(this.leaderEpoch)
                .version(this.version);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TaskExecutionLease that = (TaskExecutionLease) o;
        return Objects.equals(taskId, that.taskId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId);
    }

    @Override
    public String toString() {
        return "TaskExecutionLease{" +
                "taskId=" + taskId +
                ", attemptId=" + attemptId +
                ", workerId='" + workerId + '\'' +
                ", attemptNumber=" + attemptNumber +
                ", leaseExpiresAt=" + leaseExpiresAt +
                ", leaderEpoch=" + leaderEpoch +
                ", version=" + version +
                '}';
    }

    public static final class Builder {
        private UUID taskId;
        private UUID attemptId;
        private String workerId;
        private int attemptNumber = 1;
        private Instant leaseAcquiredAt;
        private Instant leaseExpiresAt;
        private long leaderEpoch = 0L;
        private long version = 0L;

        private Builder() {}

        public Builder taskId(UUID taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder attemptId(UUID attemptId) {
            this.attemptId = attemptId;
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

        public Builder leaseAcquiredAt(Instant leaseAcquiredAt) {
            this.leaseAcquiredAt = leaseAcquiredAt;
            return this;
        }

        public Builder leaseExpiresAt(Instant leaseExpiresAt) {
            this.leaseExpiresAt = leaseExpiresAt;
            return this;
        }

        public Builder leaderEpoch(long leaderEpoch) {
            this.leaderEpoch = leaderEpoch;
            return this;
        }

        public Builder version(long version) {
            this.version = version;
            return this;
        }

        public TaskExecutionLease build() {
            return new TaskExecutionLease(this);
        }
    }
}
