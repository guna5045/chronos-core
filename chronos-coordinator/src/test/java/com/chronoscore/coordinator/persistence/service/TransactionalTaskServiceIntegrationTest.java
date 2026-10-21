package com.chronoscore.coordinator.persistence.service;

import com.chronoscore.common.domain.ExecutionAttempt;
import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.ExecutionAttemptStatus;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.common.exceptions.InvalidTaskTransitionException;
import com.chronoscore.coordinator.persistence.model.OutboxEvent;
import com.chronoscore.coordinator.persistence.model.TaskAuditRecord;
import com.chronoscore.coordinator.persistence.repository.ExecutionAttemptRepository;
import com.chronoscore.coordinator.persistence.repository.OutboxRepository;
import com.chronoscore.coordinator.persistence.repository.TaskAuditRepository;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class TransactionalTaskServiceIntegrationTest {

    @Autowired
    private TransactionalTaskService transactionalTaskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ExecutionAttemptRepository attemptRepository;

    @Autowired
    private TaskAuditRepository auditRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM chronos_outbox_events");
        jdbcTemplate.execute("DELETE FROM chronos_task_audit_log");
        jdbcTemplate.execute("DELETE FROM chronos_execution_attempts");
        jdbcTemplate.execute("DELETE FROM chronos_tasks");
    }

    private Task createNewTask(UUID taskId) {
        return Task.builder()
                .taskId(taskId != null ? taskId : UUID.randomUUID())
                .taskType("INGESTION")
                .payload("{\"source\":\"s3://bucket/data\"}")
                .priority(TaskPriority.HIGH)
                .status(TaskStatus.CREATED)
                .tenantId("tenant-finance")
                .createdAt(Instant.now())
                .maxAttempts(2)
                .currentAttempt(0)
                .version(0L)
                .build();
    }

    @Test
    @DisplayName("submitTask: atomically commits task, audit log, and outbox event")
    void testSubmitTaskAtomicCommit() {
        UUID taskId = UUID.randomUUID();
        Task task = createNewTask(taskId);

        Task submitted = transactionalTaskService.submitTask(task, "User API submission", "{\"source\":\"web\"}");
        assertThat(submitted).isNotNull();

        // 1. Task persisted
        Task fromDb = taskRepository.findById(taskId).orElseThrow();
        assertThat(fromDb.getStatus()).isEqualTo(TaskStatus.CREATED);

        // 2. Audit log appended
        List<TaskAuditRecord> auditRecords = auditRepository.findByTaskId(taskId);
        assertThat(auditRecords).hasSize(1);
        assertThat(auditRecords.get(0).getToStatus()).isEqualTo(TaskStatus.CREATED);
        assertThat(auditRecords.get(0).getReason()).isEqualTo("User API submission");

        // 3. Outbox event appended
        List<OutboxEvent> pendingEvents = outboxRepository.findPendingEvents(10);
        assertThat(pendingEvents).hasSize(1);
        assertThat(pendingEvents.get(0).getEventType()).isEqualTo("TaskSubmitted");
        assertThat(pendingEvents.get(0).getAggregateId()).isEqualTo(taskId.toString());
    }

    @Test
    @DisplayName("transitionTask: validates state machine, updates version, and appends audit + outbox")
    void testTransitionTaskAtomic() {
        UUID taskId = UUID.randomUUID();
        transactionalTaskService.submitTask(createNewTask(taskId), "Initial", null);

        // CREATED -> QUEUED
        Task transitioned = transactionalTaskService.transitionTask(taskId, TaskStatus.QUEUED, "Enqueued for worker", null);
        assertThat(transitioned.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(transitioned.getVersion()).isEqualTo(1L);

        // Verify audit trail has 2 records (CREATED + QUEUED)
        List<TaskAuditRecord> auditRecords = auditRepository.findByTaskId(taskId);
        assertThat(auditRecords).hasSize(2);
        assertThat(auditRecords.get(1).getFromStatus()).isEqualTo(TaskStatus.CREATED);
        assertThat(auditRecords.get(1).getToStatus()).isEqualTo(TaskStatus.QUEUED);

        // Outbox has 2 events (TaskSubmitted + TaskStatusChanged)
        List<OutboxEvent> pending = outboxRepository.findPendingEvents(10);
        assertThat(pending).hasSize(2);
    }

    @Test
    @DisplayName("transitionTask: invalid state transition rejected and transaction rolled back")
    void testInvalidTransitionRejected() {
        UUID taskId = UUID.randomUUID();
        transactionalTaskService.submitTask(createNewTask(taskId), "Initial", null);

        // CREATED cannot transition directly to SUCCEEDED
        assertThatThrownBy(() ->
                transactionalTaskService.transitionTask(taskId, TaskStatus.SUCCEEDED, "Premature completion", null))
                .isInstanceOf(InvalidTaskTransitionException.class);

        // Verify task status remained CREATED and no extra audit/outbox entries were added
        Task task = taskRepository.findById(taskId).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.CREATED);
        assertThat(auditRepository.findByTaskId(taskId)).hasSize(1);
    }

    @Test
    @DisplayName("transaction rollback: intentional failure rolls back task update, audit, and outbox")
    void testTransactionRollbackOnFailure() {
        UUID taskId = UUID.randomUUID();
        transactionalTaskService.submitTask(createNewTask(taskId), "Initial", null);

        // Clear outbox and audit to isolate count checks
        jdbcTemplate.execute("DELETE FROM chronos_outbox_events");
        jdbcTemplate.execute("DELETE FROM chronos_task_audit_log");

        // Execute a transaction where task is updated, but an intentional exception is thrown
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        TransactionStatus status = transactionManager.getTransaction(def);

        try {
            Task current = taskRepository.findById(taskId).orElseThrow();
            taskRepository.update(current.toBuilder().status(TaskStatus.QUEUED).build());
            auditRepository.append(TaskAuditRecord.builder()
                    .taskId(taskId)
                    .toStatus(TaskStatus.QUEUED)
                    .build());

            // Simulate unhandled failure before commit
            throw new RuntimeException("Simulated mid-transaction failure");
        } catch (Exception e) {
            transactionManager.rollback(status);
        }

        // Verify rollback: task is still CREATED at version 0, no audit, no outbox
        Task fromDb = taskRepository.findById(taskId).orElseThrow();
        assertThat(fromDb.getStatus()).isEqualTo(TaskStatus.CREATED);
        assertThat(fromDb.getVersion()).isEqualTo(0L);
        assertThat(auditRepository.findByTaskId(taskId)).isEmpty();
        assertThat(outboxRepository.findPendingEvents(10)).isEmpty();
    }

    @Test
    @DisplayName("startExecutionAttempt & completeExecutionAttempt: successful execution transitions to SUCCEEDED")
    void testExecutionAttemptSuccessLifecycle() {
        UUID taskId = UUID.randomUUID();
        transactionalTaskService.submitTask(createNewTask(taskId), "Init", null);
        transactionalTaskService.transitionTask(taskId, TaskStatus.QUEUED, "Queue", null);

        // Start attempt 1
        ExecutionAttempt attempt = transactionalTaskService.startExecutionAttempt(taskId, "worker-1", 1);
        assertThat(attempt.getStatus()).isEqualTo(ExecutionAttemptStatus.RUNNING);

        Task runningTask = taskRepository.findById(taskId).orElseThrow();
        assertThat(runningTask.getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(runningTask.getCurrentAttempt()).isEqualTo(1);

        // Complete attempt 1 with success
        ExecutionAttempt completed = transactionalTaskService.completeExecutionAttempt(
                attempt.getAttemptId(), true, null, null);
        assertThat(completed.getStatus()).isEqualTo(ExecutionAttemptStatus.SUCCEEDED);

        Task succeededTask = taskRepository.findById(taskId).orElseThrow();
        assertThat(succeededTask.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("completeExecutionAttempt: retry exhaustion moves task to DLQ")
    void testExecutionAttemptFailureDeadLetter() {
        UUID taskId = UUID.randomUUID();
        // Task configured with maxAttempts = 2
        transactionalTaskService.submitTask(createNewTask(taskId), "Init", null);
        transactionalTaskService.transitionTask(taskId, TaskStatus.QUEUED, "Queue", null);

        // Attempt 1 fails -> should be re-queued because attempt 1 < maxAttempts 2
        ExecutionAttempt attempt1 = transactionalTaskService.startExecutionAttempt(taskId, "worker-1", 1);
        transactionalTaskService.completeExecutionAttempt(attempt1.getAttemptId(), false, "ERR_TIMEOUT", "Connection timed out");
        Task retryingTask = taskRepository.findById(taskId).orElseThrow();
        assertThat(retryingTask.getStatus()).isEqualTo(TaskStatus.QUEUED);

        // Attempt 2 fails -> max attempts reached (2 of 2) -> DLQ
        ExecutionAttempt attempt2 = transactionalTaskService.startExecutionAttempt(taskId, "worker-2", 2);
        transactionalTaskService.completeExecutionAttempt(attempt2.getAttemptId(), false, "ERR_FATAL", "Permanent failure");
        Task dlqTask = taskRepository.findById(taskId).orElseThrow();
        assertThat(dlqTask.getStatus()).isEqualTo(TaskStatus.DLQ);
    }
}
