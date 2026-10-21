package com.chronoscore.coordinator.persistence.concurrency;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.coordinator.persistence.exception.OptimisticLockingException;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Concurrency test verifying that the database optimistic locking mechanism
 * prevents stale-write lost updates when multiple transactions read the same
 * task version concurrently.
 */
@SpringBootTest
@ActiveProfiles("test")
class OptimisticConcurrencyIntegrationTest {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM chronos_outbox_events");
        jdbcTemplate.execute("DELETE FROM chronos_task_audit_log");
        jdbcTemplate.execute("DELETE FROM chronos_execution_attempts");
        jdbcTemplate.execute("DELETE FROM chronos_tasks");
    }

    @Test
    @DisplayName("Optimistic Concurrency: Transaction B fails with OptimisticLockingException when Transaction A commits first")
    void testOptimisticConcurrencyConflict() {
        UUID taskId = UUID.randomUUID();

        // 1. Initialize task with version = 5
        Task initialTask = Task.builder()
                .taskId(taskId)
                .taskType("CONCURRENCY_TEST")
                .payload("{\"test\":true}")
                .priority(TaskPriority.HIGH)
                .status(TaskStatus.QUEUED)
                .tenantId("tenant-concurrency")
                .createdAt(Instant.now())
                .maxAttempts(5)
                .currentAttempt(1)
                .version(5L)
                .build();
        taskRepository.save(initialTask);

        // 2. Transaction A reads version 5
        Task txARead = taskRepository.findById(taskId).orElseThrow();
        assertThat(txARead.getVersion()).isEqualTo(5L);

        // 3. Transaction B reads version 5 concurrently
        Task txBRead = taskRepository.findById(taskId).orElseThrow();
        assertThat(txBRead.getVersion()).isEqualTo(5L);

        // 4. Transaction A updates state -> successfully increments version from 5 to 6
        Task txAUpdated = taskRepository.update(txARead.toBuilder()
                .status(TaskStatus.RUNNING)
                .build());
        assertThat(txAUpdated.getVersion()).isEqualTo(6L);

        // Verify database row is now at version 6 with status RUNNING
        Task dbAfterTxA = taskRepository.findById(taskId).orElseThrow();
        assertThat(dbAfterTxA.getVersion()).isEqualTo(6L);
        assertThat(dbAfterTxA.getStatus()).isEqualTo(TaskStatus.RUNNING);

        // 5. Transaction B attempts update using stale version 5 -> MUST fail with OptimisticLockingException
        Task txBStaleUpdate = txBRead.toBuilder()
                .status(TaskStatus.CANCELLED)
                .build();

        assertThatThrownBy(() -> taskRepository.update(txBStaleUpdate))
                .isInstanceOf(OptimisticLockingException.class)
                .hasMessageContaining(taskId.toString());

        // 6. Verify that database state was NOT overwritten by Transaction B
        Task finalDbTask = taskRepository.findById(taskId).orElseThrow();
        assertThat(finalDbTask.getVersion()).isEqualTo(6L);
        assertThat(finalDbTask.getStatus()).isEqualTo(TaskStatus.RUNNING); // Still RUNNING from Transaction A, not CANCELLED
    }

    @Test
    @DisplayName("Threaded Concurrency: exactly one concurrent updater succeeds when contending on same version")
    void testThreadedConcurrentRace() throws InterruptedException {
        UUID taskId = UUID.randomUUID();
        taskRepository.save(Task.builder()
                .taskId(taskId)
                .taskType("RACE_TEST")
                .payload("{}")
                .priority(TaskPriority.NORMAL)
                .status(TaskStatus.QUEUED)
                .tenantId("tenant-race")
                .createdAt(Instant.now())
                .version(1L)
                .build());

        Task baseline = taskRepository.findById(taskId).orElseThrow();

        AtomicBoolean thread1Success = new AtomicBoolean(false);
        AtomicBoolean thread2Success = new AtomicBoolean(false);
        AtomicReference<Exception> thread1Exception = new AtomicReference<>();
        AtomicReference<Exception> thread2Exception = new AtomicReference<>();

        Thread t1 = new Thread(() -> {
            try {
                taskRepository.update(baseline.toBuilder().status(TaskStatus.RUNNING).build());
                thread1Success.set(true);
            } catch (Exception e) {
                thread1Exception.set(e);
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                taskRepository.update(baseline.toBuilder().status(TaskStatus.CANCELLED).build());
                thread2Success.set(true);
            } catch (Exception e) {
                thread2Exception.set(e);
            }
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // Exactly one thread must succeed, and the other must fail with OptimisticLockingException
        assertThat(thread1Success.get() ^ thread2Success.get())
                .as("Exactly one of the concurrent updates must succeed")
                .isTrue();

        if (thread1Success.get()) {
            assertThat(thread2Exception.get()).isInstanceOf(OptimisticLockingException.class);
        } else {
            assertThat(thread1Exception.get()).isInstanceOf(OptimisticLockingException.class);
        }

        Task finalTask = taskRepository.findById(taskId).orElseThrow();
        assertThat(finalTask.getVersion()).isEqualTo(2L);
    }
}
