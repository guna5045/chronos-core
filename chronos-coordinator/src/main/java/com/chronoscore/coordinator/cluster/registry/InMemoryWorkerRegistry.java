package com.chronoscore.coordinator.cluster.registry;

import com.chronoscore.coordinator.cluster.model.WorkerNode;
import com.chronoscore.coordinator.cluster.model.WorkerState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * High-performance, concurrent in-memory implementation of {@link WorkerRegistry}.
 * <p>
 * Ensures connection session safety: heartbeats and status transitions originating
 * from older/stale connections cannot overwrite state maintained by newer sessions.
 */
@Component
public class InMemoryWorkerRegistry implements WorkerRegistry {

    private static final Logger log = LoggerFactory.getLogger(InMemoryWorkerRegistry.class);

    private final ConcurrentHashMap<String, WorkerNode> workers = new ConcurrentHashMap<>();

    @Override
    public WorkerNode registerWorker(WorkerNode workerNode) {
        Objects.requireNonNull(workerNode, "workerNode must not be null");

        AtomicReference<WorkerNode> registeredRef = new AtomicReference<>();
        workers.compute(workerNode.workerId(), (id, existing) -> {
            if (existing != null) {
                log.info("Re-registering worker '{}' (replacing session '{}' with '{}')",
                        id, existing.sessionId(), workerNode.sessionId());
            } else {
                log.info("Registered new worker '{}' at {}:{} (session: '{}')",
                        id, workerNode.hostname(), workerNode.port(), workerNode.sessionId());
            }
            registeredRef.set(workerNode);
            return workerNode;
        });

        return registeredRef.get();
    }

    @Override
    public Optional<WorkerNode> getWorker(String workerId) {
        if (workerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(workers.get(workerId));
    }

    @Override
    public List<WorkerNode> getAllWorkers() {
        return Collections.unmodifiableList(new ArrayList<>(workers.values()));
    }

    @Override
    public List<WorkerNode> getActiveWorkers() {
        return workers.values().stream()
                .filter(w -> w.state() == WorkerState.ACTIVE)
                .toList();
    }

    @Override
    public List<WorkerNode> getWorkersByState(WorkerState state) {
        Objects.requireNonNull(state, "state must not be null");
        return workers.values().stream()
                .filter(w -> w.state() == state)
                .toList();
    }

    @Override
    public boolean recordHeartbeat(String workerId, String sessionId, int activeTasks, double cpu, double memory) {
        if (workerId == null || sessionId == null) {
            return false;
        }

        AtomicReference<Boolean> resultRef = new AtomicReference<>(false);
        workers.computeIfPresent(workerId, (id, current) -> {
            // Guard against stale connections
            if (!current.sessionId().equals(sessionId)) {
                log.warn("Rejected heartbeat for worker '{}': stale session '{}' (current active session: '{}')",
                        workerId, sessionId, current.sessionId());
                resultRef.set(false);
                return current;
            }

            if (current.state() == WorkerState.REMOVED) {
                log.warn("Rejected heartbeat for evicted/removed worker '{}'", workerId);
                resultRef.set(false);
                return current;
            }

            if (current.state() == WorkerState.SUSPECTED) {
                log.info("Worker '{}' recovered from SUSPECTED to ACTIVE via valid heartbeat", workerId);
            }

            WorkerNode updated = current.withHeartbeat(activeTasks, cpu, memory, Instant.now());
            resultRef.set(true);
            return updated;
        });

        return resultRef.get();
    }

    @Override
    public boolean updateState(String workerId, String sessionId, WorkerState newState) {
        if (workerId == null || sessionId == null || newState == null) {
            return false;
        }

        AtomicReference<Boolean> resultRef = new AtomicReference<>(false);
        workers.computeIfPresent(workerId, (id, current) -> {
            if (!current.sessionId().equals(sessionId)) {
                log.debug("State update rejected for worker '{}': stale session '{}'", workerId, sessionId);
                resultRef.set(false);
                return current;
            }

            if (current.state() != newState) {
                log.info("Worker '{}' state transitioned: {} -> {}", workerId, current.state(), newState);
            }

            resultRef.set(true);
            return current.withState(newState);
        });

        return resultRef.get();
    }

    @Override
    public Optional<WorkerNode> removeWorker(String workerId, String sessionId) {
        if (workerId == null || sessionId == null) {
            return Optional.empty();
        }

        AtomicReference<WorkerNode> removedRef = new AtomicReference<>();
        workers.computeIfPresent(workerId, (id, current) -> {
            if (!current.sessionId().equals(sessionId)) {
                log.warn("Removal rejected for worker '{}': stale session '{}'", workerId, sessionId);
                return current;
            }

            WorkerNode evicted = current.withState(WorkerState.REMOVED);
            removedRef.set(evicted);
            log.info("Worker '{}' marked REMOVED from active cluster", workerId);
            return evicted;
        });

        return Optional.ofNullable(removedRef.get());
    }

    @Override
    public int getActiveWorkerCount() {
        return (int) workers.values().stream()
                .filter(w -> w.state() == WorkerState.ACTIVE)
                .count();
    }

    @Override
    public void clear() {
        workers.clear();
    }
}
