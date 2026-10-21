package com.chronoscore.coordinator.recovery.lease;

import com.chronoscore.common.domain.ExecutionAttempt;
import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.ExecutionAttemptStatus;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.coordinator.persistence.repository.ExecutionAttemptRepository;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JdbcTaskLeaseRepositoryIntegrationTest {

    @Autowired
    private TaskLeaseRepository taskLeaseRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ExecutionAttemptRepository attemptRepository;

    private UUID taskId;
    private UUID attemptId;
    private String workerId;

    @BeforeEach
    void setUp() {
        taskId = UUID.randomUUID();
        attemptId = UUID.randomUUID();
        workerId = "worker-integration-1";

        Task task = Task.builder()
                .taskId(taskId)
                .tenantId("default-tenant")
                .taskType("COMPUTE")
                .payload("{}")
                .status(TaskStatus.RUNNING)
                .currentAttempt(1)
                .maxAttempts(3)
                .createdAt(Instant.now())
                .build();
        taskRepository.save(task);

        ExecutionAttempt attempt = ExecutionAttempt.builder()
                .attemptId(attemptId)
                .taskId(taskId)
                .workerId(workerId)
                .attemptNumber(1)
                .status(ExecutionAttemptStatus.RUNNING)
                .startedAt(Instant.now())
                .build();
        attemptRepository.save(attempt);
    }

    @Test
    @DisplayName("save, findByTaskId, renewLease, and releaseLease execute successfully against database")
    void testLeaseLifecycleInDatabase() {
        Instant acquiredAt = Instant.now();
        Instant expiresAt = acquiredAt.plus(Duration.ofSeconds(30));

        TaskExecutionLease lease = TaskExecutionLease.builder()
                .taskId(taskId)
                .attemptId(attemptId)
                .workerId(workerId)
                .attemptNumber(1)
                .leaseAcquiredAt(acquiredAt)
                .leaseExpiresAt(expiresAt)
                .leaderEpoch(42L)
                .version(0L)
                .build();

        taskLeaseRepository.save(lease);

        Optional<TaskExecutionLease> loaded = taskLeaseRepository.findByTaskId(taskId);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getTaskId()).isEqualTo(taskId);
        assertThat(loaded.get().getAttemptId()).isEqualTo(attemptId);
        assertThat(loaded.get().getWorkerId()).isEqualTo(workerId);
        assertThat(loaded.get().getLeaderEpoch()).isEqualTo(42L);

        // Renew lease
        Instant newExpiresAt = expiresAt.plus(Duration.ofSeconds(60));
        boolean renewed = taskLeaseRepository.renewLease(taskId, newExpiresAt);
        assertThat(renewed).isTrue();

        Optional<TaskExecutionLease> updated = taskLeaseRepository.findByTaskId(taskId);
        assertThat(updated).isPresent();
        assertThat(updated.get().getVersion()).isEqualTo(1L);

        // Delete lease
        boolean deleted = taskLeaseRepository.releaseLease(taskId);
        assertThat(deleted).isTrue();
        assertThat(taskLeaseRepository.findByTaskId(taskId)).isEmpty();
    }

    @Test
    @DisplayName("findExpiredLeases returns leases whose expiration is before or at threshold")
    void testFindExpiredLeases() {
        Instant past = Instant.now().minus(Duration.ofMinutes(5));
        TaskExecutionLease expired = TaskExecutionLease.builder()
                .taskId(taskId)
                .attemptId(attemptId)
                .workerId(workerId)
                .attemptNumber(1)
                .leaseAcquiredAt(past.minus(Duration.ofMinutes(1)))
                .leaseExpiresAt(past)
                .leaderEpoch(1L)
                .version(0L)
                .build();

        taskLeaseRepository.save(expired);

        List<TaskExecutionLease> expiredList = taskLeaseRepository.findExpiredLeases(Instant.now(), 10);
        assertThat(expiredList).isNotEmpty();
        assertThat(expiredList.stream().anyMatch(l -> l.getTaskId().equals(taskId))).isTrue();
    }
}
