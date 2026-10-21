package com.chronoscore.worker.runtime;

import com.chronoscore.common.proto.ProtoHeartbeatPing;
import com.chronoscore.common.proto.ProtoHeartbeatPong;
import com.chronoscore.common.proto.ProtoWorkerIdentity;
import com.chronoscore.worker.config.WorkerProperties;
import com.chronoscore.worker.execution.TaskExecutor;
import com.chronoscore.worker.grpc.CoordinatorClient;
import com.chronoscore.worker.grpc.WorkerGrpcServer;
import com.google.protobuf.Timestamp;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core runtime orchestrator for the Chronos Worker node.
 * <p>
 * Manages worker registration, continuous bidirectional heartbeat streaming,
 * re-registration on coordinator failover, and graceful lifecycle termination.
 */
@Component
public class WorkerRuntime implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerRuntime.class);

    private final WorkerProperties workerProperties;
    private final CoordinatorClient coordinatorClient;
    private final TaskExecutor taskExecutor;
    private final WorkerGrpcServer workerGrpcServer;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> activeSessionId = new AtomicReference<>("");
    private final AtomicLong currentLeaderEpoch = new AtomicLong(0);

    private ScheduledExecutorService heartbeatScheduler;
    private StreamObserver<ProtoHeartbeatPing> requestStream;

    public WorkerRuntime(WorkerProperties workerProperties,
                         CoordinatorClient coordinatorClient,
                         TaskExecutor taskExecutor,
                         WorkerGrpcServer workerGrpcServer) {
        this.workerProperties = Objects.requireNonNull(workerProperties, "workerProperties must not be null");
        this.coordinatorClient = Objects.requireNonNull(coordinatorClient, "coordinatorClient must not be null");
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor must not be null");
        this.workerGrpcServer = Objects.requireNonNull(workerGrpcServer, "workerGrpcServer must not be null");
    }

    @Override
    public void start() {
        if (!workerProperties.isEnabled()) {
            log.info("Worker runtime disabled by configuration");
            return;
        }

        if (running.compareAndSet(false, true)) {
            log.info("Starting WorkerRuntime for worker '{}'", workerProperties.getId());

            // 1. Register with coordinator
            boolean registered = performRegistration();
            if (!registered) {
                log.warn("Initial registration failed for worker '{}', will retry during heartbeat loop", workerProperties.getId());
            }

            // 2. Start periodic heartbeat loop
            startHeartbeatLoop();
        }
    }

    public synchronized boolean performRegistration() {
        try {
            int port = workerGrpcServer.getBoundPort() > 0 ? workerGrpcServer.getBoundPort() : workerProperties.getPort();

            ProtoWorkerIdentity identity = ProtoWorkerIdentity.newBuilder()
                    .setWorkerId(workerProperties.getId())
                    .setHostname(workerProperties.getHostname())
                    .setIpAddress(workerProperties.getIpAddress())
                    .setPort(port)
                    .setMaxConcurrentTasks(workerProperties.getMaxConcurrency())
                    .setActiveTasks(taskExecutor.getActiveTaskCount())
                    .setVersion(workerProperties.getVersion())
                    .setRegisteredAt(toProtoTimestamp(Instant.now()))
                    .addAllSupportedTaskTypes(workerProperties.getSupportedTaskTypes())
                    .setProcessId(ProcessHandle.current().pid())
                    .build();

            log.info("Attempting registration with coordinator at {}:{}...",
                    workerProperties.getCoordinatorHost(), workerProperties.getCoordinatorPort());

            ProtoHeartbeatPong response = coordinatorClient.registerWorker(identity, workerProperties.getCallTimeout());

            if (response.getAcknowledged()) {
                activeSessionId.set(response.getSessionId());
                currentLeaderEpoch.set(response.getEpochFencingToken());
                log.info("Worker '{}' registered successfully with coordinator '{}' (session: '{}', epoch: {})",
                        workerProperties.getId(), response.getCoordinatorId(), response.getSessionId(), response.getEpochFencingToken());

                // Reset stream after registration
                openHeartbeatStream();
                return true;
            } else {
                log.warn("Worker registration not acknowledged by coordinator: {}", response.getErrorMessage());
                return false;
            }
        } catch (Exception ex) {
            log.warn("Failed to register worker '{}' with coordinator: {}", workerProperties.getId(), ex.getMessage());
            return false;
        }
    }

    private synchronized void openHeartbeatStream() {
        try {
            this.requestStream = coordinatorClient.streamHeartbeat(new StreamObserver<>() {
                @Override
                public void onNext(ProtoHeartbeatPong pong) {
                    if (pong.getAcknowledged()) {
                        currentLeaderEpoch.set(pong.getEpochFencingToken());
                        log.trace("Heartbeat acknowledged by coordinator '{}' (epoch: {})",
                                pong.getCoordinatorId(), pong.getEpochFencingToken());
                    } else {
                        log.warn("Coordinator rejected heartbeat: {}. Re-registering worker...", pong.getErrorMessage());
                        performRegistration();
                    }
                }

                @Override
                public void onError(Throwable t) {
                    log.warn("Heartbeat stream error: {}. Will re-establish stream.", t.getMessage());
                    requestStream = null;
                }

                @Override
                public void onCompleted() {
                    log.info("Heartbeat stream completed by coordinator.");
                    requestStream = null;
                }
            });
        } catch (Exception ex) {
            log.warn("Unable to establish heartbeat stream: {}", ex.getMessage());
            this.requestStream = null;
        }
    }

    private void startHeartbeatLoop() {
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chronos-worker-heartbeat-" + workerProperties.getId());
            t.setDaemon(true);
            return t;
        });

        long intervalMs = Math.max(200, workerProperties.getHeartbeat().getInterval().toMillis());
        this.heartbeatScheduler.scheduleWithFixedDelay(
                this::sendHeartbeatTick,
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS
        );
    }

    public synchronized void sendHeartbeatTick() {
        if (!running.get()) {
            return;
        }

        String sessionId = activeSessionId.get();
        if (sessionId == null || sessionId.isBlank()) {
            log.debug("No active session; attempting registration before sending heartbeat");
            performRegistration();
            return;
        }

        if (requestStream == null) {
            openHeartbeatStream();
            if (requestStream == null) {
                return;
            }
        }

        try {
            int active = taskExecutor.getActiveTaskCount();
            int max = taskExecutor.getMaxConcurrency();

            ProtoHeartbeatPing ping = ProtoHeartbeatPing.newBuilder()
                    .setWorkerId(workerProperties.getId())
                    .setSessionId(sessionId)
                    .setActiveTasks(active)
                    .setAvailableCapacity(Math.max(0, max - active))
                    .setTimestamp(toProtoTimestamp(Instant.now()))
                    .build();

            requestStream.onNext(ping);
        } catch (Exception ex) {
            log.warn("Failed to send heartbeat ping: {}", ex.getMessage());
            requestStream = null;
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping WorkerRuntime for worker '{}'...", workerProperties.getId());

            if (heartbeatScheduler != null) {
                heartbeatScheduler.shutdownNow();
            }

            if (requestStream != null) {
                try {
                    requestStream.onCompleted();
                } catch (Exception ignored) {}
            }

            taskExecutor.shutdown();
            coordinatorClient.close();
            log.info("WorkerRuntime stopped cleanly.");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    public String getActiveSessionId() {
        return activeSessionId.get();
    }

    public long getCurrentLeaderEpoch() {
        return currentLeaderEpoch.get();
    }

    @Override
    public int getPhase() {
        // Start after WorkerGrpcServer
        return Integer.MAX_VALUE - 60;
    }

    private static Timestamp toProtoTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
