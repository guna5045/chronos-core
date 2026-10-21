package com.chronoscore.coordinator.cluster.grpc;

import com.chronoscore.coordinator.cluster.config.ClusterProperties;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spring-managed Netty gRPC server hosting the Coordinator's cluster communication services.
 * <p>
 * Uses Java 21 virtual threads for concurrent RPC handler dispatch.
 */
@Component
public class CoordinatorGrpcServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorGrpcServer.class);

    private final TaskCoordinationGrpcService taskCoordinationService;
    private final ClusterProperties clusterProperties;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Server server;
    private int boundPort;

    public CoordinatorGrpcServer(TaskCoordinationGrpcService taskCoordinationService,
                                 ClusterProperties clusterProperties) {
        this.taskCoordinationService = Objects.requireNonNull(taskCoordinationService, "taskCoordinationService must not be null");
        this.clusterProperties = Objects.requireNonNull(clusterProperties, "clusterProperties must not be null");
    }

    @Override
    public void start() {
        if (!clusterProperties.getGrpc().isEnabled()) {
            log.info("Coordinator gRPC server disabled by configuration");
            return;
        }

        int port = clusterProperties.getGrpc().getPort();
        if (port < 0) {
            log.info("Coordinator gRPC server port configured to {}, skipping startup", port);
            return;
        }

        if (running.compareAndSet(false, true)) {
            try {
                this.server = ServerBuilder.forPort(port)
                        .addService(taskCoordinationService)
                        .executor(Executors.newVirtualThreadPerTaskExecutor())
                        .build()
                        .start();

                this.boundPort = server.getPort();
                log.info("Coordinator gRPC server started on port {} (virtual threads enabled)", boundPort);
            } catch (IOException e) {
                running.set(false);
                log.error("Failed to start Coordinator gRPC server on port {}: {}", port, e.getMessage(), e);
                throw new IllegalStateException("Failed to start Coordinator gRPC server on port " + port, e);
            }
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping Coordinator gRPC server on port {}", boundPort);
            if (server != null) {
                server.shutdown();
                try {
                    if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                        log.warn("Coordinator gRPC server did not terminate within 5s, forcing shutdown");
                        server.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    server.shutdownNow();
                }
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    public int getBoundPort() {
        return boundPort;
    }

    @Override
    public int getPhase() {
        // Start after DB and coordination services, stop before them
        return Integer.MAX_VALUE - 80;
    }
}
