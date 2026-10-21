package com.chronoscore.common.exceptions;

import com.chronoscore.common.enums.WorkflowNodeStatus;
import com.chronoscore.common.enums.WorkflowStatus;

import java.util.UUID;

/**
 * Thrown when an illegal state machine transition is attempted on a workflow execution
 * or an individual workflow execution node.
 */
public class InvalidWorkflowTransitionException extends RuntimeException {

    private final UUID executionId;

    public InvalidWorkflowTransitionException(UUID executionId, WorkflowStatus fromStatus, WorkflowStatus toStatus) {
        super(String.format("Invalid workflow transition for execution [%s]: cannot transition from %s to %s",
                executionId != null ? executionId : "UNKNOWN", fromStatus, toStatus));
        this.executionId = executionId;
    }

    public InvalidWorkflowTransitionException(UUID executionId, String nodeId, WorkflowNodeStatus fromStatus, WorkflowNodeStatus toStatus) {
        super(String.format("Invalid workflow node transition for execution [%s] node [%s]: cannot transition from %s to %s",
                executionId != null ? executionId : "UNKNOWN", nodeId, fromStatus, toStatus));
        this.executionId = executionId;
    }

    public UUID getExecutionId() {
        return executionId;
    }
}
