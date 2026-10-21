package com.chronoscore.coordinator.cluster.integration;

import com.chronoscore.common.proto.ProtoHeartbeatPing;
import com.chronoscore.common.proto.ProtoHeartbeatPong;
import com.chronoscore.common.proto.ProtoTaskResult;
import com.chronoscore.common.proto.ProtoWorkerIdentity;
import com.chronoscore.common.proto.TaskCoordinationServiceGrpc;
import com.chronoscore.coordinator.cluster.config.ClusterProperties;
import com.chronoscore.coordinator.cluster.grpc.TaskCoordinationGrpcService;
import com.chronoscore.coordinator.cluster.health.WorkerHealthMonitor;
import com.chronoscore.coordinator.cluster.model.WorkerNode;
import com.chronoscore.coordinator.cluster.model.WorkerState;
import com.chronoscore.coordinator.cluster.registry.InMemoryWorkerRegistry;
import com.chronoscore.coordinator.coordination.CoordinationProperties;
import com.chronoscore.coordinator.coordination.fencing.FencingTokenService;
import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * Multi-worker cluster integration tests verifying:
 * <ul>
 *   <li>Independent concurrent worker registration and capacity tracking (Workers A, B, C)</li>
 *   <li>Worker failure detection and eviction isolation</li>
 *   <li>Stale connection session protection on reconnect</li>
 *   <li>Bidirectional gRPC communication flow</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MultiWorkerClusterIntegrationTest {

    private String serverName;
    private Server inProcessServer;
    private ManagedChannel channel;

    private InMemoryWorkerRegistry registry;
    private WorkerHealthMonitor healthMonitor;
    private TaskCoordinationGrpcService coordinationService;

    @Mock
    private FencingTokenService fencingTokenService;

    private CoordinationProperties coordinationProperties;
    private ClusterProperties clusterProperties;

    private TaskCoordinationServiceGrpc.TaskCoordinationServiceBlockingStub blockingStub;
    private TaskCoordinationServiceGrpc.TaskCoordinationServiceStub asyncStub;

    @BeforeEach
    void setUp() throws IOException {
        serverName = InProcessServerBuilder.generateName();

        registry = new InMemoryWorkerRegistry();
        coordinationProperties = new CoordinationProperties();
        coordinationProperties.setCoordinatorId("coordinator-leader-alpha");

        clusterProperties = new ClusterProperties();
        clusterProperties.getCluster().setHeartbeatTimeout(Duration.ofSeconds(3));
        clusterProperties.getCluster().setEvictionTimeout(Duration.ofSeconds(6));
        clusterProperties.getCluster().setEnabled(true);

        lenient().when(fencingTokenService.getCurrentFencingToken())
                .thenReturn(new com.chronoscore.coordinator.coordination.model.FencingToken("coordinator-leader-alpha", 10L, Instant.now()));

        coordinationService = new TaskCoordinationGrpcService(
                registry,
                fencingTokenService,
                coordinationProperties,
                null // transactionalTaskService optional in coordination transport test
        );

        healthMonitor = new WorkerHealthMonitor(registry, clusterProperties);

        inProcessServer = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(coordinationService)
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();

        blockingStub = TaskCoordinationServiceGrpc.newBlockingStub(channel);
        asyncStub = TaskCoordinationServiceGrpc.newStub(channel);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
        inProcessServer.shutdown().awaitTermination(1, TimeUnit.SECONDS);
    }

    private ProtoWorkerIdentity buildIdentity(String workerId, int maxConcurrency) {
        return ProtoWorkerIdentity.newBuilder()
                .setWorkerId(workerId)
                .setHostname("host-" + workerId)
                .setIpAddress("127.0.0.1")
                .setPort(9091)
                .setMaxConcurrentTasks(maxConcurrency)
                .setActiveTasks(0)
                .setVersion("0.1.0-SNAPSHOT")
                .addSupportedTaskTypes("DEFAULT")
                .addSupportedTaskTypes("COMPUTE")
                .setProcessId(1234L)
                .setRegisteredAt(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build())
                .build();
    }

    @Test
    @DisplayName("Multi-worker cluster: Workers A, B, C register, acquire sessions, and track capacity independently")
    void testMultiWorkerRegistrationAndCapacityTracking() {
        // Register Worker A, B, C
        ProtoHeartbeatPong pongA = blockingStub.registerWorker(buildIdentity("worker-A", 8));
        ProtoHeartbeatPong pongB = blockingStub.registerWorker(buildIdentity("worker-B", 4));
        ProtoHeartbeatPong pongC = blockingStub.registerWorker(buildIdentity("worker-C", 16));

        assertThat(pongA.getAcknowledged()).isTrue();
        assertThat(pongB.getAcknowledged()).isTrue();
        assertThat(pongC.getAcknowledged()).isTrue();

        assertThat(pongA.getEpochFencingToken()).isEqualTo(10L);
        assertThat(pongA.getSessionId()).isNotBlank();
        assertThat(pongB.getSessionId()).isNotBlank();
        assertThat(pongC.getSessionId()).isNotBlank();

        // Verify independent sessions
        assertThat(pongA.getSessionId()).isNotEqualTo(pongB.getSessionId());
        assertThat(pongB.getSessionId()).isNotEqualTo(pongC.getSessionId());

        // Verify registry status
        assertThat(registry.getActiveWorkerCount()).isEqualTo(3);

        // Update capacity independently via heartbeats
        registry.recordHeartbeat("worker-A", pongA.getSessionId(), 3, 20.0, 30.0);
        registry.recordHeartbeat("worker-B", pongB.getSessionId(), 1, 10.0, 15.0);

        WorkerNode nodeA = registry.getWorker("worker-A").orElseThrow();
        WorkerNode nodeB = registry.getWorker("worker-B").orElseThrow();
        WorkerNode nodeC = registry.getWorker("worker-C").orElseThrow();

        assertThat(nodeA.activeTasks()).isEqualTo(3);
        assertThat(nodeA.availableCapacity()).isEqualTo(5);

        assertThat(nodeB.activeTasks()).isEqualTo(1);
        assertThat(nodeB.availableCapacity()).isEqualTo(3);

        assertThat(nodeC.activeTasks()).isEqualTo(0);
        assertThat(nodeC.availableCapacity()).isEqualTo(16);
    }

    @Test
    @DisplayName("Worker failure isolation: Worker A dies and is evicted while Worker B and Worker C remain healthy")
    void testWorkerFailureIsolation() {
        ProtoHeartbeatPong pongA = blockingStub.registerWorker(buildIdentity("worker-A", 8));
        ProtoHeartbeatPong pongB = blockingStub.registerWorker(buildIdentity("worker-B", 8));
        ProtoHeartbeatPong pongC = blockingStub.registerWorker(buildIdentity("worker-C", 8));

        assertThat(registry.getActiveWorkerCount()).isEqualTo(3);

        // Simulate Worker A missing heartbeats (> 3s)
        WorkerNode nodeA = registry.getWorker("worker-A").orElseThrow();
        registry.registerWorker(nodeA.withHeartbeat(0, 0, 0, Instant.now().minusSeconds(4)));

        // Run health check: Worker A becomes SUSPECTED
        healthMonitor.checkHealthNow();
        assertThat(registry.getWorker("worker-A").orElseThrow().state()).isEqualTo(WorkerState.SUSPECTED);
        assertThat(registry.getWorker("worker-B").orElseThrow().state()).isEqualTo(WorkerState.ACTIVE);
        assertThat(registry.getWorker("worker-C").orElseThrow().state()).isEqualTo(WorkerState.ACTIVE);
        assertThat(registry.getActiveWorkerCount()).isEqualTo(2);

        // Simulate Worker A exceeding eviction timeout (> 6s)
        WorkerNode suspectedA = registry.getWorker("worker-A").orElseThrow();
        registry.registerWorker(new WorkerNode(
                suspectedA.workerId(), suspectedA.hostname(), suspectedA.ipAddress(), suspectedA.port(),
                suspectedA.maxConcurrency(), suspectedA.activeTasks(), suspectedA.supportedTaskTypes(),
                suspectedA.processId(), suspectedA.version(), suspectedA.sessionId(), WorkerState.SUSPECTED,
                suspectedA.registeredAt(), Instant.now().minusSeconds(7), 0.0, 0.0
        ));

        healthMonitor.checkHealthNow();
        assertThat(registry.getWorker("worker-A").orElseThrow().state()).isEqualTo(WorkerState.REMOVED);
        assertThat(registry.getWorker("worker-B").orElseThrow().state()).isEqualTo(WorkerState.ACTIVE);
        assertThat(registry.getWorker("worker-C").orElseThrow().state()).isEqualTo(WorkerState.ACTIVE);
        assertThat(registry.getActiveWorkerCount()).isEqualTo(2);

        // Worker A reconnects with new session and returns to ACTIVE
        ProtoHeartbeatPong reconnectPong = blockingStub.registerWorker(buildIdentity("worker-A", 8));
        assertThat(reconnectPong.getAcknowledged()).isTrue();
        assertThat(reconnectPong.getSessionId()).isNotEqualTo(pongA.getSessionId());
        assertThat(registry.getWorker("worker-A").orElseThrow().state()).isEqualTo(WorkerState.ACTIVE);
        assertThat(registry.getActiveWorkerCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Stale connection test: previous connection session cannot overwrite state of a new active session")
    void testStaleConnectionProtection() {
        // Worker connects as session 1
        ProtoHeartbeatPong session1Pong = blockingStub.registerWorker(buildIdentity("worker-race", 8));
        String session1 = session1Pong.getSessionId();

        // Worker disconnects and reconnects as session 2
        ProtoHeartbeatPong session2Pong = blockingStub.registerWorker(buildIdentity("worker-race", 8));
        String session2 = session2Pong.getSessionId();
        assertThat(session2).isNotEqualTo(session1);

        // Stale connection 1 attempts to send heartbeat
        boolean staleHeartbeatAccepted = registry.recordHeartbeat("worker-race", session1, 5, 50.0, 50.0);
        assertThat(staleHeartbeatAccepted).isFalse();

        // Stale connection 1 attempts state update
        boolean staleStateUpdateAccepted = registry.updateState("worker-race", session1, WorkerState.SUSPECTED);
        assertThat(staleStateUpdateAccepted).isFalse();

        // Verify session 2 state remains completely intact
        WorkerNode activeNode = registry.getWorker("worker-race").orElseThrow();
        assertThat(activeNode.sessionId()).isEqualTo(session2);
        assertThat(activeNode.state()).isEqualTo(WorkerState.ACTIVE);
        assertThat(activeNode.activeTasks()).isEqualTo(0);
    }

    @Test
    @DisplayName("Bidirectional heartbeat stream: processes streaming pings and returns pongs with leader epoch")
    void testBidirectionalHeartbeatStream() throws InterruptedException {
        ProtoHeartbeatPong registerPong = blockingStub.registerWorker(buildIdentity("worker-stream", 8));
        String sessionId = registerPong.getSessionId();

        CountDownLatch pongLatch = new CountDownLatch(2);
        AtomicReference<ProtoHeartbeatPong> lastPong = new AtomicReference<>();

        StreamObserver<ProtoHeartbeatPing> requestStream = asyncStub.streamHeartbeat(new StreamObserver<>() {
            @Override
            public void onNext(ProtoHeartbeatPong value) {
                lastPong.set(value);
                pongLatch.countDown();
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onCompleted() {}
        });

        // Send 2 pings
        requestStream.onNext(ProtoHeartbeatPing.newBuilder()
                .setWorkerId("worker-stream")
                .setSessionId(sessionId)
                .setActiveTasks(1)
                .setCpuUsagePercent(15.0)
                .build());

        requestStream.onNext(ProtoHeartbeatPing.newBuilder()
                .setWorkerId("worker-stream")
                .setSessionId(sessionId)
                .setActiveTasks(2)
                .setCpuUsagePercent(25.0)
                .build());

        boolean received = pongLatch.await(3, TimeUnit.SECONDS);
        requestStream.onCompleted();

        assertThat(received).isTrue();
        assertThat(lastPong.get()).isNotNull();
        assertThat(lastPong.get().getAcknowledged()).isTrue();
        assertThat(lastPong.get().getEpochFencingToken()).isEqualTo(10L);
        assertThat(lastPong.get().getSessionId()).isEqualTo(sessionId);
    }

    @Test
    @DisplayName("reportTaskResult: reports attempt completion and receives acknowledgment")
    void testReportTaskResult() {
        ProtoHeartbeatPong registerPong = blockingStub.registerWorker(buildIdentity("worker-res", 8));

        ProtoTaskResult result = ProtoTaskResult.newBuilder()
                .setTaskId(UUID.randomUUID().toString())
                .setWorkerId("worker-res")
                .setAttemptNumber(1)
                .setOutputPayload("{\"computed\":42}")
                .setStatus(com.chronoscore.common.proto.ProtoExecutionStatus.EXECUTION_STATUS_SUCCEEDED)
                .build();

        ProtoHeartbeatPong pong = blockingStub.reportTaskResult(result);
        assertThat(pong.getAcknowledged()).isTrue();
        assertThat(pong.getEpochFencingToken()).isEqualTo(10L);
    }
}
