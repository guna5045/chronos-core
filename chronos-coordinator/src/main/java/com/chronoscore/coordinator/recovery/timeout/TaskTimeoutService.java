package com.chronoscore.coordinator.recovery.timeout;

import com.chronoscore.common.domain.ExecutionAttempt;
import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.common.proto.ProtoTaskCancelRequest;
import com.chronoscore.common.statemachine.TaskStateMachine;
import com.chronoscore.coordinator.cluster.grpc.WorkerDispatchClient;
import com.chronoscore.coordinator.cluster.model.WorkerNode;
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
import com.chronoscore.coordinator.recovery.policy.RetryPolicy;
import com.chronoscore.coordinator.workflow.service.WorkflowExecutionService;
import com.google.protobuf.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service detecting and reclaiming tasks exceeding their configured execution timeouts.
 */
@Service
public class TaskTimeoutService {

    private static final Logger log = LoggerFactory.getLogger(TaskTimeoutService.class);
    private static final Duration CANCELLATION_DEADLINE = Duration.ofSeconds(3);
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);

    private final TaskRepository taskRepository;
    private final ExecutionAttemptRepository attemptRepository;
    private final TaskAuditRepository auditRepository;
    private final OutboxRepository outboxRepository;
    private final ExecutionLeaseService leaseService;
    private final RetryPolicy retryPolicy;
    private final WorkerRegistry workerRegistry;
    private final WorkerDispatchClient dispatchClient;
    private final DistributedLockService lockService;

    @Autowired(required = false)
    private WorkflowExecutionService workflowExecutionService;

    public TaskTimeoutService(TaskRepository taskRepository,
                              ExecutionAttemptRepository attemptRepository,
                              TaskAuditRepository auditRepository,
                              OutboxRepository outboxRepository,
                              ExecutionLeaseService leaseService,
                              RetryPolicy retryPolicy,
                              WorkerRegistry workerRegistry,
                              WorkerDispatchClient dispatchClient,
                              DistributedLockService lockService) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository must not be null");
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository must not be null");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.leaseService = Objects.requireNonNull(leaseService, "leaseService must not be null");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        this.workerRegistry = Objects.requireNonNull(workerRegistry, "workerRegistry must not be null");
        this.dispatchClient = Objects.requireNonNull(dispatchClient, "dispatchClient must not be null");
        this.lockService = Objects.requireNonNull(lockService, "lockService must not be null");
    }

    public void setWorkflowExecutionService(WorkflowExecutionService workflowExecutionService) {
        this.workflowExecutionService = workflowExecutionService;
    }

    /**
     * Checks running tasks and reclaims any that have exceeded their configured timeout.
     *
     * @param asOf  current cutoff timestamp.
     * @param limit maximum batch size.
     * @return count of timed out tasks recovered.
     */
    public int recoverTimedOutTasks(Instant asOf, int limit) {
        Instant now = asOf != null ? asOf : Instant.now();
        List<Task> runningTasks = taskRepository.findByStatus(TaskStatus.RUNNING, limit);

        int recoveredCount = 0;
        for (Task task : runningTasks) {
            if (task.getTimeout() == null || task.getTimeout().isZero() || task.getTimeout().isNegative()) {
                continue;
            }

            Optional<ExecutionAttempt> latestAttemptOpt = attemptRepository.findLatestAttempt(task.getTaskId());
            if (latestAttemptOpt.isEmpty()) {
                continue;
            }

            ExecutionAttempt latestAttempt = latestAttemptOpt.get();
            Duration elapsed = Duration.between(latestAttempt.getStartedAt(), now);

            if (elapsed.compareTo(task.getTimeout()) > 0) {
                boolean handled = processTimeout(task, latestAttempt, now);
                if (handled) {
                    recoveredCount++;
                }
            }
        }

        return recoveredCount;
    }

    /**
     * Processes timeout for an individual task attempt under distributed lock protection.
     */
    public boolean processTimeout(Task task, ExecutionAttempt attempt, Instant now) {
        String lockKey = "chronos:task:timeout:" + task.getTaskId();
        Optional<LockHandle> lockOpt = lockService.tryAcquire(lockKey, LOCK_TTL);
        if (lockOpt.isEmpty()) {
            return false;
        }

        LockHandle lock = lockOpt.get();
        try {
            return handleTaskTimeoutUnderLock(task.getTaskId(), attempt.getAttemptNumber(), now);
        } finally {
            lockService.release(lock);
        }
    }

    @Transactional
    protected boolean handleTaskTimeoutUnderLock(UUID taskId, int attemptNumber, Instant now) {
        Optional<Task> currentTaskOpt = taskRepository.findById(taskId);
        if (currentTaskOpt.isEmpty()) {
            return false;
        }

        Task currentTask = currentTaskOpt.get();
        if (currentTask.getStatus() != TaskStatus.RUNNING || currentTask.getCurrentAttempt() != attemptNumber) {
            // Task has already concluded or progressed
            return false;
        }

        Optional<ExecutionAttempt> attemptOpt = attemptRepository.findByTaskIdAndAttemptNumber(taskId, attemptNumber);
        if (attemptOpt.isEmpty()) {
            return false;
        }

        ExecutionAttempt attempt = attemptOpt.get();

        log.warn("Task '{}' exceeded timeout (attempt #{}, worker '{}'). Requesting cooperative cancellation.",
                taskId, attemptNumber, attempt.getWorkerId());

        // 1. Cooperative cancellation request over gRPC to worker
        sendCooperativeCancellation(attempt.getWorkerId(), taskId.toString(), now);

        // 2. Mark attempt FAILED with EXECUTION_TIMEOUT
        ExecutionAttempt failedAttempt = attempt.withFailure(
                "EXECUTION_TIMEOUT",
                "Task execution exceeded configured timeout (" + currentTask.getTimeout() + ")",
                now
        );
        attemptRepository.update(failedAttempt);

        // 3. Evaluate retry eligibility
        boolean shouldRetry = retryPolicy.shouldRetry(currentTask, attemptNumber, "EXECUTION_TIMEOUT", null);

        TaskStatus nextStatus;
        Instant nextScheduleTime = null;
        String reason;

        if (shouldRetry) {
            nextStatus = TaskStatus.QUEUED;
            nextScheduleTime = retryPolicy.calculateNextScheduleTime(attemptNumber, now);
            reason = "Task execution timed out; retry scheduled at " + nextScheduleTime;
        } else {
            nextStatus = TaskStatus.DLQ;
            reason = "Task execution timed out; retries exhausted (" + attemptNumber + "/" + currentTask.getMaxAttempts() + "); moved to DLQ";
        }

        // 4. Update task state
        TaskStateMachine.transition(taskId, TaskStatus.RUNNING, TaskStatus.FAILED);
        TaskStateMachine.transition(taskId, TaskStatus.FAILED, nextStatus);

        Task updatedTask = currentTask.toBuilder()
                .status(nextStatus)
                .scheduledAt(nextScheduleTime)
                .build();
        Task persistedTask = taskRepository.update(updatedTask);

        // 5. Release lease
        leaseService.releaseLease(taskId);

        // 6. Audit log
        TaskAuditRecord audit = TaskAuditRecord.builder()
                .taskId(taskId)
                .fromStatus(TaskStatus.RUNNING)
                .toStatus(nextStatus)
                .attemptNumber(attemptNumber)
                .reason(reason)
                .metadata("timeout=" + currentTask.getTimeout() + ";workerId=" + attempt.getWorkerId())
                .createdAt(now)
                .build();
        auditRepository.append(audit);

        // 7. Outbox event
        OutboxEvent outbox = OutboxEvent.builder()
                .aggregateType("TASK")
                .aggregateId(taskId.toString())
                .eventType(nextStatus == TaskStatus.DLQ ? "TaskDeadLettered" : "TaskRetryScheduled")
                .payload(String.format("{\"taskId\":\"%s\",\"attemptNumber\":%d,\"status\":\"%s\",\"reason\":\"EXECUTION_TIMEOUT\",\"version\":%d}",
                        taskId, attemptNumber, nextStatus, persistedTask.getVersion()))
                .createdAt(now)
                .build();
        outboxRepository.append(outbox);

        log.info("Task '{}' timeout recovery complete: transitioned to {} (version {})",
                taskId, nextStatus, persistedTask.getVersion());

        if (workflowExecutionService != null && nextStatus == TaskStatus.DLQ) {
            try {
                workflowExecutionService.onTaskCompleted(taskId, false, "EXECUTION_TIMEOUT", "Task execution timed out");
            } catch (Exception ex) {
                log.error("Failed to notify workflow execution service for timed out DLQ task '{}': {}",
                        taskId, ex.getMessage(), ex);
            }
        }

        return true;
    }

    private void sendCooperativeCancellation(String workerId, String taskId, Instant now) {
        try {
            Optional<WorkerNode> workerOpt = workerRegistry.getWorker(workerId);
            if (workerOpt.isPresent()) {
                ProtoTaskCancelRequest request = ProtoTaskCancelRequest.newBuilder()
                        .setTaskId(taskId)
                        .setReason("Task execution exceeded timeout")
                        .setRequestedAt(Timestamp.newBuilder()
                                .setSeconds(now.getEpochSecond())
                                .setNanos(now.getNano())
                                .build())
                        .build();
                dispatchClient.cancelTask(workerOpt.get(), request, CANCELLATION_DEADLINE);
            }
        } catch (Exception ex) {
            log.warn("Failed to deliver cooperative cancellation to worker '{}' for task '{}': {}",
                    workerId, taskId, ex.getMessage());
        }
    }
}
