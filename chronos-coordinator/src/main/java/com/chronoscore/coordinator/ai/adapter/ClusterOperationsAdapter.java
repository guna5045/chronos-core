package com.chronoscore.coordinator.ai.adapter;

import com.chronoscore.ai.ports.AiClusterOperationsPort;
import com.chronoscore.coordinator.cluster.model.WorkerNode;
import com.chronoscore.coordinator.cluster.registry.WorkerRegistry;
import com.chronoscore.coordinator.coordination.leader.LeaderElectionService;
import com.chronoscore.coordinator.coordination.model.LeaderIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Adapter implementing AiClusterOperationsPort by delegating to LeaderElectionService and WorkerRegistry.
 */
@Component
public class ClusterOperationsAdapter implements AiClusterOperationsPort {

    private final LeaderElectionService leaderElectionService;
    private final WorkerRegistry workerRegistry;

    public ClusterOperationsAdapter(@Autowired(required = false) LeaderElectionService leaderElectionService,
                                    WorkerRegistry workerRegistry) {
        this.leaderElectionService = leaderElectionService;
        this.workerRegistry = Objects.requireNonNull(workerRegistry, "workerRegistry must not be null");
    }

    @Override
    public ClusterTelemetry getClusterOverview() {
        boolean isLeader = leaderElectionService != null && leaderElectionService.isLeader();
        LeaderIdentity identity = leaderElectionService != null ? leaderElectionService.getLeadershipIdentity() : null;

        String leaderId = identity != null ? identity.getCoordinatorId() : "standalone";
        long leaderEpoch = identity != null ? identity.getLeaderEpoch() : 0L;

        List<WorkerNode> allWorkers = workerRegistry.getAllWorkers();
        List<WorkerNode> activeWorkers = workerRegistry.getActiveWorkers();
        int suspected = allWorkers.size() - activeWorkers.size();
        int totalCapacity = activeWorkers.stream().mapToInt(WorkerNode::maxConcurrency).sum();

        return new ClusterTelemetry(leaderId, leaderEpoch, isLeader, activeWorkers.size(), suspected, totalCapacity);
    }
}
