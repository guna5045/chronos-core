package com.chronoscore.coordinator.cluster.model;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable snapshot representing a Worker node registered in the coordinator's cluster registry.
 */
public record WorkerNode(
        String workerId,
        String hostname,
        String ipAddress,
        int port,
        int maxConcurrency,
        int activeTasks,
        Set<String> supportedTaskTypes,
        long processId,
        String version,
        String sessionId,
        WorkerState state,
        Instant registeredAt,
        Instant lastHeartbeatAt,
        double cpuUsagePercent,
        double memoryUsagePercent
) {

    public WorkerNode {
        Objects.requireNonNull(workerId, "workerId must not be null");
        Objects.requireNonNull(hostname, "hostname must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(registeredAt, "registeredAt must not be null");
        Objects.requireNonNull(lastHeartbeatAt, "lastHeartbeatAt must not be null");

        supportedTaskTypes = supportedTaskTypes == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(supportedTaskTypes));

        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("maxConcurrency must be positive, was: " + maxConcurrency);
        }
        if (activeTasks < 0) {
            activeTasks = 0;
        }
    }

    public int availableCapacity() {
        return Math.max(0, maxConcurrency - activeTasks);
    }

    public boolean hasAvailableCapacity() {
        return availableCapacity() > 0;
    }

    public boolean isHealthy() {
        return state == WorkerState.ACTIVE;
    }

    public boolean supportsTaskType(String taskType) {
        if (supportedTaskTypes.isEmpty()) {
            return true; // Empty set implies general worker supporting all types
        }
        return supportedTaskTypes.contains(taskType);
    }

    public WorkerNode withHeartbeat(int newActiveTasks, double cpu, double memory, Instant timestamp) {
        return new WorkerNode(
                workerId,
                hostname,
                ipAddress,
                port,
                maxConcurrency,
                Math.max(0, newActiveTasks),
                supportedTaskTypes,
                processId,
                version,
                sessionId,
                WorkerState.ACTIVE, // Heartbeat recovers suspected worker to active
                registeredAt,
                timestamp,
                cpu,
                memory
        );
    }

    public WorkerNode withState(WorkerState newState) {
        return new WorkerNode(
                workerId,
                hostname,
                ipAddress,
                port,
                maxConcurrency,
                activeTasks,
                supportedTaskTypes,
                processId,
                version,
                sessionId,
                newState,
                registeredAt,
                lastHeartbeatAt,
                cpuUsagePercent,
                memoryUsagePercent
        );
    }

    public WorkerNode withActiveTasks(int newActiveTasks) {
        return new WorkerNode(
                workerId,
                hostname,
                ipAddress,
                port,
                maxConcurrency,
                Math.max(0, newActiveTasks),
                supportedTaskTypes,
                processId,
                version,
                sessionId,
                state,
                registeredAt,
                lastHeartbeatAt,
                cpuUsagePercent,
                memoryUsagePercent
        );
    }
}
