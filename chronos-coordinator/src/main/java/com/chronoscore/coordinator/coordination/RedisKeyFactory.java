package com.chronoscore.coordinator.coordination;

import java.util.Objects;
import java.util.UUID;

/**
 * Centralized factory for constructing deterministic Redis keys across the coordination layer.
 * <p>
 * Enforces key namespacing and prevents scattered or duplicate key literals throughout the codebase.
 */
public final class RedisKeyFactory {

    private static final String NAMESPACE_PREFIX = "chronos";
    private static final String LEADER_NAMESPACE = NAMESPACE_PREFIX + ":leader";
    private static final String LOCK_NAMESPACE = NAMESPACE_PREFIX + ":task:lock";

    private static final String LEADER_LOCK_KEY = LEADER_NAMESPACE + ":lock";
    private static final String LEADER_EPOCH_KEY = LEADER_NAMESPACE + ":epoch";

    private RedisKeyFactory() {
        // Utility factory; prevent instantiation
    }

    /**
     * Redis key for the lease-based leader election lock.
     * Value stores the active leader's coordinatorId.
     *
     * @return Redis key string.
     */
    public static String leaderLockKey() {
        return LEADER_LOCK_KEY;
    }

    /**
     * Redis key for the monotonically increasing cluster leader epoch counter.
     * Incremented via {@code INCR} whenever a coordinator acquires leadership.
     *
     * @return Redis key string.
     */
    public static String leaderEpochKey() {
        return LEADER_EPOCH_KEY;
    }

    /**
     * Redis key for a distributed task lock for a specific task UUID.
     *
     * @param taskId task UUID.
     * @return Redis key string.
     */
    public static String taskLockKey(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        return taskLockKey(taskId.toString());
    }

    /**
     * Redis key for a distributed lock on an arbitrary resource name.
     *
     * @param resource unique resource identifier.
     * @return Redis key string.
     */
    public static String taskLockKey(String resource) {
        Objects.requireNonNull(resource, "resource must not be null");
        return LOCK_NAMESPACE + ":" + resource;
    }

    /**
     * Redis key for token-bucket rate limiting state for a tenant.
     *
     * @param tenantId tenant identifier.
     * @return Redis key string.
     */
    public static String tenantRateLimitKey(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        return NAMESPACE_PREFIX + ":tenant:" + tenantId + ":ratelimit";
    }

    /**
     * Redis key for the Set of active running task UUIDs for a tenant.
     *
     * @param tenantId tenant identifier.
     * @return Redis key string.
     */
    public static String tenantRunningKey(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        return NAMESPACE_PREFIX + ":tenant:" + tenantId + ":running";
    }

    /**
     * Redis key for a mutual-exclusion concurrency lock on a logical concurrency key.
     *
     * @param tenantId       tenant identifier.
     * @param concurrencyKey logical concurrency group key.
     * @return Redis lock resource string.
     */
    public static String concurrencyKeyLock(String tenantId, String concurrencyKey) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(concurrencyKey, "concurrencyKey must not be null");
        return NAMESPACE_PREFIX + ":tenant:" + tenantId + ":concurrency:" + concurrencyKey;
    }

    /**
     * Redis key for active workflow executions counter/set.
     *
     * @param workflowId workflow UUID.
     * @return Redis key string.
     */
    public static String workflowRunningKey(UUID workflowId) {
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        return NAMESPACE_PREFIX + ":workflow:" + workflowId + ":running";
    }
}

