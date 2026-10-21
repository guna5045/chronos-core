package com.chronoscore.coordinator.coordination.fencing;

import com.chronoscore.coordinator.coordination.exception.StaleLeaderException;
import com.chronoscore.coordinator.coordination.model.FencingToken;

/**
 * Reusable fencing validation abstraction guarding against stale coordinator operations.
 * <p>
 * Key Invariant:
 * Whenever an active leader performs an authoritative operation (e.g. dispatching or rescheduling),
 * its fencing token must be validated against the cluster's active generation.
 * If another coordinator has subsequently acquired leadership and incremented the epoch,
 * the stale leader's operation must be rejected.
 */
public interface FencingTokenService {

    /**
     * Retrieves the current fencing token representing this coordinator's active leadership generation.
     *
     * @return active {@link FencingToken}.
     * @throws StaleLeaderException if this coordinator is not currently the active leader.
     */
    FencingToken getCurrentFencingToken();

    /**
     * Validates whether a given fencing token corresponds to the currently active leader epoch.
     *
     * @param token candidate fencing token.
     * @return true if the token is valid and active, false otherwise.
     */
    boolean validateFencingToken(FencingToken token);

    /**
     * Asserts that this coordinator is the active leader with the specified expected epoch.
     *
     * @param expectedEpoch epoch value expected by caller.
     * @throws StaleLeaderException if this coordinator is not leader or epoch does not match.
     */
    void assertActiveLeader(long expectedEpoch);
}
