package com.chronoscore.worker.grpc;

import com.chronoscore.common.proto.ProtoHeartbeatPing;
import com.chronoscore.common.proto.ProtoHeartbeatPong;
import com.chronoscore.common.proto.ProtoTaskResult;
import com.chronoscore.common.proto.ProtoTaskStateUpdate;
import com.chronoscore.common.proto.ProtoWorkerIdentity;
import com.chronoscore.common.proto.TaskCoordinationServiceGrpc;
import com.chronoscore.worker.config.WorkerProperties;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Production gRPC implementation of {@link CoordinatorClient}.
 */
@Component
public class GrpcCoordinatorClient implements CoordinatorClient {

    private static final Logger log = LoggerFactory.getLogger(GrpcCoordinatorClient.class);

    private final ManagedChannel channel;
    private final TaskCoordinationServiceGrpc.TaskCoordinationServiceBlockingStub blockingStub;
    private final TaskCoordinationServiceGrpc.TaskCoordinationServiceStub asyncStub;

    @org.springframework.beans.factory.annotation.Autowired
    public GrpcCoordinatorClient(WorkerProperties workerProperties) {
        Objects.requireNonNull(workerProperties, "workerProperties must not be null");
        String host = workerProperties.getCoordinatorHost();
        int port = workerProperties.getCoordinatorPort();

        log.info("Initializing Coordinator gRPC client connecting to {}:{}", host, port);
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.blockingStub = TaskCoordinationServiceGrpc.newBlockingStub(channel);
        this.asyncStub = TaskCoordinationServiceGrpc.newStub(channel);
    }

    // Constructor for testing with custom channel (e.g., InProcessChannel)
    public GrpcCoordinatorClient(ManagedChannel channel) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.blockingStub = TaskCoordinationServiceGrpc.newBlockingStub(channel);
        this.asyncStub = TaskCoordinationServiceGrpc.newStub(channel);
    }

    @Override
    public ProtoHeartbeatPong registerWorker(ProtoWorkerIdentity identity, Duration timeout) {
        Duration effectiveTimeout = timeout != null ? timeout : Duration.ofSeconds(5);
        return blockingStub.withDeadlineAfter(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .registerWorker(identity);
    }

    @Override
    public StreamObserver<ProtoHeartbeatPing> streamHeartbeat(StreamObserver<ProtoHeartbeatPong> responseObserver) {
        return asyncStub.streamHeartbeat(responseObserver);
    }

    @Override
    public ProtoHeartbeatPong reportTaskResult(ProtoTaskResult result, Duration timeout) {
        Duration effectiveTimeout = timeout != null ? timeout : Duration.ofSeconds(5);
        return blockingStub.withDeadlineAfter(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .reportTaskResult(result);
    }

    @Override
    public ProtoHeartbeatPong updateTaskState(ProtoTaskStateUpdate stateUpdate, Duration timeout) {
        Duration effectiveTimeout = timeout != null ? timeout : Duration.ofSeconds(5);
        return blockingStub.withDeadlineAfter(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .updateTaskState(stateUpdate);
    }

    @Override
    public void close() {
        if (channel != null && !channel.isShutdown()) {
            log.info("Closing gRPC CoordinatorClient channel");
            try {
                channel.shutdown().awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
            }
        }
    }
}
