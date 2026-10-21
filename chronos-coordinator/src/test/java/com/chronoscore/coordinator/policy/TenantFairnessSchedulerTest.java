package com.chronoscore.coordinator.policy;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import com.chronoscore.coordinator.scheduler.SchedulerProperties;
import com.chronoscore.coordinator.scheduler.SchedulingPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Test suite verifying tenant-aware fair scheduling, round-robin interleaving,
 * priority precedence, and anti-starvation aging guarantees (Phase 09 Section 6, 7, 8, 39).
 */
@ExtendWith(MockitoExtension.class)
class TenantFairnessSchedulerTest {

    @Mock
    private TaskRepository taskRepository;

    private SchedulerProperties properties;
    private SchedulingPolicy policy;

    @BeforeEach
    void setUp() {
        properties = new SchedulerProperties();
        properties.setBatchSize(20);
        properties.setCandidatePoolMultiplier(4); // Pool of up to 80 candidate tasks
        properties.setAgingThreshold(Duration.ofSeconds(30));
        properties.setStarvationBatchRatio(0.2); // 20% starved quota (4 tasks)

        policy = new SchedulingPolicy(taskRepository, properties);
    }

    private Task createTask(String tenantId, TaskPriority priority, Instant createdAt) {
        return Task.builder()
                .taskId(UUID.randomUUID())
                .tenantId(tenantId)
                .taskType("TEST_JOB")
                .payload("{}")
                .priority(priority)
                .status(TaskStatus.QUEUED)
                .createdAt(createdAt)
                .scheduledAt(createdAt)
                .maxAttempts(3)
                .currentAttempt(0)
                .build();
    }

    @Test
    @DisplayName("Case 1: Monopolizing Tenant A (1000 tasks) vs Small Tenant B (10 tasks) - B receives fair scheduling")
    void testTenantFairnessMonopolizationPrevention() {
        Instant now = Instant.now();

        // Simulate Tenant A having 70 tasks in candidate window and Tenant B having 10 tasks
        List<Task> candidatePool = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            candidatePool.add(createTask("tenant-A", TaskPriority.HIGH, now.minusSeconds(10)));
        }
        for (int i = 0; i < 10; i++) {
            candidatePool.add(createTask("tenant-B", TaskPriority.HIGH, now.minusSeconds(10)));
        }

        when(taskRepository.findStarvedTasks(any(), any(), anyInt())).thenReturn(Collections.emptyList());
        when(taskRepository.findSchedulableTasks(any(), anyInt())).thenReturn(candidatePool);

        List<Task> selected = policy.selectCandidateTasks(now);

        assertThat(selected).hasSize(20);

        long tenantACount = selected.stream().filter(t -> "tenant-A".equals(t.getTenantId())).count();
        long tenantBCount = selected.stream().filter(t -> "tenant-B".equals(t.getTenantId())).count();

        // Round-robin interleaving ensures both tenants get equal shares of the batch
        assertThat(tenantACount).isEqualTo(10);
        assertThat(tenantBCount).isEqualTo(10);

        // Verify round-robin alternating order in the batch
        for (int i = 0; i < selected.size(); i++) {
            String expectedTenant = (i % 2 == 0) ? "tenant-A" : "tenant-B";
            assertThat(selected.get(i).getTenantId()).isEqualTo(expectedTenant);
        }
    }

    @Test
    @DisplayName("Case 2: Multiple tenants under equal load - No tenant is systematically starved")
    void testMultipleTenantsEqualLoadFairDistribution() {
        Instant now = Instant.now();
        List<Task> candidatePool = new ArrayList<>();

        // 4 tenants each submitting 20 tasks
        String[] tenants = {"tenant-alpha", "tenant-beta", "tenant-gamma", "tenant-delta"};
        for (String tenant : tenants) {
            for (int i = 0; i < 20; i++) {
                candidatePool.add(createTask(tenant, TaskPriority.NORMAL, now.minusSeconds(5)));
            }
        }

        when(taskRepository.findStarvedTasks(any(), any(), anyInt())).thenReturn(Collections.emptyList());
        when(taskRepository.findSchedulableTasks(any(), anyInt())).thenReturn(candidatePool);

        List<Task> selected = policy.selectCandidateTasks(now);

        assertThat(selected).hasSize(20);

        Map<String, Integer> counts = new HashMap<>();
        for (Task t : selected) {
            counts.put(t.getTenantId(), counts.getOrDefault(t.getTenantId(), 0) + 1);
        }

        // Each of the 4 tenants must receive exactly 5 slots in the 20-task batch
        for (String tenant : tenants) {
            assertThat(counts.get(tenant))
                    .as("Tenant %s must receive equal batch representation", tenant)
                    .isEqualTo(5);
        }
    }

    @Test
    @DisplayName("Case 3: High-priority from Tenant B vs Low-priority backlog from Tenant A - Priority respected per tenant")
    void testPriorityAndFairnessInteraction() {
        Instant now = Instant.now();

        List<Task> candidatePool = new ArrayList<>();
        // Tenant A has 5 CRITICAL and 10 LOW tasks
        for (int i = 0; i < 5; i++) {
            candidatePool.add(createTask("tenant-A", TaskPriority.CRITICAL, now.minusSeconds(10)));
        }
        for (int i = 0; i < 10; i++) {
            candidatePool.add(createTask("tenant-A", TaskPriority.LOW, now.minusSeconds(10)));
        }

        // Tenant B has 5 HIGH and 5 NORMAL tasks
        for (int i = 0; i < 5; i++) {
            candidatePool.add(createTask("tenant-B", TaskPriority.HIGH, now.minusSeconds(10)));
        }
        for (int i = 0; i < 5; i++) {
            candidatePool.add(createTask("tenant-B", TaskPriority.NORMAL, now.minusSeconds(10)));
        }

        when(taskRepository.findStarvedTasks(any(), any(), anyInt())).thenReturn(Collections.emptyList());
        when(taskRepository.findSchedulableTasks(any(), anyInt())).thenReturn(candidatePool);

        List<Task> selected = policy.selectCandidateTasks(now);

        assertThat(selected).hasSize(20);

        // Verify that within Tenant A's interleaved tasks, CRITICAL appears before LOW
        List<TaskPriority> tenantAPriorities = selected.stream()
                .filter(t -> "tenant-A".equals(t.getTenantId()))
                .map(Task::getPriority)
                .toList();

        assertThat(tenantAPriorities.subList(0, 5)).allMatch(p -> p == TaskPriority.CRITICAL);
        assertThat(tenantAPriorities.subList(5, 10)).allMatch(p -> p == TaskPriority.LOW);

        // Verify that within Tenant B's interleaved tasks, HIGH appears before NORMAL
        List<TaskPriority> tenantBPriorities = selected.stream()
                .filter(t -> "tenant-B".equals(t.getTenantId()))
                .map(Task::getPriority)
                .toList();

        assertThat(tenantBPriorities.subList(0, 5)).allMatch(p -> p == TaskPriority.HIGH);
        assertThat(tenantBPriorities.subList(5, 10)).allMatch(p -> p == TaskPriority.NORMAL);
    }

    @Test
    @DisplayName("Starvation Aging: Starved tasks from any tenant always preempt regular priority queue")
    void testStarvationAgingPreemptsRegularQueue() {
        Instant now = Instant.now();

        // 4 starved tasks from Tenant A (queued 60s ago > 30s threshold)
        List<Task> starvedTasks = List.of(
                createTask("tenant-A", TaskPriority.LOW, now.minusSeconds(60)),
                createTask("tenant-A", TaskPriority.LOW, now.minusSeconds(55)),
                createTask("tenant-B", TaskPriority.LOW, now.minusSeconds(50)),
                createTask("tenant-B", TaskPriority.LOW, now.minusSeconds(45))
        );

        // 20 fresh CRITICAL tasks
        List<Task> freshCritical = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            freshCritical.add(createTask("tenant-C", TaskPriority.CRITICAL, now.minusSeconds(1)));
        }

        when(taskRepository.findStarvedTasks(any(), any(), anyInt())).thenReturn(starvedTasks);
        when(taskRepository.findSchedulableTasks(any(), anyInt())).thenReturn(freshCritical);

        List<Task> selected = policy.selectCandidateTasks(now);

        assertThat(selected).hasSize(20);

        // First 4 slots are reserved for starved tasks regardless of LOW priority
        List<Task> head = selected.subList(0, 4);
        assertThat(head).allMatch(t -> t.getPriority() == TaskPriority.LOW);

        // Remaining 16 slots are filled by fresh CRITICAL tasks
        List<Task> tail = selected.subList(4, 20);
        assertThat(tail).allMatch(t -> t.getPriority() == TaskPriority.CRITICAL);
    }
}
