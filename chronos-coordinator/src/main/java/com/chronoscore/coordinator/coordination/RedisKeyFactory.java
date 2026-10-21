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
}
