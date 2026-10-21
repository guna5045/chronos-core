package com.chronoscore.coordinator.recovery.lease;

import com.chronoscore.coordinator.recovery.config.LeaseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionLeaseServiceTest {

    private LeaseProperties leaseProperties;
    private InMemoryTaskLeaseRepository repository;
    private ExecutionLeaseService leaseService;

    @BeforeEach
    void setUp() {
        leaseProperties = new LeaseProperties();
        leaseProperties.setDuration(Duration.ofSeconds(30));
        leaseProperties.setRenewalInterval(Duration.ofSeconds(10));

        repository = new InMemoryTaskLeaseRepository();
        leaseService = new ExecutionLeaseService(repository, leaseProperties);
    }

    @Test
    @DisplayName("acquireLease correctly creates lease with configured expiration duration")
    void testAcquireLease() {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        String workerId = "worker-1";

        Instant before = Instant.now();
        TaskExecutionLease lease = leaseService.acquireLease(taskId, attemptId, workerId, 1, 10L);
        Instant after = Instant.now();

        assertThat(lease.getTaskId()).isEqualTo(taskId);
        assertThat(lease.getAttemptId()).isEqualTo(attemptId);
        assertThat(lease.getWorkerId()).isEqualTo(workerId);
        assertThat(lease.getAttemptNumber()).isEqualTo(1);
        assertThat(lease.getLeaderEpoch()).isEqualTo(10L);
        assertThat(lease.getVersion()).isEqualTo(0L);

        assertThat(lease.getLeaseExpiresAt()).isAfterOrEqualTo(before.plus(Duration.ofSeconds(30)));
        assertThat(lease.getLeaseExpiresAt()).isBeforeOrEqualTo(after.plus(Duration.ofSeconds(30)));
        assertThat(leaseService.getLease(taskId)).isPresent();
    }

    @Test
    @DisplayName("renewLease extends expiration and increments version")
    void testRenewLease() {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        leaseService.acquireLease(taskId, attemptId, "worker-1", 1, 10L);

        boolean renewed = leaseService.renewLease(taskId);

        assertThat(renewed).isTrue();
        TaskExecutionLease updated = leaseService.getLease(taskId).orElseThrow();
        assertThat(updated.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("renewLease returns false when lease does not exist")
    void testRenewLeaseNonExistent() {
        UUID nonExistentTaskId = UUID.randomUUID();
        boolean renewed = leaseService.renewLease(nonExistentTaskId);
        assertThat(renewed).isFalse();
    }

    @Test
    @DisplayName("releaseLease deletes lease and invalidates ownership")
    void testReleaseLease() {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        leaseService.acquireLease(taskId, attemptId, "worker-1", 1, 10L);

        boolean released = leaseService.releaseLease(taskId);
        assertThat(released).isTrue();
        assertThat(leaseService.getLease(taskId)).isEmpty();
    }

    @Test
    @DisplayName("getExpiredLeases returns leases that have exceeded expiresAt")
    void testGetExpiredLeases() {
        UUID taskId1 = UUID.randomUUID();
        UUID attemptId1 = UUID.randomUUID();
        UUID taskId2 = UUID.randomUUID();
        UUID attemptId2 = UUID.randomUUID();

        // Expired lease in past
        TaskExecutionLease expired = TaskExecutionLease.builder()
                .taskId(taskId1)
                .attemptId(attemptId1)
                .workerId("worker-1")
                .attemptNumber(1)
                .leaseAcquiredAt(Instant.now().minus(Duration.ofMinutes(2)))
                .leaseExpiresAt(Instant.now().minus(Duration.ofMinutes(1)))
                .leaderEpoch(1L)
                .version(0L)
                .build();
        repository.save(expired);

        // Active lease in future
        leaseService.acquireLease(taskId2, attemptId2, "worker-2", 1, 1L);

        List<TaskExecutionLease> expiredList = leaseService.getExpiredLeases(Instant.now(), 50);
        assertThat(expiredList).hasSize(1);
        assertThat(expiredList.get(0).getTaskId()).isEqualTo(taskId1);
    }

    @Test
    @DisplayName("getLeasesForWorker finds all leases belonging to a specific worker")
    void testGetLeasesForWorker() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID t3 = UUID.randomUUID();

        leaseService.acquireLease(t1, UUID.randomUUID(), "worker-A", 1, 1L);
        leaseService.acquireLease(t2, UUID.randomUUID(), "worker-A", 1, 1L);
        leaseService.acquireLease(t3, UUID.randomUUID(), "worker-B", 1, 1L);

        List<TaskExecutionLease> workerALeases = leaseService.getLeasesForWorker("worker-A");
        assertThat(workerALeases).hasSize(2);
        assertThat(workerALeases).extracting(TaskExecutionLease::getTaskId).containsExactlyInAnyOrder(t1, t2);
    }

    private static class InMemoryTaskLeaseRepository implements TaskLeaseRepository {
        private final Map<UUID, TaskExecutionLease> store = new ConcurrentHashMap<>();

        @Override
        public TaskExecutionLease save(TaskExecutionLease lease) {
            store.put(lease.getTaskId(), lease);
            return lease;
        }

        @Override
        public Optional<TaskExecutionLease> findByTaskId(UUID taskId) {
            return Optional.ofNullable(store.get(taskId));
        }

        @Override
        public List<TaskExecutionLease> findByWorkerId(String workerId) {
            return store.values().stream().filter(l -> l.getWorkerId().equals(workerId)).toList();
        }

        @Override
        public List<TaskExecutionLease> findExpiredLeases(Instant asOf, int limit) {
            return store.values().stream()
                    .filter(l -> l.getLeaseExpiresAt().isBefore(asOf) || l.getLeaseExpiresAt().equals(asOf))
                    .limit(limit)
                    .toList();
        }

        @Override
        public boolean renewLease(UUID taskId, Instant newExpiresAt) {
            TaskExecutionLease current = store.get(taskId);
            if (current == null) {
                return false;
            }
            TaskExecutionLease renewed = current.toBuilder()
                    .leaseExpiresAt(newExpiresAt)
                    .version(current.getVersion() + 1)
                    .build();
            store.put(taskId, renewed);
            return true;
        }

        @Override
        public boolean releaseLease(UUID taskId) {
            return store.remove(taskId) != null;
        }
    }
}
