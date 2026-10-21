package com.chronoscore.common.exceptions;

/**
 * Thrown when task submission is rejected due to active tenant admission rate limits.
 */
public class TenantRateLimitExceededException extends RuntimeException {

    private final String tenantId;
    private final double tasksPerSecond;

    public TenantRateLimitExceededException(String tenantId, double tasksPerSecond) {
        super(String.format("Tenant '%s' rate limit exceeded: limit=%.2f tasks/sec", tenantId, tasksPerSecond));
        this.tenantId = tenantId;
        this.tasksPerSecond = tasksPerSecond;
    }

    public TenantRateLimitExceededException(String tenantId, String message) {
        super(message != null ? message : "Tenant '" + tenantId + "' rate limit exceeded");
        this.tenantId = tenantId;
        this.tasksPerSecond = -1.0;
    }

    public String getTenantId() {
        return tenantId;
    }

    public double getTasksPerSecond() {
        return tasksPerSecond;
    }
}
