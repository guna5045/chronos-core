package com.chronoscore.common.validation;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.exceptions.InvalidTaskException;

/**
 * Pure domain validator for {@link Task} definitions.
 * <p>
 * Completely decoupled from Spring MVC, Hibernate Validator, or JSR-380 annotations,
 * ensuring domain models remain independently verifiable in non-Spring contexts.
 */
public final class TaskValidator {

    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 256;
    public static final int MAX_TASK_TYPE_LENGTH = 128;
    public static final int MAX_TENANT_ID_LENGTH = 128;

    private TaskValidator() {
        // Utility validator
    }

    /**
     * Validates that the provided Task conforms to all domain integrity rules.
     *
     * @param task the task to validate.
     * @throws InvalidTaskException if any validation rule is violated.
     */
    public static void validate(Task task) {
        if (task == null) {
            throw new InvalidTaskException("Task must not be null");
        }

        if (task.getTaskId() == null) {
            throw new InvalidTaskException("Task taskId must not be null");
        }

        if (task.getTaskType() == null || task.getTaskType().trim().isEmpty()) {
            throw new InvalidTaskException("Task taskType must not be null or blank");
        }

        if (task.getTaskType().length() > MAX_TASK_TYPE_LENGTH) {
            throw new InvalidTaskException("Task taskType exceeds maximum length of " + MAX_TASK_TYPE_LENGTH + " characters");
        }

        if (task.getPayload() == null) {
            throw new InvalidTaskException("Task payload must not be null");
        }

        if (task.getPriority() == null) {
            throw new InvalidTaskException("Task priority must not be null");
        }

        if (task.getStatus() == null) {
            throw new InvalidTaskException("Task status must not be null");
        }

        if (task.getTenantId() == null || task.getTenantId().trim().isEmpty()) {
            throw new InvalidTaskException("Task tenantId must not be null or blank");
        }

        if (task.getTenantId().length() > MAX_TENANT_ID_LENGTH) {
            throw new InvalidTaskException("Task tenantId exceeds maximum length of " + MAX_TENANT_ID_LENGTH + " characters");
        }

        if (task.getMaxAttempts() <= 0) {
            throw new InvalidTaskException("Task maxAttempts must be positive (received: " + task.getMaxAttempts() + ")");
        }

        if (task.getCurrentAttempt() < 0) {
            throw new InvalidTaskException("Task currentAttempt must be non-negative (received: " + task.getCurrentAttempt() + ")");
        }

        if (task.getVersion() < 0) {
            throw new InvalidTaskException("Task version must be non-negative (received: " + task.getVersion() + ")");
        }

        if (task.getTimeout() != null && task.getTimeout().isNegative()) {
            throw new InvalidTaskException("Task timeout must not be negative (received: " + task.getTimeout() + ")");
        }

        if (task.getCreatedAt() == null) {
            throw new InvalidTaskException("Task createdAt must not be null");
        }

        if (task.getScheduledAt() != null && task.getScheduledAt().isBefore(task.getCreatedAt())) {
            throw new InvalidTaskException("Task scheduledAt (" + task.getScheduledAt() +
                    ") cannot be before createdAt (" + task.getCreatedAt() + ")");
        }

        if (task.getDeadline() != null) {
            if (task.getDeadline().isBefore(task.getCreatedAt())) {
                throw new InvalidTaskException("Task deadline (" + task.getDeadline() +
                        ") cannot be before createdAt (" + task.getCreatedAt() + ")");
            }
            if (task.getScheduledAt() != null && task.getDeadline().isBefore(task.getScheduledAt())) {
                throw new InvalidTaskException("Task deadline (" + task.getDeadline() +
                        ") cannot be before scheduledAt (" + task.getScheduledAt() + ")");
            }
        }

        if (task.getIdempotencyKey() != null) {
            if (task.getIdempotencyKey().trim().isEmpty()) {
                throw new InvalidTaskException("Task idempotencyKey cannot be empty or whitespace when provided");
            }
            if (task.getIdempotencyKey().length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
                throw new InvalidTaskException("Task idempotencyKey exceeds maximum length of " +
                        MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
            }
        }
    }
}
