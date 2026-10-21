package com.chronoscore.ai.ports;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Operational port for worker fleet observation.
 */
public interface AiWorkerOperationsPort {

    record WorkerSummary(
            String workerId,
            String state,
            int totalCapacity,
            int activeTasks,
            Instant lastHeartbeat
    ) {}

    record WorkerDetail(
            String workerId,
            String state,
            int totalCapacity,
            int activeTasks,
            Set<String> supportedTaskTypes,
            Instant lastHeartbeat,
            Instant registeredAt
    ) {}

    List<WorkerSummary> listWorkers();

    Optional<WorkerDetail> getWorker(String workerId);
}
