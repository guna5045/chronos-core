package com.chronoscore.coordinator.coordination.fencing;

import com.chronoscore.coordinator.coordination.RedisKeyFactory;
import com.chronoscore.coordinator.coordination.exception.StaleLeaderException;
import com.chronoscore.coordinator.coordination.leader.LeaderElectionService;
import com.chronoscore.coordinator.coordination.model.FencingToken;
import com.chronoscore.coordinator.coordination.model.LeaderIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FencingTokenServiceTest {

    @Mock
    private LeaderElectionService leaderElectionService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private DefaultFencingTokenService fencingTokenService;

    private final String coordinatorId = "coord-1";

    @BeforeEach
    void setUp() {
        fencingTokenService = new DefaultFencingTokenService(leaderElectionService, redisTemplate);
    }

    @Test
    @DisplayName("getCurrentFencingToken: returns token when coordinator is the active leader")
    void testGetCurrentFencingTokenAsLeader() {
        Instant now = Instant.now();
        LeaderIdentity leaderIdentity = LeaderIdentity.leader(coordinatorId, 5L, now, now.plusSeconds(5));

        when(leaderElectionService.isLeader()).thenReturn(true);
        when(leaderElectionService.getLeadershipIdentity()).thenReturn(leaderIdentity);

        FencingToken token = fencingTokenService.getCurrentFencingToken();

        assertThat(token).isNotNull();
        assertThat(token.getCoordinatorId()).isEqualTo(coordinatorId);
        assertThat(token.getEpoch()).isEqualTo(5L);
        assertThat(token.isValid()).isTrue();
    }

    @Test
    @DisplayName("getCurrentFencingToken: throws StaleLeaderException when coordinator is a follower")
    void testGetCurrentFencingTokenAsFollowerThrows() {
        when(leaderElectionService.isLeader()).thenReturn(false);

        assertThatThrownBy(() -> fencingTokenService.getCurrentFencingToken())
                .isInstanceOf(StaleLeaderException.class)
                .hasMessageContaining("not currently the active cluster leader");
    }

    @Test
    @DisplayName("validateFencingToken: returns true when token matches current leader epoch")
    void testValidateFencingTokenSuccess() {
        Instant now = Instant.now();
        LeaderIdentity leaderIdentity = LeaderIdentity.leader(coordinatorId, 7L, now, now.plusSeconds(5));

        when(leaderElectionService.isLeader()).thenReturn(true);
        when(leaderElectionService.getLeadershipIdentity()).thenReturn(leaderIdentity);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeyFactory.leaderEpochKey())).thenReturn("7");

        FencingToken token = new FencingToken(coordinatorId, 7L, now);
        assertThat(fencingTokenService.validateFencingToken(token)).isTrue();
    }

    @Test
    @DisplayName("validateFencingToken: returns false when token has been superseded by a higher cluster epoch")
    void testValidateFencingTokenSuperseded() {
        Instant now = Instant.now();
        LeaderIdentity leaderIdentity = LeaderIdentity.leader(coordinatorId, 7L, now, now.plusSeconds(5));

        when(leaderElectionService.isLeader()).thenReturn(true);
        when(leaderElectionService.getLeadershipIdentity()).thenReturn(leaderIdentity);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeyFactory.leaderEpochKey())).thenReturn("8"); // Superseded in Redis!

        FencingToken token = new FencingToken(coordinatorId, 7L, now);
        assertThat(fencingTokenService.validateFencingToken(token)).isFalse();
    }

    @Test
    @DisplayName("assertActiveLeader: throws StaleLeaderException when epoch is mismatched")
    void testAssertActiveLeaderMismatch() {
        when(leaderElectionService.isLeader()).thenReturn(true);
        when(leaderElectionService.getCurrentEpoch()).thenReturn(5L);
        LeaderIdentity identity = LeaderIdentity.leader(coordinatorId, 5L, Instant.now(), Instant.now().plusSeconds(5));
        when(leaderElectionService.getLeadershipIdentity()).thenReturn(identity);

        assertThatThrownBy(() -> fencingTokenService.assertActiveLeader(4L))
                .isInstanceOf(StaleLeaderException.class);
    }
}
