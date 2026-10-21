package com.chronoscore.coordinator.coordination.lock;

import com.chronoscore.coordinator.coordination.CoordinationProperties;
import com.chronoscore.coordinator.coordination.RedisKeyFactory;
import com.chronoscore.coordinator.coordination.exception.CoordinationException;
import com.chronoscore.coordinator.coordination.exception.LockAcquisitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Production Redis implementation of {@link DistributedLockService} using atomic operations
 * and safe ownership verification Lua scripts.
 * <p>
 * Key Invariants:
 * <ul>
 *   <li>Atomic Acquisition: Single-round-trip {@code SET ... NX EX}.</li>
 *   <li>Safe Release: Lua script verifies the caller owns the lock token before deleting.</li>
 *   <li>Safe Renewal: Lua script verifies ownership before extending TTL.</li>
 *   <li>No Stale Deletes: Slow workers cannot delete locks acquired by newer coordinators.</li>
 * </ul>
 */
@Service
public class RedisDistributedLockService implements DistributedLockService {

    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLockService.class);

    private static final String RELEASE_LUA_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then\n" +
            "    return redis.call('del', KEYS[1])\n" +
            "else\n" +
            "    return 0\n" +
            "end";

    private static final String RENEW_LUA_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then\n" +
            "    return redis.call('expire', KEYS[1], ARGV[2])\n" +
            "else\n" +
            "    return 0\n" +
            "end";

    private final StringRedisTemplate redisTemplate;
    private final CoordinationProperties properties;
    private final RedisScript<Long> releaseScript;
    private final RedisScript<Long> renewScript;

    public RedisDistributedLockService(StringRedisTemplate redisTemplate,
                                       CoordinationProperties properties) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.releaseScript = new DefaultRedisScript<>(RELEASE_LUA_SCRIPT, Long.class);
        this.renewScript = new DefaultRedisScript<>(RENEW_LUA_SCRIPT, Long.class);
    }

    @Override
    public Optional<LockHandle> tryAcquire(String resource, Duration ttl) {
        Objects.requireNonNull(resource, "resource must not be null");

        String lockKey = RedisKeyFactory.taskLockKey(resource);
        String ownerToken = UUID.randomUUID().toString();
        Duration lockTtl = (ttl != null && !ttl.isNegative() && !ttl.isZero()) ? ttl : properties.getTaskLockTtl();

        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, ownerToken, lockTtl);

            if (Boolean.TRUE.equals(acquired)) {
                log.debug("Distributed lock acquired for resource '{}' with TTL {}", resource, lockTtl);
                return Optional.of(new LockHandle(resource, ownerToken, Instant.now(), lockTtl));
            } else {
                log.debug("Distributed lock acquisition failed for resource '{}': already held", resource);
                return Optional.empty();
            }
        } catch (Exception ex) {
            log.error("Redis error while attempting lock acquisition for resource '{}': {}", resource, ex.getMessage());
            throw new LockAcquisitionException(resource, ex.getMessage(), ex);
        }
    }

    @Override
    public boolean release(LockHandle handle) {
        if (handle == null) {
            return false;
        }

        String lockKey = RedisKeyFactory.taskLockKey(handle.getResource());

        try {
            Long result = redisTemplate.execute(
                    releaseScript,
                    Collections.singletonList(lockKey),
                    handle.getOwnerToken()
            );

            boolean released = result != null && result == 1L;
            if (released) {
                log.debug("Distributed lock released for resource '{}'", handle.getResource());
            } else {
                log.warn("Failed to release lock for resource '{}': ownership check failed (expired or stolen)",
                        handle.getResource());
            }
            return released;
        } catch (Exception ex) {
            log.error("Redis error while releasing lock for resource '{}': {}", handle.getResource(), ex.getMessage());
            throw new CoordinationException("Failed to release lock for resource " + handle.getResource(), ex);
        }
    }

    @Override
    public boolean renew(LockHandle handle, Duration ttl) {
        if (handle == null) {
            return false;
        }

        String lockKey = RedisKeyFactory.taskLockKey(handle.getResource());
        Duration renewalTtl = (ttl != null && !ttl.isNegative() && !ttl.isZero()) ? ttl : handle.getTtl();
        long ttlSeconds = Math.max(1, renewalTtl.toSeconds());

        try {
            Long result = redisTemplate.execute(
                    renewScript,
                    Collections.singletonList(lockKey),
                    handle.getOwnerToken(),
                    String.valueOf(ttlSeconds)
            );

            boolean renewed = result != null && result == 1L;
            if (renewed) {
                log.debug("Distributed lock renewed for resource '{}' (extended by {}s)", handle.getResource(), ttlSeconds);
            } else {
                log.warn("Failed to renew lock for resource '{}': ownership check failed", handle.getResource());
            }
            return renewed;
        } catch (Exception ex) {
            log.error("Redis error while renewing lock for resource '{}': {}", handle.getResource(), ex.getMessage());
            throw new CoordinationException("Failed to renew lock for resource " + handle.getResource(), ex);
        }
    }

    @Override
    public boolean isOwner(LockHandle handle) {
        if (handle == null) {
            return false;
        }

        String lockKey = RedisKeyFactory.taskLockKey(handle.getResource());
        try {
            String currentVal = redisTemplate.opsForValue().get(lockKey);
            return handle.getOwnerToken().equals(currentVal);
        } catch (Exception ex) {
            log.error("Redis error while checking lock ownership for resource '{}': {}", handle.getResource(), ex.getMessage());
            return false;
        }
    }
}
