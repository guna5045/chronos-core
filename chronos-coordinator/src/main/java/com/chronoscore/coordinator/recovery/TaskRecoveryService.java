package com.chronoscore.coordinator.recovery;

import com.chronoscore.common.domain.ExecutionAttempt;
import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.ExecutionAttemptStatus;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.common.proto.ProtoExecutionStatus;
import com.chronoscore.common.proto.ProtoTaskResult;
import com.chronoscore.common.statemachine.TaskStateMachine;
import com.chronoscore.coordinator.cluster.model.WorkerNode;
import com.chronoscore.coordinator.cluster.model.WorkerState;
import com.chronoscore.coordinator.cluster.registry.WorkerRegistry;
import com.chronoscore.coordinator.coordination.lock.DistributedLockService;
import com.chronoscore.coordinator.coordination.lock.LockHandle;
import com.chronoscore.coordinator.persistence.model.OutboxEvent;
import com.chronoscore.coordinator.persistence.model.TaskAuditRecord;
import com.chronoscore.coordinator.persistence.repository.ExecutionAttemptRepository;
import com.chronoscore.coordinator.persistence.repository.OutboxRepository;
import com.chronoscore.coordinator.persistence.repository.TaskAuditRepository;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import com.chronoscore.coordinator.recovery.lease.ExecutionLeaseService;
import com.chronoscore.coordinator.recovery.lease.TaskExecutionLease;
import com.chronoscore.coordinator.recovery.policy.RetryPolicy;
import com.chronoscore.coordinator.recovery.timeout.TaskTimeoutService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Core orchestration service for task failure recovery, stale execution fencing,
 * worker crash reclamation, and result processing.
 */
@Service
public class TaskRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(TaskRecoveryService.class);
    private static final Duration RECOVERY_LOCK_TTL = Duration.ofSeconds(15);

    private final TaskRepository taskRepository;
    private final ExecutionAttemptRepository attemptRepository;
    private final TaskAuditRepository auditRepository;
    private final OutboxRepository outboxRepository;
    private final ExecutionLeaseService leaseService;
    private final RetryPolicy retryPolicy;
    private final WorkerRegistry workerRegistry;
    private final TaskTimeoutService timeoutService;
    private final DistributedLockService lockService;

    public TaskRecoveryService(TaskRepository taskRepository,
                               ExecutionAttemptRepository attemptRepository,
                               TaskAuditRepository auditRepository,
                               OutboxRepository outboxRepository,
                               ExecutionLeaseService leaseService,
                               RetryPolicy retryPolicy,
                               WorkerRegistry workerRegistry,
                               TaskTimeoutService timeoutService,
                               DistributedLockService lockService) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository must not be null");
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository must not be null");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.leaseService = Objects.requireNonNull(leaseService, "leaseService must not be null");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        this.workerRegistry = Objects.requireNonNull(workerRegistry, "workerRegistry must not be null");
        this.timeoutService = Objects.requireNonNull(timeoutService, "timeoutService must not be null");
        this.lockService = Objects.requireNonNull(lockService, "lockService must not be null");
    }

    /**
     * Validates and processes a task execution result reported by a worker.
     * Enforces fencing and rejects stale worker results.
     *
     * @param result protobuf execution result payload.
     * @return true if result was valid and accepted, false if stale or rejected.
     */
    @Transactional
    public boolean handleTaskResult(ProtoTaskResult result) {
        Objects.requireNonNull(result, "result must not be null");
        UUID taskId = UUID.fromString(result.getTaskId());
        String workerId = result.getWorkerId();
        int reportedAttemptNumber = result.getAttemptNumber();
        Instant completedAt = Instant.now();

        Optional<Task> taskOpt = taskRepository.findById(taskId);
        if (taskOpt.isEmpty()) {
            log.warn("Received result for non-existent task '{}' from worker '{}'", taskId, workerId);
            return false;
        }

        Task currentTask = taskOpt.get();

        // 1. Stale Result Check A: Task is already in terminal state (SUCCEEDED, CANCELLED, DLQ)
        if (currentTask.getStatus() == TaskStatus.SUCCEEDED ||
                currentTask.getStatus() == TaskStatus.CANCELLED ||
                currentTask.getStatus() == TaskStatus.DLQ) {
            rejectStaleResult(taskId, workerId, reportedAttemptNumber, currentTask,
                    "Task is already in terminal status: " + currentTask.getStatus());
            return false;
        }

        // 2. Stale Result Check B: Attempt number is older than current attempt
        if (reportedAttemptNumber < currentTask.getCurrentAttempt()) {
            rejectStaleResult(taskId, workerId, reportedAttemptNumber, currentTask,
                    "Reported attempt #" + reportedAttemptNumber + " is older than current attempt #" + currentTask.getCurrentAttempt());
            return false;
        }

        // 3. Stale Result Check C: Verify against execution lease ownership
        Optional<TaskExecutionLease> leaseOpt = leaseService.getLease(taskId);
        if (leaseOpt.isPresent()) {
            TaskExecutionLease lease = leaseOpt.get();
            if (!lease.getWorkerId().equals(workerId) || lease.getAttemptNumber() != reportedAttemptNumber) {
                rejectStaleResult(taskId, workerId, reportedAttemptNumber, currentTask,
                        "Lease owned by worker '" + lease.getWorkerId() + "' (attempt #" + lease.getAttemptNumber() + ")");
                return false;
            }
        }

        // Find the physical attempt record
        Optional<ExecutionAttempt> attemptOpt = attemptRepository.findByTaskIdAndAttemptNumber(taskId, reportedAttemptNumber);
        if (attemptOpt.isEmpty()) {
            log.warn("Execution attempt #{} not found for task '{}'", reportedAttemptNumber, taskId);
            return false;
        }

        ExecutionAttempt attempt = attemptOpt.get();
        boolean isSuccess = result.getStatus() == ProtoExecutionStatus.EXECUTION_STATUS_SUCCEEDED;

        if (isSuccess) {
            // Success flow
            ExecutionAttempt succeededAttempt = attempt.withSuccess(completedAt);
            attemptRepository.update(succeededAttempt);

            TaskStateMachine.transition(taskId, currentTask.getStatus(), TaskStatus.SUCCEEDED);
            Task updatedTask = currentTask.toBuilder()
                    .status(TaskStatus.SUCCEEDED)
                    .build();
            Task persistedTask = taskRepository.update(updatedTask);

            leaseService.releaseLease(taskId);

            TaskAuditRecord audit = TaskAuditRecord.builder()
                    .taskId(taskId)
                    .fromStatus(currentTask.getStatus())
                    .toStatus(TaskStatus.SUCCEEDED)
                    .attemptNumber(reportedAttemptNumber)
                    .reason("Execution attempt succeeded on worker " + workerId)
                    .createdAt(completedAt)
                    .build();
            auditRepository.append(audit);

            OutboxEvent outbox = OutboxEvent.builder()
                    .aggregateType("TASK")
                    .aggregateId(taskId.toString())
                    .eventType("TaskSucceeded")
                    .payload(String.format("{\"taskId\":\"%s\",\"attemptNumber\":%d,\"workerId\":\"%s\",\"version\":%d}",
                            taskId, reportedAttemptNumber, workerId, persistedTask.getVersion()))
                    .createdAt(completedAt)
                    .build();
            outboxRepository.append(outbox);

            log.info("Task '{}' succeeded on worker '{}' (attempt #{}, version {})",
                    taskId, workerId, reportedAttemptNumber, persistedTask.getVersion());
            return true;
        } else {
            // Failure flow
            String errorCode = result.getErrorCode().isBlank() ? "EXECUTION_FAILED" : result.getErrorCode();
            String errorMessage = result.getErrorMessage();

            ExecutionAttempt failedAttempt = attempt.withFailure(errorCode, errorMessage, completedAt);
            attemptRepository.update(failedAttempt);

            boolean shouldRetry = retryPolicy.shouldRetry(currentTask, reportedAttemptNumber, errorCode, null);

            TaskStatus nextStatus;
            Instant nextScheduleTime = null;
            String reason;

            if (shouldRetry) {
                nextStatus = TaskStatus.QUEUED;
                nextScheduleTime = retryPolicy.calculateNextScheduleTime(reportedAttemptNumber, completedAt);
                reason = "Attempt #" + reportedAttemptNumber + " failed (" + errorCode + "); retry scheduled at " + nextScheduleTime;
            } else {
                nextStatus = TaskStatus.DLQ;
                reason = "Attempt #" + reportedAttemptNumber + " failed (" + errorCode + "); retries exhausted or non-retryable; moved to DLQ";
            }

            TaskStateMachine.transition(taskId, currentTask.getStatus(), TaskStatus.FAILED);
            TaskStateMachine.transition(taskId, TaskStatus.FAILED, nextStatus);

            Task updatedTask = currentTask.toBuilder()
                    .status(nextStatus)
                    .scheduledAt(nextScheduleTime)
                    .build();
            Task persistedTask = taskRepository.update(updatedTask);

            leaseService.releaseLease(taskId);

            TaskAuditRecord audit = TaskAuditRecord.builder()
                    .taskId(taskId)
                    .fromStatus(currentTask.getStatus())
                    .toStatus(nextStatus)
                    .attemptNumber(reportedAttemptNumber)
                    .reason(reason)
                    .metadata("errorCode=" + errorCode + ";errorMessage=" + errorMessage)
                    .createdAt(completedAt)
                    .build();
            auditRepository.append(audit);

            OutboxEvent outbox = OutboxEvent.builder()
                    .aggregateType("TASK")
                    .aggregateId(taskId.toString())
                    .eventType(nextStatus == TaskStatus.DLQ ? "TaskDeadLettered" : "TaskRetryScheduled")
                    .payload(String.format("{\"taskId\":\"%s\",\"attemptNumber\":%d,\"status\":\"%s\",\"errorCode\":\"%s\",\"version\":%d}",
                            taskId, reportedAttemptNumber, nextStatus, errorCode, persistedTask.getVersion()))
                    .createdAt(completedAt)
                    .build();
            outboxRepository.append(outbox);

            log.info("Task '{}' failed on worker '{}' (attempt #{}): transitioned to {} (version {})",
                    taskId, workerId, reportedAttemptNumber, nextStatus, persistedTask.getVersion());
            return true;
        }
    }

    private void rejectStaleResult(UUID taskId, String workerId, int attemptNumber, Task currentTask, String reason) {
        log.warn("STALE RESULT REJECTED: Task '{}' attempt #{} from worker '{}' rejected: {}",
                taskId, attemptNumber, workerId, reason);

        TaskAuditRecord audit = TaskAuditRecord.builder()
                .taskId(taskId)
                .fromStatus(currentTask.getStatus())
                .toStatus(currentTask.getStatus())
                .attemptNumber(attemptNumber)
                .reason("STALE_RESULT_REJECTED: " + reason)
                .metadata("workerId=" + workerId + ";staleWorker=" + workerId + ";attemptNumber=" + attemptNumber + ";reportedAttempt=" + attemptNumber + ";currentAttempt=" + currentTask.getCurrentAttempt())
                .createdAt(Instant.now())
                .build();
        auditRepository.append(audit);
    }

    /**
     * Recovers active tasks assigned to a worker that was evicted from the cluster.
     *
     * @param workerId evicted worker node identifier.
     * @return number of orphaned tasks recovered.
     */
    public int recoverWorkerFailure(String workerId) {
        Objects.requireNonNull(workerId, "workerId must not be null");
        log.info("Initiating worker failure recovery for evicted worker '{}'", workerId);

        List<TaskExecutionLease> leases = leaseService.getLeasesForWorker(workerId);
        int recoveredCount = 0;

        for (TaskExecutionLease lease : leases) {
            String lockKey = "chronos:task:recovery:" + lease.getTaskId();
            Optional<LockHandle> lockOpt = lockService.tryAcquire(lockKey, RECOVERY_LOCK_TTL);
            if (lockOpt.isEmpty()) {
                continue;
            }

            LockHandle lock = lockOpt.get();
            try {
                boolean recovered = recoverSingleLeaseUnderLock(lease.getTaskId(), lease.getAttemptNumber(),
                        "WORKER_UNAVAILABLE", "Worker " + workerId + " was evicted from cluster");
                if (recovered) {
                    recoveredCount++;
                }
            } finally {
                lockService.release(lock);
            }
        }

        log.info("Worker failure recovery concluded for worker '{}': {} tasks recovered", workerId, recoveredCount);
        return recoveredCount;
    }

    /**
     * Recovers expired task execution leases whose workers failed to renew them.
     *
     * @param asOf  cutoff instant.
     * @param limit maximum batch size.
     * @return number of expired leases recovered.
     */
    public int recoverExpiredLeases(Instant asOf, int limit) {
        Instant cutoff = asOf != null ? asOf : Instant.now();
        List<TaskExecutionLease> expiredLeases = leaseService.getExpiredLeases(cutoff, limit);

        int recoveredCount = 0;
        for (TaskExecutionLease lease : expiredLeases) {
            // Check if worker is still active and heartbeating
            Optional<WorkerNode> workerOpt = workerRegistry.getWorker(lease.getWorkerId());
            if (workerOpt.isPresent() && workerOpt.get().state() == WorkerState.ACTIVE) {
                // Worker is active; perhaps missed a lease renewal tick, try renewing or check
                Duration elapsed = Duration.between(workerOpt.get().lastHeartbeatAt(), cutoff);
                if (elapsed.toMillis() < 3000) {
                    leaseService.renewLease(lease.getTaskId());
                    continue;
                }
            }

            String lockKey = "chronos:task:recovery:" + lease.getTaskId();
            Optional<LockHandle> lockOpt = lockService.tryAcquire(lockKey, RECOVERY_LOCK_TTL);
            if (lockOpt.isEmpty()) {
                continue;
            }

            LockHandle lock = lockOpt.get();
            try {
                boolean recovered = recoverSingleLeaseUnderLock(lease.getTaskId(), lease.getAttemptNumber(),
                        "LEASE_EXPIRED", "Execution lease expired without renewal (worker: " + lease.getWorkerId() + ")");
                if (recovered) {
                    recoveredCount++;
                }
            } finally {
                lockService.release(lock);
            }
        }

        return recoveredCount;
    }

    /**
     * Delegates timed-out task recovery to {@link TaskTimeoutService}.
     *
     * @param asOf  cutoff instant.
     * @param limit maximum batch size.
     * @return number of timed-out tasks recovered.
     */
    public int recoverTimedOutTasks(Instant asOf, int limit) {
        return timeoutService.recoverTimedOutTasks(asOf, limit);
    }

    @Transactional
    protected boolean recoverSingleLeaseUnderLock(UUID taskId, int attemptNumber, String errorCode, String errorMessage) {
        Optional<Task> taskOpt = taskRepository.findById(taskId);
        if (taskOpt.isEmpty()) {
            leaseService.releaseLease(taskId);
            return false;
        }

        Task task = taskOpt.get();

        // If task is not in an active execution state or attempt has already progressed, release lease and return
        if ((task.getStatus() != TaskStatus.RUNNING && task.getStatus() != TaskStatus.DISPATCHED) ||
                task.getCurrentAttempt() != attemptNumber) {
            leaseService.releaseLease(taskId);
            return false;
        }

        Instant now = Instant.now();

        // Mark execution attempt FAILED
        Optional<ExecutionAttempt> attemptOpt = attemptRepository.findByTaskIdAndAttemptNumber(taskId, attemptNumber);
        if (attemptOpt.isPresent()) {
            ExecutionAttempt failedAttempt = attemptOpt.get().withFailure(errorCode, errorMessage, now);
            attemptRepository.update(failedAttempt);
        }

        // Evaluate retry policy
        boolean shouldRetry = retryPolicy.shouldRetry(task, attemptNumber, errorCode, null);

        TaskStatus nextStatus;
        Instant nextScheduleTime = null;
        String reason;

        if (shouldRetry) {
            nextStatus = TaskStatus.QUEUED;
            nextScheduleTime = retryPolicy.calculateNextScheduleTime(attemptNumber, now);
            reason = "Task recovered (" + errorCode + "); retry scheduled at " + nextScheduleTime;
        } else {
            nextStatus = TaskStatus.DLQ;
            reason = "Task recovered (" + errorCode + "); max retries reached (" + attemptNumber + "/" + task.getMaxAttempts() + "); moved to DLQ";
        }

        // Apply state transitions
        TaskStateMachine.transition(taskId, task.getStatus(), TaskStatus.FAILED);
        TaskStateMachine.transition(taskId, TaskStatus.FAILED, nextStatus);

        Task updatedTask = task.toBuilder()
                .status(nextStatus)
                .scheduledAt(nextScheduleTime)
                .build();
        Task persistedTask = taskRepository.update(updatedTask);

        // Clean up execution lease
        leaseService.releaseLease(taskId);

        // Audit log
        TaskAuditRecord audit = TaskAuditRecord.builder()
                .taskId(taskId)
                .fromStatus(task.getStatus())
                .toStatus(nextStatus)
                .attemptNumber(attemptNumber)
                .reason(reason)
                .metadata("errorCode=" + errorCode + ";errorMessage=" + errorMessage)
                .createdAt(now)
                .build();
        auditRepository.append(audit);

        // Outbox event
        OutboxEvent outbox = OutboxEvent.builder()
                .aggregateType("TASK")
                .aggregateId(taskId.toString())
                .eventType(nextStatus == TaskStatus.DLQ ? "TaskDeadLettered" : "TaskRetryScheduled")
                .payload(String.format("{\"taskId\":\"%s\",\"attemptNumber\":%d,\"status\":\"%s\",\"reason\":\"%s\",\"version\":%d}",
                        taskId, attemptNumber, nextStatus, errorCode, persistedTask.getVersion()))
                .createdAt(now)
                .build();
        outboxRepository.append(outbox);

        log.info("Recovered task '{}' (attempt #{}): transitioned to {} (version {})",
                taskId, attemptNumber, nextStatus, persistedTask.getVersion());
        return true;
    }
}
