package com.chronoscore.coordinator.coordination.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable fencing token representing a coordinator's leadership generation.
 * <p>
 * Passed across distributed boundaries (such as task scheduling, dispatching, and recovery)
 * to reject operations initiated by stale coordinators that experienced network pauses or GC freezes.
 */
public final class FencingToken {

    private final String coordinatorId;
    private final long epoch;
    private final Instant issuedAt;

    public FencingToken(String coordinatorId, long epoch, Instant issuedAt) {
        this.coordinatorId = Objects.requireNonNull(coordinatorId, "coordinatorId must not be null");
        this.epoch = epoch;
        this.issuedAt = issuedAt != null ? issuedAt : Instant.now();
    }

    public String getCoordinatorId() {
        return coordinatorId;
    }

    public long getEpoch() {
        return epoch;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    /**
     * Checks if this fencing token has a valid active epoch (> 0).
     *
     * @return true if valid.
     */
    public boolean isValid() {
        return epoch > 0 && coordinatorId != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FencingToken that = (FencingToken) o;
        return epoch == that.epoch && Objects.equals(coordinatorId, that.coordinatorId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(coordinatorId, epoch);
    }

    @Override
    public String toString() {
        return "FencingToken{" +
                "coordinatorId='" + coordinatorId + '\'' +
                ", epoch=" + epoch +
                ", issuedAt=" + issuedAt +
                '}';
    }
}
