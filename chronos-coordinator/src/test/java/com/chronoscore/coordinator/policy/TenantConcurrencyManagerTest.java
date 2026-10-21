package com.chronoscore.coordinator.policy;

import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Test suite verifying atomic tenant concurrency limits, safe release,
 * race prevention, and database reconciliation (Phase 09 Section 11, 12, 35, 41).
 */
@ExtendWith(MockitoExtension.class)
class TenantConcurrencyManagerTest {

    @Mock
    private TaskRepository taskRepository;

    private TenantConcurrencyManager manager;

    @BeforeEach
    void setUp() {
        // null RedisTemplate falls back to atomic in-memory tracking
        manager = new TenantConcurrencyManager(null, taskRepository);
    }

    @Test
    @DisplayName("Concurrency: limit = 2 with 5 tasks -> exactly 2 admitted, 3 held")
    void testConcurrencyLimitEnforcement() {
        String tenantId = "tenant-concurrency";
        int limit = 2;

        UUID task1 = UUID.randomUUID();
        UUID task2 = UUID.randomUUID();
        UUID task3 = UUID.randomUUID();
        UUID task4 = UUID.randomUUID();
        UUID task5 = UUID.randomUUID();

        // 1. Submit task 1 & 2 -> should be admitted
        assertThat(manager.tryAcquire(tenantId, task1, limit)).isTrue();
        assertThat(manager.tryAcquire(tenantId, task2, limit)).isTrue();

        // 2. Submit task 3, 4, 5 -> must be rejected/held
        assertThat(manager.tryAcquire(tenantId, task3, limit)).isFalse();
        assertThat(manager.tryAcquire(tenantId, task4, limit)).isFalse();
        assertThat(manager.tryAcquire(tenantId, task5, limit)).isFalse();

        assertThat(manager.getRunningCount(tenantId)).isEqualTo(2);

        // 3. Task 1 completes -> release slot
        manager.release(tenantId, task1);
        assertThat(manager.getRunningCount(tenantId)).isEqualTo(1);

        // 4. Now task 3 can be admitted!
        assertThat(manager.tryAcquire(tenantId, task3, limit)).isTrue();
        assertThat(manager.getRunningCount(tenantId)).isEqualTo(2);

        // 5. Subsequent tasks still rejected
        assertThat(manager.tryAcquire(tenantId, task4, limit)).isFalse();
    }

    @Test
    @DisplayName("Concurrency: Concurrent acquisitions by 10 threads for limit = 3 -> exactly 3 succeed")
    void testConcurrentRaceSafety() throws Exception {
        String tenantId = "tenant-race";
        int limit = 3;
        int threadCount = 10;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    UUID taskId = UUID.randomUUID();
                    if (manager.tryAcquire(tenantId, taskId, limit)) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        startLatch.countDown();
        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(successCount.get())
                .as("Under heavy race conditions, exactly %d tasks must be admitted", limit)
                .isEqualTo(limit);
        assertThat(manager.getRunningCount(tenantId)).isEqualTo(limit);
    }

    @Test
    @DisplayName("Failover Reconciliation: Syncs tracking counters with PostgreSQL countRunningByTenant")
    void testReconciliationWithDurableState() {
        String tenantId = "tenant-failover";

        // Suppose in-memory has 10 leaked running tasks from a crashed coordinator
        for (int i = 0; i < 10; i++) {
            manager.tryAcquire(tenantId, UUID.randomUUID(), 20);
        }
        assertThat(manager.getRunningCount(tenantId)).isEqualTo(10);

        // Database only has 2 tasks actually executing
        com.chronoscore.common.domain.Task task1 = com.chronoscore.common.domain.Task.builder()
                .taskId(UUID.randomUUID())
                .tenantId(tenantId)
                .taskType("TASK")
                .payload("{}")
                .status(com.chronoscore.common.enums.TaskStatus.RUNNING)
                .build();
        com.chronoscore.common.domain.Task task2 = com.chronoscore.common.domain.Task.builder()
                .taskId(UUID.randomUUID())
                .tenantId(tenantId)
                .taskType("TASK")
                .payload("{}")
                .status(com.chronoscore.common.enums.TaskStatus.RUNNING)
                .build();

        when(taskRepository.findByTenantAndStatus(org.mockito.ArgumentMatchers.eq(tenantId),
                org.mockito.ArgumentMatchers.eq(com.chronoscore.common.enums.TaskStatus.DISPATCHED),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(java.util.Collections.emptyList());
        when(taskRepository.findByTenantAndStatus(org.mockito.ArgumentMatchers.eq(tenantId),
                org.mockito.ArgumentMatchers.eq(com.chronoscore.common.enums.TaskStatus.RUNNING),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(task1, task2));

        manager.reconcileWithDurableState(tenantId);

        // Counter is reset/reconciled back to database truth
        assertThat(manager.getRunningCount(tenantId)).isEqualTo(2);
    }
}
