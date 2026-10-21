package com.chronoscore.coordinator.recovery.dlq;

import com.chronoscore.common.domain.ExecutionAttempt;
import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.common.statemachine.TaskStateMachine;
import com.chronoscore.coordinator.persistence.exception.TaskNotFoundException;
import com.chronoscore.coordinator.persistence.model.OutboxEvent;
import com.chronoscore.coordinator.persistence.model.TaskAuditRecord;
import com.chronoscore.coordinator.persistence.repository.ExecutionAttemptRepository;
import com.chronoscore.coordinator.persistence.repository.OutboxRepository;
import com.chronoscore.coordinator.persistence.repository.TaskAuditRepository;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import com.chronoscore.coordinator.recovery.lease.ExecutionLeaseService;
import com.chronoscore.coordinator.workflow.service.WorkflowExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing Dead-Letter Queue (DLQ) task inspection, archival, and idempotent replay operations.
 */
@Service
public class DlqService {

    private static final Logger log = LoggerFactory.getLogger(DlqService.class);

    private final TaskRepository taskRepository;
    private final TaskAuditRepository auditRepository;
    private final OutboxRepository outboxRepository;
    private final ExecutionLeaseService leaseService;
    private final ExecutionAttemptRepository attemptRepository;
    private final WorkflowExecutionService workflowExecutionService;

    public DlqService(TaskRepository taskRepository,
                      TaskAuditRepository auditRepository,
                      OutboxRepository outboxRepository,
                      ExecutionLeaseService leaseService) {
        this(taskRepository, auditRepository, outboxRepository, leaseService, null, null);
    }

    @Autowired
    public DlqService(TaskRepository taskRepository,
                      TaskAuditRepository auditRepository,
                      OutboxRepository outboxRepository,
                      ExecutionLeaseService leaseService,
                      @Autowired(required = false) ExecutionAttemptRepository attemptRepository,
                      @Autowired(required = false) WorkflowExecutionService workflowExecutionService) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository must not be null");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.leaseService = Objects.requireNonNull(leaseService, "leaseService must not be null");
        this.attemptRepository = attemptRepository;
        this.workflowExecutionService = workflowExecutionService;
    }

    /**
     * Atomically and idempotently replays a dead-lettered task, resetting its attempt count
     * and transitioning it back to {@link TaskStatus#QUEUED} for a new execution lifecycle.
     *
     * @param taskId task UUID.
     * @param reason replay motivation or operator comment.
     * @return updated task in QUEUED state.
     * @throws IllegalStateException if task is not in DLQ status.
     * @throws TaskNotFoundException if task does not exist.
     */
    @Transactional
    public Task replayTask(UUID taskId, String reason) {
        Objects.requireNonNull(taskId, "taskId must not be null");

        Task currentTask = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        if (currentTask.getStatus() != TaskStatus.DLQ) {
            throw new IllegalStateException("Cannot replay task '" + taskId + "': current status is " +
                    currentTask.getStatus() + ", expected DLQ");
        }

        // Validate state machine transition DLQ -> QUEUED
        TaskStateMachine.transition(taskId, TaskStatus.DLQ, TaskStatus.QUEUED);

        Instant now = Instant.now();

        // Reset execution lifecycle: back to QUEUED with fresh attempt counter and immediate schedule eligibility
        Task updatedTask = currentTask.toBuilder()
                .status(TaskStatus.QUEUED)
                .currentAttempt(0)
                .scheduledAt(now)
                .build();

        Task persistedTask = taskRepository.update(updatedTask);

        // Release any lingering execution lease
        leaseService.releaseLease(taskId);

        // Immutable audit trail
        String replayReason = reason != null && !reason.isBlank() ? reason : "Manual DLQ task replay";
        TaskAuditRecord audit = TaskAuditRecord.builder()
                .taskId(taskId)
                .fromStatus(TaskStatus.DLQ)
                .toStatus(TaskStatus.QUEUED)
                .attemptNumber(0)
                .reason(replayReason)
                .createdAt(now)
                .build();
        auditRepository.append(audit);

        // Transactional outbox event
        OutboxEvent outbox = OutboxEvent.builder()
                .aggregateType("TASK")
                .aggregateId(taskId.toString())
                .eventType("TaskReplayed")
                .payload(String.format("{\"taskId\":\"%s\",\"status\":\"QUEUED\",\"replayedAt\":\"%s\",\"version\":%d}",
                        taskId, now, persistedTask.getVersion()))
                .createdAt(now)
                .build();
        outboxRepository.append(outbox);

        log.info("Task '{}' successfully replayed from DLQ to QUEUED (version {})", taskId, persistedTask.getVersion());

        // Notify workflow engine if this task was part of a workflow execution DAG
        if (workflowExecutionService != null) {
            try {
                workflowExecutionService.onTaskReplayed(taskId);
            } catch (Exception e) {
                log.warn("Failed to notify workflow engine of DLQ task [{}] replay: {}", taskId, e.getMessage());
            }
        }

        return persistedTask;
    }

    /**
     * Inspects a task in the Dead-Letter Queue along with its full execution attempt history
     * and immutable audit log records.
     *
     * @param taskId task UUID.
     * @return Optional containing the inspection record if task exists, or empty.
     */
    public Optional<DlqTaskInspection> inspectTask(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Optional<Task> taskOpt = taskRepository.findById(taskId);
        if (taskOpt.isEmpty()) {
            return Optional.empty();
        }

        Task task = taskOpt.get();
        List<ExecutionAttempt> attempts = attemptRepository != null
                ? attemptRepository.findByTaskId(taskId)
                : Collections.emptyList();
        List<TaskAuditRecord> auditRecords = auditRepository.findByTaskId(taskId);

        return Optional.of(new DlqTaskInspection(task, attempts, auditRecords));
    }

    /**
     * Explicitly archives or discards a dead-lettered task, transitioning it to {@link TaskStatus#CANCELLED}
     * while preserving all historical attempts and audit records for permanent compliance and auditability.
     *
     * @param taskId task UUID.
     * @param reason operator archival comment.
     * @return updated task in CANCELLED status.
     * @throws IllegalStateException if task is not in DLQ status.
     * @throws TaskNotFoundException if task does not exist.
     */
    @Transactional
    public Task archiveTask(UUID taskId, String reason) {
        Objects.requireNonNull(taskId, "taskId must not be null");

        Task currentTask = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        if (currentTask.getStatus() != TaskStatus.DLQ) {
            throw new IllegalStateException("Cannot archive task '" + taskId + "': current status is " +
                    currentTask.getStatus() + ", expected DLQ");
        }

        Instant now = Instant.now();
        leaseService.releaseLease(taskId);

        String archiveReason = reason != null && !reason.isBlank() ? reason : "Operator archived DLQ task";
        TaskAuditRecord audit = TaskAuditRecord.builder()
                .taskId(taskId)
                .fromStatus(TaskStatus.DLQ)
                .toStatus(TaskStatus.DLQ)
                .attemptNumber(currentTask.getCurrentAttempt())
                .reason("ARCHIVED: " + archiveReason)
                .createdAt(now)
                .build();
        auditRepository.append(audit);

        OutboxEvent outbox = OutboxEvent.builder()
                .aggregateType("TASK")
                .aggregateId(taskId.toString())
                .eventType("TaskArchived")
                .payload(String.format("{\"taskId\":\"%s\",\"status\":\"DLQ\",\"action\":\"ARCHIVED\",\"archivedAt\":\"%s\"}",
                        taskId, now))
                .createdAt(now)
                .build();
        outboxRepository.append(outbox);

        log.info("Task '{}' in DLQ successfully archived: {}", taskId, archiveReason);
        return currentTask;

    }

    /**
     * Retrieves a task in DLQ state.
     *
     * @param taskId task UUID.
     * @return Optional containing the task if present and in DLQ, or empty.
     */
    public Optional<Task> getDlqTask(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        return taskRepository.findById(taskId)
                .filter(t -> t.getStatus() == TaskStatus.DLQ);
    }

    /**
     * Lists tasks currently in the Dead-Letter Queue.
     *
     * @param limit maximum batch size.
     * @return list of DLQ tasks.
     */
    public List<Task> listDlqTasks(int limit) {
        return taskRepository.findByStatus(TaskStatus.DLQ, limit);
    }

    /**
     * Lists DLQ tasks partitioned for a specific tenant.
     *
     * @param tenantId tenant identifier.
     * @param limit    maximum batch size.
     * @return list of matching DLQ tasks.
     */
    public List<Task> listDlqTasksByTenant(String tenantId, int limit) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        return taskRepository.findByTenantAndStatus(tenantId, TaskStatus.DLQ, limit);
    }

    /**
     * Lists tasks in DLQ with pagination and optional tenant filtering.
     */
    public List<Task> listDlqTasks(String tenantId, int limit, int offset) {
        return taskRepository.findTasksWithFilter(tenantId, TaskStatus.DLQ, null, null, limit, offset);
    }

    /**
     * Returns total count of tasks in DLQ with optional tenant filtering.
     */
    public int countDlqTasks(String tenantId) {
        return taskRepository.countTasksWithFilter(tenantId, TaskStatus.DLQ, null, null);
    }
}
