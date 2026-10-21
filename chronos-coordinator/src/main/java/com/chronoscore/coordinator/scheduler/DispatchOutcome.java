package com.chronoscore.coordinator.scheduler;

/**
 * Result outcome of attempting to dispatch an individual candidate task.
 */
public enum DispatchOutcome {

    /** Task was successfully dispatched and accepted by the target worker. */
    DISPATCHED,

    /** Task's deadline had already elapsed; task was cancelled rather than dispatched. */
    DEADLINE_EXPIRED,

    /** Task was already locked by a competing scheduler iteration or process. */
    LOCKED_BY_OTHER,

    /** Task is no longer in QUEUED status upon acquisition. */
    SKIPPED_NOT_QUEUED,

    /** No eligible or non-saturated worker was available on the hash ring. */
    NO_WORKER_AVAILABLE,

    /** Worker explicitly rejected the task (e.g. capacity exhausted, unavailable). */
    DISPATCH_REJECTED
}
