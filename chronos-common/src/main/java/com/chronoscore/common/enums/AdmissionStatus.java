package com.chronoscore.common.enums;

/**
 * Status outcomes resulting from the evaluation of a task against admission control policies.
 */
public enum AdmissionStatus {

    /**
     * The task satisfies all admission criteria and is eligible for immediate scheduling/execution.
     */
    ADMITTED,

    /**
     * Admission is temporarily denied because the tenant has exceeded its token-bucket rate limit.
     */
    RATE_LIMITED,

    /**
     * Admission is denied because the tenant has reached its maximum concurrent task execution quota.
     */
    TENANT_CONCURRENCY_LIMIT,

    /**
     * Submission is rejected because the tenant has reached its maximum allowable queued task limit.
     */
    TENANT_QUEUE_LIMIT,

    /**
     * Admission is suspended because total cluster worker capacity is saturated.
     */
    SYSTEM_BACKPRESSURE,

    /**
     * Admission evaluation failed due to an invalid or malformed tenant execution policy.
     */
    INVALID_POLICY
}
