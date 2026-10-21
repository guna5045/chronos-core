package com.chronoscore.coordinator.coordination.fencing;

import com.chronoscore.coordinator.coordination.RedisKeyFactory;
import com.chronoscore.coordinator.coordination.exception.StaleLeaderException;
import com.chronoscore.coordinator.coordination.leader.LeaderElectionService;
import com.chronoscore.coordinator.coordination.model.FencingToken;
import com.chronoscore.coordinator.coordination.model.LeaderIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Default implementation of {@link FencingTokenService}.
 * <p>
 * Guarantees that later scheduling and task dispatching operations can verify that
 * their fencing token has not been superseded by a newer cluster leader generation.
 */
@Service
public class DefaultFencingTokenService implements FencingTokenService {

    private static final Logger log = LoggerFactory.getLogger(DefaultFencingTokenService.class);

    private final LeaderElectionService leaderElectionService;
    private final StringRedisTemplate redisTemplate;

    public DefaultFencingTokenService(LeaderElectionService leaderElectionService,
                                     StringRedisTemplate redisTemplate) {
        this.leaderElectionService = Objects.requireNonNull(leaderElectionService, "leaderElectionService must not be null");
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
    }

    @Override
    public FencingToken getCurrentFencingToken() {
        if (!leaderElectionService.isLeader()) {
            throw new StaleLeaderException("Coordinator is not currently the active cluster leader");
        }

        LeaderIdentity identity = leaderElectionService.getLeadershipIdentity();
        return new FencingToken(identity.getCoordinatorId(), identity.getLeaderEpoch(), identity.getAcquiredAt());
    }

    @Override
    public boolean validateFencingToken(FencingToken token) {
        if (token == null || !token.isValid()) {
            return false;
        }

        // 1. Verify this coordinator is still the leader holding this epoch
        if (!leaderElectionService.isLeader()) {
            log.warn("Fencing validation failed: this coordinator is no longer leader");
            return false;
        }

        LeaderIdentity current = leaderElectionService.getLeadershipIdentity();
        if (!current.getCoordinatorId().equals(token.getCoordinatorId()) || current.getLeaderEpoch() != token.getEpoch()) {
            log.warn("Fencing validation failed: token epoch {} does not match current local epoch {}",
                    token.getEpoch(), current.getLeaderEpoch());
            return false;
        }

        // 2. Validate against cluster-wide Redis epoch counter
        try {
            String clusterEpochStr = redisTemplate.opsForValue().get(RedisKeyFactory.leaderEpochKey());
            if (clusterEpochStr != null) {
                long clusterEpoch = Long.parseLong(clusterEpochStr);
                if (token.getEpoch() < clusterEpoch) {
                    log.warn("Fencing validation failed: token epoch {} is superseded by cluster epoch {}",
                            token.getEpoch(), clusterEpoch);
                    return false;
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to check cluster epoch from Redis: {}", ex.getMessage());
        }

        return true;
    }

    @Override
    public void assertActiveLeader(long expectedEpoch) {
        if (!leaderElectionService.isLeader()) {
            throw new StaleLeaderException(
                    leaderElectionService.getLeadershipIdentity().getCoordinatorId(),
                    expectedEpoch,
                    0L
            );
        }

        long currentEpoch = leaderElectionService.getCurrentEpoch();
        if (currentEpoch != expectedEpoch) {
            throw new StaleLeaderException(
                    leaderElectionService.getLeadershipIdentity().getCoordinatorId(),
                    expectedEpoch,
                    currentEpoch
            );
        }
    }
}
