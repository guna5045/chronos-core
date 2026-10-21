package com.chronoscore.coordinator.coordination.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable snapshot of a coordinator's leadership identity and lease status.
 * <p>
 * Key Distinction:
 * <ul>
 *   <li>{@code leaderEpoch}: Fencing generation number issued by Redis to reject stale coordinator operations.</li>
 *   <li>{@code Task.version}: Database row optimistic concurrency counter.</li>
 * </ul>
 */
public final class LeaderIdentity {

    private final String coordinatorId;
    private final long leaderEpoch;
    private final Instant acquiredAt;
    private final Instant leaseExpiresAt;
    private final LeadershipState state;

    private LeaderIdentity(String coordinatorId,
                           long leaderEpoch,
                           Instant acquiredAt,
                           Instant leaseExpiresAt,
                           LeadershipState state) {
        this.coordinatorId = Objects.requireNonNull(coordinatorId, "coordinatorId must not be null");
        this.leaderEpoch = leaderEpoch;
        this.acquiredAt = acquiredAt;
        this.leaseExpiresAt = leaseExpiresAt;
        this.state = Objects.requireNonNull(state, "state must not be null");
    }

    /**
     * Creates an initial or standby FOLLOWER identity.
     *
     * @param coordinatorId unique coordinator ID.
     * @return follower identity.
     */
    public static LeaderIdentity follower(String coordinatorId) {
        return new LeaderIdentity(coordinatorId, 0L, null, null, LeadershipState.FOLLOWER);
    }

    /**
     * Creates an active LEADER identity.
     *
     * @param coordinatorId  unique coordinator ID.
     * @param leaderEpoch    assigned monotonically increasing epoch.
     * @param acquiredAt     time of lease acquisition.
     * @param leaseExpiresAt time when the lease will expire without renewal.
     * @return leader identity.
     */
    public static LeaderIdentity leader(String coordinatorId,
                                        long leaderEpoch,
                                        Instant acquiredAt,
                                        Instant leaseExpiresAt) {
        return new LeaderIdentity(coordinatorId, leaderEpoch, acquiredAt, leaseExpiresAt, LeadershipState.LEADER);
    }

    /**
     * Creates a LEADERSHIP_LOST transitional identity.
     *
     * @param coordinatorId unique coordinator ID.
     * @param priorEpoch    last held leader epoch.
     * @return leadership-lost identity.
     */
    public static LeaderIdentity lost(String coordinatorId, long priorEpoch) {
        return new LeaderIdentity(coordinatorId, priorEpoch, null, null, LeadershipState.LEADERSHIP_LOST);
    }

    public String getCoordinatorId() {
        return coordinatorId;
    }

    public long getLeaderEpoch() {
        return leaderEpoch;
    }

    public Instant getAcquiredAt() {
        return acquiredAt;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public LeadershipState getState() {
        return state;
    }

    public boolean isLeader() {
        return state == LeadershipState.LEADER && isLeaseValid();
    }

    public boolean isLeaseValid() {
        return leaseExpiresAt != null && Instant.now().isBefore(leaseExpiresAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LeaderIdentity that = (LeaderIdentity) o;
        return leaderEpoch == that.leaderEpoch &&
                Objects.equals(coordinatorId, that.coordinatorId) &&
                state == that.state;
    }

    @Override
    public int hashCode() {
        return Objects.hash(coordinatorId, leaderEpoch, state);
    }

    @Override
    public String toString() {
        return "LeaderIdentity{" +
                "coordinatorId='" + coordinatorId + '\'' +
                ", leaderEpoch=" + leaderEpoch +
                ", state=" + state +
                ", leaseExpiresAt=" + leaseExpiresAt +
                ", isLeader=" + isLeader() +
                '}';
    }
}
