package com.chronoscore.common.exceptions;

/**
 * Thrown when a workflow definition violates structural DAG constraints,
 * such as containing cycles, self-dependencies, duplicate node identifiers,
 * or referencing non-existent nodes.
 */
public class WorkflowValidationException extends RuntimeException {

    public WorkflowValidationException(String message) {
        super(message);
    }

    public WorkflowValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
