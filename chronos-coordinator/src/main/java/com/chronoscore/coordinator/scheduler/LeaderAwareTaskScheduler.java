package com.chronoscore.coordinator.scheduler;

import com.chronoscore.common.domain.ExecutionAttempt;
import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.common.proto.ProtoTask;
import com.chronoscore.common.proto.ProtoTaskAssignment;
import com.chronoscore.common.proto.ProtoTaskStateUpdate;
import com.chronoscore.common.proto.ProtoTaskStatus;
import com.chronoscore.coordinator.cluster.grpc.WorkerDispatchClient;
import com.chronoscore.coordinator.cluster.model.WorkerNode;
import com.chronoscore.coordinator.coordination.exception.StaleLeaderException;
import com.chronoscore.coordinator.coordination.fencing.FencingTokenService;
import com.chronoscore.coordinator.coordination.leader.LeaderElectionService;
import com.chronoscore.coordinator.coordination.lock.DistributedLockService;
import com.chronoscore.coordinator.coordination.lock.LockHandle;
import com.chronoscore.coordinator.coordination.model.FencingToken;
import com.chronoscore.coordinator.coordination.model.LeaderIdentity;
import com.chronoscore.coordinator.coordination.model.LeadershipStateListener;
import com.chronoscore.coordinator.distribution.WorkerSelector;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import com.chronoscore.coordinator.persistence.service.TransactionalTaskService;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Leader-aware distributed task scheduler.
 * <p>
 * <b>Architectural Responsibilities &amp; Guarantees:</b>
 * <ul>
 *   <li><b>Leader-Only Execution</b>: The scheduler is only active on the cluster leader coordinator.
 *       Follower coordinators maintain the scheduler in a suspended standby state.</li>
 *   <li><b>Instant Leadership Teardown</b>: Transitions to paused immediately upon receiving
 *       {@link LeadershipStateListener#onLeadershipLost(LeaderIdentity)}.</li>
 *   <li><b>Fencing-Token Guarded Operations</b>: Carries the leader epoch into scheduling ticks,
 *       asserting generation validity to prevent zombie coordinator operations.</li>
 *   <li><b>Distributed Lock Isolation</b>: Acquires a Redis distributed task lock before dispatching
 *       to prevent competing coordinator iterations from double-scheduling tasks.</li>
 *   <li><b>Durable Truth in PostgreSQL</b>: All state transitions ({@code QUEUED -> RUNNING} or
 *       {@code QUEUED -> CANCELLED}) are atomically persisted to PostgreSQL with audit records and outbox events.</li>
 *   <li><b>Controlled Admission &amp; Backpressure</b>: If candidate workers are saturated, tasks remain
 *       safely in {@code QUEUED} without busy-looping or dropping work.</li>
 * </ul>
 */
@Component
public class LeaderAwareTaskScheduler implements TaskScheduler, LeadershipStateListener, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LeaderAwareTaskScheduler.class);

    private final LeaderElectionService leaderElectionService;
    private final FencingTokenService fencingTokenService;
    private final DistributedLockService distributedLockService;
    private final TaskRepository taskRepository;
    private final TransactionalTaskService transactionalTaskService;
    private final WorkerSelector workerSelector;
    private final WorkerDispatchClient workerDispatchClient;
    private final SchedulingPolicy schedulingPolicy;
    private final SchedulerProperties schedulerProperties;
    private final SchedulerMetricsRecorder metricsRecorder;
    private final com.chronoscore.coordinator.recovery.lease.ExecutionLeaseService leaseService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean loopInProgress = new AtomicBoolean(false);
    private ScheduledExecutorService schedulerExecutor;

    public LeaderAwareTaskScheduler(LeaderElectionService leaderElectionService,
                                    FencingTokenService fencingTokenService,
                                    DistributedLockService distributedLockService,
                                    TaskRepository taskRepository,
                                    TransactionalTaskService transactionalTaskService,
                                    WorkerSelector workerSelector,
                                    WorkerDispatchClient workerDispatchClient,
                                    SchedulingPolicy schedulingPolicy,
                                    SchedulerProperties schedulerProperties,
                                    SchedulerMetricsRecorder metricsRecorder) {
        this(leaderElectionService, fencingTokenService, distributedLockService, taskRepository,
                transactionalTaskService, workerSelector, workerDispatchClient, schedulingPolicy,
                schedulerProperties, metricsRecorder, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LeaderAwareTaskScheduler(LeaderElectionService leaderElectionService,
                                    FencingTokenService fencingTokenService,
                                    DistributedLockService distributedLockService,
                                    TaskRepository taskRepository,
                                    TransactionalTaskService transactionalTaskService,
                                    WorkerSelector workerSelector,
                                    WorkerDispatchClient workerDispatchClient,
                                    SchedulingPolicy schedulingPolicy,
                                    SchedulerProperties schedulerProperties,
                                    SchedulerMetricsRecorder metricsRecorder,
                                    @org.springframework.beans.factory.annotation.Autowired(required = false)
                                    com.chronoscore.coordinator.recovery.lease.ExecutionLeaseService leaseService) {
        this.leaderElectionService = Objects.requireNonNull(leaderElectionService, "leaderElectionService must not be null");
        this.fencingTokenService = Objects.requireNonNull(fencingTokenService, "fencingTokenService must not be null");
        this.distributedLockService = Objects.requireNonNull(distributedLockService, "distributedLockService must not be null");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        this.transactionalTaskService = Objects.requireNonNull(transactionalTaskService, "transactionalTaskService must not be null");
        this.workerSelector = Objects.requireNonNull(workerSelector, "workerSelector must not be null");
        this.workerDispatchClient = Objects.requireNonNull(workerDispatchClient, "workerDispatchClient must not be null");
        this.schedulingPolicy = Objects.requireNonNull(schedulingPolicy, "schedulingPolicy must not be null");
        this.schedulerProperties = Objects.requireNonNull(schedulerProperties, "schedulerProperties must not be null");
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder must not be null");
        this.leaseService = leaseService;
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting LeaderAwareTaskScheduler (enabled: {}, pollInterval: {}ms, batchSize: {})",
                    schedulerProperties.isEnabled(), schedulerProperties.getPollIntervalMs(), schedulerProperties.getBatchSize());

            leaderElectionService.addListener(this);

            if (schedulerProperties.isEnabled()) {
                schedulerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "chronos-scheduler-loop");
                    t.setDaemon(true);
                    return t;
                });

                schedulerExecutor.scheduleWithFixedDelay(
                        this::runScheduledTick,
                        schedulerProperties.getPollIntervalMs(),
                        schedulerProperties.getPollIntervalMs(),
                        TimeUnit.MILLISECONDS
                );
            }

            if (leaderElectionService.isLeader()) {
                log.info("Coordinator is already LEADER; scheduler loop active");
            } else {
                log.info("Coordinator is FOLLOWER; scheduler loop suspended pending leadership");
            }
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping LeaderAwareTaskScheduler");
            if (schedulerExecutor != null) {
                schedulerExecutor.shutdown();
                try {
                    if (!schedulerExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                        schedulerExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    schedulerExecutor.shutdownNow();
                }
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get() && leaderElectionService.isLeader();
    }

    @Override
    public int getPhase() {
        // Runs late in startup after coordination and cluster servers are initialized
        return Integer.MAX_VALUE - 100;
    }

    @Override
    public void onLeadershipAcquired(LeaderIdentity identity) {
        log.info("Scheduling loop RESUMED: coordinator acquired cluster leadership (epoch: {})",
                identity != null ? identity.getLeaderEpoch() : 0);
    }

    @Override
    public void onLeadershipLost(LeaderIdentity identity) {
        log.warn("Scheduling loop PAUSED: coordinator lost cluster leadership (epoch: {})",
                identity != null ? identity.getLeaderEpoch() : 0);
    }

    private void runScheduledTick() {
        if (!running.get() || !leaderElectionService.isLeader()) {
            return;
        }

        // Concurrency gate: prevent overlapping loop iterations
        if (!loopInProgress.compareAndSet(false, true)) {
            log.debug("Previous scheduling iteration still running; skipping tick");
            return;
        }

        try {
            scheduleOnce();
        } catch (Throwable t) {
            log.error("Unexpected error in scheduling loop tick: {}", t.getMessage(), t);
        } finally {
            loopInProgress.set(false);
        }
    }

    @Override
    public SchedulingResult scheduleOnce() {
        if (!leaderElectionService.isLeader()) {
            return SchedulingResult.paused("Coordinator is not cluster leader");
        }

        // 1. Assert leader fencing token validity
        FencingToken fencingToken;
        try {
            fencingToken = fencingTokenService.getCurrentFencingToken();
        } catch (StaleLeaderException e) {
            log.warn("Scheduling aborted: stale leader detected ({})", e.getMessage());
            return SchedulingResult.paused("Stale leader: " + e.getMessage());
        }
        long leaderEpoch = fencingToken.getEpoch();

        metricsRecorder.recordPoll();

        // 2. Select ready candidate tasks via scheduling policy
        List<Task> candidateTasks = schedulingPolicy.selectCandidateTasks(Instant.now());
        if (candidateTasks.isEmpty()) {
            return SchedulingResult.empty();
        }

        metricsRecorder.recordTasksSelected(candidateTasks.size());
        log.debug("Selected {} candidate tasks for scheduling (leader epoch: {})", candidateTasks.size(), leaderEpoch);

        int dispatched = 0;
        int rejected = 0;
        int skipped = 0;
        int deadlines = 0;

        // 3. Process candidate tasks in priority order
        for (Task task : candidateTasks) {
            // Verify coordinator is still active leader before dispatching each task
            if (!leaderElectionService.isLeader()) {
                log.warn("Coordinator lost leadership during scheduling batch; aborting iteration");
                break;
            }

            try {
                DispatchOutcome outcome = dispatchSingleTask(task, leaderEpoch);
                switch (outcome) {
                    case DISPATCHED -> dispatched++;
                    case DEADLINE_EXPIRED -> deadlines++;
                    case LOCKED_BY_OTHER, SKIPPED_NOT_QUEUED -> skipped++;
                    case NO_WORKER_AVAILABLE, DISPATCH_REJECTED -> rejected++;
                }
            } catch (StaleLeaderException e) {
                log.warn("Stale leader epoch during task dispatch; halting batch immediately: {}", e.getMessage());
                break;
            } catch (Exception e) {
                log.error("Error processing candidate task '{}': {}", task.getTaskId(), e.getMessage(), e);
                rejected++;
            }
        }

        log.debug("Scheduling batch concluded: selected={}, dispatched={}, rejected={}, skipped={}, deadlinesExpired={}",
                candidateTasks.size(), dispatched, rejected, skipped, deadlines);

        return SchedulingResult.success(candidateTasks.size(), dispatched, rejected, skipped, deadlines);
    }

    private DispatchOutcome dispatchSingleTask(Task task, long leaderEpoch) {
        String taskIdStr = task.getTaskId().toString();
        String lockResource = "chronos:task:lock:" + taskIdStr;

        // 1. Deadline check before lock acquisition: cancel if expired
        if (task.getDeadline() != null && task.getDeadline().isBefore(Instant.now())) {
            Optional<LockHandle> lockOpt = distributedLockService.tryAcquire(lockResource, schedulerProperties.getTaskLockTtl());
            if (lockOpt.isPresent()) {
                try {
                    transactionalTaskService.transitionTask(task.getTaskId(), TaskStatus.CANCELLED,
                            "Task deadline expired before dispatch", null);
                    log.info("Task '{}' cancelled due to expired deadline ({})", task.getTaskId(), task.getDeadline());
                    return DispatchOutcome.DEADLINE_EXPIRED;
                } finally {
                    distributedLockService.release(lockOpt.get());
                }
            }
            return DispatchOutcome.LOCKED_BY_OTHER;
        }

        // 2. Acquire distributed coordination lock to guarantee scheduling exclusivity
        Optional<LockHandle> lockOpt = distributedLockService.tryAcquire(lockResource, schedulerProperties.getTaskLockTtl());
        if (lockOpt.isEmpty()) {
            log.debug("Task '{}' locked by another coordinator or scheduling cycle; skipping", taskIdStr);
            return DispatchOutcome.LOCKED_BY_OTHER;
        }

        LockHandle lockHandle = lockOpt.get();
        try {
            // 3. Fencing assertion: verify caller is still leader with active epoch
            fencingTokenService.assertActiveLeader(leaderEpoch);

            // 4. Verify task state in PostgreSQL: ensure task is still in QUEUED status
            Optional<Task> currentOpt = taskRepository.findById(task.getTaskId());
            if (currentOpt.isEmpty()) {
                return DispatchOutcome.SKIPPED_NOT_QUEUED;
            }

            Task currentTask = currentOpt.get();
            if (currentTask.getStatus() != TaskStatus.QUEUED) {
                log.debug("Task '{}' is in status '{}', not QUEUED; skipping", taskIdStr, currentTask.getStatus());
                return DispatchOutcome.SKIPPED_NOT_QUEUED;
            }

            // Re-check deadline in case time elapsed during lock acquisition
            if (currentTask.getDeadline() != null && currentTask.getDeadline().isBefore(Instant.now())) {
                transactionalTaskService.transitionTask(currentTask.getTaskId(), TaskStatus.CANCELLED,
                        "Task deadline expired before dispatch", null);
                log.info("Task '{}' cancelled due to expired deadline ({})", taskIdStr, currentTask.getDeadline());
                return DispatchOutcome.DEADLINE_EXPIRED;
            }

            // 5. Select eligible worker via consistent hash ring with capacity gating
            Optional<WorkerNode> workerOpt = workerSelector.selectWorker(currentTask);
            if (workerOpt.isEmpty()) {
                metricsRecorder.recordNoEligibleWorker();
                log.debug("No eligible worker available for task '{}'; task remains QUEUED", taskIdStr);
                return DispatchOutcome.NO_WORKER_AVAILABLE;
            }

            WorkerNode targetWorker = workerOpt.get();
            int nextAttempt = currentTask.getCurrentAttempt() + 1;

            // 6. Build Protobuf TaskAssignment payload
            ProtoTask protoTask = ProtoTask.newBuilder()
                    .setTaskId(taskIdStr)
                    .setTaskType(currentTask.getTaskType())
                    .setPayload(currentTask.getPayload() != null ? currentTask.getPayload() : "")
                    .build();

            ProtoTaskAssignment assignment = ProtoTaskAssignment.newBuilder()
                    .setTaskId(taskIdStr)
                    .setWorkerId(targetWorker.workerId())
                    .setAttemptNumber(nextAttempt)
                    .setTask(protoTask)
                    .build();

            // 7. Dispatch to worker over gRPC with explicit deadline
            Instant dispatchStart = Instant.now();
            ProtoTaskStateUpdate response;
            try {
                response = workerDispatchClient.dispatchTask(targetWorker, assignment, schedulerProperties.getDispatchTimeout());
            } catch (StatusRuntimeException e) {
                log.warn("Worker '{}' rejected dispatch for task '{}' (status: {}): {}",
                        targetWorker.workerId(), taskIdStr, e.getStatus().getCode(), e.getMessage());
                metricsRecorder.recordTaskDispatchRejected(e.getStatus().getCode().name());
                return DispatchOutcome.DISPATCH_REJECTED;
            }
            metricsRecorder.recordDispatchLatency(Duration.between(dispatchStart, Instant.now()));

            // 8. Upon worker acceptance, commit transition atomically in PostgreSQL
            if (response != null && response.getNewStatus() == ProtoTaskStatus.TASK_STATUS_RUNNING) {
                ExecutionAttempt attempt = transactionalTaskService.startExecutionAttempt(currentTask.getTaskId(), targetWorker.workerId(), nextAttempt);
                if (leaseService != null) {
                    try {
                        leaseService.acquireLease(currentTask.getTaskId(), attempt.getAttemptId(), targetWorker.workerId(), nextAttempt, leaderEpoch);
                    } catch (Exception e) {
                        log.warn("Failed to acquire execution lease for task '{}': {}", taskIdStr, e.getMessage());
                    }
                }
                metricsRecorder.recordTaskDispatched(currentTask.getTaskType(), targetWorker.workerId());
                log.info("Task '{}' successfully dispatched to worker '{}' (attempt #{})",
                        taskIdStr, targetWorker.workerId(), nextAttempt);
                return DispatchOutcome.DISPATCHED;
            }

            log.warn("Worker '{}' did not accept task '{}' (response status: {})",
                    targetWorker.workerId(), taskIdStr, response != null ? response.getNewStatus() : "null");
            return DispatchOutcome.DISPATCH_REJECTED;

        } finally {
            // Always safely release the distributed task lock
            distributedLockService.release(lockHandle);
        }
    }
}
