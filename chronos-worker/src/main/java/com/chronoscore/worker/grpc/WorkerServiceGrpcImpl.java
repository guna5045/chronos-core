package com.chronoscore.worker.grpc;

import com.chronoscore.common.proto.ProtoExecutionStatus;
import com.chronoscore.common.proto.ProtoTaskAssignment;
import com.chronoscore.common.proto.ProtoTaskCancelRequest;
import com.chronoscore.common.proto.ProtoTaskCancelResponse;
import com.chronoscore.common.proto.ProtoTaskResult;
import com.chronoscore.common.proto.ProtoTaskStateUpdate;
import com.chronoscore.common.proto.ProtoTaskStatus;
import com.chronoscore.common.proto.WorkerServiceGrpc;
import com.chronoscore.worker.config.WorkerProperties;
import com.chronoscore.worker.execution.CapacityExhaustedException;
import com.chronoscore.worker.execution.TaskExecutionResult;
import com.chronoscore.worker.execution.TaskExecutor;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Worker-side gRPC service handling task assignments and cancellation signals from the Coordinator.
 */
@Service
public class WorkerServiceGrpcImpl extends WorkerServiceGrpc.WorkerServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(WorkerServiceGrpcImpl.class);

    private final TaskExecutor taskExecutor;
    private final WorkerProperties workerProperties;
    private final CoordinatorClient coordinatorClient;

    public WorkerServiceGrpcImpl(TaskExecutor taskExecutor,
                                 WorkerProperties workerProperties,
                                 CoordinatorClient coordinatorClient) {
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor must not be null");
        this.workerProperties = Objects.requireNonNull(workerProperties, "workerProperties must not be null");
        this.coordinatorClient = Objects.requireNonNull(coordinatorClient, "coordinatorClient must not be null");
    }

    @Override
    public void dispatchTask(ProtoTaskAssignment assignment, StreamObserver<ProtoTaskStateUpdate> responseObserver) {
        String taskId = assignment.getTaskId();
        if (taskId == null || taskId.isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("task_id must not be blank")
                    .asRuntimeException());
            return;
        }

        if (!taskExecutor.hasAvailableCapacity()) {
            log.warn("Task '{}' rejected on worker '{}': capacity exhausted ({}/{})",
                    taskId, workerProperties.getId(), taskExecutor.getActiveTaskCount(), taskExecutor.getMaxConcurrency());
            responseObserver.onError(Status.RESOURCE_EXHAUSTED
                    .withDescription("Worker concurrency capacity saturated")
                    .asRuntimeException());
            return;
        }

        try {
            CompletableFuture<TaskExecutionResult> future = taskExecutor.executeTask(assignment);

            // Respond immediately with running state update
            ProtoTaskStateUpdate runningUpdate = ProtoTaskStateUpdate.newBuilder()
                    .setTaskId(taskId)
                    .setWorkerId(workerProperties.getId())
                    .setPreviousStatus(ProtoTaskStatus.TASK_STATUS_QUEUED)
                    .setNewStatus(ProtoTaskStatus.TASK_STATUS_RUNNING)
                    .setReason("Task accepted and running on virtual thread")
                    .setTimestamp(toProtoTimestamp(Instant.now()))
                    .build();

            responseObserver.onNext(runningUpdate);
            responseObserver.onCompleted();

            // When task execution concludes, report result back to Coordinator
            future.thenAccept(this::handleExecutionResult);
        } catch (CapacityExhaustedException e) {
            responseObserver.onError(Status.RESOURCE_EXHAUSTED
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (Exception e) {
            log.error("Failed to accept task '{}': {}", taskId, e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to submit task: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void cancelTask(ProtoTaskCancelRequest request, StreamObserver<ProtoTaskCancelResponse> responseObserver) {
        String taskId = request.getTaskId();
        String reason = request.getReason();

        boolean cancelled = taskExecutor.cancelTask(taskId, reason);
        log.info("Task '{}' cancellation result: {}", taskId, cancelled);

        ProtoTaskCancelResponse response = ProtoTaskCancelResponse.newBuilder()
                .setTaskId(taskId)
                .setAccepted(cancelled)
                .setMessage(cancelled ? "Cancellation signal accepted" : "Task not found in active execution")
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private void handleExecutionResult(TaskExecutionResult result) {
        try {
            ProtoTaskResult protoResult = ProtoTaskResult.newBuilder()
                    .setTaskId(result.taskId())
                    .setWorkerId(workerProperties.getId())
                    .setAttemptNumber(result.attemptNumber())
                    .setStatus(result.status())
                    .setOutputPayload(result.outputPayload())
                    .setErrorCode(result.errorCode())
                    .setErrorMessage(result.errorMessage())
                    .setCompletedAt(toProtoTimestamp(result.completedAt()))
                    .build();

            log.info("Reporting result for task '{}' (status: {}) to coordinator", result.taskId(), result.status());
            coordinatorClient.reportTaskResult(protoResult, Duration.ofSeconds(5));
        } catch (Exception ex) {
            log.error("Failed to report result for task '{}' to coordinator: {}",
                    result.taskId(), ex.getMessage(), ex);
        }
    }

    private static Timestamp toProtoTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
