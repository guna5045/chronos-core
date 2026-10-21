package com.chronoscore.common.enums;

/**
 * Lifecycle status of an individual workflow execution instance.
 * <p>
 * Governed strictly by the deterministic {@link com.chronoscore.common.statemachine.WorkflowStateMachine}.
 */
public enum WorkflowStatus {

    /**
     * Workflow execution has been instantiated but has not yet started evaluating root nodes.
     */
    PENDING(false),

    /**
     * Workflow execution is actively running with one or more nodes pending, ready, or running.
     */
    RUNNING(false),

    /**
     * All required workflow nodes have successfully concluded. Terminal state.
     */
    SUCCEEDED(true),

    /**
     * One or more required workflow nodes suffered permanent failure. Terminal state.
     */
    FAILED(true),

    /**
     * Workflow execution was explicitly cancelled by operator or timeout policy. Terminal state.
     */
    CANCELLED(true);

    private final boolean terminal;

    WorkflowStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
