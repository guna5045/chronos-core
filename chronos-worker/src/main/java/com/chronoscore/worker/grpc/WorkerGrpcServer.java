package com.chronoscore.worker.grpc;

import com.chronoscore.worker.config.WorkerProperties;
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
 * Spring-managed Netty gRPC server hosting the Worker node service.
 */
@Component
public class WorkerGrpcServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerGrpcServer.class);

    private final WorkerServiceGrpcImpl workerService;
    private final WorkerProperties workerProperties;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Server server;
    private int boundPort;

    public WorkerGrpcServer(WorkerServiceGrpcImpl workerService, WorkerProperties workerProperties) {
        this.workerService = Objects.requireNonNull(workerService, "workerService must not be null");
        this.workerProperties = Objects.requireNonNull(workerProperties, "workerProperties must not be null");
    }

    @Override
    public void start() {
        if (!workerProperties.isEnabled()) {
            log.info("Worker gRPC server disabled by configuration");
            return;
        }

        int port = workerProperties.getPort();
        if (port < 0) {
            log.info("Worker gRPC server port configured to {}, skipping startup", port);
            return;
        }

        if (running.compareAndSet(false, true)) {
            try {
                this.server = ServerBuilder.forPort(port)
                        .addService(workerService)
                        .executor(Executors.newVirtualThreadPerTaskExecutor())
                        .build()
                        .start();

                this.boundPort = server.getPort();
                log.info("Worker '{}' gRPC server started on port {} (virtual threads enabled)",
                        workerProperties.getId(), boundPort);
            } catch (IOException e) {
                running.set(false);
                log.error("Failed to start Worker gRPC server on port {}: {}", port, e.getMessage(), e);
                throw new IllegalStateException("Failed to start Worker gRPC server on port " + port, e);
            }
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping Worker gRPC server on port {}", boundPort);
            if (server != null) {
                server.shutdown();
                try {
                    if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                        log.warn("Worker gRPC server did not terminate in 5s, forcing shutdown");
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
        return Integer.MAX_VALUE - 80;
    }
}
