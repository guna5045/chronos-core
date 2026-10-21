package com.chronoscore.common.exceptions;

import java.util.UUID;

/**
 * Thrown when an expected workflow execution cannot be found.
 */
public class WorkflowExecutionNotFoundException extends RuntimeException {

    private final UUID executionId;

    public WorkflowExecutionNotFoundException(UUID executionId) {
        super(String.format("Workflow execution not found with ID [%s]", executionId));
        this.executionId = executionId;
    }

    public UUID getExecutionId() {
        return executionId;
    }
}
