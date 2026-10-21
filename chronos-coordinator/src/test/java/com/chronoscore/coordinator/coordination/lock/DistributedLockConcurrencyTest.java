package com.chronoscore.coordinator.coordination.lock;

import com.chronoscore.coordinator.coordination.CoordinationProperties;
import com.chronoscore.coordinator.coordination.RedisKeyFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Concurrency tests for distributed task locks verifying mutual exclusion,
 * unique owner tokens, atomic acquisition, and stale owner release protection.
 */
class DistributedLockConcurrencyTest {

    private final ConcurrentHashMap<String, String> redisStorage = new ConcurrentHashMap<>();

    private StringRedisTemplate createMockRedisTemplate() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);

        lenient().when(template.opsForValue()).thenReturn(ops);

        // SET NX EX
        lenient().when(ops.setIfAbsent(any(), any(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    String val = invocation.getArgument(1);
                    return redisStorage.putIfAbsent(key, val) == null;
                });

        // GET
        lenient().when(ops.get(any()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    return redisStorage.get(key);
                });

        // Lua script execute for release: if GET(key) == ownerToken then DEL(key)
        lenient().when(template.execute(any(RedisScript.class), any(List.class), any()))
                .thenAnswer(invocation -> {
                    List<String> keys = invocation.getArgument(1);
                    String expectedOwner = invocation.getArgument(2);
                    String key = keys.get(0);

                    String current = redisStorage.get(key);
                    if (expectedOwner.equals(current)) {
                        redisStorage.remove(key);
                        return 1L;
                    }
                    return 0L;
                });

        return template;
    }

    @BeforeEach
    void setUp() {
        redisStorage.clear();
    }

    @Test
    @DisplayName("Concurrent lock acquisition: exactly one thread acquires task lock among N competing threads")
    void testConcurrentTaskLockAcquisition() throws InterruptedException {
        int threadCount = 10;
        String resourceId = "task-uuid-concurrent-1";
        String lockKey = RedisKeyFactory.taskLockKey(resourceId);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        CoordinationProperties props = new CoordinationProperties();
        props.setTaskLockTtl(Duration.ofSeconds(10));

        RedisDistributedLockService lockService = new RedisDistributedLockService(createMockRedisTemplate(), props);

        AtomicInteger acquiredCount = new AtomicInteger(0);
        AtomicReference<LockHandle> winningHandle = new AtomicReference<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Optional<LockHandle> handleOpt = lockService.tryAcquire(resourceId, Duration.ofSeconds(10));
                    if (handleOpt.isPresent()) {
                        acquiredCount.incrementAndGet();
                        winningHandle.set(handleOpt.get());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = finishLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(acquiredCount.get()).isEqualTo(1);
        assertThat(winningHandle.get()).isNotNull();
        assertThat(winningHandle.get().getOwnerToken()).isNotBlank();
        assertThat(redisStorage.get(lockKey)).isEqualTo(winningHandle.get().getOwnerToken());

        // Winner safely releases lock
        boolean released = lockService.release(winningHandle.get());
        assertThat(released).isTrue();
        assertThat(redisStorage.get(lockKey)).isNull();
    }

    @Test
    @DisplayName("Stale owner protection: previous owner cannot release a newly acquired lock after expiration")
    void testStaleOwnerCannotReleaseNewLock() {
        String resourceId = "task-uuid-race-2";
        String lockKey = RedisKeyFactory.taskLockKey(resourceId);

        CoordinationProperties props = new CoordinationProperties();
        props.setTaskLockTtl(Duration.ofSeconds(10));
        RedisDistributedLockService lockService = new RedisDistributedLockService(createMockRedisTemplate(), props);

        // Worker 1 acquires lock
        Optional<LockHandle> handle1Opt = lockService.tryAcquire(resourceId, Duration.ofSeconds(10));
        assertThat(handle1Opt).isPresent();
        LockHandle handle1 = handle1Opt.get();
        assertThat(redisStorage.get(lockKey)).isEqualTo(handle1.getOwnerToken());

        // Simulate TTL expiry in Redis: key disappears
        redisStorage.remove(lockKey);

        // Worker 2 acquires the lock on the same resource with new unique token
        Optional<LockHandle> handle2Opt = lockService.tryAcquire(resourceId, Duration.ofSeconds(10));
        assertThat(handle2Opt).isPresent();
        LockHandle handle2 = handle2Opt.get();
        assertThat(handle2.getOwnerToken()).isNotEqualTo(handle1.getOwnerToken());
        assertThat(redisStorage.get(lockKey)).isEqualTo(handle2.getOwnerToken());

        // Worker 1 attempts late release of handle1: must fail and NOT delete Worker 2's lock!
        boolean lateReleaseSuccess = lockService.release(handle1);
        assertThat(lateReleaseSuccess).isFalse();

        // Worker 2's lock is completely safe and intact in Redis
        assertThat(redisStorage.get(lockKey)).isEqualTo(handle2.getOwnerToken());
        assertThat(lockService.isOwner(handle2)).isTrue();
        assertThat(lockService.isOwner(handle1)).isFalse();

        // Worker 2 can release its own lock safely
        assertThat(lockService.release(handle2)).isTrue();
        assertThat(redisStorage.get(lockKey)).isNull();
    }
}
