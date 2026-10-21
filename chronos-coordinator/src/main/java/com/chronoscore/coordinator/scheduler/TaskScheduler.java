package com.chronoscore.coordinator.scheduler;

/**
 * Contract for the leader-aware task scheduler.
 */
public interface TaskScheduler {

    /**
     * Executes a single scheduling cycle synchronously:
     * selects ready tasks from PostgreSQL, evaluates worker eligibility via consistent hashing,
     * acquires distributed locks, and dispatches to workers over gRPC.
     *
     * @return summary of the scheduling cycle outcome.
     */
    SchedulingResult scheduleOnce();

    /**
     * Checks whether the scheduler background loop is actively running on this node.
     *
     * @return true if running, false if paused or follower.
     */
    boolean isRunning();
}
