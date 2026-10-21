package com.chronoscore.common.enums;

/**
 * Lifecycle status of an individual node within a workflow execution.
 * <p>
 * Governed strictly by {@link com.chronoscore.common.statemachine.WorkflowNodeStateMachine}.
 */
public enum WorkflowNodeStatus {

    /**
     * Node is waiting for one or more upstream predecessor dependencies to complete.
     */
    PENDING(false),

    /**
     * All predecessor dependencies are satisfied; the node is eligible for scheduling
     * and its corresponding Chronos Task has been enqueued.
     */
    READY(false),

    /**
     * The node's corresponding Chronos Task is actively executing on a worker.
     */
    RUNNING(false),

    /**
     * The node's underlying task execution completed with terminal success.
     */
    SUCCEEDED(true),

    /**
     * The node's underlying task execution suffered terminal failure (retries exhausted or non-retryable error).
     */
    FAILED(true),

    /**
     * The node was skipped because an upstream predecessor failed or was skipped.
     */
    SKIPPED(true),

    /**
     * The node was cancelled due to workflow cancellation.
     */
    CANCELLED(true);

    private final boolean terminal;

    WorkflowNodeStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
