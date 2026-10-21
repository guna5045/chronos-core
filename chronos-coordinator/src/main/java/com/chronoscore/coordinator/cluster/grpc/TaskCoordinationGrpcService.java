package com.chronoscore.coordinator.cluster.grpc;

import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.common.proto.ProtoExecutionStatus;
import com.chronoscore.common.proto.ProtoHeartbeatPing;
import com.chronoscore.common.proto.ProtoHeartbeatPong;
import com.chronoscore.common.proto.ProtoTaskResult;
import com.chronoscore.common.proto.ProtoTaskStateUpdate;
import com.chronoscore.common.proto.ProtoWorkerIdentity;
import com.chronoscore.common.proto.TaskCoordinationServiceGrpc;
import com.chronoscore.coordinator.cluster.model.WorkerNode;
import com.chronoscore.coordinator.cluster.model.WorkerState;
import com.chronoscore.coordinator.cluster.registry.WorkerRegistry;
import com.chronoscore.coordinator.coordination.CoordinationProperties;
import com.chronoscore.coordinator.coordination.fencing.FencingTokenService;
import com.chronoscore.coordinator.persistence.service.TransactionalTaskService;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Coordinator-side implementation of {@link TaskCoordinationServiceGrpc}.
 * <p>
 * Handles worker registration, bidirectional streaming heartbeats, task state updates,
 * and execution result submissions.
 */
@Service
public class TaskCoordinationGrpcService extends TaskCoordinationServiceGrpc.TaskCoordinationServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(TaskCoordinationGrpcService.class);

    private final WorkerRegistry workerRegistry;
    private final FencingTokenService fencingTokenService;
    private final CoordinationProperties coordinationProperties;
    private final TransactionalTaskService transactionalTaskService;
    private final com.chronoscore.coordinator.recovery.TaskRecoveryService taskRecoveryService;

    public TaskCoordinationGrpcService(WorkerRegistry workerRegistry,
                                       FencingTokenService fencingTokenService,
                                       CoordinationProperties coordinationProperties,
                                       TransactionalTaskService transactionalTaskService) {
        this(workerRegistry, fencingTokenService, coordinationProperties, transactionalTaskService, null);
    }

    @Autowired
    public TaskCoordinationGrpcService(WorkerRegistry workerRegistry,
                                       FencingTokenService fencingTokenService,
                                       CoordinationProperties coordinationProperties,
                                       @Autowired(required = false) TransactionalTaskService transactionalTaskService,
                                       @Autowired(required = false) com.chronoscore.coordinator.recovery.TaskRecoveryService taskRecoveryService) {
        this.workerRegistry = Objects.requireNonNull(workerRegistry, "workerRegistry must not be null");
        this.fencingTokenService = Objects.requireNonNull(fencingTokenService, "fencingTokenService must not be null");
        this.coordinationProperties = Objects.requireNonNull(coordinationProperties, "coordinationProperties must not be null");
        this.transactionalTaskService = transactionalTaskService;
        this.taskRecoveryService = taskRecoveryService;
    }

    @Override
    public void registerWorker(ProtoWorkerIdentity request, StreamObserver<ProtoHeartbeatPong> responseObserver) {
        String workerId = request.getWorkerId();
        if (workerId == null || workerId.isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("worker_id must not be blank")
                    .asRuntimeException());
            return;
        }

        int maxConcurrency = request.getMaxConcurrentTasks() > 0 ? request.getMaxConcurrentTasks() : 8;
        String sessionId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        Set<String> supportedTypes = new HashSet<>(request.getSupportedTaskTypesList());

        WorkerNode workerNode = new WorkerNode(
                workerId,
                request.getHostname().isBlank() ? "localhost" : request.getHostname(),
                request.getIpAddress(),
                request.getPort(),
                maxConcurrency,
                request.getActiveTasks(),
                supportedTypes,
                request.getProcessId(),
                request.getVersion(),
                sessionId,
                WorkerState.ACTIVE,
                now,
                now,
                0.0,
                0.0
        );

        workerRegistry.registerWorker(workerNode);

        long currentEpoch = getCurrentEpochSafely();

        ProtoHeartbeatPong pong = ProtoHeartbeatPong.newBuilder()
                .setCoordinatorId(coordinationProperties.getCoordinatorId())
                .setEpochFencingToken(currentEpoch)
                .setTimestamp(toProtoTimestamp(now))
                .setAcknowledged(true)
                .setSessionId(sessionId)
                .build();

        log.info("Worker '{}' successfully registered (session: '{}', epoch: {})", workerId, sessionId, currentEpoch);

        responseObserver.onNext(pong);
        responseObserver.onCompleted();
    }

    @Override
    public StreamObserver<ProtoHeartbeatPing> streamHeartbeat(StreamObserver<ProtoHeartbeatPong> responseObserver) {
        return new StreamObserver<>() {
            @Override
            public void onNext(ProtoHeartbeatPing ping) {
                String workerId = ping.getWorkerId();
                String sessionId = ping.getSessionId();
                Instant now = Instant.now();

                boolean recorded = workerRegistry.recordHeartbeat(
                        workerId,
                        sessionId,
                        ping.getActiveTasks(),
                        ping.getCpuUsagePercent(),
                        ping.getMemoryUsagePercent()
                );

                long currentEpoch = getCurrentEpochSafely();

                ProtoHeartbeatPong.Builder pongBuilder = ProtoHeartbeatPong.newBuilder()
                        .setCoordinatorId(coordinationProperties.getCoordinatorId())
                        .setEpochFencingToken(currentEpoch)
                        .setTimestamp(toProtoTimestamp(now))
                        .setSessionId(sessionId);

                if (recorded) {
                    pongBuilder.setAcknowledged(true);
                } else {
                    pongBuilder.setAcknowledged(false)
                            .setErrorMessage("Heartbeat rejected: worker unregistered, evicted, or session mismatch");
                    log.warn("Rejected heartbeat ping from worker '{}' (session: '{}')", workerId, sessionId);
                }

                responseObserver.onNext(pongBuilder.build());
            }

            @Override
            public void onError(Throwable t) {
                log.warn("Worker heartbeat stream terminated with error: {}", t.getMessage());
            }

            @Override
            public void onCompleted() {
                log.debug("Worker heartbeat stream completed by client");
                responseObserver.onCompleted();
            }
        };
    }

    @Override
    public void updateTaskState(ProtoTaskStateUpdate request, StreamObserver<ProtoHeartbeatPong> responseObserver) {
        String taskIdStr = request.getTaskId();
        String workerId = request.getWorkerId();
        Instant now = Instant.now();

        try {
            UUID taskId = UUID.fromString(taskIdStr);
            TaskStatus domainStatus = mapProtoStatusToDomain(request.getNewStatus());

            if (transactionalTaskService != null && domainStatus != null) {
                transactionalTaskService.transitionTask(
                        taskId,
                        domainStatus,
                        request.getReason().isBlank() ? "Worker state update" : request.getReason(),
                        "workerId=" + workerId
                );
            }

            ProtoHeartbeatPong pong = ProtoHeartbeatPong.newBuilder()
                    .setCoordinatorId(coordinationProperties.getCoordinatorId())
                    .setEpochFencingToken(getCurrentEpochSafely())
                    .setTimestamp(toProtoTimestamp(now))
                    .setAcknowledged(true)
                    .build();

            responseObserver.onNext(pong);
            responseObserver.onCompleted();
        } catch (Exception ex) {
            log.error("Failed to process updateTaskState for task '{}' from worker '{}': {}",
                    taskIdStr, workerId, ex.getMessage(), ex);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to persist task state update: " + ex.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void reportTaskResult(ProtoTaskResult request, StreamObserver<ProtoHeartbeatPong> responseObserver) {
        String taskIdStr = request.getTaskId();
        String workerId = request.getWorkerId();
        boolean success = request.getStatus() == ProtoExecutionStatus.EXECUTION_STATUS_SUCCEEDED;
        Instant now = Instant.now();

        try {
            UUID taskId = UUID.fromString(taskIdStr);

            if (taskRecoveryService != null) {
                taskRecoveryService.handleTaskResult(request);
            } else if (transactionalTaskService != null) {
                transactionalTaskService.transitionTask(
                        taskId,
                        success ? TaskStatus.SUCCEEDED : TaskStatus.FAILED,
                        success ? "Attempt completed successfully" : "Attempt execution failed",
                        "errorCode=" + request.getErrorCode() + ";errorMessage=" + request.getErrorMessage()
                );
            }

            ProtoHeartbeatPong pong = ProtoHeartbeatPong.newBuilder()
                    .setCoordinatorId(coordinationProperties.getCoordinatorId())
                    .setEpochFencingToken(getCurrentEpochSafely())
                    .setTimestamp(toProtoTimestamp(now))
                    .setAcknowledged(true)
                    .build();

            responseObserver.onNext(pong);
            responseObserver.onCompleted();
        } catch (Exception ex) {
            log.error("Failed to process reportTaskResult for task '{}' from worker '{}': {}",
                    taskIdStr, workerId, ex.getMessage(), ex);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to process task result: " + ex.getMessage())
                    .asRuntimeException());
        }
    }

    private long getCurrentEpochSafely() {
        try {
            return fencingTokenService != null ? fencingTokenService.getCurrentFencingToken().getEpoch() : 0L;
        } catch (Exception ex) {
            return 0L;
        }
    }

    private static Timestamp toProtoTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static TaskStatus mapProtoStatusToDomain(com.chronoscore.common.proto.ProtoTaskStatus protoStatus) {
        if (protoStatus == null) return null;
        return switch (protoStatus) {
            case TASK_STATUS_CREATED -> TaskStatus.CREATED;
            case TASK_STATUS_QUEUED -> TaskStatus.QUEUED;
            case TASK_STATUS_DISPATCHED -> TaskStatus.DISPATCHED;
            case TASK_STATUS_RUNNING -> TaskStatus.RUNNING;
            case TASK_STATUS_SUCCEEDED -> TaskStatus.SUCCEEDED;
            case TASK_STATUS_FAILED -> TaskStatus.FAILED;
            case TASK_STATUS_CANCELLED -> TaskStatus.CANCELLED;
            case TASK_STATUS_DLQ -> TaskStatus.DLQ;
            default -> null;
        };
    }
}
