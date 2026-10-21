package com.chronoscore.coordinator.api.dto.response;

import com.chronoscore.coordinator.cluster.model.WorkerNode;
import com.chronoscore.coordinator.cluster.model.WorkerState;

import java.time.Instant;
import java.util.Set;

/**
 * REST response representing a registered worker node snapshot.
 */
public record WorkerResponse(
        String workerId,
        String address,
        WorkerState state,
        int capacity,
        int activeTasks,
        Set<String> supportedTaskTypes,
        Instant registeredAt,
        Instant lastHeartbeatAt,
        double cpu,
        double memory
) {

    public static WorkerResponse from(WorkerNode node) {
        if (node == null) {
            return null;
        }
        return new WorkerResponse(
                node.workerId(),
                node.ipAddress() + ":" + node.port(),
                node.state(),
                node.maxConcurrency(),
                node.activeTasks(),
                node.supportedTaskTypes(),
                node.registeredAt(),
                node.lastHeartbeatAt(),
                node.cpuUsagePercent(),
                node.memoryUsagePercent()
        );
    }
}
