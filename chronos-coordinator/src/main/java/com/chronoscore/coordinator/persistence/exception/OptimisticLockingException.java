package com.chronoscore.coordinator.persistence.exception;

import java.util.UUID;

/**
 * Thrown when an optimistic concurrency version check fails during a database update.
 * <p>
 * Indicates that the task row in the database has been modified concurrently by another
 * transaction or worker since it was read.
 */
public class OptimisticLockingException extends RuntimeException {

    private final UUID taskId;
    private final long expectedVersion;

    public OptimisticLockingException(UUID taskId, long expectedVersion) {
        super(String.format("Optimistic locking conflict for entity %s at version %d: row was modified concurrently or deleted",
                taskId, expectedVersion));
        this.taskId = taskId;
        this.expectedVersion = expectedVersion;
    }

    public OptimisticLockingException(String message) {
        super(message);
        this.taskId = null;
        this.expectedVersion = -1;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }
}
