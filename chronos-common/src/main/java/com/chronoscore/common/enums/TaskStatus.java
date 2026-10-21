package com.chronoscore.common.enums;

/**
 * Represents the explicit lifecycle status of a Task in Chronos Core.
 * <p>
 * Lifecycle transitions:
 * <pre>
 *   CREATED ──► QUEUED ──► DISPATCHED ──► RUNNING ──┬──► SUCCEEDED (terminal)
 *      │          │            │             │      │
 *      │          │            │             ├──► FAILED ──► [retry?] ──► QUEUED
 *      │          │            │             │                  └──► DLQ (terminal)
 *      └──────────┴────────────┴─────────────┴──► CANCELLED (terminal)
 * </pre>
 * <p>
 * Transitions are strictly validated and enforced by {@link com.chronoscore.common.statemachine.TaskStateMachine}.
 */
public enum TaskStatus {

    /**
     * Task has been created and validated but not yet enqueued for dispatching.
     */
    CREATED,

    /**
     * Task is waiting in the queue, eligible for priority scheduling and worker allocation.
     */
    QUEUED,

    /**
     * Task has been allocated to a specific worker node; awaiting worker pickup/acknowledgement.
     */
    DISPATCHED,

    /**
     * Task is actively executing on the assigned worker node.
     */
    RUNNING,

    /**
     * Terminal state indicating the task finished successfully.
     */
    SUCCEEDED,

    /**
     * Task execution failed on the current attempt.
     * May transition to QUEUED if retries remain, or DLQ if max attempts have been reached.
     */
    FAILED,

    /**
     * Terminal state indicating the task was explicitly cancelled by operator or timeout.
     */
    CANCELLED,

    /**
     * Terminal state indicating retry attempts have been exhausted and the task
     * is placed in the Dead-Letter Queue for inspection.
     */
    DLQ;

    /**
     * Checks if this status is a terminal state (no further transitions allowed).
     *
     * @return true if SUCCEEDED, CANCELLED, or DLQ.
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == CANCELLED || this == DLQ;
    }

    /**
     * Checks if this status represents an active in-flight or waiting state.
     *
     * @return true if QUEUED, DISPATCHED, or RUNNING.
     */
    public boolean isActive() {
        return this == QUEUED || this == DISPATCHED || this == RUNNING;
    }
}
