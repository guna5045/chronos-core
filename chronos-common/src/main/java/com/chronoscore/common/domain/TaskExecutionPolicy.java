package com.chronoscore.common.domain;

import com.chronoscore.common.enums.TaskPriority;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Encapsulates the execution constraints and policies governing an individual task's lifecycle.
 *
 * @param priority       task priority (CRITICAL, HIGH, NORMAL, LOW).
 * @param timeout        execution timeout duration before cooperative cancellation.
 * @param deadline       absolute timestamp after which the task cannot be scheduled.
 * @param maxAttempts    maximum execution attempts before moving to Dead-Letter Queue.
 * @param concurrencyKey optional logical concurrency key (e.g. "customer:123") enforcing mutual exclusion.
 */
public record TaskExecutionPolicy(
        TaskPriority priority,
        Duration timeout,
        Instant deadline,
        int maxAttempts,
        String concurrencyKey
) {
    public TaskExecutionPolicy {
        if (priority == null) {
            priority = TaskPriority.NORMAL;
        }
        if (maxAttempts <= 0) {
            maxAttempts = 3;
        }
    }

    public static TaskExecutionPolicy defaultPolicy() {
        return new TaskExecutionPolicy(TaskPriority.NORMAL, null, null, 3, null);
    }
}
