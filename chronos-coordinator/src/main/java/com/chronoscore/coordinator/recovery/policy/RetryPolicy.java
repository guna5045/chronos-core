package com.chronoscore.coordinator.recovery.policy;

import com.chronoscore.common.domain.Task;

import java.time.Duration;
import java.time.Instant;

/**
 * Strategy contract governing retry eligibility, failure classification,
 * and exponential backoff calculations.
 */
public interface RetryPolicy {

    /**
     * Determines whether an error is transient/retryable.
     *
     * @param errorCode failure classification code.
     * @param cause     optional root cause exception.
     * @return true if error is eligible for retry.
     */
    boolean isRetryable(String errorCode, Throwable cause);

    /**
     * Classifies a failure into {@link FailureClassification#RETRYABLE} or {@link FailureClassification#NON_RETRYABLE}.
     *
     * @param errorCode failure classification code.
     * @param cause     optional root cause exception.
     * @return classification outcome.
     */
    FailureClassification classify(String errorCode, Throwable cause);

    /**
     * Evaluates whether a failed task attempt should be retried or transitioned to Dead-Letter Queue (DLQ).
     *
     * @param task           the task entity.
     * @param currentAttempt attempt number just completed.
     * @param errorCode      failure classification code.
     * @param cause          optional exception.
     * @return true if retry should be scheduled, false if task should move to DLQ.
     */
    boolean shouldRetry(Task task, int currentAttempt, String errorCode, Throwable cause);

    /**
     * Calculates the exponential backoff duration for the next retry attempt.
     *
     * @param attemptNumber the physical attempt sequence number (1-based).
     * @return backoff delay duration.
     */
    Duration calculateBackoff(int attemptNumber);

    /**
     * Computes the scheduled instant for the next retry attempt based on backoff delay.
     *
     * @param attemptNumber physical attempt number.
     * @param fromTime      base reference instant (usually {@code Instant.now()}).
     * @return next scheduled timestamp.
     */
    Instant calculateNextScheduleTime(int attemptNumber, Instant fromTime);
}
