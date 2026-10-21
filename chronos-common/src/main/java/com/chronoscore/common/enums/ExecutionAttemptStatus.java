package com.chronoscore.common.enums;

/**
 * Represents the status of an individual execution attempt of a Task.
 * <p>
 * A {@code Task} represents the logical unit of work spanning its complete lifecycle,
 * whereas an {@code ExecutionAttempt} represents an individual, physical execution instance
 * performed by a specific worker node.
 */
public enum ExecutionAttemptStatus {

    /**
     * The attempt record has been created and is waiting to begin execution on the worker.
     */
    PENDING,

    /**
     * The worker is actively running the task attempt.
     */
    RUNNING,

    /**
     * The attempt completed with successful result.
     */
    SUCCEEDED,

    /**
     * The attempt failed with an error, exception, or non-zero exit code.
     */
    FAILED,

    /**
     * The attempt was aborted/cancelled while in-flight.
     */
    CANCELLED;

    /**
     * Checks if this execution attempt status is terminal.
     *
     * @return true if SUCCEEDED, FAILED, or CANCELLED.
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
