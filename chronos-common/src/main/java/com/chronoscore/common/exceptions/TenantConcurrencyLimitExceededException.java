package com.chronoscore.common.exceptions;

/**
 * Thrown when task execution admission is rejected due to tenant concurrency saturation.
 */
public class TenantConcurrencyLimitExceededException extends RuntimeException {

    private final String tenantId;
    private final int maxConcurrentTasks;

    public TenantConcurrencyLimitExceededException(String tenantId, int maxConcurrentTasks) {
        super(String.format("Tenant '%s' has reached maximum concurrent task limit: %d",
                tenantId, maxConcurrentTasks));
        this.tenantId = tenantId;
        this.maxConcurrentTasks = maxConcurrentTasks;
    }

    public String getTenantId() {
        return tenantId;
    }

    public int getMaxConcurrentTasks() {
        return maxConcurrentTasks;
    }
}
