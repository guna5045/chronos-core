package com.chronoscore.coordinator.policy;

import com.chronoscore.common.domain.TenantExecutionPolicy;

/**
 * Contract for enforcing per-tenant admission rate limits.
 */
public interface TenantRateLimiter {

    /**
     * Attempts to acquire execution admission permission for a task under the tenant's rate limit.
     *
     * @param tenantId tenant identifier.
     * @param policy   tenant execution policy with tasksPerSecond and burstCapacity.
     * @return true if admitted within rate limits, false if rate limited.
     */
    boolean tryAcquire(String tenantId, TenantExecutionPolicy policy);

    /**
     * Convenience method to attempt acquisition using explicit rate and burst values.
     */
    default boolean tryAcquire(String tenantId, double tasksPerSecond, int burstCapacity) {
        if (tasksPerSecond <= 0) {
            return true;
        }
        return tryAcquire(tenantId, TenantExecutionPolicy.builder()
                .tenantId(tenantId)
                .tasksPerSecond(tasksPerSecond)
                .burstCapacity(Math.max(1, burstCapacity))
                .build());
    }

    /**
     * Checks if the tenant is currently rate limited without consuming tokens.
     *
     * @param tenantId tenant identifier.
     * @param policy   tenant execution policy.
     * @return true if currently rate-limited, false otherwise.
     */
    boolean isRateLimited(String tenantId, TenantExecutionPolicy policy);

    /**
     * Resets rate limiter state for a tenant (used in tests or operational maintenance).
     *
     * @param tenantId tenant identifier.
     */
    void reset(String tenantId);
}
