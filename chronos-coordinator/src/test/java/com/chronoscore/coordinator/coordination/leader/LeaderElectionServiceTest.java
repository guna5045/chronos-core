package com.chronoscore.coordinator.coordination.leader;

import com.chronoscore.coordinator.coordination.CoordinationProperties;
import com.chronoscore.coordinator.coordination.RedisKeyFactory;
import com.chronoscore.coordinator.coordination.model.LeaderIdentity;
import com.chronoscore.coordinator.coordination.model.LeadershipState;
import com.chronoscore.coordinator.coordination.model.LeadershipStateListener;
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
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeaderElectionServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private CoordinationProperties properties;
    private RedisLeaderElectionService electionService;

    private final String coordinatorId = "coord-alpha";

    @BeforeEach
    void setUp() {
        properties = new CoordinationProperties();
        properties.setCoordinatorId(coordinatorId);
        properties.setLeaderLeaseDuration(Duration.ofSeconds(5));
        properties.setLeaderRenewalInterval(Duration.ofMillis(1500));
        properties.setEnabled(true);

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        electionService = new RedisLeaderElectionService(redisTemplate, properties);
    }

    @Test
    @DisplayName("tryAcquireLeadership: acquires lease, increments epoch, and sets LEADER state")
    void testAcquireLeadershipSuccess() {
        String lockKey = RedisKeyFactory.leaderLockKey();
        String epochKey = RedisKeyFactory.leaderEpochKey();

        when(valueOperations.setIfAbsent(eq(lockKey), eq(coordinatorId), eq(Duration.ofSeconds(5))))
                .thenReturn(Boolean.TRUE);
        when(valueOperations.increment(epochKey)).thenReturn(1L);

        AtomicBoolean listenerNotified = new AtomicBoolean(false);
        AtomicReference<LeaderIdentity> acquiredIdentity = new AtomicReference<>();
        electionService.addListener(new LeadershipStateListener() {
            @Override
            public void onLeadershipAcquired(LeaderIdentity identity) {
                listenerNotified.set(true);
                acquiredIdentity.set(identity);
            }

            @Override
            public void onLeadershipLost(LeaderIdentity identity) {}
        });

        boolean acquired = electionService.tryAcquireLeadership();

        assertThat(acquired).isTrue();
        assertThat(electionService.isLeader()).isTrue();
        assertThat(electionService.getCurrentEpoch()).isEqualTo(1L);

        LeaderIdentity identity = electionService.getLeadershipIdentity();
        assertThat(identity.getCoordinatorId()).isEqualTo(coordinatorId);
        assertThat(identity.getLeaderEpoch()).isEqualTo(1L);
        assertThat(identity.getState()).isEqualTo(LeadershipState.LEADER);
        assertThat(identity.isLeaseValid()).isTrue();

        assertThat(listenerNotified.get()).isTrue();
        assertThat(acquiredIdentity.get().getLeaderEpoch()).isEqualTo(1L);
    }

    @Test
    @DisplayName("tryAcquireLeadership: returns false when lease is already held by another coordinator")
    void testAcquireLeadershipConflict() {
        String lockKey = RedisKeyFactory.leaderLockKey();

        when(valueOperations.setIfAbsent(eq(lockKey), eq(coordinatorId), eq(Duration.ofSeconds(5))))
                .thenReturn(Boolean.FALSE);

        boolean acquired = electionService.tryAcquireLeadership();

        assertThat(acquired).isFalse();
        assertThat(electionService.isLeader()).isFalse();
        assertThat(electionService.getCurrentEpoch()).isEqualTo(0L);
        assertThat(electionService.getLeadershipIdentity().getState()).isEqualTo(LeadershipState.FOLLOWER);
    }

    @Test
    @DisplayName("renewLease: extends lease TTL when caller is current verified leader in Redis")
    void testRenewLeaseSuccess() {
        // First acquire leadership
        String lockKey = RedisKeyFactory.leaderLockKey();
        when(valueOperations.setIfAbsent(eq(lockKey), eq(coordinatorId), eq(Duration.ofSeconds(5))))
                .thenReturn(Boolean.TRUE);
        when(valueOperations.increment(RedisKeyFactory.leaderEpochKey())).thenReturn(1L);
        electionService.tryAcquireLeadership();

        // Renew lease
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList(lockKey)), eq(coordinatorId), eq("5")))
                .thenReturn(1L);

        boolean renewed = electionService.renewLease();

        assertThat(renewed).isTrue();
        assertThat(electionService.isLeader()).isTrue();
        assertThat(electionService.getCurrentEpoch()).isEqualTo(1L);
    }

    @Test
    @DisplayName("renewLease: detects ownership loss, transitions to FOLLOWER, and notifies listener")
    void testRenewLeaseOwnershipLoss() {
        // Acquire leadership
        String lockKey = RedisKeyFactory.leaderLockKey();
        when(valueOperations.setIfAbsent(eq(lockKey), eq(coordinatorId), eq(Duration.ofSeconds(5))))
                .thenReturn(Boolean.TRUE);
        when(valueOperations.increment(RedisKeyFactory.leaderEpochKey())).thenReturn(2L);
        electionService.tryAcquireLeadership();

        AtomicBoolean lossNotified = new AtomicBoolean(false);
        electionService.addListener(new LeadershipStateListener() {
            @Override
            public void onLeadershipAcquired(LeaderIdentity identity) {}

            @Override
            public void onLeadershipLost(LeaderIdentity identity) {
                lossNotified.set(true);
            }
        });

        // Renewal Lua script returns 0 (ownership lost in Redis)
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList(lockKey)), eq(coordinatorId), eq("5")))
                .thenReturn(0L);

        boolean renewed = electionService.renewLease();

        assertThat(renewed).isFalse();
        assertThat(electionService.isLeader()).isFalse();
        assertThat(electionService.getLeadershipIdentity().getState()).isEqualTo(LeadershipState.FOLLOWER);
        assertThat(lossNotified.get()).isTrue();
    }

    @Test
    @DisplayName("renewLease: returns false immediately if coordinator is not currently in LEADER state")
    void testRenewLeaseWhenNotLeader() {
        boolean renewed = electionService.renewLease();
        assertThat(renewed).isFalse();
        verify(redisTemplate, never()).execute(any(RedisScript.class), any(), any());
    }

    @Test
    @DisplayName("releaseLeadership: safely deletes lease via Lua script and transitions to FOLLOWER")
    void testReleaseLeadershipSuccess() {
        // Acquire leadership
        String lockKey = RedisKeyFactory.leaderLockKey();
        when(valueOperations.setIfAbsent(eq(lockKey), eq(coordinatorId), eq(Duration.ofSeconds(5))))
                .thenReturn(Boolean.TRUE);
        when(valueOperations.increment(RedisKeyFactory.leaderEpochKey())).thenReturn(3L);
        electionService.tryAcquireLeadership();

        AtomicBoolean lossNotified = new AtomicBoolean(false);
        electionService.addListener(new LeadershipStateListener() {
            @Override
            public void onLeadershipAcquired(LeaderIdentity identity) {}

            @Override
            public void onLeadershipLost(LeaderIdentity identity) {
                lossNotified.set(true);
            }
        });

        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList(lockKey)), eq(coordinatorId)))
                .thenReturn(1L);

        boolean released = electionService.releaseLeadership();

        assertThat(released).isTrue();
        assertThat(electionService.isLeader()).isFalse();
        assertThat(electionService.getLeadershipIdentity().getState()).isEqualTo(LeadershipState.FOLLOWER);
        assertThat(lossNotified.get()).isTrue();
    }

    @Test
    @DisplayName("monotonic epochs: multiple leadership cycles produce monotonically increasing epochs")
    void testMonotonicEpochs() {
        String lockKey = RedisKeyFactory.leaderLockKey();
        String epochKey = RedisKeyFactory.leaderEpochKey();

        // Term 1
        when(valueOperations.setIfAbsent(eq(lockKey), eq(coordinatorId), eq(Duration.ofSeconds(5))))
                .thenReturn(Boolean.TRUE);
        when(valueOperations.increment(epochKey)).thenReturn(10L);
        electionService.tryAcquireLeadership();
        assertThat(electionService.getCurrentEpoch()).isEqualTo(10L);

        // Release
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList(lockKey)), eq(coordinatorId)))
                .thenReturn(1L);
        electionService.releaseLeadership();

        // Term 2
        when(valueOperations.increment(epochKey)).thenReturn(11L);
        electionService.tryAcquireLeadership();
        assertThat(electionService.getCurrentEpoch()).isEqualTo(11L);
    }

    @Test
    @DisplayName("shutdown: coordinator stop() safely releases active leadership lease and terminates lifecycle")
    void testShutdownReleasesLeadership() {
        String lockKey = RedisKeyFactory.leaderLockKey();
        when(valueOperations.setIfAbsent(eq(lockKey), eq(coordinatorId), eq(Duration.ofSeconds(5))))
                .thenReturn(Boolean.TRUE);
        when(valueOperations.increment(RedisKeyFactory.leaderEpochKey())).thenReturn(1L);
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList(lockKey)), eq(coordinatorId)))
                .thenReturn(1L);

        electionService.start();
        assertThat(electionService.isRunning()).isTrue();

        electionService.tryAcquireLeadership();
        assertThat(electionService.isLeader()).isTrue();

        electionService.stop();
        assertThat(electionService.isRunning()).isFalse();
        assertThat(electionService.isLeader()).isFalse();
        assertThat(electionService.getLeadershipIdentity().getState()).isEqualTo(LeadershipState.FOLLOWER);
    }

    @Test
    @DisplayName("releaseLeadership: returns false immediately if coordinator is not in LEADER state")
    void testReleaseLeadershipWhenNotLeader() {
        boolean released = electionService.releaseLeadership();
        assertThat(released).isFalse();
        verify(redisTemplate, never()).execute(any(RedisScript.class), any(), any());
    }
}
