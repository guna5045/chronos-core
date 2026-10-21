package com.chronoscore.coordinator.coordination.model;

/**
 * Represents the discrete leadership states of a coordinator instance.
 * <p>
 * State progression is deterministic:
 * <pre>
 *   FOLLOWER ──(acquire lease)──► LEADER ──(lease lost/renewal failure)──► LEADERSHIP_LOST ──► FOLLOWER
 * </pre>
 */
public enum LeadershipState {

    /**
     * Standby follower; not holding the Redis leader lease.
     */
    FOLLOWER,

    /**
     * Active cluster leader holding a valid Redis lease and active leader epoch.
     */
    LEADER,

    /**
     * Transient state entered immediately upon lease renewal failure or ownership loss.
     * Triggers active teardown of leader-only tasks before reverting to {@link #FOLLOWER}.
     */
    LEADERSHIP_LOST;

    /**
     * Checks if this instance currently holds active leadership.
     *
     * @return true only if LEADER.
     */
    public boolean isLeader() {
        return this == LEADER;
    }
}
