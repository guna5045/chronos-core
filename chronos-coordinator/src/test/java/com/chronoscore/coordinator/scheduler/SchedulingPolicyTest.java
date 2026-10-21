package com.chronoscore.coordinator.scheduler;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchedulingPolicyTest {

    @Mock
    private TaskRepository taskRepository;

    private SchedulerProperties properties;
    private SchedulingPolicy policy;

    @BeforeEach
    void setUp() {
        properties = new SchedulerProperties();
        properties.setBatchSize(10);
        properties.setAgingThreshold(Duration.ofSeconds(30));
        properties.setStarvationBatchRatio(0.3); // 30% quota for starved tasks (3 tasks)

        policy = new SchedulingPolicy(taskRepository, properties);
    }

    private Task createTask(TaskPriority priority, Instant createdAt, Instant scheduledAt) {
        return Task.builder()
                .taskId(UUID.randomUUID())
                .taskType("COMPUTE")
                .payload("{}")
                .priority(priority)
                .status(TaskStatus.QUEUED)
                .tenantId("tenant-1")
                .createdAt(createdAt)
                .scheduledAt(scheduledAt)
                .timeout(Duration.ofSeconds(30))
                .maxAttempts(3)
                .currentAttempt(0)
                .version(0L)
                .build();
    }

    @Test
    @DisplayName("SchedulingPolicy queries schedulable tasks ordered by priority when no tasks are starved")
    void testNormalPriorityOrdering() {
        Instant now = Instant.now();
        List<Task> priorityTasks = List.of(
                createTask(TaskPriority.CRITICAL, now.minusSeconds(5), null),
                createTask(TaskPriority.HIGH, now.minusSeconds(10), null),
                createTask(TaskPriority.NORMAL, now.minusSeconds(15), null)
        );

        when(taskRepository.findStarvedTasks(eq(now), any(Duration.class), anyInt())).thenReturn(List.of());
        when(taskRepository.findSchedulableTasks(eq(now), anyInt())).thenReturn(priorityTasks);

        List<Task> selected = policy.selectCandidateTasks(now);

        assertThat(selected).hasSize(3);
        assertThat(selected.get(0).getPriority()).isEqualTo(TaskPriority.CRITICAL);
        assertThat(selected.get(1).getPriority()).isEqualTo(TaskPriority.HIGH);
        assertThat(selected.get(2).getPriority()).isEqualTo(TaskPriority.NORMAL);
    }

    @Test
    @DisplayName("Anti-starvation aging guarantees low-priority work is admitted under continuous high-priority load")
    void testStarvationPreventionUnderContinuousHighLoad() {
        Instant now = Instant.now();

        // 3 aged LOW tasks waiting > 30s
        List<Task> agedLowTasks = List.of(
                createTask(TaskPriority.LOW, now.minusSeconds(60), null),
                createTask(TaskPriority.LOW, now.minusSeconds(50), null),
                createTask(TaskPriority.LOW, now.minusSeconds(40), null)
        );

        // Continuous incoming stream of freshly created HIGH tasks
        List<Task> freshHighTasks = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            freshHighTasks.add(createTask(TaskPriority.HIGH, now.minusSeconds(1), null));
        }

        when(taskRepository.findStarvedTasks(eq(now), eq(Duration.ofSeconds(30)), eq(3)))
                .thenReturn(agedLowTasks);
        when(taskRepository.findSchedulableTasks(eq(now), eq(7)))
                .thenReturn(freshHighTasks);

        List<Task> selected = policy.selectCandidateTasks(now);

        assertThat(selected).hasSize(10);
        // Crucial invariant: The 3 aged LOW tasks are guaranteed admission at the head of the batch
        List<Task> firstThree = selected.subList(0, 3);
        assertThat(firstThree).allMatch(t -> t.getPriority() == TaskPriority.LOW);

        // The remaining 7 slots process the incoming HIGH priority tasks
        List<Task> remainingSeven = selected.subList(3, 10);
        assertThat(remainingSeven).allMatch(t -> t.getPriority() == TaskPriority.HIGH);
    }
}
