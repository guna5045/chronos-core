package com.chronoscore.coordinator.coordination.exception;

/**
 * Thrown when a distributed task lock acquisition fails unexpectedly due to Redis errors.
 */
public class LockAcquisitionException extends CoordinationException {

    private final String resource;

    public LockAcquisitionException(String resource, String message, Throwable cause) {
        super(String.format("Failed to acquire distributed lock for resource '%s': %s", resource, message), cause);
        this.resource = resource;
    }

    public String getResource() {
        return resource;
    }
}
