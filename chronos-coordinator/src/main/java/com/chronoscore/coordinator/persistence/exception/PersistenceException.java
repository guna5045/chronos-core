package com.chronoscore.coordinator.persistence.exception;

/**
 * General persistence exception thrown when a database operation fails unexpectedly.
 */
public class PersistenceException extends RuntimeException {

    public PersistenceException(String message) {
        super(message);
    }

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
