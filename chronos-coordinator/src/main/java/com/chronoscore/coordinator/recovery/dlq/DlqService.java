package com.chronoscore.coordinator.recovery.dlq;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.common.statemachine.TaskStateMachine;
import com.chronoscore.coordinator.persistence.exception.TaskNotFoundException;
import com.chronoscore.coordinator.persistence.model.OutboxEvent;
import com.chronoscore.coordinator.persistence.model.TaskAuditRecord;
import com.chronoscore.coordinator.persistence.repository.OutboxRepository;
import com.chronoscore.coordinator.persistence.repository.TaskAuditRepository;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import com.chronoscore.coordinator.recovery.lease.ExecutionLeaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing Dead-Letter Queue (DLQ) task inspection and idempotent replay operations.
 */
@Service
public class DlqService {

    private static final Logger log = LoggerFactory.getLogger(DlqService.class);

    private final TaskRepository taskRepository;
    private final TaskAuditRepository auditRepository;
    private final OutboxRepository outboxRepository;
    private final ExecutionLeaseService leaseService;

    public DlqService(TaskRepository taskRepository,
                      TaskAuditRepository auditRepository,
                      OutboxRepository outboxRepository,
                      ExecutionLeaseService leaseService) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository must not be null");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.leaseService = Objects.requireNonNull(leaseService, "leaseService must not be null");
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
        return persistedTask;
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
}
