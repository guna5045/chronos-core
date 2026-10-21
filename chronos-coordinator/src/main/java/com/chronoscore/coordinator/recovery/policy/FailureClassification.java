package com.chronoscore.coordinator.recovery.policy;

/**
 * Classification of execution failure causes.
 */
public enum FailureClassification {
    /**
     * Transient or recoverable failure (e.g. worker crash, timeout, temporary network issue).
     * Eligible for retry subject to attempt limits and backoff policy.
     */
    RETRYABLE,

    /**
     * Permanent failure (e.g. payload validation error, illegal argument, unsupported task type).
     * Ineligible for retry; routed directly to Dead-Letter Queue (DLQ).
     */
    NON_RETRYABLE
}
