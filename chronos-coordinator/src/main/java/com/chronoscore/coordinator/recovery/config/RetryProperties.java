package com.chronoscore.coordinator.recovery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * Configuration properties for retry policy and exponential backoff.
 */
@Component
@ConfigurationProperties(prefix = "chronos.retry")
public class RetryProperties {

    /**
     * Whether automatic retry on failure is enabled.
     * Default: true.
     */
    private boolean enabled = true;

    /**
     * Initial base backoff delay.
     * Default: 1 second.
     */
    private Duration baseDelay = Duration.ofSeconds(1);

    /**
     * Maximum backoff delay cap.
     * Default: 60 seconds.
     */
    private Duration maxDelay = Duration.ofSeconds(60);

    /**
     * Backoff multiplier exponent base.
     * Default: 2.0.
     */
    private double multiplier = 2.0;

    /**
     * Whether randomized jitter is applied to prevent thundering herd retry storms.
     * Default: true.
     */
    private boolean jitter = true;

    /**
     * Error codes classified as retryable.
     */
    private Set<String> retryableErrors = new HashSet<>(Set.of(
            "WORKER_UNAVAILABLE",
            "WORKER_TIMEOUT",
            "TEMPORARY_NETWORK_FAILURE",
            "CAPACITY_EXHAUSTED",
            "EXECUTION_TIMEOUT",
            "TRANSIENT_INFRASTRUCTURE_ERROR",
            "LEASE_EXPIRED",
            "WORKER_EVICTED",
            "STATUS_UNAVAILABLE",
            "DEADLINE_EXCEEDED"
    ));

    /**
     * Error codes classified as permanently non-retryable.
     */
    private Set<String> nonRetryableErrors = new HashSet<>(Set.of(
            "INVALID_TASK",
            "INVALID_PAYLOAD",
            "UNSUPPORTED_TASK_TYPE",
            "VALIDATION_ERROR",
            "PERMISSION_ERROR",
            "TASK_CANCELLED",
            "INTERNAL_NON_RETRYABLE"
    ));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getBaseDelay() {
        return baseDelay;
    }

    public void setBaseDelay(Duration baseDelay) {
        this.baseDelay = baseDelay != null ? baseDelay : Duration.ofSeconds(1);
    }

    public Duration getMaxDelay() {
        return maxDelay;
    }

    public void setMaxDelay(Duration maxDelay) {
        this.maxDelay = maxDelay != null ? maxDelay : Duration.ofSeconds(60);
    }

    public double getMultiplier() {
        return multiplier;
    }

    public void setMultiplier(double multiplier) {
        this.multiplier = multiplier > 1.0 ? multiplier : 2.0;
    }

    public boolean isJitter() {
        return jitter;
    }

    public void setJitter(boolean jitter) {
        this.jitter = jitter;
    }

    public Set<String> getRetryableErrors() {
        return retryableErrors;
    }

    public void setRetryableErrors(Set<String> retryableErrors) {
        this.retryableErrors = retryableErrors != null ? retryableErrors : new HashSet<>();
    }

    public Set<String> getNonRetryableErrors() {
        return nonRetryableErrors;
    }

    public void setNonRetryableErrors(Set<String> nonRetryableErrors) {
        this.nonRetryableErrors = nonRetryableErrors != null ? nonRetryableErrors : new HashSet<>();
    }
}
