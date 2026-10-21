package com.chronoscore.coordinator.persistence.exception;

import java.util.UUID;

/**
 * Thrown when a task is queried by ID but does not exist in the database.
 */
public class TaskNotFoundException extends RuntimeException {

    private final UUID taskId;

    public TaskNotFoundException(UUID taskId) {
        super(String.format("Task not found with ID: %s", taskId));
        this.taskId = taskId;
    }

    public UUID getTaskId() {
        return taskId;
    }
}
