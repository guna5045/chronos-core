package com.chronoscore.coordinator.cluster.grpc;

import com.chronoscore.common.proto.ProtoTaskAssignment;
import com.chronoscore.common.proto.ProtoTaskCancelRequest;
import com.chronoscore.common.proto.ProtoTaskCancelResponse;
import com.chronoscore.common.proto.ProtoTaskStateUpdate;
import com.chronoscore.common.proto.WorkerServiceGrpc;
import com.chronoscore.coordinator.cluster.model.WorkerNode;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Production gRPC implementation of {@link WorkerDispatchClient}.
 * <p>
 * Manages cached channels to target worker nodes, enforces call deadlines,
 * and handles communication errors gracefully.
 */
@Component
public class GrpcWorkerDispatchClient implements WorkerDispatchClient, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GrpcWorkerDispatchClient.class);

    private final ConcurrentHashMap<String, ManagedChannel> channelPool = new ConcurrentHashMap<>();

    @Override
    public ProtoTaskStateUpdate dispatchTask(WorkerNode targetWorker, ProtoTaskAssignment assignment, Duration deadline) {
        Objects.requireNonNull(targetWorker, "targetWorker must not be null");
        Objects.requireNonNull(assignment, "assignment must not be null");
        Duration effectiveDeadline = deadline != null ? deadline : Duration.ofSeconds(5);

        ManagedChannel channel = getOrCreateChannel(targetWorker);
        try {
            WorkerServiceGrpc.WorkerServiceBlockingStub stub = WorkerServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(effectiveDeadline.toMillis(), TimeUnit.MILLISECONDS);

            log.debug("Dispatching task '{}' to worker '{}' ({}:{})",
                    assignment.getTaskId(), targetWorker.workerId(), targetWorker.hostname(), targetWorker.port());

            return stub.dispatchTask(assignment);
        } catch (StatusRuntimeException e) {
            log.error("Failed to dispatch task '{}' to worker '{}' (status: {}): {}",
                    assignment.getTaskId(), targetWorker.workerId(), e.getStatus().getCode(), e.getMessage());
            throw e;
        }
    }

    @Override
    public ProtoTaskCancelResponse cancelTask(WorkerNode targetWorker, ProtoTaskCancelRequest request, Duration deadline) {
        Objects.requireNonNull(targetWorker, "targetWorker must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Duration effectiveDeadline = deadline != null ? deadline : Duration.ofSeconds(5);

        ManagedChannel channel = getOrCreateChannel(targetWorker);
        try {
            WorkerServiceGrpc.WorkerServiceBlockingStub stub = WorkerServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(effectiveDeadline.toMillis(), TimeUnit.MILLISECONDS);

            log.debug("Sending cancel for task '{}' to worker '{}'", request.getTaskId(), targetWorker.workerId());
            return stub.cancelTask(request);
        } catch (StatusRuntimeException e) {
            log.error("Failed to cancel task '{}' on worker '{}': {}",
                    request.getTaskId(), targetWorker.workerId(), e.getMessage());
            throw e;
        }
    }

    protected ManagedChannel getOrCreateChannel(WorkerNode worker) {
        String key = worker.hostname() + ":" + worker.port();
        return channelPool.computeIfAbsent(key, k -> {
            log.info("Creating new gRPC channel to worker '{}' at {}", worker.workerId(), k);
            return ManagedChannelBuilder.forAddress(worker.hostname(), worker.port())
                    .usePlaintext()
                    .build();
        });
    }

    @Override
    public void close() {
        log.info("Closing all pooled gRPC dispatch channels (count: {})", channelPool.size());
        channelPool.values().forEach(channel -> {
            try {
                channel.shutdown().awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
            }
        });
        channelPool.clear();
    }
}
