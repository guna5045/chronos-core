package com.chronoscore.ai.ports;

import java.util.Optional;

/**
 * Operational port for tenant policy telemetry inspection.
 */
public interface AiTenantOperationsPort {

    record TenantPolicyDetail(
            String tenantId,
            int maxConcurrentTasks,
            int maxQueuedTasks,
            double tasksPerSecond,
            int burstCapacity,
            int maxWorkflowExecutions
    ) {}

    Optional<TenantPolicyDetail> getTenantPolicy(String tenantId);
}
