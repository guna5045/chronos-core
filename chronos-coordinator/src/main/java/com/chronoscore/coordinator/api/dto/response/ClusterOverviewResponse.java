package com.chronoscore.coordinator.api.dto.response;

/**
 * REST response representing active cluster status and worker capacity.
 */
public record ClusterOverviewResponse(
        String coordinatorId,
        boolean isLeader,
        String leaderId,
        Long fencingToken,
        int totalWorkers,
        int activeWorkers,
        int totalCapacity,
        int totalActiveTasks,
        boolean schedulerEnabled
) {
}
