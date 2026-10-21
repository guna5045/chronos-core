package com.chronoscore.coordinator.ai.adapter;

import com.chronoscore.ai.ports.AiWorkerOperationsPort;
import com.chronoscore.coordinator.cluster.model.WorkerNode;
import com.chronoscore.coordinator.cluster.registry.WorkerRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Adapter implementing AiWorkerOperationsPort by delegating to Chronos Core WorkerRegistry.
 */
@Component
public class WorkerOperationsAdapter implements AiWorkerOperationsPort {

    private final WorkerRegistry workerRegistry;

    public WorkerOperationsAdapter(WorkerRegistry workerRegistry) {
        this.workerRegistry = Objects.requireNonNull(workerRegistry, "workerRegistry must not be null");
    }

    @Override
    public List<WorkerSummary> listWorkers() {
        return workerRegistry.getAllWorkers().stream()
                .map(w -> new WorkerSummary(
                        w.workerId(),
                        w.state().name(),
                        w.maxConcurrency(),
                        w.activeTasks(),
                        w.lastHeartbeatAt()
                ))
                .toList();
    }

    @Override
    public Optional<WorkerDetail> getWorker(String workerId) {
        return workerRegistry.getWorker(workerId)
                .map(w -> new WorkerDetail(
                        w.workerId(),
                        w.state().name(),
                        w.maxConcurrency(),
                        w.activeTasks(),
                        w.supportedTaskTypes(),
                        w.lastHeartbeatAt(),
                        w.registeredAt()
                ));
    }
}
