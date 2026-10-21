package com.chronoscore.common.exceptions;

/**
 * Thrown when a task definition or submission fails domain validation constraints.
 * <p>
 * This is an independent domain exception decoupled from any HTTP or web tier concepts.
 */
public class InvalidTaskException extends RuntimeException {

    public InvalidTaskException(String message) {
        super(message);
    }

    public InvalidTaskException(String message, Throwable cause) {
        super(message, cause);
    }
}
