package com.chronoscore.coordinator.policy;

import com.chronoscore.common.domain.AdmissionDecision;
import com.chronoscore.common.domain.Task;
import com.chronoscore.common.domain.TenantExecutionPolicy;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import com.chronoscore.coordinator.persistence.repository.TenantPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/**
 * Authoritative controller governing task admission decisions across submission
 * and scheduling lifecycle stages.
 * <p>
 * Evaluates:
 * <ul>
 *   <li>Tenant queue limits (rejection at submission)</li>
 *   <li>Tenant concurrency quotas (holding in queue during scheduling)</li>
 *   <li>Token-bucket rate limits (throttling without failure)</li>
 *   <li>System backpressure (worker capacity exhaustion)</li>
 * </ul>
 */
@Component
public class TaskAdmissionController {

    private static final Logger log = LoggerFactory.getLogger(TaskAdmissionController.class);

    private final TenantPolicyRepository tenantPolicyRepository;
    private final TenantRateLimiter rateLimiter;
    private final TenantConcurrencyManager concurrencyManager;
    private final SystemBackpressureMonitor backpressureMonitor;
    private final TaskRepository taskRepository;

    @Autowired
    public TaskAdmissionController(TenantPolicyRepository tenantPolicyRepository,
                                   TenantRateLimiter rateLimiter,
                                   TenantConcurrencyManager concurrencyManager,
                                   SystemBackpressureMonitor backpressureMonitor,
                                   TaskRepository taskRepository) {
        this.tenantPolicyRepository = Objects.requireNonNull(tenantPolicyRepository, "tenantPolicyRepository must not be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter must not be null");
        this.concurrencyManager = Objects.requireNonNull(concurrencyManager, "concurrencyManager must not be null");
        this.backpressureMonitor = Objects.requireNonNull(backpressureMonitor, "backpressureMonitor must not be null");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
    }

    /**
     * Evaluates task admission at submission boundary before durable persistence.
     * Enforces queue depth quotas and policy integrity.
     *
     * @param task task candidate for submission.
     * @return AdmissionDecision result.
     */
    public AdmissionDecision evaluateSubmission(Task task) {
        Objects.requireNonNull(task, "task must not be null");

        String tenantId = task.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return AdmissionDecision.invalidPolicy("Task missing tenantId");
        }

        TenantExecutionPolicy policy;
        try {
            policy = tenantPolicyRepository.getEffectivePolicy(tenantId);
        } catch (Exception e) {
            log.error("Failed to load policy for tenant '{}': {}", tenantId, e.getMessage());
            return AdmissionDecision.invalidPolicy("Invalid tenant execution policy: " + e.getMessage());
        }

        // Check tenant queue quota
        int currentQueued = taskRepository.countByTenantAndStatus(tenantId, TaskStatus.QUEUED);
        if (currentQueued >= policy.getMaxQueuedTasks()) {
            log.warn("Tenant '{}' queue limit reached: current={}, max={}",
                    tenantId, currentQueued, policy.getMaxQueuedTasks());
            return AdmissionDecision.tenantQueueLimit(
                    String.format("Tenant '%s' queue quota exceeded (%d/%d)",
                            tenantId, currentQueued, policy.getMaxQueuedTasks()));
        }

        return AdmissionDecision.admitted();
    }

    /**
     * Evaluates task admission at scheduler dispatch boundary.
     * Enforces system backpressure, tenant concurrency limits, and rate limiting.
     * <p>
     * <b>Crucial Semantic:</b> If admission is denied here, the task MUST NOT be marked failed.
     * It remains in the {@code QUEUED} status in PostgreSQL.
     *
     * @param task candidate task selected by scheduling policy.
     * @return AdmissionDecision result.
     */
    public AdmissionDecision evaluateScheduling(Task task) {
        Objects.requireNonNull(task, "task must not be null");

        // 1. System-wide backpressure evaluation
        if (backpressureMonitor.isUnderBackpressure()) {
            return AdmissionDecision.systemBackpressure("Total cluster worker capacity saturated");
        }

        String tenantId = task.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return AdmissionDecision.invalidPolicy("Invalid tenant ID: tenantId cannot be null or blank");
        }
        TenantExecutionPolicy policy = tenantPolicyRepository.getEffectivePolicy(tenantId);

        // 2. Tenant Concurrency Quota Check
        if (!concurrencyManager.canAdmit(tenantId, policy.getMaxConcurrentTasks())) {
            int running = concurrencyManager.getRunningCount(tenantId);
            log.debug("Task '{}' held in queue: tenant '{}' concurrency limit reached ({}/{})",
                    task.getTaskId(), tenantId, running, policy.getMaxConcurrentTasks());
            return AdmissionDecision.tenantConcurrencyLimit(
                    String.format("Tenant '%s' reached concurrency limit (%d/%d)",
                            tenantId, running, policy.getMaxConcurrentTasks()));
        }

        // 3. Tenant Rate Limiting Check
        if (!rateLimiter.tryAcquire(tenantId, policy)) {
            log.debug("Task '{}' held in queue: tenant '{}' rate limited (rate: {} tps, burst: {})",
                    task.getTaskId(), tenantId, policy.getTasksPerSecond(), policy.getBurstCapacity());
            return AdmissionDecision.rateLimited(
                    String.format("Tenant '%s' rate limit exceeded", tenantId),
                    Instant.now().plusMillis(500));
        }

        return AdmissionDecision.admitted();
    }
}
