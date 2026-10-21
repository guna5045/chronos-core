package com.chronoscore.coordinator.coordination.model;

/**
 * Listener interface notified when a coordinator instance transitions leadership states.
 * <p>
 * Allows future scheduler, dispatcher, and recovery workers to start or halt
 * operations dynamically as cluster leadership shifts.
 */
public interface LeadershipStateListener {

    /**
     * Invoked immediately after this coordinator successfully acquires the Redis leader lease
     * and receives a new monotonically increasing leader epoch.
     *
     * @param identity the newly acquired leader identity.
     */
    void onLeadershipAcquired(LeaderIdentity identity);

    /**
     * Invoked immediately when this coordinator loses leadership (e.g. lease expiration,
     * renewal failure, or graceful release).
     *
     * @param identity the revoked leader identity.
     */
    void onLeadershipLost(LeaderIdentity identity);
}
