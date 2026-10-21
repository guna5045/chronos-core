package com.chronoscore.worker.execution;

/**
 * Thrown when a task is dispatched to a worker whose concurrency capacity is fully saturated.
 */
public class CapacityExhaustedException extends RuntimeException {

    public CapacityExhaustedException(String message) {
        super(message);
    }
}
