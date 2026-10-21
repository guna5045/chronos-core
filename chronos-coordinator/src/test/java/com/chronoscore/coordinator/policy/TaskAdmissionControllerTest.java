package com.chronoscore.coordinator.policy;

import com.chronoscore.common.domain.AdmissionDecision;
import com.chronoscore.common.domain.Task;
import com.chronoscore.common.domain.TenantExecutionPolicy;
import com.chronoscore.common.enums.AdmissionStatus;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import com.chronoscore.coordinator.persistence.repository.TenantPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Test suite verifying admission control across all 6 explicit decisions:
 * ADMITTED, RATE_LIMITED, TENANT_CONCURRENCY_LIMIT, TENANT_QUEUE_LIMIT,
 * SYSTEM_BACKPRESSURE, and INVALID_POLICY.
 */
@ExtendWith(MockitoExtension.class)
class TaskAdmissionControllerTest {

    @Mock
    private TenantPolicyRepository policyRepository;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TenantRateLimiter rateLimiter;

    @Mock
    private TenantConcurrencyManager concurrencyManager;

    @Mock
    private SystemBackpressureMonitor backpressureMonitor;

    private TaskAdmissionController admissionController;

    @BeforeEach
    void setUp() {
        admissionController = new TaskAdmissionController(
                policyRepository,
                rateLimiter,
                concurrencyManager,
                backpressureMonitor,
                taskRepository
        );
    }

    private Task createTask(String tenantId) {
        return Task.builder()
                .taskId(UUID.randomUUID())
                .tenantId(tenantId)
                .taskType("ETL_JOB")
                .payload("{}")
                .priority(TaskPriority.NORMAL)
                .status(TaskStatus.QUEUED)
                .createdAt(Instant.now())
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    @Test
    @DisplayName("Admission Decision: ADMITTED when all quotas and capacity checks pass")
    void testAdmittedDecision() {
        Task task = createTask("tenant-alpha");
        TenantExecutionPolicy policy = TenantExecutionPolicy.builder()
                .tenantId("tenant-alpha")
                .maxQueuedTasks(100)
                .maxConcurrentTasks(10)
                .tasksPerSecond(50.0)
                .burstCapacity(100)
                .build();

        when(policyRepository.getEffectivePolicy("tenant-alpha")).thenReturn(policy);
        when(backpressureMonitor.isUnderBackpressure()).thenReturn(false);
        when(concurrencyManager.canAdmit("tenant-alpha", 10)).thenReturn(true);
        when(rateLimiter.tryAcquire("tenant-alpha", policy)).thenReturn(true);

        AdmissionDecision decision = admissionController.evaluateScheduling(task);

        assertThat(decision.isAdmitted()).isTrue();
        assertThat(decision.status()).isEqualTo(AdmissionStatus.ADMITTED);
        assertThat(decision.reason().toLowerCase()).contains("admitted");
    }

    @Test
    @DisplayName("Admission Decision: TENANT_QUEUE_LIMIT rejected at submission boundary")
    void testTenantQueueLimitExceeded() {
        Task task = createTask("tenant-busy");
        TenantExecutionPolicy policy = TenantExecutionPolicy.builder()
                .tenantId("tenant-busy")
                .maxQueuedTasks(5)
                .build();

        when(policyRepository.getEffectivePolicy("tenant-busy")).thenReturn(policy);
        when(taskRepository.countByTenantAndStatus("tenant-busy", TaskStatus.QUEUED)).thenReturn(5);

        AdmissionDecision decision = admissionController.evaluateSubmission(task);

        assertThat(decision.isAdmitted()).isFalse();
        assertThat(decision.status()).isEqualTo(AdmissionStatus.TENANT_QUEUE_LIMIT);
        assertThat(decision.reason()).contains("queue quota exceeded");
    }

    @Test
    @DisplayName("Admission Decision: RATE_LIMITED when rate limit tokens exhausted")
    void testRateLimitedDecision() {
        Task task = createTask("tenant-throttled");
        TenantExecutionPolicy policy = TenantExecutionPolicy.builder()
                .tenantId("tenant-throttled")
                .tasksPerSecond(10.0)
                .burstCapacity(20)
                .maxConcurrentTasks(50)
                .build();

        when(policyRepository.getEffectivePolicy("tenant-throttled")).thenReturn(policy);
        when(backpressureMonitor.isUnderBackpressure()).thenReturn(false);
        when(concurrencyManager.canAdmit(eq("tenant-throttled"), anyInt())).thenReturn(true);
        when(rateLimiter.tryAcquire("tenant-throttled", policy)).thenReturn(false);

        AdmissionDecision decision = admissionController.evaluateScheduling(task);

        assertThat(decision.isAdmitted()).isFalse();
        assertThat(decision.status()).isEqualTo(AdmissionStatus.RATE_LIMITED);
        assertThat(decision.reason()).contains("rate limit exceeded");
    }

    @Test
    @DisplayName("Admission Decision: TENANT_CONCURRENCY_LIMIT when max concurrent tasks running")
    void testTenantConcurrencyLimitDecision() {
        Task task = createTask("tenant-at-capacity");
        TenantExecutionPolicy policy = TenantExecutionPolicy.builder()
                .tenantId("tenant-at-capacity")
                .maxConcurrentTasks(5)
                .build();

        when(policyRepository.getEffectivePolicy("tenant-at-capacity")).thenReturn(policy);
        when(backpressureMonitor.isUnderBackpressure()).thenReturn(false);
        when(concurrencyManager.canAdmit("tenant-at-capacity", 5)).thenReturn(false);

        AdmissionDecision decision = admissionController.evaluateScheduling(task);

        assertThat(decision.isAdmitted()).isFalse();
        assertThat(decision.status()).isEqualTo(AdmissionStatus.TENANT_CONCURRENCY_LIMIT);
        assertThat(decision.reason()).contains("concurrency limit");
    }

    @Test
    @DisplayName("Admission Decision: SYSTEM_BACKPRESSURE when workers are saturated")
    void testSystemBackpressureDecision() {
        Task task = createTask("tenant-any");
        when(backpressureMonitor.isUnderBackpressure()).thenReturn(true);

        AdmissionDecision decision = admissionController.evaluateScheduling(task);

        assertThat(decision.isAdmitted()).isFalse();
        assertThat(decision.status()).isEqualTo(AdmissionStatus.SYSTEM_BACKPRESSURE);
        assertThat(decision.reason()).contains("capacity saturated");
    }

    @Test
    @DisplayName("Admission Decision: INVALID_POLICY when task has empty or invalid tenant ID")
    void testInvalidPolicyDecision() {
        Task invalidTask = Task.builder()
                .taskId(UUID.randomUUID())
                .tenantId("")
                .taskType("TEST")
                .payload("{}")
                .status(TaskStatus.QUEUED)
                .build();

        when(backpressureMonitor.isUnderBackpressure()).thenReturn(false);

        AdmissionDecision decision = admissionController.evaluateScheduling(invalidTask);

        assertThat(decision.isAdmitted()).isFalse();
        assertThat(decision.status()).isEqualTo(AdmissionStatus.INVALID_POLICY);
        assertThat(decision.reason()).contains("Invalid tenant ID");
    }
}
