package com.chronoscore.coordinator.coordination.exception;

/**
 * Thrown when an operation is rejected because the executing coordinator's leadership
 * epoch is stale or leadership lease has expired.
 * <p>
 * Guards against split-brain operations following network pauses, GC freezes, or partition healing.
 */
public class StaleLeaderException extends CoordinationException {

    private final String coordinatorId;
    private final long attemptedEpoch;
    private final long currentEpoch;

    public StaleLeaderException(String coordinatorId, long attemptedEpoch, long currentEpoch) {
        super(String.format("Stale leader detected for coordinator '%s': attempted epoch %d is invalid or superseded by current epoch %d",
                coordinatorId, attemptedEpoch, currentEpoch));
        this.coordinatorId = coordinatorId;
        this.attemptedEpoch = attemptedEpoch;
        this.currentEpoch = currentEpoch;
    }

    public StaleLeaderException(String message) {
        super(message);
        this.coordinatorId = null;
        this.attemptedEpoch = 0;
        this.currentEpoch = 0;
    }

    public String getCoordinatorId() {
        return coordinatorId;
    }

    public long getAttemptedEpoch() {
        return attemptedEpoch;
    }

    public long getCurrentEpoch() {
        return currentEpoch;
    }
}
