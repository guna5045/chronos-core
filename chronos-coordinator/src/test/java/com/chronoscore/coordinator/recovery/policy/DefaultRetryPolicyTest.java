package com.chronoscore.coordinator.recovery.policy;

import com.chronoscore.common.domain.Task;
import com.chronoscore.coordinator.recovery.config.RetryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultRetryPolicyTest {

    private RetryProperties properties;
    private DefaultRetryPolicy retryPolicyNoJitter;

    @BeforeEach
    void setUp() {
        properties = new RetryProperties();
        properties.setEnabled(true);
        properties.setBaseDelay(Duration.ofSeconds(1));
        properties.setMaxDelay(Duration.ofSeconds(60));
        properties.setMultiplier(2.0);
        properties.setJitter(false);

        retryPolicyNoJitter = new DefaultRetryPolicy(properties, JitterStrategy.none());
    }

    private Task createTask(int maxAttempts) {
        return Task.builder()
                .taskId(UUID.randomUUID())
                .tenantId("default-tenant")
                .taskType("COMPUTE")
                .payload("{}")
                .maxAttempts(maxAttempts)
                .currentAttempt(1)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("Exponential backoff progresses deterministically without jitter: 1s, 2s, 4s, 8s")
    void testExponentialBackoffProgression() {
        Duration delay1 = retryPolicyNoJitter.calculateBackoff(1);
        Duration delay2 = retryPolicyNoJitter.calculateBackoff(2);
        Duration delay3 = retryPolicyNoJitter.calculateBackoff(3);
        Duration delay4 = retryPolicyNoJitter.calculateBackoff(4);

        assertThat(delay1).isEqualTo(Duration.ofSeconds(1));
        assertThat(delay2).isEqualTo(Duration.ofSeconds(2));
        assertThat(delay3).isEqualTo(Duration.ofSeconds(4));
        assertThat(delay4).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    @DisplayName("Exponential backoff is capped at maxDelay")
    void testMaxDelayCap() {
        properties.setMaxDelay(Duration.ofSeconds(10));
        DefaultRetryPolicy policyWithCap = new DefaultRetryPolicy(properties, JitterStrategy.none());

        // 2^(6-1) = 32s, but capped at 10s
        Duration delay = policyWithCap.calculateBackoff(6);
        assertThat(delay).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("Jitter produces delay bounded between 0 and upper backoff bound")
    void testJitterStrategyBounds() {
        Random deterministicRandom = new Random(42);
        DefaultRetryPolicy policyWithJitter = new DefaultRetryPolicy(properties, JitterStrategy.full(deterministicRandom));

        for (int attempt = 1; attempt <= 4; attempt++) {
            Duration delay = policyWithJitter.calculateBackoff(attempt);
            Duration upperBound = Duration.ofSeconds((long) Math.pow(2, attempt - 1));
            assertThat(delay.toMillis()).isGreaterThanOrEqualTo(0);
            assertThat(delay.toMillis()).isLessThanOrEqualTo(upperBound.toMillis());
        }
    }

    @Test
    @DisplayName("Retryable failures are correctly classified")
    void testRetryableFailures() {
        assertThat(retryPolicyNoJitter.isRetryable("WORKER_UNAVAILABLE", null)).isTrue();
        assertThat(retryPolicyNoJitter.isRetryable("WORKER_TIMEOUT", null)).isTrue();
        assertThat(retryPolicyNoJitter.isRetryable("TEMPORARY_NETWORK_FAILURE", null)).isTrue();
        assertThat(retryPolicyNoJitter.isRetryable("CAPACITY_EXHAUSTED", null)).isTrue();
        assertThat(retryPolicyNoJitter.isRetryable("EXECUTION_TIMEOUT", null)).isTrue();
        assertThat(retryPolicyNoJitter.isRetryable("TRANSIENT_INFRASTRUCTURE_ERROR", null)).isTrue();
    }

    @Test
    @DisplayName("Non-retryable failures are correctly classified")
    void testNonRetryableFailures() {
        assertThat(retryPolicyNoJitter.isRetryable("INVALID_TASK", null)).isFalse();
        assertThat(retryPolicyNoJitter.isRetryable("INVALID_PAYLOAD", null)).isFalse();
        assertThat(retryPolicyNoJitter.isRetryable("UNSUPPORTED_TASK_TYPE", null)).isFalse();
        assertThat(retryPolicyNoJitter.isRetryable("VALIDATION_ERROR", null)).isFalse();
        assertThat(retryPolicyNoJitter.isRetryable("PERMISSION_ERROR", null)).isFalse();
    }

    @Test
    @DisplayName("Unknown errors default to retryable")
    void testUnknownErrorClassification() {
        assertThat(retryPolicyNoJitter.isRetryable("SOME_STRANGE_NEW_ERROR", null)).isTrue();
    }

    @Test
    @DisplayName("shouldRetry returns false when maxAttempts is exhausted")
    void testMaxAttemptsExhausted() {
        Task task = createTask(3);

        assertThat(retryPolicyNoJitter.shouldRetry(task, 1, "WORKER_TIMEOUT", null)).isTrue();
        assertThat(retryPolicyNoJitter.shouldRetry(task, 2, "WORKER_TIMEOUT", null)).isTrue();
        assertThat(retryPolicyNoJitter.shouldRetry(task, 3, "WORKER_TIMEOUT", null)).isFalse();
        assertThat(retryPolicyNoJitter.shouldRetry(task, 4, "WORKER_TIMEOUT", null)).isFalse();
    }

    @Test
    @DisplayName("shouldRetry returns false for non-retryable failure even on attempt 1")
    void testNonRetryableFailureRejection() {
        Task task = createTask(5);
        assertThat(retryPolicyNoJitter.shouldRetry(task, 1, "VALIDATION_ERROR", null)).isFalse();
    }

    @Test
    @DisplayName("shouldRetry returns false when retry policy is globally disabled")
    void testRetryPolicyDisabled() {
        properties.setEnabled(false);
        Task task = createTask(5);
        assertThat(retryPolicyNoJitter.shouldRetry(task, 1, "WORKER_TIMEOUT", null)).isFalse();
    }
}
