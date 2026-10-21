package com.chronoscore.worker.execution;

import com.chronoscore.common.proto.ProtoTaskAssignment;
import com.chronoscore.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Java 21 Virtual Thread-backed task executor for Chronos Worker nodes.
 * <p>
 * Manages concurrent task isolation, capacity limits, and execution tracking.
 */
@Component
public class VirtualThreadTaskExecutor implements TaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(VirtualThreadTaskExecutor.class);

    private final int maxConcurrency;
    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private final ConcurrentHashMap<String, RunningContext> runningTasks = new ConcurrentHashMap<>();
    private final ExecutorService virtualThreadExecutor;

    @org.springframework.beans.factory.annotation.Autowired
    public VirtualThreadTaskExecutor(WorkerProperties workerProperties) {
        Objects.requireNonNull(workerProperties, "workerProperties must not be null");
        this.maxConcurrency = workerProperties.getMaxConcurrency();
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    // Direct constructor for isolated testing
    public VirtualThreadTaskExecutor(int maxConcurrency) {
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("maxConcurrency must be positive");
        }
        this.maxConcurrency = maxConcurrency;
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public CompletableFuture<TaskExecutionResult> executeTask(ProtoTaskAssignment assignment) {
        Objects.requireNonNull(assignment, "assignment must not be null");
        String taskId = assignment.getTaskId();
        int attemptNumber = assignment.getAttemptNumber();

        // Enforce concurrency capacity limit
        int previous = activeTasks.getAndUpdate(count -> {
            if (count >= maxConcurrency) {
                return count; // Do not increment if saturated
            }
            return count + 1;
        });

        if (previous >= maxConcurrency) {
            log.warn("Task '{}' rejected: capacity saturated ({}/{})", taskId, previous, maxConcurrency);
            throw new CapacityExhaustedException("Worker capacity exhausted: " + previous + "/" + maxConcurrency);
        }

        CompletableFuture<TaskExecutionResult> future = new CompletableFuture<>();
        RunningContext context = new RunningContext(Thread.currentThread(), future, new AtomicBoolean(false));
        runningTasks.put(taskId, context);

        virtualThreadExecutor.submit(() -> {
            context.thread = Thread.currentThread();
            Instant startedAt = Instant.now();
            log.info("Started virtual thread execution for task '{}' (attempt #{}, active: {}/{})",
                    taskId, attemptNumber, activeTasks.get(), maxConcurrency);

            try {
                // Check if pre-cancelled
                if (context.cancelled.get()) {
                    TaskExecutionResult result = TaskExecutionResult.cancelled(
                            taskId, attemptNumber, "Cancelled before start", startedAt, Instant.now());
                    future.complete(result);
                    return;
                }

                // Execute task payload logic
                String payload = assignment.hasTask() ? assignment.getTask().getPayload() : "";
                TaskExecutionResult result = executePayload(taskId, attemptNumber, payload, startedAt, context);
                future.complete(result);
                log.info("Task '{}' execution concluded: {}", taskId, result.status());
            } catch (Throwable t) {
                log.error("Task '{}' failed with unexpected error: {}", taskId, t.getMessage(), t);
                TaskExecutionResult result = TaskExecutionResult.failure(
                        taskId, attemptNumber, "UNEXPECTED_ERROR", t.getMessage(), startedAt, Instant.now());
                future.complete(result);
            } finally {
                activeTasks.decrementAndGet();
                runningTasks.remove(taskId);
            }
        });

        return future;
    }

    private TaskExecutionResult executePayload(String taskId,
                                              int attemptNumber,
                                              String payload,
                                              Instant startedAt,
                                              RunningContext context) {
        // Controlled task simulation support:
        if (payload != null && payload.contains("\"fail\":true")) {
            return TaskExecutionResult.failure(
                    taskId,
                    attemptNumber,
                    "TASK_INTENTIONAL_FAILURE",
                    "Task execution payload triggered simulation failure",
                    startedAt,
                    Instant.now()
            );
        }

        if (payload != null && payload.contains("\"sleep\":")) {
            try {
                long sleepMs = 1000;
                int idx = payload.indexOf("\"sleep\":");
                String rest = payload.substring(idx + 8).replaceAll("[^0-9]", " ").trim();
                String[] tokens = rest.split("\\s+");
                if (tokens.length > 0 && !tokens[0].isEmpty()) {
                    sleepMs = Long.parseLong(tokens[0]);
                }
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return TaskExecutionResult.cancelled(
                        taskId,
                        attemptNumber,
                        "Task interrupted during execution",
                        startedAt,
                        Instant.now()
                );
            }
        }

        if (context.cancelled.get()) {
            return TaskExecutionResult.cancelled(
                    taskId,
                    attemptNumber,
                    "Task cancelled during execution",
                    startedAt,
                    Instant.now()
            );
        }

        String output = "{\"status\":\"COMPLETED\",\"processedBy\":\"VirtualThreadTaskExecutor\"}";
        return TaskExecutionResult.success(taskId, attemptNumber, output, startedAt, Instant.now());
    }

    @Override
    public boolean cancelTask(String taskId, String reason) {
        if (taskId == null) return false;

        RunningContext context = runningTasks.get(taskId);
        if (context != null) {
            context.cancelled.set(true);
            if (context.thread != null) {
                context.thread.interrupt();
            }
            log.info("Cancellation signalled for task '{}': {}", taskId, reason);
            return true;
        }
        return false;
    }

    @Override
    public int getActiveTaskCount() {
        return activeTasks.get();
    }

    @Override
    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    @Override
    public boolean hasAvailableCapacity() {
        return activeTasks.get() < maxConcurrency;
    }

    @Override
    public void shutdown() {
        log.info("Shutting down VirtualThreadTaskExecutor (active tasks: {})", activeTasks.get());
        virtualThreadExecutor.shutdown();
        try {
            if (!virtualThreadExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                virtualThreadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            virtualThreadExecutor.shutdownNow();
        }
    }

    private static class RunningContext {
        volatile Thread thread;
        final CompletableFuture<TaskExecutionResult> future;
        final AtomicBoolean cancelled;

        RunningContext(Thread thread, CompletableFuture<TaskExecutionResult> future, AtomicBoolean cancelled) {
            this.thread = thread;
            this.future = future;
            this.cancelled = cancelled;
        }
    }
}
