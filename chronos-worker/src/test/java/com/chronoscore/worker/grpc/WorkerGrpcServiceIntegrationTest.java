package com.chronoscore.worker.grpc;

import com.chronoscore.common.proto.ProtoHeartbeatPong;
import com.chronoscore.common.proto.ProtoTask;
import com.chronoscore.common.proto.ProtoTaskAssignment;
import com.chronoscore.common.proto.ProtoTaskCancelRequest;
import com.chronoscore.common.proto.ProtoTaskCancelResponse;
import com.chronoscore.common.proto.ProtoTaskResult;
import com.chronoscore.common.proto.ProtoTaskStateUpdate;
import com.chronoscore.common.proto.ProtoTaskStatus;
import com.chronoscore.common.proto.WorkerServiceGrpc;
import com.chronoscore.worker.config.WorkerProperties;
import com.chronoscore.worker.execution.VirtualThreadTaskExecutor;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class WorkerGrpcServiceIntegrationTest {

    private String serverName;
    private Server inProcessServer;
    private ManagedChannel channel;

    private VirtualThreadTaskExecutor executor;
    private WorkerProperties properties;
    private WorkerServiceGrpcImpl workerService;

    @Mock
    private CoordinatorClient coordinatorClient;

    private WorkerServiceGrpc.WorkerServiceBlockingStub blockingStub;

    @BeforeEach
    void setUp() throws IOException {
        serverName = InProcessServerBuilder.generateName();

        properties = new WorkerProperties();
        properties.setId("worker-it-01");
        properties.setMaxConcurrency(4);

        executor = new VirtualThreadTaskExecutor(4);

        lenient().when(coordinatorClient.reportTaskResult(any(ProtoTaskResult.class), any(Duration.class)))
                .thenReturn(ProtoHeartbeatPong.newBuilder().setAcknowledged(true).build());

        workerService = new WorkerServiceGrpcImpl(executor, properties, coordinatorClient);

        inProcessServer = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(workerService)
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();

        blockingStub = WorkerServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdown();
        channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
        inProcessServer.shutdown().awaitTermination(1, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("DispatchTask: accepts assignment and immediately returns RUNNING state update")
    void testDispatchTask() {
        String taskId = UUID.randomUUID().toString();
        ProtoTask task = ProtoTask.newBuilder()
                .setTaskId(taskId)
                .setTaskType("COMPUTE")
                .setPayload("{\"data\":42}")
                .build();

        ProtoTaskAssignment assignment = ProtoTaskAssignment.newBuilder()
                .setTaskId(taskId)
                .setWorkerId("worker-it-01")
                .setAttemptNumber(1)
                .setTask(task)
                .build();

        ProtoTaskStateUpdate response = blockingStub.dispatchTask(assignment);

        assertThat(response).isNotNull();
        assertThat(response.getTaskId()).isEqualTo(taskId);
        assertThat(response.getNewStatus()).isEqualTo(ProtoTaskStatus.TASK_STATUS_RUNNING);
        assertThat(response.getWorkerId()).isEqualTo("worker-it-01");
    }

    @Test
    @DisplayName("CancelTask: accepts cancellation request and returns positive response")
    void testCancelTask() {
        String taskId = UUID.randomUUID().toString();

        ProtoTaskCancelRequest cancelRequest = ProtoTaskCancelRequest.newBuilder()
                .setTaskId(taskId)
                .setReason("Aborted by coordinator")
                .build();

        ProtoTaskCancelResponse response = blockingStub.cancelTask(cancelRequest);

        assertThat(response).isNotNull();
        assertThat(response.getTaskId()).isEqualTo(taskId);
    }
}
