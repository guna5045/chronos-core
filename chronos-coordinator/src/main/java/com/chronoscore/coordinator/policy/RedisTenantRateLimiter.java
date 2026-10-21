package com.chronoscore.coordinator.policy;

import com.chronoscore.common.domain.TenantExecutionPolicy;
import com.chronoscore.coordinator.coordination.RedisKeyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Collections;
import java.util.Objects;

/**
 * Distributed, Redis-backed implementation of {@link TenantRateLimiter} using a deterministic
 * token-bucket Lua script.
 * <p>
 * Key Architectural Characteristics:
 * <ul>
 *   <li><b>Atomic Evaluation</b>: Token refill and consumption occur in a single atomic Lua script round-trip.</li>
 *   <li><b>Bounded State &amp; TTL</b>: Rate limit hash keys expire after an inactivity window to prevent state leakage.</li>
 *   <li><b>Resilience Policy</b>: Configurable {@code failOpen} mode (default: {@code true}). If Redis is
 *       temporarily unavailable, admission fails open to prevent rate limiting from becoming a cluster SPOF.</li>
 * </ul>
 */
@Component
public class RedisTenantRateLimiter implements TenantRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTenantRateLimiter.class);

    private static final String TOKEN_BUCKET_LUA =
            "local key = KEYS[1]\n" +
            "local max_tokens = tonumber(ARGV[1])\n" +
            "local refill_rate = tonumber(ARGV[2])\n" +
            "local now = tonumber(ARGV[3])\n" +
            "local requested = tonumber(ARGV[4])\n" +
            "local ttl = tonumber(ARGV[5])\n" +
            "\n" +
            "local data = redis.call('HMGET', key, 'tokens', 'last_updated')\n" +
            "local tokens = tonumber(data[1])\n" +
            "local last_updated = tonumber(data[2])\n" +
            "\n" +
            "if tokens == nil or last_updated == nil then\n" +
            "    tokens = max_tokens\n" +
            "    last_updated = now\n" +
            "else\n" +
            "    local delta = math.max(0, now - last_updated)\n" +
            "    tokens = math.min(max_tokens, tokens + (delta * refill_rate))\n" +
            "    last_updated = now\n" +
            "end\n" +
            "\n" +
            "if tokens >= requested then\n" +
            "    tokens = tokens - requested\n" +
            "    redis.call('HMSET', key, 'tokens', tokens, 'last_updated', last_updated)\n" +
            "    redis.call('EXPIRE', key, ttl)\n" +
            "    return 1\n" +
            "else\n" +
            "    redis.call('HMSET', key, 'tokens', tokens, 'last_updated', last_updated)\n" +
            "    redis.call('EXPIRE', key, ttl)\n" +
            "    return 0\n" +
            "end";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> rateLimitScript;
    private final boolean failOpen;
    private Clock clock = Clock.systemUTC();

    @Autowired
    public RedisTenantRateLimiter(@Autowired(required = false) StringRedisTemplate redisTemplate,
                                  @Value("${chronos.ratelimit.fail-open:true}") boolean failOpen) {
        this.redisTemplate = redisTemplate;
        this.failOpen = failOpen;
        this.rateLimitScript = new DefaultRedisScript<>(TOKEN_BUCKET_LUA, Long.class);
    }

    public void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public boolean tryAcquire(String tenantId, TenantExecutionPolicy policy) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(policy, "policy must not be null");

        if (redisTemplate == null) {
            log.debug("RedisTemplate not configured; admitting task for tenant '{}' (in-memory mode)", tenantId);
            return true;
        }

        String key = RedisKeyFactory.tenantRateLimitKey(tenantId);
        double maxTokens = policy.getBurstCapacity();
        double refillRate = policy.getTasksPerSecond();
        double now = clock.millis() / 1000.0;
        double requested = 1.0;
        long ttlSeconds = Math.max(60, (long) Math.ceil((maxTokens / Math.max(0.1, refillRate)) * 2));

        try {
            Long result = redisTemplate.execute(
                    rateLimitScript,
                    Collections.singletonList(key),
                    String.valueOf(maxTokens),
                    String.valueOf(refillRate),
                    String.valueOf(now),
                    String.valueOf(requested),
                    String.valueOf(ttlSeconds)
            );
            return result != null && result == 1L;
        } catch (Exception e) {
            log.warn("Redis error during rate limit evaluation for tenant '{}': {}. FailOpen policy={}",
                    tenantId, e.getMessage(), failOpen);
            return failOpen;
        }
    }

    @Override
    public boolean isRateLimited(String tenantId, TenantExecutionPolicy policy) {
        return !tryAcquire(tenantId, policy);
    }

    @Override
    public void reset(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(RedisKeyFactory.tenantRateLimitKey(tenantId));
            } catch (Exception e) {
                log.warn("Failed to reset rate limit key for tenant '{}': {}", tenantId, e.getMessage());
            }
        }
    }
}
