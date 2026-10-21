package com.chronoscore.coordinator.persistence.repository;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.coordinator.persistence.exception.DuplicateTaskException;
import com.chronoscore.coordinator.persistence.exception.OptimisticLockingException;
import com.chronoscore.coordinator.persistence.exception.TaskNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TaskRepositoryIntegrationTest {

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

    private Task createSampleTask(UUID taskId, String tenantId, String idempotencyKey) {
        return Task.builder()
                .taskId(taskId != null ? taskId : UUID.randomUUID())
                .taskType("PAYMENT_PROCESSING")
                .payload("{\"orderId\":\"12345\",\"amount\":99.95}")
                .priority(TaskPriority.HIGH)
                .status(TaskStatus.CREATED)
                .tenantId(tenantId != null ? tenantId : "tenant-alpha")
                .idempotencyKey(idempotencyKey)
                .createdAt(Instant.now())
                .timeout(Duration.ofSeconds(60))
                .maxAttempts(3)
                .currentAttempt(0)
                .version(0L)
                .build();
    }

    @Test
    @DisplayName("save and findById: persists task and retrieves it with identical attributes")
    void testSaveAndFindById() {
        UUID taskId = UUID.randomUUID();
        Task task = createSampleTask(taskId, "tenant-a", "idem-001");

        Task saved = taskRepository.save(task);
        assertThat(saved).isNotNull();

        Optional<Task> retrieved = taskRepository.findById(taskId);
        assertThat(retrieved).isPresent();
        Task t = retrieved.get();
        assertThat(t.getTaskId()).isEqualTo(taskId);
        assertThat(t.getTaskType()).isEqualTo("PAYMENT_PROCESSING");
        assertThat(t.getPayload()).contains("orderId");
        assertThat(t.getPriority()).isEqualTo(TaskPriority.HIGH);
        assertThat(t.getStatus()).isEqualTo(TaskStatus.CREATED);
        assertThat(t.getTenantId()).isEqualTo("tenant-a");
        assertThat(t.getIdempotencyKey()).isEqualTo("idem-001");
        assertThat(t.getTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(t.getMaxAttempts()).isEqualTo(3);
        assertThat(t.getCurrentAttempt()).isEqualTo(0);
        assertThat(t.getVersion()).isEqualTo(0L);
    }

    @Test
    @DisplayName("findById: returns empty when task does not exist")
    void testFindByIdNotFound() {
        Optional<Task> result = taskRepository.findById(UUID.randomUUID());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByIdForUpdate: acquires row lock and retrieves task")
    void testFindByIdForUpdate() {
        UUID taskId = UUID.randomUUID();
        taskRepository.save(createSampleTask(taskId, "tenant-a", null));

        Optional<Task> locked = taskRepository.findByIdForUpdate(taskId);
        assertThat(locked).isPresent();
        assertThat(locked.get().getTaskId()).isEqualTo(taskId);
    }

    @Test
    @DisplayName("update: increments version and updates task state")
    void testUpdateSuccess() {
        UUID taskId = UUID.randomUUID();
        Task initial = taskRepository.save(createSampleTask(taskId, "tenant-a", null));

        Task toUpdate = initial.toBuilder()
                .status(TaskStatus.QUEUED)
                .currentAttempt(1)
                .build();

        Task updated = taskRepository.update(toUpdate);
        assertThat(updated.getVersion()).isEqualTo(initial.getVersion() + 1);

        Task fromDb = taskRepository.findById(taskId).orElseThrow();
        assertThat(fromDb.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(fromDb.getCurrentAttempt()).isEqualTo(1);
        assertThat(fromDb.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("update: stale update rejected with OptimisticLockingException")
    void testUpdateOptimisticLockingConflict() {
        UUID taskId = UUID.randomUUID();
        Task initial = taskRepository.save(createSampleTask(taskId, "tenant-a", null));

        // First update advances version to 1
        Task updatedOnce = taskRepository.update(initial.toBuilder().status(TaskStatus.QUEUED).build());
        assertThat(updatedOnce.getVersion()).isEqualTo(1L);

        // Attempt second update using old version 0
        Task staleTask = initial.toBuilder().status(TaskStatus.RUNNING).build();

        assertThatThrownBy(() -> taskRepository.update(staleTask))
                .isInstanceOf(OptimisticLockingException.class)
                .hasMessageContaining(taskId.toString());
    }

    @Test
    @DisplayName("update: non-existent task throws TaskNotFoundException")
    void testUpdateNonExistentTask() {
        Task nonExistent = createSampleTask(UUID.randomUUID(), "tenant-a", null);

        assertThatThrownBy(() -> taskRepository.update(nonExistent))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    @DisplayName("idempotency: findByIdempotencyKey retrieves matching task")
    void testFindByIdempotencyKey() {
        UUID taskId = UUID.randomUUID();
        taskRepository.save(createSampleTask(taskId, "tenant-x", "idem-unique-123"));

        Optional<Task> found = taskRepository.findByIdempotencyKey("tenant-x", "idem-unique-123");
        assertThat(found).isPresent();
        assertThat(found.get().getTaskId()).isEqualTo(taskId);

        Optional<Task> wrongTenant = taskRepository.findByIdempotencyKey("tenant-y", "idem-unique-123");
        assertThat(wrongTenant).isEmpty();
    }

    @Test
    @DisplayName("idempotency: duplicate (tenant_id, idempotency_key) rejected with DuplicateTaskException")
    void testDuplicateIdempotencyKeyRejected() {
        taskRepository.save(createSampleTask(UUID.randomUUID(), "tenant-alpha", "idem-conflict"));

        Task duplicate = createSampleTask(UUID.randomUUID(), "tenant-alpha", "idem-conflict");
        assertThatThrownBy(() -> taskRepository.save(duplicate))
                .isInstanceOf(DuplicateTaskException.class);
    }

    @Test
    @DisplayName("idempotency: multiple tasks with null idempotency key for same tenant are permitted")
    void testNullableIdempotencyKeyAllowed() {
        Task task1 = taskRepository.save(createSampleTask(UUID.randomUUID(), "tenant-beta", null));
        Task task2 = taskRepository.save(createSampleTask(UUID.randomUUID(), "tenant-beta", null));

        assertThat(task1).isNotNull();
        assertThat(task2).isNotNull();
        assertThat(taskRepository.existsById(task1.getTaskId())).isTrue();
        assertThat(taskRepository.existsById(task2.getTaskId())).isTrue();
    }

    @Test
    @DisplayName("existsById: returns true when present, false otherwise")
    void testExistsById() {
        UUID taskId = UUID.randomUUID();
        assertThat(taskRepository.existsById(taskId)).isFalse();

        taskRepository.save(createSampleTask(taskId, "tenant-a", null));
        assertThat(taskRepository.existsById(taskId)).isTrue();
    }

    @Test
    @DisplayName("findQueuedTasks: retrieves queued tasks ordered by priority and scheduling time")
    void testFindQueuedTasks() {
        Task taskLow = taskRepository.save(createSampleTask(UUID.randomUUID(), "t1", null).toBuilder()
                .status(TaskStatus.QUEUED)
                .priority(TaskPriority.LOW)
                .createdAt(Instant.now().minusSeconds(10))
                .build());

        Task taskCritical = taskRepository.save(createSampleTask(UUID.randomUUID(), "t1", null).toBuilder()
                .status(TaskStatus.QUEUED)
                .priority(TaskPriority.CRITICAL)
                .createdAt(Instant.now())
                .build());

        Task taskHigh = taskRepository.save(createSampleTask(UUID.randomUUID(), "t1", null).toBuilder()
                .status(TaskStatus.QUEUED)
                .priority(TaskPriority.HIGH)
                .createdAt(Instant.now().minusSeconds(5))
                .build());

        // Non-queued task should not be retrieved
        taskRepository.save(createSampleTask(UUID.randomUUID(), "t1", null).toBuilder()
                .status(TaskStatus.RUNNING)
                .priority(TaskPriority.CRITICAL)
                .build());

        List<Task> queued = taskRepository.findQueuedTasks(10);
        assertThat(queued).hasSize(3);
        // Ordering should prioritize CRITICAL -> HIGH -> LOW
        assertThat(queued.get(0).getTaskId()).isEqualTo(taskCritical.getTaskId());
        assertThat(queued.get(1).getTaskId()).isEqualTo(taskHigh.getTaskId());
        assertThat(queued.get(2).getTaskId()).isEqualTo(taskLow.getTaskId());
    }

    @Test
    @DisplayName("findSchedulableTasks: excludes tasks with future scheduledAt timestamps")
    void testFindSchedulableTasksFiltersFutureTasks() {
        Instant now = Instant.now();

        Task readyTask1 = taskRepository.save(createSampleTask(UUID.randomUUID(), "t1", null).toBuilder()
                .status(TaskStatus.QUEUED)
                .priority(TaskPriority.HIGH)
                .scheduledAt(now.minusSeconds(10))
                .build());

        Task readyTask2 = taskRepository.save(createSampleTask(UUID.randomUUID(), "t1", null).toBuilder()
                .status(TaskStatus.QUEUED)
                .priority(TaskPriority.NORMAL)
                .scheduledAt(null)
                .build());

        Task futureTask = taskRepository.save(createSampleTask(UUID.randomUUID(), "t1", null).toBuilder()
                .status(TaskStatus.QUEUED)
                .priority(TaskPriority.CRITICAL) // Even CRITICAL must not be scheduled early
                .scheduledAt(now.plusSeconds(300))
                .build());

        List<Task> schedulable = taskRepository.findSchedulableTasks(now, 10);

        assertThat(schedulable).hasSize(2);
        assertThat(schedulable).extracting(Task::getTaskId)
                .containsExactly(readyTask1.getTaskId(), readyTask2.getTaskId())
                .doesNotContain(futureTask.getTaskId());
    }

    @Test
    @DisplayName("findStarvedTasks: identifies queued tasks waiting longer than maxWaitTime")
    void testFindStarvedTasks() {
        Instant now = Instant.now();

        Task starvedTask = taskRepository.save(createSampleTask(UUID.randomUUID(), "t1", null).toBuilder()
                .status(TaskStatus.QUEUED)
                .priority(TaskPriority.LOW)
                .createdAt(now.minusSeconds(120))
                .build());

        Task freshTask = taskRepository.save(createSampleTask(UUID.randomUUID(), "t1", null).toBuilder()
                .status(TaskStatus.QUEUED)
                .priority(TaskPriority.HIGH)
                .createdAt(now.minusSeconds(5))
                .build());

        List<Task> starved = taskRepository.findStarvedTasks(now, Duration.ofSeconds(60), 10);

        assertThat(starved).hasSize(1);
        assertThat(starved.get(0).getTaskId()).isEqualTo(starvedTask.getTaskId());
    }
}
