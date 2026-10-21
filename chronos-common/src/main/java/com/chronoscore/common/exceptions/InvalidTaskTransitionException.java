package com.chronoscore.common.exceptions;

import com.chronoscore.common.enums.TaskStatus;

import java.util.UUID;

/**
 * Thrown when an illegal task state machine transition is attempted.
 * <p>
 * Ensures that all lifecycle changes are strictly deterministic and invalid transitions
 * are rejected with explicit diagnostic context rather than silently corrected.
 */
public class InvalidTaskTransitionException extends RuntimeException {

    private final UUID taskId;
    private final TaskStatus fromStatus;
    private final TaskStatus toStatus;

    public InvalidTaskTransitionException(UUID taskId, TaskStatus fromStatus, TaskStatus toStatus) {
        super(String.format("Invalid task transition for task [%s]: cannot transition from %s to %s",
                taskId != null ? taskId : "UNKNOWN", fromStatus, toStatus));
        this.taskId = taskId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }

    public InvalidTaskTransitionException(TaskStatus fromStatus, TaskStatus toStatus, String reason) {
        super(String.format("Invalid task transition: cannot transition from %s to %s. Reason: %s",
                fromStatus, toStatus, reason));
        this.taskId = null;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public TaskStatus getFromStatus() {
        return fromStatus;
    }

    public TaskStatus getToStatus() {
        return toStatus;
    }
}
