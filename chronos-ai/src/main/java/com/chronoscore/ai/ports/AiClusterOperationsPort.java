package com.chronoscore.ai.ports;

/**
 * Operational port for coordinator leadership and cluster capacity telemetry.
 */
public interface AiClusterOperationsPort {

    record ClusterTelemetry(
            String leaderId,
            long leaderEpoch,
            boolean leader,
            int activeWorkers,
            int suspectedWorkers,
            int totalCapacity
    ) {}

    ClusterTelemetry getClusterOverview();
}
