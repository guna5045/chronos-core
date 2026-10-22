package com.chronoscore.coordinator.coordination.leader;

import com.chronoscore.coordinator.coordination.CoordinationProperties;
import com.chronoscore.coordinator.coordination.RedisKeyFactory;
import com.chronoscore.coordinator.coordination.model.LeaderIdentity;
import com.chronoscore.coordinator.coordination.model.LeadershipState;
import com.chronoscore.coordinator.coordination.model.LeadershipStateListener;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Production Redis-backed implementation of {@link LeaderElectionService}.
 * <p>
 * Key Features:
 * <ul>
 *   <li>Lease-Based Election: Short TTL key in Redis ({@code SET chronos:leader:lock <id> NX EX 5}).</li>
 *   <li>Atomic Renewal &amp; Release: Lua scripts ensuring only the current owner can extend or delete the lease.</li>
 *   <li>Monotonic Fencing Tokens: Uses {@code INCR chronos:leader:epoch} on election to increment cluster generation.</li>
 *   <li>Scheduled Lifecycle: Runs scheduled renewals and election loops without busy polling.</li>
 * </ul>
 */
@Service
public class RedisLeaderElectionService implements LeaderElectionService, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RedisLeaderElectionService.class);

    private static final String RENEW_LUA_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then\n" +
            "    return redis.call('expire', KEYS[1], ARGV[2])\n" +
            "else\n" +
            "    return 0\n" +
            "end";

    private static final String RELEASE_LUA_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then\n" +
            "    return redis.call('del', KEYS[1])\n" +
            "else\n" +
            "    return 0\n" +
            "end";

    private final StringRedisTemplate redisTemplate;
    private final CoordinationProperties properties;
    private final String coordinatorId;

    private final RedisScript<Long> renewScript;
    private final RedisScript<Long> releaseScript;

    private final AtomicReference<LeaderIdentity> currentIdentity;
    private final List<LeadershipStateListener> listeners = new CopyOnWriteArrayList<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    private final com.chronoscore.coordinator.observability.ChronosMetricsService metricsService;

    public RedisLeaderElectionService(StringRedisTemplate redisTemplate,
                                      CoordinationProperties properties) {
        this(redisTemplate, properties, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RedisLeaderElectionService(StringRedisTemplate redisTemplate,
                                      CoordinationProperties properties,
                                      @org.springframework.beans.factory.annotation.Autowired(required = false)
                                      com.chronoscore.coordinator.observability.ChronosMetricsService metricsService) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.metricsService = metricsService;
        this.coordinatorId = properties.getCoordinatorId();

        this.renewScript = new DefaultRedisScript<>(RENEW_LUA_SCRIPT, Long.class);
        this.releaseScript = new DefaultRedisScript<>(RELEASE_LUA_SCRIPT, Long.class);

        this.currentIdentity = new AtomicReference<>(LeaderIdentity.follower(coordinatorId));
    }

    @Override
    public boolean tryAcquireLeadership() {
        String lockKey = RedisKeyFactory.leaderLockKey();
        String epochKey = RedisKeyFactory.leaderEpochKey();

        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, coordinatorId, properties.getLeaderLeaseDuration());

            if (Boolean.TRUE.equals(acquired)) {
                // Increment epoch atomically to generate a new fencing generation
                Long newEpoch = redisTemplate.opsForValue().increment(epochKey);
                long epochVal = newEpoch != null ? newEpoch : 1L;

                Instant now = Instant.now();
                Instant expiresAt = now.plus(properties.getLeaderLeaseDuration());
                LeaderIdentity leaderIdentity = LeaderIdentity.leader(coordinatorId, epochVal, now, expiresAt);

                currentIdentity.set(leaderIdentity);
                log.info("Coordinator '{}' acquired cluster leadership for epoch {}", coordinatorId, epochVal);

                notifyAcquired(leaderIdentity);
                return true;
            } else {
                return false;
            }
        } catch (Exception ex) {
            log.warn("Redis error while attempting leader acquisition for coordinator '{}': {}",
                    coordinatorId, ex.getMessage());
            return false;
        }
    }

    @Override
    public boolean renewLease() {
        if (!isLeader()) {
            return false;
        }

        String lockKey = RedisKeyFactory.leaderLockKey();
        long leaseDurationSeconds = Math.max(1, properties.getLeaderLeaseDuration().toSeconds());

        try {
            Long result = redisTemplate.execute(
                    renewScript,
                    Collections.singletonList(lockKey),
                    coordinatorId,
                    String.valueOf(leaseDurationSeconds)
            );

            if (result != null && result == 1L) {
                Instant now = Instant.now();
                Instant expiresAt = now.plus(properties.getLeaderLeaseDuration());
                LeaderIdentity active = currentIdentity.get();

                LeaderIdentity renewed = LeaderIdentity.leader(
                        coordinatorId,
                        active.getLeaderEpoch(),
                        active.getAcquiredAt(),
                        expiresAt
                );
                currentIdentity.set(renewed);
                log.debug("Coordinator '{}' renewed leader lease for epoch {} until {}",
                        coordinatorId, renewed.getLeaderEpoch(), expiresAt);
                return true;
            } else {
                handleLeadershipLoss("Lease renewal failed: lock ownership lost or key expired in Redis");
                return false;
            }
        } catch (Exception ex) {
            log.error("Redis connectivity error during leader lease renewal for coordinator '{}': {}",
                    coordinatorId, ex.getMessage());
            handleLeadershipLoss("Redis connectivity error during lease renewal: " + ex.getMessage());
            return false;
        }
    }

    @Override
    public boolean releaseLeadership() {
        if (!isLeader()) {
            return false;
        }

        String lockKey = RedisKeyFactory.leaderLockKey();
        LeaderIdentity previous = currentIdentity.get();

        try {
            Long result = redisTemplate.execute(
                    releaseScript,
                    Collections.singletonList(lockKey),
                    coordinatorId
            );

            boolean released = result != null && result == 1L;
            LeaderIdentity lost = LeaderIdentity.lost(coordinatorId, previous.getLeaderEpoch());
            currentIdentity.set(lost);

            log.info("Coordinator '{}' released leadership for epoch {}", coordinatorId, previous.getLeaderEpoch());
            notifyLost(lost);

            currentIdentity.set(LeaderIdentity.follower(coordinatorId));
            return released;
        } catch (Exception ex) {
            log.error("Redis error releasing leadership for coordinator '{}': {}", coordinatorId, ex.getMessage());
            currentIdentity.set(LeaderIdentity.follower(coordinatorId));
            return false;
        }
    }

    @Override
    public LeaderIdentity getLeadershipIdentity() {
        return currentIdentity.get();
    }

    @Override
    public boolean isLeader() {
        return currentIdentity.get().isLeader();
    }

    @Override
    public long getCurrentEpoch() {
        LeaderIdentity identity = currentIdentity.get();
        return identity.isLeader() ? identity.getLeaderEpoch() : 0L;
    }

    @Override
    public void addListener(LeadershipStateListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private void handleLeadershipLoss(String reason) {
        LeaderIdentity previous = currentIdentity.get();
        if (previous.getState() == LeadershipState.LEADER) {
            LeaderIdentity lost = LeaderIdentity.lost(coordinatorId, previous.getLeaderEpoch());
            currentIdentity.set(lost);
            log.warn("Coordinator '{}' lost leadership for epoch {}: {}", coordinatorId, previous.getLeaderEpoch(), reason);
            if (metricsService != null) {
                metricsService.recordLeadershipLost();
                metricsService.updateLeadershipState(false, 0);
            }
            notifyLost(lost);
            currentIdentity.set(LeaderIdentity.follower(coordinatorId));
        }
    }

    private void notifyAcquired(LeaderIdentity identity) {
        if (metricsService != null && identity != null) {
            metricsService.recordLeadershipAcquired();
            metricsService.updateLeadershipState(true, identity.getLeaderEpoch());
        }
        for (LeadershipStateListener listener : listeners) {
            try {
                listener.onLeadershipAcquired(identity);
            } catch (Exception ex) {
                log.error("Error in onLeadershipAcquired listener: {}", ex.getMessage(), ex);
            }
        }
    }

    private void notifyLost(LeaderIdentity identity) {
        for (LeadershipStateListener listener : listeners) {
            try {
                listener.onLeadershipLost(identity);
            } catch (Exception ex) {
                log.error("Error in onLeadershipLost listener: {}", ex.getMessage(), ex);
            }
        }
    }

    private void coordinationTick() {
        if (!running.get()) {
            return;
        }
        try {
            if (isLeader()) {
                renewLease();
            } else {
                tryAcquireLeadership();
            }
        } catch (Exception ex) {
            log.error("Unexpected error in coordination tick loop: {}", ex.getMessage(), ex);
        }
    }

    @Override
    public void start() {
        if (!properties.isEnabled()) {
            log.info("Distributed coordination is disabled by configuration");
            return;
        }

        if (running.compareAndSet(false, true)) {
            log.info("Starting distributed leader election loop for coordinator '{}' (renewal interval: {})",
                    coordinatorId, properties.getLeaderRenewalInterval());

            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "chronos-leader-election-" + coordinatorId);
                t.setDaemon(true);
                return t;
            });

            long intervalMs = Math.max(100, properties.getLeaderRenewalInterval().toMillis());
            this.scheduler.scheduleWithFixedDelay(
                    this::coordinationTick,
                    0,
                    intervalMs,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping leader election loop for coordinator '{}'", coordinatorId);
            if (scheduler != null) {
                scheduler.shutdownNow();
                try {
                    scheduler.awaitTermination(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            if (isLeader()) {
                releaseLeadership();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        // High phase number to ensure coordination starts after DB/data components and stops before them
        return Integer.MAX_VALUE - 100;
    }
}
