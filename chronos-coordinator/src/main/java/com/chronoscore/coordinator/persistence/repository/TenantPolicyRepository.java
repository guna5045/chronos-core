package com.chronoscore.coordinator.persistence.repository;

import com.chronoscore.common.domain.TenantExecutionPolicy;

import java.util.Optional;

/**
 * Persistence contract for {@link TenantExecutionPolicy} entities.
 */
public interface TenantPolicyRepository {

    /**
     * Persists a new tenant execution policy.
     *
     * @param policy tenant execution policy to save.
     * @return persisted policy.
     */
    TenantExecutionPolicy save(TenantExecutionPolicy policy);

    /**
     * Finds a tenant execution policy by tenant ID.
     *
     * @param tenantId tenant identifier.
     * @return Optional containing the policy if configured, or empty.
     */
    Optional<TenantExecutionPolicy> findByTenantId(String tenantId);

    /**
     * Updates an existing tenant execution policy.
     *
     * @param policy policy with updated limits.
     * @return updated policy.
     */
    TenantExecutionPolicy update(TenantExecutionPolicy policy);

    /**
     * Retrieves the effective policy for a tenant. If no explicit policy is persisted,
     * returns a default policy with cluster-standard quotas.
     *
     * @param tenantId tenant identifier.
     * @return active or default TenantExecutionPolicy.
     */
    TenantExecutionPolicy getEffectivePolicy(String tenantId);

    /**
     * Deletes a tenant execution policy, reverting the tenant to default cluster quotas.
     *
     * @param tenantId tenant identifier.
     * @return true if deleted, false if not found.
     */
    boolean delete(String tenantId);
}
