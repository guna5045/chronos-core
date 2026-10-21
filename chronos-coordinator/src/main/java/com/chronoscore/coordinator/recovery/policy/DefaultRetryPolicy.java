package com.chronoscore.coordinator.recovery.policy;

import com.chronoscore.common.domain.Task;
import com.chronoscore.coordinator.recovery.config.RetryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Default implementation of {@link RetryPolicy} providing bounded exponential backoff,
 * jitter randomization, error classification, and attempt limits.
 */
@Component
public class DefaultRetryPolicy implements RetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(DefaultRetryPolicy.class);

    private final RetryProperties retryProperties;
    private final JitterStrategy jitterStrategy;

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultRetryPolicy(RetryProperties retryProperties) {
        this(retryProperties, retryProperties.isJitter() ? JitterStrategy.full() : JitterStrategy.none());
    }

    public DefaultRetryPolicy(RetryProperties retryProperties, JitterStrategy jitterStrategy) {
        this.retryProperties = Objects.requireNonNull(retryProperties, "retryProperties must not be null");
        this.jitterStrategy = Objects.requireNonNull(jitterStrategy, "jitterStrategy must not be null");
    }

    @Override
    public boolean isRetryable(String errorCode, Throwable cause) {
        return classify(errorCode, cause) == FailureClassification.RETRYABLE;
    }

    @Override
    public FailureClassification classify(String errorCode, Throwable cause) {
        if (errorCode != null && retryProperties.getNonRetryableErrors().contains(errorCode.toUpperCase())) {
            return FailureClassification.NON_RETRYABLE;
        }

        if (errorCode != null && retryProperties.getRetryableErrors().contains(errorCode.toUpperCase())) {
            return FailureClassification.RETRYABLE;
        }

        // Unknown error codes default to retryable up to configured maxAttempts
        return FailureClassification.RETRYABLE;
    }

    @Override
    public boolean shouldRetry(Task task, int currentAttempt, String errorCode, Throwable cause) {
        Objects.requireNonNull(task, "task must not be null");

        if (!retryProperties.isEnabled()) {
            log.debug("Task '{}' retry rejected: retry policy globally disabled", task.getTaskId());
            return false;
        }

        FailureClassification classification = classify(errorCode, cause);
        if (classification == FailureClassification.NON_RETRYABLE) {
            log.info("Task '{}' failed with non-retryable error '{}'; transitioning directly to DLQ",
                    task.getTaskId(), errorCode);
            return false;
        }

        int maxAttempts = task.getMaxAttempts();
        if (currentAttempt >= maxAttempts) {
            log.info("Task '{}' exhausted maximum retry attempts ({}/{}); transitioning to DLQ",
                    task.getTaskId(), currentAttempt, maxAttempts);
            return false;
        }

        // Enforce that retry execution does not exceed task deadline
        if (task.getDeadline() != null) {
            Instant now = Instant.now();
            Instant nextEstimatedSchedule = calculateNextScheduleTime(currentAttempt, now);
            if (now.isAfter(task.getDeadline()) || nextEstimatedSchedule.isAfter(task.getDeadline())) {
                log.info("Task '{}' exceeded or retry schedule ({}) would exceed configured deadline ({}); transitioning to DLQ",
                        task.getTaskId(), nextEstimatedSchedule, task.getDeadline());
                return false;
            }
        }

        return true;
    }

    @Override
    public Duration calculateBackoff(int attemptNumber) {
        int attempt = Math.max(1, attemptNumber);

        // delay = baseDelay * (multiplier ^ (attempt - 1))
        double factor = Math.pow(retryProperties.getMultiplier(), attempt - 1);
        long baseMillis = retryProperties.getBaseDelay().toMillis();
        long maxMillis = retryProperties.getMaxDelay().toMillis();

        long calculatedMillis;
        // Guard against double/long overflow
        if (factor > (double) maxMillis / (double) Math.max(1, baseMillis)) {
            calculatedMillis = maxMillis;
        } else {
            calculatedMillis = Math.min((long) (baseMillis * factor), maxMillis);
        }

        Duration unjittered = Duration.ofMillis(Math.max(1, calculatedMillis));
        return retryProperties.isJitter() ? jitterStrategy.applyJitter(unjittered) : unjittered;
    }

    @Override
    public Instant calculateNextScheduleTime(int attemptNumber, Instant fromTime) {
        Instant base = fromTime != null ? fromTime : Instant.now();
        Duration backoff = calculateBackoff(attemptNumber);
        return base.plus(backoff);
    }
}
