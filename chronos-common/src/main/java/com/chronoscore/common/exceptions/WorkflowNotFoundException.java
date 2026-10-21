package com.chronoscore.common.exceptions;

import java.util.UUID;

/**
 * Thrown when an expected workflow definition or version cannot be found.
 */
public class WorkflowNotFoundException extends RuntimeException {

    private final UUID workflowId;

    public WorkflowNotFoundException(UUID workflowId) {
        super(String.format("Workflow not found with ID [%s]", workflowId));
        this.workflowId = workflowId;
    }

    public WorkflowNotFoundException(String message) {
        super(message);
        this.workflowId = null;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }
}
