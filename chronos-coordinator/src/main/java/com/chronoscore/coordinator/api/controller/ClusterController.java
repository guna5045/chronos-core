package com.chronoscore.coordinator.api.controller;

import com.chronoscore.coordinator.api.dto.response.ClusterOverviewResponse;
import com.chronoscore.coordinator.cluster.model.WorkerNode;
import com.chronoscore.coordinator.cluster.registry.WorkerRegistry;
import com.chronoscore.coordinator.coordination.leader.LeaderElectionService;
import com.chronoscore.coordinator.coordination.model.LeaderIdentity;
import com.chronoscore.coordinator.scheduler.SchedulerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only REST controller exposing cluster leadership status, worker capacity, and scheduler health.
 */
@RestController
@RequestMapping("/api/v1/cluster")
public class ClusterController {

    private final LeaderElectionService leaderElectionService;
    private final WorkerRegistry workerRegistry;
    private final SchedulerProperties schedulerProperties;

    public ClusterController(
            @Autowired(required = false) LeaderElectionService leaderElectionService,
            WorkerRegistry workerRegistry,
            SchedulerProperties schedulerProperties) {
        this.leaderElectionService = leaderElectionService;
        this.workerRegistry = workerRegistry;
        this.schedulerProperties = schedulerProperties;
    }

    @GetMapping
    public ResponseEntity<ClusterOverviewResponse> getClusterOverview() {
        boolean isLeader = leaderElectionService != null && leaderElectionService.isLeader();
        LeaderIdentity identity = leaderElectionService != null ? leaderElectionService.getLeadershipIdentity() : null;

        String leaderId = identity != null ? identity.getCoordinatorId() : "standalone";
        Long fencingToken = identity != null ? identity.getLeaderEpoch() : 0L;

        List<WorkerNode> allWorkers = workerRegistry.getAllWorkers();
        List<WorkerNode> activeWorkers = workerRegistry.getActiveWorkers();

        int totalCapacity = activeWorkers.stream().mapToInt(WorkerNode::maxConcurrency).sum();
        int totalActiveTasks = activeWorkers.stream().mapToInt(WorkerNode::activeTasks).sum();

        boolean schedulerEnabled = schedulerProperties != null && schedulerProperties.isEnabled();

        ClusterOverviewResponse response = new ClusterOverviewResponse(
                leaderId,
                isLeader,
                leaderId,
                fencingToken,
                allWorkers.size(),
                activeWorkers.size(),
                totalCapacity,
                totalActiveTasks,
                schedulerEnabled
        );

        return ResponseEntity.ok(response);
    }
}
