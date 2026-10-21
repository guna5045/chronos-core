package com.chronoscore.common.exceptions;

/**
 * Thrown when task submission is rejected because the tenant has reached its configured maxQueuedTasks limit.
 */
public class TenantQueueLimitExceededException extends RuntimeException {

    private final String tenantId;
    private final int maxQueuedTasks;
    private final int currentQueuedTasks;

    public TenantQueueLimitExceededException(String tenantId, int maxQueuedTasks, int currentQueuedTasks) {
        super(String.format("Tenant '%s' exceeded maximum queued task limit: current=%d, max=%d",
                tenantId, currentQueuedTasks, maxQueuedTasks));
        this.tenantId = tenantId;
        this.maxQueuedTasks = maxQueuedTasks;
        this.currentQueuedTasks = currentQueuedTasks;
    }

    public TenantQueueLimitExceededException(String tenantId, String message) {
        super(message != null ? message : "Tenant '" + tenantId + "' exceeded maximum queued task limit");
        this.tenantId = tenantId;
        this.maxQueuedTasks = -1;
        this.currentQueuedTasks = -1;
    }

    public String getTenantId() {
        return tenantId;
    }

    public int getMaxQueuedTasks() {
        return maxQueuedTasks;
    }

    public int getCurrentQueuedTasks() {
        return currentQueuedTasks;
    }
}
