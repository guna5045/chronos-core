package com.chronoscore.coordinator.coordination.leader;

import com.chronoscore.coordinator.coordination.CoordinationProperties;
import com.chronoscore.coordinator.coordination.RedisKeyFactory;
import com.chronoscore.coordinator.coordination.model.LeadershipState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Concurrency tests verifying multi-coordinator leader election, lease-based
 * mutual exclusion, monotonic epoch progression, and stale leader protection.
 */
class LeaderElectionConcurrencyTest {

    private final ConcurrentHashMap<String, String> redisStorage = new ConcurrentHashMap<>();
    private final AtomicLong redisEpochCounter = new AtomicLong(0);

    private StringRedisTemplate createMockRedisTemplate() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);

        lenient().when(template.opsForValue()).thenReturn(ops);

        // Mock SET NX EX
        lenient().when(ops.setIfAbsent(any(), any(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    String val = invocation.getArgument(1);
                    return redisStorage.putIfAbsent(key, val) == null;
                });

        // Mock GET
        lenient().when(ops.get(any()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    return redisStorage.get(key);
                });

        // Mock INCR
        lenient().when(ops.increment(any(String.class)))
                .thenAnswer(invocation -> redisEpochCounter.incrementAndGet());

        // Mock execute for Lua scripts (renewal & release)
        lenient().when(template.execute(any(RedisScript.class), any(List.class), any()))
                .thenAnswer(invocation -> {
                    List<String> keys = invocation.getArgument(1);
                    String expectedOwner = invocation.getArgument(2);
                    String key = keys.get(0);

                    // If release script (GET == owner -> DEL)
                    String current = redisStorage.get(key);
                    if (expectedOwner.equals(current)) {
                        redisStorage.remove(key);
                        return 1L;
                    }
                    return 0L;
                });

        lenient().when(template.execute(any(RedisScript.class), any(List.class), any(), any()))
                .thenAnswer(invocation -> {
                    List<String> keys = invocation.getArgument(1);
                    String expectedOwner = invocation.getArgument(2);
                    String key = keys.get(0);

                    // Renewal script (GET == owner -> EXPIRE)
                    String current = redisStorage.get(key);
                    if (expectedOwner.equals(current)) {
                        return 1L;
                    }
                    return 0L;
                });

        return template;
    }

    @BeforeEach
    void setUp() {
        redisStorage.clear();
        redisEpochCounter.set(0);
    }

    @Test
    @DisplayName("Concurrent acquisition: exactly one coordinator becomes leader among N concurrent instances")
    void testConcurrentLeaderAcquisition() throws InterruptedException {
        int coordinatorCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(coordinatorCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(coordinatorCount);

        List<RedisLeaderElectionService> services = new ArrayList<>();
        for (int i = 0; i < coordinatorCount; i++) {
            CoordinationProperties props = new CoordinationProperties();
            props.setCoordinatorId("coordinator-" + i);
            props.setLeaderLeaseDuration(Duration.ofSeconds(5));
            props.setLeaderRenewalInterval(Duration.ofMillis(1500));
            props.setEnabled(true);

            services.add(new RedisLeaderElectionService(createMockRedisTemplate(), props));
        }

        AtomicInteger leaderCount = new AtomicInteger(0);

        for (RedisLeaderElectionService service : services) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (service.tryAcquireLeadership()) {
                        leaderCount.incrementAndGet();
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
        assertThat(leaderCount.get()).isEqualTo(1);

        long activeLeaders = services.stream().filter(RedisLeaderElectionService::isLeader).count();
        long activeFollowers = services.stream()
                .filter(s -> s.getLeadershipIdentity().getState() == LeadershipState.FOLLOWER)
                .count();

        assertThat(activeLeaders).isEqualTo(1);
        assertThat(activeFollowers).isEqualTo(coordinatorCount - 1);
        assertThat(redisEpochCounter.get()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Follower isolation: follower cannot renew or release the active leader's lease")
    void testFollowerCannotRenewOrReleaseLeaderLease() {
        StringRedisTemplate templateA = createMockRedisTemplate();
        StringRedisTemplate templateB = createMockRedisTemplate();

        CoordinationProperties propsA = new CoordinationProperties();
        propsA.setCoordinatorId("coordinator-A");
        RedisLeaderElectionService serviceA = new RedisLeaderElectionService(templateA, propsA);

        CoordinationProperties propsB = new CoordinationProperties();
        propsB.setCoordinatorId("coordinator-B");
        RedisLeaderElectionService serviceB = new RedisLeaderElectionService(templateB, propsB);

        // Coordinator A acquires leadership
        assertThat(serviceA.tryAcquireLeadership()).isTrue();
        assertThat(serviceA.isLeader()).isTrue();

        // Coordinator B tries to acquire and fails
        assertThat(serviceB.tryAcquireLeadership()).isFalse();
        assertThat(serviceB.isLeader()).isFalse();

        // Coordinator B attempts to renew -> rejected
        assertThat(serviceB.renewLease()).isFalse();

        // Coordinator B attempts to release -> rejected
        assertThat(serviceB.releaseLeadership()).isFalse();

        // Verify Coordinator A is still leader in Redis and memory
        assertThat(redisStorage.get(RedisKeyFactory.leaderLockKey())).isEqualTo("coordinator-A");
        assertThat(serviceA.isLeader()).isTrue();
    }

    @Test
    @DisplayName("Stale leader: expired leader cannot delete or renew a new leader's lease after failover")
    void testStaleLeaderCannotDisruptNewLeader() {
        StringRedisTemplate templateA = createMockRedisTemplate();
        StringRedisTemplate templateB = createMockRedisTemplate();

        CoordinationProperties propsA = new CoordinationProperties();
        propsA.setCoordinatorId("coordinator-A");
        RedisLeaderElectionService serviceA = new RedisLeaderElectionService(templateA, propsA);

        CoordinationProperties propsB = new CoordinationProperties();
        propsB.setCoordinatorId("coordinator-B");
        RedisLeaderElectionService serviceB = new RedisLeaderElectionService(templateB, propsB);

        // Coordinator A becomes leader with epoch 1
        assertThat(serviceA.tryAcquireLeadership()).isTrue();
        assertThat(serviceA.getCurrentEpoch()).isEqualTo(1L);

        // Simulate network pause on Coordinator A: Redis lease expires
        redisStorage.remove(RedisKeyFactory.leaderLockKey());

        // Coordinator B acquires leadership during A's pause, epoch becomes 2
        assertThat(serviceB.tryAcquireLeadership()).isTrue();
        assertThat(serviceB.getCurrentEpoch()).isEqualTo(2L);
        assertThat(redisStorage.get(RedisKeyFactory.leaderLockKey())).isEqualTo("coordinator-B");

        // Coordinator A resumes from pause and attempts renewal: must fail and lose leadership
        assertThat(serviceA.renewLease()).isFalse();
        assertThat(serviceA.isLeader()).isFalse();
        assertThat(serviceA.getLeadershipIdentity().getState()).isEqualTo(LeadershipState.FOLLOWER);

        // Coordinator A attempts release: must fail and must NOT delete Coordinator B's lease
        assertThat(serviceA.releaseLeadership()).isFalse();
        assertThat(redisStorage.get(RedisKeyFactory.leaderLockKey())).isEqualTo("coordinator-B");

        // Coordinator B remains leader unharmed
        assertThat(serviceB.isLeader()).isTrue();
        assertThat(serviceB.renewLease()).isTrue();
    }
}
