package com.chronoscore.coordinator.coordination.exception;

/**
 * Base runtime exception thrown when distributed coordination operations fail
 * due to Redis connectivity, timeouts, or unexpected cluster state conflicts.
 */
public class CoordinationException extends RuntimeException {

    public CoordinationException(String message) {
        super(message);
    }

    public CoordinationException(String message, Throwable cause) {
        super(message, cause);
    }
}
