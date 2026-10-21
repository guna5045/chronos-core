package com.chronoscore.coordinator.api.dto.response;

import com.chronoscore.common.domain.TenantExecutionPolicy;

import java.time.Instant;

/**
 * REST response representing a tenant's execution policy and quotas.
 */
public record TenantPolicyResponse(
        String tenantId,
        int maxConcurrentTasks,
        int maxQueuedTasks,
        double tasksPerSecond,
        int burstCapacity,
        int maxWorkflowExecutions,
        Instant createdAt,
        Instant updatedAt
) {

    public static TenantPolicyResponse from(TenantExecutionPolicy policy) {
        if (policy == null) {
            return null;
        }
        return new TenantPolicyResponse(
                policy.getTenantId(),
                policy.getMaxConcurrentTasks(),
                policy.getMaxQueuedTasks(),
                policy.getTasksPerSecond(),
                policy.getBurstCapacity(),
                policy.getMaxWorkflowExecutions(),
                policy.getCreatedAt(),
                policy.getUpdatedAt()
        );
    }
}
