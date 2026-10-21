package com.chronoscore.coordinator.coordination.leader;

import com.chronoscore.coordinator.coordination.model.LeaderIdentity;
import com.chronoscore.coordinator.coordination.model.LeadershipStateListener;

/**
 * Service contract managing lease-based leader election lifecycle, epoch assignment,
 * and loss detection across the coordinator cluster.
 * <p>
 * Key Architectural Guarantees:
 * <ul>
 *   <li><b>Lease-Based Election with TTL</b>: Leadership is held via an active TTL-bounded Redis lease.</li>
 *   <li><b>Epoch Monotonicity</b>: Every successful election cycle increments the leader epoch.</li>
 *   <li><b>Safe Renewal &amp; Release</b>: Ownership verification Lua scripts prevent accidental lease steals.</li>
 *   <li><b>Instant Loss Teardown</b>: Immediate state transition to {@code LEADERSHIP_LOST} on renewal failure.</li>
 * </ul>
 */
public interface LeaderElectionService {

    /**
     * Attempts to atomically acquire the Redis leader lease and assign a new leader epoch.
     *
     * @return true if leadership was successfully acquired, false if held by another coordinator.
     */
    boolean tryAcquireLeadership();

    /**
     * Renews the active leader lease if this coordinator remains the verified owner in Redis.
     *
     * @return true if renewed, false if lease was lost or owned by another.
     */
    boolean renewLease();

    /**
     * Safely and gracefully releases leadership by deleting the Redis lock if owned by this coordinator.
     *
     * @return true if cleanly released, false otherwise.
     */
    boolean releaseLeadership();

    /**
     * Returns an immutable snapshot of this coordinator's current leadership state and epoch.
     *
     * @return current {@link LeaderIdentity}.
     */
    LeaderIdentity getLeadershipIdentity();

    /**
     * Fast liveness check verifying whether this coordinator is currently an active leader
     * with an unexpired lease.
     *
     * @return true if actively LEADER, false otherwise.
     */
    boolean isLeader();

    /**
     * Returns the active leader epoch if this coordinator is leader, or 0 if follower.
     *
     * @return current leader epoch.
     */
    long getCurrentEpoch();

    /**
     * Registers a listener to be notified of leadership transitions.
     *
     * @param listener state transition listener.
     */
    void addListener(LeadershipStateListener listener);
}
