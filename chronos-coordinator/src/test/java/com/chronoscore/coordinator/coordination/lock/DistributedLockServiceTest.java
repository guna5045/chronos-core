package com.chronoscore.coordinator.coordination.lock;

import com.chronoscore.coordinator.coordination.CoordinationProperties;
import com.chronoscore.coordinator.coordination.RedisKeyFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DistributedLockServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private CoordinationProperties properties;
    private RedisDistributedLockService lockService;

    @BeforeEach
    void setUp() {
        properties = new CoordinationProperties();
        properties.setCoordinatorId("coordinator-01");
        properties.setTaskLockTtl(Duration.ofSeconds(30));

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lockService = new RedisDistributedLockService(redisTemplate, properties);
    }

    @Test
    @DisplayName("tryAcquire: returns LockHandle with generated token and TTL when Redis key is absent")
    void testTryAcquireSuccess() {
        String resource = "task-1001";
        String expectedKey = RedisKeyFactory.taskLockKey(resource);

        when(valueOperations.setIfAbsent(eq(expectedKey), any(String.class), eq(Duration.ofSeconds(30))))
                .thenReturn(Boolean.TRUE);

        Optional<LockHandle> handleOpt = lockService.tryAcquire(resource, Duration.ofSeconds(30));

        assertThat(handleOpt).isPresent();
        LockHandle handle = handleOpt.get();
        assertThat(handle.getResource()).isEqualTo(resource);
        assertThat(handle.getOwnerToken()).isNotNull().isNotBlank();
        assertThat(handle.getTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(handle.isExpired()).isFalse();
    }

    @Test
    @DisplayName("tryAcquire: returns empty Optional when lock is already held by another owner")
    void testTryAcquireConflict() {
        String resource = "task-1002";
        String expectedKey = RedisKeyFactory.taskLockKey(resource);

        when(valueOperations.setIfAbsent(eq(expectedKey), any(String.class), eq(Duration.ofSeconds(30))))
                .thenReturn(Boolean.FALSE);

        Optional<LockHandle> handleOpt = lockService.tryAcquire(resource, Duration.ofSeconds(30));

        assertThat(handleOpt).isEmpty();
    }

    @Test
    @DisplayName("release: successfully releases lock when Lua script confirms ownership")
    void testReleaseSuccess() {
        String resource = "task-1003";
        String token = "owner-token-abc";
        LockHandle handle = new LockHandle(resource, token, Instant.now(), Duration.ofSeconds(30));
        String lockKey = RedisKeyFactory.taskLockKey(resource);

        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList(lockKey)), eq(token)))
                .thenReturn(1L);

        boolean released = lockService.release(handle);
        assertThat(released).isTrue();
    }

    @Test
    @DisplayName("release: fails and returns false when caller does not own the lock in Redis")
    void testReleaseWrongOwner() {
        String resource = "task-1004";
        String staleToken = "stale-owner-token";
        LockHandle handle = new LockHandle(resource, staleToken, Instant.now(), Duration.ofSeconds(30));
        String lockKey = RedisKeyFactory.taskLockKey(resource);

        // Lua script returns 0 when token does not match
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList(lockKey)), eq(staleToken)))
                .thenReturn(0L);

        boolean released = lockService.release(handle);
        assertThat(released).isFalse();
    }

    @Test
    @DisplayName("renew: extends lock lease when owner matches")
    void testRenewSuccess() {
        String resource = "task-1005";
        String token = "token-xyz";
        LockHandle handle = new LockHandle(resource, token, Instant.now(), Duration.ofSeconds(30));
        String lockKey = RedisKeyFactory.taskLockKey(resource);

        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList(lockKey)), eq(token), eq("30")))
                .thenReturn(1L);

        boolean renewed = lockService.renew(handle, Duration.ofSeconds(30));
        assertThat(renewed).isTrue();
    }

    @Test
    @DisplayName("renew: returns false when ownership check fails")
    void testRenewOwnershipFailed() {
        String resource = "task-1006";
        String token = "token-xyz";
        LockHandle handle = new LockHandle(resource, token, Instant.now(), Duration.ofSeconds(30));
        String lockKey = RedisKeyFactory.taskLockKey(resource);

        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList(lockKey)), eq(token), eq("30")))
                .thenReturn(0L);

        boolean renewed = lockService.renew(handle, Duration.ofSeconds(30));
        assertThat(renewed).isFalse();
    }

    @Test
    @DisplayName("isOwner: returns true if Redis holds identical owner token, false otherwise")
    void testIsOwner() {
        String resource = "task-1007";
        String token = "token-owner-1";
        LockHandle handle = new LockHandle(resource, token, Instant.now(), Duration.ofSeconds(30));
        String lockKey = RedisKeyFactory.taskLockKey(resource);

        when(valueOperations.get(lockKey)).thenReturn(token);
        assertThat(lockService.isOwner(handle)).isTrue();

        when(valueOperations.get(lockKey)).thenReturn("another-token");
        assertThat(lockService.isOwner(handle)).isFalse();

        when(valueOperations.get(lockKey)).thenReturn(null);
        assertThat(lockService.isOwner(handle)).isFalse();
    }

    @Test
    @DisplayName("LockHandle: isExpired accurately evaluates expiration based on time")
    void testLockHandleExpiration() {
        Instant past = Instant.now().minusSeconds(60);
        LockHandle expiredHandle = new LockHandle("task-exp", "tok", past, Duration.ofSeconds(10));
        assertThat(expiredHandle.isExpired()).isTrue();

        Instant future = Instant.now();
        LockHandle activeHandle = new LockHandle("task-act", "tok", future, Duration.ofMinutes(10));
        assertThat(activeHandle.isExpired()).isFalse();
    }
}
