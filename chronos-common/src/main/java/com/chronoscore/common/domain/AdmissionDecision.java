package com.chronoscore.common.domain;

import com.chronoscore.common.enums.AdmissionStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * Explicit outcome of a task admission control evaluation.
 *
 * @param status     categorical admission status.
 * @param reason     human-readable diagnostic rationale.
 * @param retryAfter optional timestamp suggesting when admission may be retried (e.g. for rate-limited tasks).
 */
public record AdmissionDecision(
        AdmissionStatus status,
        String reason,
        Instant retryAfter
) {
    public AdmissionDecision {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }

    public static AdmissionDecision admitted() {
        return new AdmissionDecision(AdmissionStatus.ADMITTED, "Task admitted successfully", null);
    }

    public static AdmissionDecision rateLimited(String reason, Instant retryAfter) {
        return new AdmissionDecision(AdmissionStatus.RATE_LIMITED, reason, retryAfter);
    }

    public static AdmissionDecision tenantConcurrencyLimit(String reason) {
        return new AdmissionDecision(AdmissionStatus.TENANT_CONCURRENCY_LIMIT, reason, null);
    }

    public static AdmissionDecision tenantQueueLimit(String reason) {
        return new AdmissionDecision(AdmissionStatus.TENANT_QUEUE_LIMIT, reason, null);
    }

    public static AdmissionDecision systemBackpressure(String reason) {
        return new AdmissionDecision(AdmissionStatus.SYSTEM_BACKPRESSURE, reason, null);
    }

    public static AdmissionDecision invalidPolicy(String reason) {
        return new AdmissionDecision(AdmissionStatus.INVALID_POLICY, reason, null);
    }

    public boolean isAdmitted() {
        return status == AdmissionStatus.ADMITTED;
    }
}
