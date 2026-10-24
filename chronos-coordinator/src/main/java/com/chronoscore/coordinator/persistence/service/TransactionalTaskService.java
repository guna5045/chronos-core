package com.chronoscore.coordinator.persistence.service;

import com.chronoscore.common.domain.ExecutionAttempt;
import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.ExecutionAttemptStatus;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.common.statemachine.TaskStateMachine;
import com.chronoscore.coordinator.persistence.exception.PersistenceException;
import com.chronoscore.coordinator.persistence.exception.TaskNotFoundException;
import com.chronoscore.coordinator.persistence.model.OutboxEvent;
import com.chronoscore.coordinator.persistence.model.TaskAuditRecord;
import com.chronoscore.coordinator.persistence.repository.ExecutionAttemptRepository;
import com.chronoscore.coordinator.persistence.repository.OutboxRepository;
import com.chronoscore.coordinator.persistence.repository.TaskAuditRepository;
import com.chronoscore.common.domain.AdmissionDecision;
import com.chronoscore.common.enums.AdmissionStatus;
import com.chronoscore.common.exceptions.InvalidTaskTransitionException;
import com.chronoscore.common.exceptions.TenantQueueLimitExceededException;
import com.chronoscore.common.exceptions.TenantRateLimitExceededException;
import com.chronoscore.coordinator.persistence.exception.DuplicateTaskException;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import com.chronoscore.coordinator.policy.TaskAdmissionController;
import com.chronoscore.coordinator.policy.TenantConcurrencyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing transactional persistence boundaries for tasks, execution attempts,
 * audit trails, and transactional outbox events.
 * <p>
 * Architectural Guarantees:
 * <ul>
 *   <li><b>Atomic Commit Boundary</b>: Task state mutations, audit log records, and outbox events
 *       commit or roll back together within a single ACID transaction.</li>
 *   <li><b>State Machine Integrity</b>: Transitions are strictly validated against {@link TaskStateMachine}
 *       before touching durable storage.</li>
 *   <li><b>Optimistic Concurrency Protection</b>: Database updates verify expected row versions,
 *       throwing {@link com.chronoscore.coordinator.persistence.exception.OptimisticLockingException} on stale writes.</li>
 * </ul>
 */
@Service
public class TransactionalTaskService {

    private static final Logger log = LoggerFactory.getLogger(TransactionalTaskService.class);

    private final TaskRepository taskRepository;
    private final ExecutionAttemptRepository attemptRepository;
    private final TaskAuditRepository auditRepository;
    private final OutboxRepository outboxRepository;

    @Autowired(required = false)
    private TaskAdmissionController admissionController;

    @Autowired(required = false)
    private TenantConcurrencyManager concurrencyManager;

    public TransactionalTaskService(TaskRepository taskRepository,
                                    ExecutionAttemptRepository attemptRepository,
                                    TaskAuditRepository auditRepository,
                                    OutboxRepository outboxRepository) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository must not be null");
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository must not be null");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
    }

    public void setAdmissionController(TaskAdmissionController admissionController) {
        this.admissionController = admissionController;
    }

    public void setConcurrencyManager(TenantConcurrencyManager concurrencyManager) {
        this.concurrencyManager = concurrencyManager;
    }

    /**
     * Atomically submits a new task, writing the initial task entity, audit record,
     * and transactional outbox event.
     *
     * @param task     task entity to submit.
     * @param reason   submission reason or source description.
     * @param metadata optional structured metadata.
     * @return persisted task.
     */
    @Transactional
    public Task submitTask(Task task, String reason, String metadata) {
        Objects.requireNonNull(task, "task must not be null");
        log.debug("Submitting task {} for tenant {}", task.getTaskId(), task.getTenantId());

        // Admission control evaluation at submission boundary
        if (admissionController != null) {
            AdmissionDecision decision = admissionController.evaluateSubmission(task);
            if (!decision.isAdmitted()) {
                if (decision.status() == AdmissionStatus.TENANT_QUEUE_LIMIT) {
                    throw new TenantQueueLimitExceededException(task.getTenantId(), decision.reason());
                }
                if (decision.status() == AdmissionStatus.RATE_LIMITED) {
                    throw new TenantRateLimitExceededException(task.getTenantId(), decision.reason());
                }
                throw new IllegalStateException("Task admission rejected: " + decision.reason());
            }
        }

        // Idempotency resolution before insert
        if (task.getIdempotencyKey() != null && !task.getIdempotencyKey().isBlank()) {
            Optional<Task> existingOpt = taskRepository.findByIdempotencyKey(task.getTenantId(), task.getIdempotencyKey());
            if (existingOpt.isPresent()) {
                log.info("Idempotent task submission detected for tenant '{}', key '{}': returning existing task '{}'",
                        task.getTenantId(), task.getIdempotencyKey(), existingOpt.get().getTaskId());
                return existingOpt.get();
            }
        }

        Task savedTask;
        try {
            savedTask = taskRepository.save(task);
        } catch (DuplicateTaskException ex) {
            if (task.getIdempotencyKey() != null) {
                Optional<Task> existingOpt = taskRepository.findByIdempotencyKey(task.getTenantId(), task.getIdempotencyKey());
                if (existingOpt.isPresent()) {
                    log.info("Concurrent idempotent submission detected for tenant '{}', key '{}': returning existing task '{}'",
                            task.getTenantId(), task.getIdempotencyKey(), existingOpt.get().getTaskId());
                    return existingOpt.get();
                }
            }
            throw ex;
        }

        // Append initial audit record
        TaskAuditRecord auditRecord = TaskAuditRecord.builder()
                .taskId(savedTask.getTaskId())
                .fromStatus(null)
                .toStatus(savedTask.getStatus())
                .attemptNumber(savedTask.getCurrentAttempt())
                .reason(reason != null ? reason : "Initial task submission")
                .metadata(metadata)
                .createdAt(Instant.now())
                .build();
        auditRepository.append(auditRecord);

        // Append transactional outbox event
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateType("TASK")
                .aggregateId(savedTask.getTaskId().toString())
                .eventType("TaskSubmitted")
                .payload(String.format("{\"taskId\":\"%s\",\"status\":\"%s\",\"tenantId\":\"%s\"}",
                        savedTask.getTaskId(), savedTask.getStatus(), savedTask.getTenantId()))
                .createdAt(Instant.now())
                .build();
        outboxRepository.append(outboxEvent);

        return savedTask;
    }

    /**
     * Atomically transitions a task to a new lifecycle status with optimistic locking,
     * validation against {@link TaskStateMachine}, audit recording, and outbox event staging.
     *
     * @param taskId       task UUID.
     * @param targetStatus requested next status.
     * @param reason       transition rationale.
     * @param metadata     optional contextual metadata.
     * @return updated task with incremented version.
     */
    @Transactional
    public Task transitionTask(UUID taskId, TaskStatus targetStatus, String reason, String metadata) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(targetStatus, "targetStatus must not be null");

        Task currentTask = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        TaskStatus fromStatus = currentTask.getStatus();

        // 1. Validate requested state transition against pure domain state machine
        TaskStateMachine.transition(taskId, fromStatus, targetStatus);

        // 2. Prepare mutated task entity (version check performed by repository update)
        Task updatedTaskState = currentTask.toBuilder()
                .status(targetStatus)
                .build();

        // 3. Persist update using version protection
        Task persistedTask = taskRepository.update(updatedTaskState);

        // 4. Create immutable audit log record
        TaskAuditRecord auditRecord = TaskAuditRecord.builder()
                .taskId(taskId)
                .fromStatus(fromStatus)
                .toStatus(targetStatus)
                .attemptNumber(persistedTask.getCurrentAttempt())
                .reason(reason != null ? reason : "Task state transition")
                .metadata(metadata)
                .createdAt(Instant.now())
                .build();
        auditRepository.append(auditRecord);

        // 5. Create transactional outbox event
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateType("TASK")
                .aggregateId(taskId.toString())
                .eventType("TaskStatusChanged")
                .payload(String.format("{\"taskId\":\"%s\",\"fromStatus\":\"%s\",\"toStatus\":\"%s\",\"version\":%d}",
                        taskId, fromStatus, targetStatus, persistedTask.getVersion()))
                .createdAt(Instant.now())
                .build();
        outboxRepository.append(outboxEvent);

        log.debug("Transitioned task {} from {} to {} (version {})", taskId, fromStatus, targetStatus, persistedTask.getVersion());
        return persistedTask;
    }

    /**
     * Determines the next physical attempt number for a task, ensuring strictly monotonic
     * ordering even if the task was previously replayed from the Dead-Letter Queue.
     */
    public int getNextAttemptNumber(UUID taskId, int baseAttempt) {
        int next = baseAttempt + 1;
        if (attemptRepository != null && taskId != null) {
            Optional<ExecutionAttempt> latest = attemptRepository.findLatestAttempt(taskId);
            if (latest.isPresent() && latest.get().getAttemptNumber() >= next) {
                return latest.get().getAttemptNumber() + 1;
            }
        }
        return next;
    }

    /**
     * Atomically records the start of an execution attempt, transitioning the task to RUNNING
     * if applicable, and saving the execution attempt history row.
     *
     * @param taskId        task UUID.
     * @param workerId      executing worker node identifier.
     * @param attemptNumber physical attempt number (1-based).
     * @return persisted ExecutionAttempt.
     */
    @Transactional
    public ExecutionAttempt startExecutionAttempt(UUID taskId, String workerId, int attemptNumber) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(workerId, "workerId must not be null");

        Task currentTask = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        // If task is already cancelled, worker cannot start running!
        if (currentTask.getStatus() == TaskStatus.CANCELLED) {
            throw new InvalidTaskTransitionException(taskId, TaskStatus.CANCELLED, TaskStatus.RUNNING);
        }

        // If task already reached SUCCEEDED (e.g. fast completion reported concurrently), return attempt
        if (currentTask.getStatus() == TaskStatus.SUCCEEDED) {
            return attemptRepository.findByTaskIdAndAttemptNumber(taskId, attemptNumber)
                    .orElseGet(() -> attemptRepository.findLatestAttempt(taskId).orElse(null));
        }

        // Validate state transition to RUNNING. If currently QUEUED, transition through DISPATCHED first
        TaskStatus targetStatus = TaskStatus.RUNNING;
        if (currentTask.getStatus() == TaskStatus.QUEUED) {
            TaskStateMachine.transition(taskId, TaskStatus.QUEUED, TaskStatus.DISPATCHED);
            TaskStateMachine.transition(taskId, TaskStatus.DISPATCHED, targetStatus);
        } else {
            TaskStateMachine.transition(taskId, currentTask.getStatus(), targetStatus);
        }

        int effectiveAttempt = attemptNumber;
        if (attemptRepository != null) {
            Optional<ExecutionAttempt> latest = attemptRepository.findLatestAttempt(taskId);
            if (latest.isPresent() && latest.get().getAttemptNumber() >= effectiveAttempt) {
                effectiveAttempt = latest.get().getAttemptNumber() + 1;
            }
        }

        Task updatedTask = currentTask.toBuilder()
                .status(targetStatus)
                .currentAttempt(effectiveAttempt)
                .build();
        Task persistedTask = taskRepository.update(updatedTask);

        // Persist physical attempt record
        ExecutionAttempt attempt = ExecutionAttempt.builder()
                .attemptId(UUID.randomUUID())
                .taskId(taskId)
                .workerId(workerId)
                .attemptNumber(effectiveAttempt)
                .status(ExecutionAttemptStatus.RUNNING)
                .startedAt(Instant.now())
                .build();
        ExecutionAttempt savedAttempt = attemptRepository.save(attempt);

        // Audit log
        TaskAuditRecord auditRecord = TaskAuditRecord.builder()
                .taskId(taskId)
                .fromStatus(currentTask.getStatus())
                .toStatus(targetStatus)
                .attemptNumber(attemptNumber)
                .reason("Execution started on worker " + workerId)
                .createdAt(Instant.now())
                .build();
        auditRepository.append(auditRecord);

        // Outbox event
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateType("TASK")
                .aggregateId(taskId.toString())
                .eventType("ExecutionAttemptStarted")
                .payload(String.format("{\"taskId\":\"%s\",\"attemptId\":\"%s\",\"workerId\":\"%s\",\"attemptNumber\":%d}",
                        taskId, savedAttempt.getAttemptId(), workerId, attemptNumber))
                .createdAt(Instant.now())
                .build();
        outboxRepository.append(outboxEvent);

        return savedAttempt;
    }

    /**
     * Atomically records the completion of an execution attempt (success or failure) and updates
     * the task lifecycle state machine accordingly.
     *
     * @param attemptId    attempt UUID.
     * @param success      true if physical execution succeeded, false if failed.
     * @param errorCode    optional failure code.
     * @param errorMessage optional failure message or exception summary.
     * @return updated ExecutionAttempt.
     */
    @Transactional
    public ExecutionAttempt completeExecutionAttempt(UUID attemptId, boolean success, String errorCode, String errorMessage) {
        Objects.requireNonNull(attemptId, "attemptId must not be null");

        ExecutionAttempt currentAttempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new PersistenceException("Execution attempt not found with ID: " + attemptId));

        UUID taskId = currentAttempt.getTaskId();
        Task currentTask = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        Instant completedAt = Instant.now();
        ExecutionAttempt updatedAttempt;
        TaskStatus nextTaskStatus;
        String reason;

        // If task was already cancelled while this attempt was running, do NOT resurrect task
        if (currentTask.getStatus() == TaskStatus.CANCELLED) {
            if (concurrencyManager != null) {
                concurrencyManager.releaseConcurrency(currentTask.getTenantId(), taskId);
            }
            log.warn("Task {} was CANCELLED while attempt #{} was in flight; ignoring completion to prevent resurrecting task",
                    taskId, currentAttempt.getAttemptNumber());
            updatedAttempt = success ? currentAttempt.withSuccess(completedAt) : currentAttempt.withFailure(errorCode, errorMessage, completedAt);
            attemptRepository.update(updatedAttempt);
            return updatedAttempt;
        }

        // Release tenant concurrency slot upon attempt completion
        if (concurrencyManager != null) {
            concurrencyManager.releaseConcurrency(currentTask.getTenantId(), taskId);
        }

        if (success) {
            updatedAttempt = currentAttempt.withSuccess(completedAt);
            nextTaskStatus = TaskStatus.SUCCEEDED;
            reason = "Execution completed successfully";
        } else {
            updatedAttempt = currentAttempt.withFailure(errorCode, errorMessage, completedAt);
            // Transition RUNNING -> FAILED
            TaskStateMachine.transition(taskId, currentTask.getStatus(), TaskStatus.FAILED);
            // Evaluate retry eligibility: QUEUED or DLQ
            nextTaskStatus = TaskStateMachine.determinePostFailureStatus(currentTask.getCurrentAttempt(), currentTask.getMaxAttempts());
            reason = nextTaskStatus == TaskStatus.DLQ
                    ? "Max attempts exceeded (" + currentTask.getMaxAttempts() + "); moved to DLQ"
                    : "Execution failed; re-queued for retry (attempt " + currentTask.getCurrentAttempt() + "/" + currentTask.getMaxAttempts() + ")";
        }

        // 1. Update attempt
        attemptRepository.update(updatedAttempt);

        // 2. Validate and transition task state
        TaskStateMachine.transition(taskId, success ? currentTask.getStatus() : TaskStatus.FAILED, nextTaskStatus);
        Task updatedTask = currentTask.toBuilder()
                .status(nextTaskStatus)
                .build();
        Task persistedTask = taskRepository.update(updatedTask);

        // 3. Append audit record
        TaskAuditRecord auditRecord = TaskAuditRecord.builder()
                .taskId(taskId)
                .fromStatus(currentTask.getStatus())
                .toStatus(nextTaskStatus)
                .attemptNumber(currentAttempt.getAttemptNumber())
                .reason(reason)
                .metadata(errorMessage)
                .createdAt(completedAt)
                .build();
        auditRepository.append(auditRecord);

        // 4. Append outbox event
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateType("TASK")
                .aggregateId(taskId.toString())
                .eventType(success ? "TaskSucceeded" : (nextTaskStatus == TaskStatus.DLQ ? "TaskDeadLettered" : "TaskFailed"))
                .payload(String.format("{\"taskId\":\"%s\",\"attemptId\":\"%s\",\"status\":\"%s\",\"version\":%d}",
                        taskId, attemptId, nextTaskStatus, persistedTask.getVersion()))
                .createdAt(completedAt)
                .build();
        outboxRepository.append(outboxEvent);

        return updatedAttempt;
    }

    /**
     * Atomically cancels a task across all valid non-terminal lifecycle stages
     * (CREATED, QUEUED, DISPATCHED, RUNNING, FAILED/retry-waiting, DLQ).
     * Releases any active tenant concurrency accounting.
     *
     * @param taskId task UUID to cancel.
     * @param reason cancellation rationale.
     * @return updated cancelled task.
     */
    @Transactional
    public Task cancelTask(UUID taskId, String reason) {
        Objects.requireNonNull(taskId, "taskId must not be null");

        Task currentTask = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        TaskStatus fromStatus = currentTask.getStatus();

        // Idempotent cancellation: if already cancelled, return existing
        if (fromStatus == TaskStatus.CANCELLED) {
            log.debug("Task {} is already CANCELLED; ignoring redundant cancellation", taskId);
            return currentTask;
        }

        // If in terminal success, cannot cancel
        if (fromStatus == TaskStatus.SUCCEEDED) {
            throw new InvalidTaskTransitionException(taskId, fromStatus, TaskStatus.CANCELLED);
        }

        // Validate and apply transition to CANCELLED
        TaskStateMachine.transition(taskId, fromStatus, TaskStatus.CANCELLED);

        Task updatedTaskState = currentTask.toBuilder()
                .status(TaskStatus.CANCELLED)
                .build();
        Task persistedTask = taskRepository.update(updatedTaskState);

        // Release tenant concurrency slot if task was in an active executing state
        if (concurrencyManager != null) {
            concurrencyManager.releaseConcurrency(persistedTask.getTenantId(), taskId);
        }

        // Audit log
        TaskAuditRecord auditRecord = TaskAuditRecord.builder()
                .taskId(taskId)
                .fromStatus(fromStatus)
                .toStatus(TaskStatus.CANCELLED)
                .attemptNumber(persistedTask.getCurrentAttempt())
                .reason(reason != null ? reason : "Task cancelled by operator or system")
                .createdAt(Instant.now())
                .build();
        auditRepository.append(auditRecord);

        // Outbox event
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateType("TASK")
                .aggregateId(taskId.toString())
                .eventType("TaskCancelled")
                .payload(String.format("{\"taskId\":\"%s\",\"fromStatus\":\"%s\",\"toStatus\":\"CANCELLED\",\"version\":%d}",
                        taskId, fromStatus, persistedTask.getVersion()))
                .createdAt(Instant.now())
                .build();
        outboxRepository.append(outboxEvent);

        log.info("Cancelled task {} from status {} (version {})", taskId, fromStatus, persistedTask.getVersion());
        return persistedTask;
    }
}
