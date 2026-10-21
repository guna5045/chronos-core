package com.chronoscore.coordinator.policy;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.coordinator.recovery.config.RetryProperties;
import com.chronoscore.coordinator.recovery.policy.DefaultRetryPolicy;
import com.chronoscore.coordinator.recovery.policy.JitterStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test suite verifying task deadline enforcement, retry cutoff, and timeout handling
 * (Phase 09 Section 33, 34, 35).
 */
class TaskDeadlineAndTimeoutTest {

    private DefaultRetryPolicy retryPolicy;
    private RetryProperties retryProperties;

    @BeforeEach
    void setUp() {
        retryProperties = new RetryProperties();
        retryProperties.setEnabled(true);
        retryProperties.setBaseDelay(Duration.ofSeconds(10));
        retryProperties.setMultiplier(2.0);
        retryProperties.setMaxDelay(Duration.ofMinutes(5));
        retryProperties.setJitter(false);

        retryPolicy = new DefaultRetryPolicy(retryProperties, JitterStrategy.none());
    }

    private Task createTaskWithDeadline(Instant deadline) {
        return Task.builder()
                .taskId(UUID.randomUUID())
                .tenantId("tenant-deadline")
                .taskType("SLA_TASK")
                .payload("{}")
                .priority(TaskPriority.HIGH)
                .status(TaskStatus.FAILED)
                .currentAttempt(1)
                .maxAttempts(5)
                .deadline(deadline)
                .build();
    }

    @Test
    @DisplayName("Deadline: Retry is permitted if next scheduled time is before deadline")
    void testRetryAllowedBeforeDeadline() {
        // Deadline is 1 hour in the future; retry backoff is only 10s
        Instant futureDeadline = Instant.now().plus(Duration.ofHours(1));
        Task task = createTaskWithDeadline(futureDeadline);

        boolean shouldRetry = retryPolicy.shouldRetry(task, 1, "TRANSIENT_ERROR", null);
        assertThat(shouldRetry).isTrue();
    }

    @Test
    @DisplayName("Deadline Cutoff: Retry rejected when deadline is in the past")
    void testRetryRejectedWhenDeadlineAlreadyPassed() {
        // Deadline was 5 seconds ago
        Instant pastDeadline = Instant.now().minusSeconds(5);
        Task task = createTaskWithDeadline(pastDeadline);

        boolean shouldRetry = retryPolicy.shouldRetry(task, 1, "TRANSIENT_ERROR", null);
        // Crucial invariant: must NOT retry; must transition directly to DLQ
        assertThat(shouldRetry).isFalse();
    }

    @Test
    @DisplayName("Deadline Cutoff: Retry rejected when next backoff schedule would exceed deadline")
    void testRetryRejectedWhenBackoffWouldExceedDeadline() {
        // Deadline is 5 seconds from now, but attempt #1 backoff is 10s (exceeds deadline)
        Instant tightDeadline = Instant.now().plusSeconds(5);
        Task task = createTaskWithDeadline(tightDeadline);

        boolean shouldRetry = retryPolicy.shouldRetry(task, 1, "TRANSIENT_ERROR", null);
        // Crucial invariant: must NOT schedule retry beyond deadline
        assertThat(shouldRetry).isFalse();
    }
}
