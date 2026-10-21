package com.chronoscore.coordinator.api.controller;

import com.chronoscore.coordinator.api.advice.GlobalExceptionHandler;
import com.chronoscore.coordinator.api.security.DevJwtTokenHelper;
import com.chronoscore.coordinator.api.security.JwtSecurityProperties;
import com.chronoscore.coordinator.api.security.SecurityConfig;
import com.chronoscore.coordinator.api.security.TenantSecurityContext;
import com.chronoscore.coordinator.cluster.model.WorkerNode;
import com.chronoscore.coordinator.cluster.model.WorkerState;
import com.chronoscore.coordinator.cluster.registry.WorkerRegistry;
import com.chronoscore.coordinator.coordination.leader.LeaderElectionService;
import com.chronoscore.coordinator.coordination.model.LeaderIdentity;
import com.chronoscore.coordinator.scheduler.SchedulerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {WorkerController.class, ClusterController.class})
@Import({SecurityConfig.class, JwtSecurityProperties.class, DevJwtTokenHelper.class, TenantSecurityContext.class, GlobalExceptionHandler.class})
class WorkerAndClusterControllerRestTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DevJwtTokenHelper tokenHelper;

    @MockBean
    private WorkerRegistry workerRegistry;

    @MockBean
    private LeaderElectionService leaderElectionService;

    @MockBean
    private SchedulerProperties schedulerProperties;

    private String getViewerToken() {
        return tokenHelper.generateToken("viewer-user", "tenant-alpha", List.of("VIEWER"));
    }

    private WorkerNode createWorker(String id, WorkerState state, int maxConcurrency, int active) {
        return new WorkerNode(
                id,
                id + ".local",
                "10.0.0.1",
                9090,
                maxConcurrency,
                active,
                Set.of("PAYMENT", "IMAGE_PROCESS"),
                12345L,
                "1.0.0",
                "session-" + id,
                state,
                Instant.now(),
                Instant.now(),
                12.5,
                45.0
        );
    }

    @Test
    @DisplayName("GET /api/v1/workers returns list of all cluster workers (200 OK)")
    void listWorkersSuccess() throws Exception {
        String token = getViewerToken();
        WorkerNode w1 = createWorker("worker-1", WorkerState.ACTIVE, 10, 2);
        WorkerNode w2 = createWorker("worker-2", WorkerState.ACTIVE, 20, 5);

        when(workerRegistry.getAllWorkers()).thenReturn(List.of(w1, w2));

        mockMvc.perform(get("/api/v1/workers")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].workerId").value("worker-1"))
                .andExpect(jsonPath("$[0].capacity").value(10))
                .andExpect(jsonPath("$[0].activeTasks").value(2))
                .andExpect(jsonPath("$[1].workerId").value("worker-2"));
    }

    @Test
    @DisplayName("GET /api/v1/workers/{workerId} returns worker details (200 OK)")
    void getWorkerDetailsSuccess() throws Exception {
        String token = getViewerToken();
        WorkerNode w1 = createWorker("worker-1", WorkerState.ACTIVE, 10, 2);

        when(workerRegistry.getWorker("worker-1")).thenReturn(Optional.of(w1));

        mockMvc.perform(get("/api/v1/workers/worker-1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerId").value("worker-1"))
                .andExpect(jsonPath("$.address").value("10.0.0.1:9090"))
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.cpu").value(12.5))
                .andExpect(jsonPath("$.memory").value(45.0));
    }

    @Test
    @DisplayName("GET /api/v1/workers/{workerId} returns 404 when worker is not registered")
    void getWorkerNotFound() throws Exception {
        String token = getViewerToken();
        when(workerRegistry.getWorker("unknown-worker")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/workers/unknown-worker")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/v1/cluster returns leadership state, fencing token, worker count, and total capacity")
    void getClusterOverviewSuccess() throws Exception {
        String token = getViewerToken();
        WorkerNode w1 = createWorker("worker-1", WorkerState.ACTIVE, 10, 2);
        WorkerNode w2 = createWorker("worker-2", WorkerState.ACTIVE, 20, 5);

        when(leaderElectionService.isLeader()).thenReturn(true);
        when(leaderElectionService.getLeadershipIdentity()).thenReturn(
                LeaderIdentity.leader("coord-primary", 42L, Instant.now(), Instant.now().plusSeconds(60)));
        when(workerRegistry.getAllWorkers()).thenReturn(List.of(w1, w2));
        when(workerRegistry.getActiveWorkers()).thenReturn(List.of(w1, w2));
        when(schedulerProperties.isEnabled()).thenReturn(true);

        mockMvc.perform(get("/api/v1/cluster")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coordinatorId").value("coord-primary"))
                .andExpect(jsonPath("$.isLeader").value(true))
                .andExpect(jsonPath("$.leaderId").value("coord-primary"))
                .andExpect(jsonPath("$.fencingToken").value(42))
                .andExpect(jsonPath("$.totalWorkers").value(2))
                .andExpect(jsonPath("$.activeWorkers").value(2))
                .andExpect(jsonPath("$.totalCapacity").value(30))
                .andExpect(jsonPath("$.totalActiveTasks").value(7))
                .andExpect(jsonPath("$.schedulerEnabled").value(true));
    }
}
