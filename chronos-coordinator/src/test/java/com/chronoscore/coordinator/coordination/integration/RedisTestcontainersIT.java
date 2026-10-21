package com.chronoscore.coordinator.coordination.integration;

import com.chronoscore.coordinator.coordination.CoordinationProperties;
import com.chronoscore.coordinator.coordination.RedisKeyFactory;
import com.chronoscore.coordinator.coordination.fencing.DefaultFencingTokenService;
import com.chronoscore.coordinator.coordination.fencing.FencingTokenService;
import com.chronoscore.coordinator.coordination.leader.LeaderElectionService;
import com.chronoscore.coordinator.coordination.leader.RedisLeaderElectionService;
import com.chronoscore.coordinator.coordination.lock.DistributedLockService;
import com.chronoscore.coordinator.coordination.lock.LockHandle;
import com.chronoscore.coordinator.coordination.lock.RedisDistributedLockService;
import com.chronoscore.coordinator.coordination.model.FencingToken;
import com.chronoscore.coordinator.coordination.model.LeadershipState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test running against a real Redis 7.2 container via Testcontainers.
 * <p>
 * Validates real Redis atomic SET NX EX, Lua script execution for lease renewal/release,
 * atomic INCR for leader epochs, and distributed task lock semantics.
 * <p>
 * Conditionally executes when a local Docker daemon is available.
 */
@SpringBootTest
@Testcontainers
@EnabledIf("isDockerAvailable")
class RedisTestcontainersIT {

    private static final Logger log = LoggerFactory.getLogger(RedisTestcontainersIT.class);

    private static final int REDIS_PORT = 6379;

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(REDIS_PORT);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (isDockerAvailable()) {
            registry.add("spring.data.redis.host", redisContainer::getHost);
            registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(REDIS_PORT));
            registry.add("chronos.coordination.enabled", () -> "true");
        }
    }

    static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            log.info("Docker daemon not available; Testcontainers Redis tests will be skipped: {}", t.getMessage());
            return false;
        }
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CoordinationProperties coordinationProperties;

    @Autowired
    private LeaderElectionService leaderElectionService;

    @Autowired
    private DistributedLockService distributedLockService;

    @Autowired
    private FencingTokenService fencingTokenService;

    @Test
    @DisplayName("Testcontainers: verifies real Redis leader election, epoch increment, and safe release")
    void testRealRedisLeaderElectionLifecycle() {
        // Try acquiring leadership
        boolean acquired = leaderElectionService.tryAcquireLeadership();
        assertThat(acquired).isTrue();
        assertThat(leaderElectionService.isLeader()).isTrue();
        assertThat(leaderElectionService.getCurrentEpoch()).isGreaterThanOrEqualTo(1L);

        // Verify key exists directly in Redis with TTL
        String lockVal = redisTemplate.opsForValue().get(RedisKeyFactory.leaderLockKey());
        assertThat(lockVal).isEqualTo(coordinationProperties.getCoordinatorId());

        Long ttl = redisTemplate.getExpire(RedisKeyFactory.leaderLockKey());
        assertThat(ttl).isNotNull().isPositive();

        // Verify leader renewal via Lua script
        boolean renewed = leaderElectionService.renewLease();
        assertThat(renewed).isTrue();

        // Verify fencing token generation
        FencingToken fencingToken = fencingTokenService.getCurrentFencingToken();
        assertThat(fencingToken.isValid()).isTrue();
        assertThat(fencingToken.getEpoch()).isEqualTo(leaderElectionService.getCurrentEpoch());

        // Safe release via Lua script
        boolean released = leaderElectionService.releaseLeadership();
        assertThat(released).isTrue();
        assertThat(leaderElectionService.isLeader()).isFalse();

        // Redis lock key must be removed
        assertThat(redisTemplate.hasKey(RedisKeyFactory.leaderLockKey())).isFalse();
    }

    @Test
    @DisplayName("Testcontainers: verifies real Redis task lock mutual exclusion and Lua safe release")
    void testRealRedisTaskLocks() {
        String resourceId = "real-task-lock-it-1";

        // Acquire lock
        Optional<LockHandle> handleOpt = distributedLockService.tryAcquire(resourceId, Duration.ofSeconds(10));
        assertThat(handleOpt).isPresent();
        LockHandle handle = handleOpt.get();

        // Second acquisition attempt on same resource must fail
        Optional<LockHandle> conflictOpt = distributedLockService.tryAcquire(resourceId, Duration.ofSeconds(10));
        assertThat(conflictOpt).isEmpty();

        // Verify owner check
        assertThat(distributedLockService.isOwner(handle)).isTrue();

        // Safe release via Lua script
        boolean released = distributedLockService.release(handle);
        assertThat(released).isTrue();

        // After release, acquisition succeeds again
        Optional<LockHandle> reacquireOpt = distributedLockService.tryAcquire(resourceId, Duration.ofSeconds(10));
        assertThat(reacquireOpt).isPresent();
        distributedLockService.release(reacquireOpt.get());
    }
}
