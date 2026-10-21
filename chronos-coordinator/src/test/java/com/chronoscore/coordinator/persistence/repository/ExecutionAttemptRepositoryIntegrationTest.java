package com.chronoscore.coordinator.persistence.repository;

import com.chronoscore.common.domain.ExecutionAttempt;
import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.ExecutionAttemptStatus;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.coordinator.persistence.exception.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExecutionAttemptRepositoryIntegrationTest {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ExecutionAttemptRepository attemptRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID taskId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM chronos_outbox_events");
        jdbcTemplate.execute("DELETE FROM chronos_task_audit_log");
        jdbcTemplate.execute("DELETE FROM chronos_execution_attempts");
        jdbcTemplate.execute("DELETE FROM chronos_tasks");

        taskId = UUID.randomUUID();
        taskRepository.save(Task.builder()
                .taskId(taskId)
                .taskType("BATCH_JOB")
                .payload("{}")
                .priority(TaskPriority.NORMAL)
                .status(TaskStatus.RUNNING)
                .tenantId("tenant-attempts")
                .createdAt(Instant.now())
                .maxAttempts(3)
                .currentAttempt(1)
                .build());
    }

    private ExecutionAttempt createAttempt(UUID attemptId, int attemptNumber, String workerId) {
        return ExecutionAttempt.builder()
                .attemptId(attemptId != null ? attemptId : UUID.randomUUID())
                .taskId(taskId)
                .workerId(workerId != null ? workerId : "worker-node-1")
                .attemptNumber(attemptNumber)
                .status(ExecutionAttemptStatus.RUNNING)
                .startedAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("save and findById: persists and retrieves an execution attempt")
    void testSaveAndFindById() {
        UUID attemptId = UUID.randomUUID();
        ExecutionAttempt attempt = createAttempt(attemptId, 1, "worker-node-1");

        ExecutionAttempt saved = attemptRepository.save(attempt);
        assertThat(saved).isNotNull();

        Optional<ExecutionAttempt> retrieved = attemptRepository.findById(attemptId);
        assertThat(retrieved).isPresent();
        ExecutionAttempt a = retrieved.get();
        assertThat(a.getAttemptId()).isEqualTo(attemptId);
        assertThat(a.getTaskId()).isEqualTo(taskId);
        assertThat(a.getWorkerId()).isEqualTo("worker-node-1");
        assertThat(a.getAttemptNumber()).isEqualTo(1);
        assertThat(a.getStatus()).isEqualTo(ExecutionAttemptStatus.RUNNING);
        assertThat(a.getStartedAt()).isNotNull();
        assertThat(a.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("findByTaskId: preserves complete history of multiple attempts in ascending order")
    void testMultipleAttemptsRetained() {
        ExecutionAttempt attempt1 = createAttempt(UUID.randomUUID(), 1, "worker-1");
        ExecutionAttempt attempt2 = createAttempt(UUID.randomUUID(), 2, "worker-2");
        ExecutionAttempt attempt3 = createAttempt(UUID.randomUUID(), 3, "worker-3");

        attemptRepository.save(attempt1);
        attemptRepository.save(attempt2);
        attemptRepository.save(attempt3);

        List<ExecutionAttempt> attempts = attemptRepository.findByTaskId(taskId);
        assertThat(attempts).hasSize(3);
        assertThat(attempts.get(0).getAttemptNumber()).isEqualTo(1);
        assertThat(attempts.get(1).getAttemptNumber()).isEqualTo(2);
        assertThat(attempts.get(2).getAttemptNumber()).isEqualTo(3);
    }

    @Test
    @DisplayName("findLatestAttempt: retrieves the highest attempt number record")
    void testFindLatestAttempt() {
        attemptRepository.save(createAttempt(UUID.randomUUID(), 1, "worker-1"));
        UUID attempt2Id = UUID.randomUUID();
        attemptRepository.save(createAttempt(attempt2Id, 2, "worker-2"));

        Optional<ExecutionAttempt> latest = attemptRepository.findLatestAttempt(taskId);
        assertThat(latest).isPresent();
        assertThat(latest.get().getAttemptId()).isEqualTo(attempt2Id);
        assertThat(latest.get().getAttemptNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("findByTaskIdAndAttemptNumber: retrieves specific attempt")
    void testFindByTaskIdAndAttemptNumber() {
        UUID attemptId = UUID.randomUUID();
        attemptRepository.save(createAttempt(attemptId, 1, "worker-1"));

        Optional<ExecutionAttempt> found = attemptRepository.findByTaskIdAndAttemptNumber(taskId, 1);
        assertThat(found).isPresent();
        assertThat(found.get().getAttemptId()).isEqualTo(attemptId);

        Optional<ExecutionAttempt> notFound = attemptRepository.findByTaskIdAndAttemptNumber(taskId, 99);
        assertThat(notFound).isEmpty();
    }

    @Test
    @DisplayName("duplicate attempt protection: unique (task_id, attempt_number) rejects duplicates")
    void testDuplicateAttemptRejected() {
        attemptRepository.save(createAttempt(UUID.randomUUID(), 1, "worker-1"));

        ExecutionAttempt duplicateAttempt = createAttempt(UUID.randomUUID(), 1, "worker-2");
        assertThatThrownBy(() -> attemptRepository.save(duplicateAttempt))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("update: updates completion details without replacing history")
    void testUpdateAttempt() {
        UUID attemptId = UUID.randomUUID();
        ExecutionAttempt initial = attemptRepository.save(createAttempt(attemptId, 1, "worker-1"));

        Instant completedAt = Instant.now();
        ExecutionAttempt completed = initial.withSuccess(completedAt);
        attemptRepository.update(completed);

        ExecutionAttempt retrieved = attemptRepository.findById(attemptId).orElseThrow();
        assertThat(retrieved.getStatus()).isEqualTo(ExecutionAttemptStatus.SUCCEEDED);
        assertThat(retrieved.getCompletedAt()).isNotNull();
    }
}
