package com.chronoscore.coordinator.policy;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.coordinator.persistence.exception.DuplicateTaskException;
import com.chronoscore.coordinator.persistence.repository.ExecutionAttemptRepository;
import com.chronoscore.coordinator.persistence.repository.OutboxRepository;
import com.chronoscore.coordinator.persistence.repository.TaskAuditRepository;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import com.chronoscore.coordinator.persistence.service.TransactionalTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Test suite verifying idempotency hardening against concurrent duplicate submission races
 * (Phase 09 Section 26, 27, 28).
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyHardeningTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private ExecutionAttemptRepository attemptRepository;

    @Mock
    private TaskAuditRepository auditRepository;

    @Mock
    private OutboxRepository outboxRepository;

    private TransactionalTaskService taskService;

    @BeforeEach
    void setUp() {
        taskService = new TransactionalTaskService(taskRepository, attemptRepository, auditRepository, outboxRepository);
    }

    private Task createIdempotentTask(String tenantId, String idempotencyKey) {
        return Task.builder()
                .taskId(UUID.randomUUID())
                .tenantId(tenantId)
                .idempotencyKey(idempotencyKey)
                .taskType("INGESTION")
                .payload("{\"source\":\"s3://bucket/data.csv\"}")
                .priority(TaskPriority.HIGH)
                .status(TaskStatus.QUEUED)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("Idempotency: Sequential duplicate submission returns existing task without duplicate creation")
    void testSequentialDuplicateSubmission() {
        String tenantId = "tenant-idempotent";
        String key = "txn-req-12345";

        Task task1 = createIdempotentTask(tenantId, key);

        // When submitted first time, no existing task
        when(taskRepository.findByIdempotencyKey(tenantId, key))
                .thenReturn(Optional.empty()) // Before first save
                .thenReturn(Optional.of(task1)); // Subsequent lookups

        when(taskRepository.save(any())).thenReturn(task1);

        Task submitted1 = taskService.submitTask(task1, "First submission", null);
        assertThat(submitted1.getTaskId()).isEqualTo(task1.getTaskId());

        // Second submission with exact same tenantId + idempotencyKey
        Task task2 = createIdempotentTask(tenantId, key);
        Task submitted2 = taskService.submitTask(task2, "Duplicate submission", null);

        // Crucial invariant: resolves to existing task1!
        assertThat(submitted2.getTaskId()).isEqualTo(task1.getTaskId());
    }

    @Test
    @DisplayName("Idempotency Race: Thread A and Thread B submit concurrently with key X -> exactly one logical task")
    void testConcurrentSubmissionRaceResolvesToSameTask() throws Exception {
        String tenantId = "tenant-concurrent-race";
        String key = "idemp-key-xyz";

        Task taskA = createIdempotentTask(tenantId, key);
        Task taskB = createIdempotentTask(tenantId, key);

        AtomicBoolean firstSaved = new AtomicBoolean(false);

        // Simulate database behavior:
        // Before insert, findByIdempotencyKey returns empty (both queries arrive before either commits)
        when(taskRepository.findByIdempotencyKey(tenantId, key)).thenAnswer(inv -> {
            if (firstSaved.get()) {
                return Optional.of(taskA);
            }
            return Optional.empty();
        });

        // First thread to save succeeds; second thread hits DB unique constraint (DuplicateTaskException)
        when(taskRepository.save(any())).thenAnswer(inv -> {
            if (firstSaved.compareAndSet(false, true)) {
                return taskA;
            } else {
                throw new DuplicateTaskException(tenantId, key);
            }
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);

        Future<Task> futureA = executor.submit(() -> {
            startGate.await();
            return taskService.submitTask(taskA, "Thread A submit", null);
        });

        Future<Task> futureB = executor.submit(() -> {
            startGate.await();
            return taskService.submitTask(taskB, "Thread B submit", null);
        });

        startGate.countDown();

        Task resultA = futureA.get(5, TimeUnit.SECONDS);
        Task resultB = futureB.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Crucial invariant: Both threads resolve to the EXACT SAME logical task!
        assertThat(resultA.getTaskId()).isEqualTo(taskA.getTaskId());
        assertThat(resultB.getTaskId()).isEqualTo(taskA.getTaskId());
    }
}
