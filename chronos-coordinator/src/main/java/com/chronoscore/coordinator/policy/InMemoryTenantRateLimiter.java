package com.chronoscore.coordinator.policy;

import com.chronoscore.common.domain.TenantExecutionPolicy;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token bucket rate limiter for deterministic unit and concurrency testing.
 */
public class InMemoryTenantRateLimiter implements TenantRateLimiter {

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    public InMemoryTenantRateLimiter() {
    }

    public InMemoryTenantRateLimiter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public void advanceClock(java.time.Duration duration) {
        this.clock = Clock.offset(this.clock, duration);
    }

    @Override
    public boolean tryAcquire(String tenantId, TenantExecutionPolicy policy) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(policy, "policy must not be null");

        TokenBucket bucket = buckets.computeIfAbsent(tenantId,
                id -> new TokenBucket(policy.getBurstCapacity(), policy.getTasksPerSecond(), clock.millis() / 1000.0));
        return bucket.tryConsume(policy.getBurstCapacity(), policy.getTasksPerSecond(), 1.0, clock.millis() / 1000.0);
    }

    @Override
    public boolean isRateLimited(String tenantId, TenantExecutionPolicy policy) {
        return !tryAcquire(tenantId, policy);
    }

    @Override
    public void reset(String tenantId) {
        buckets.remove(tenantId);
    }

    private static class TokenBucket {
        private double tokens;
        private double lastRefillTime;

        TokenBucket(double initialCapacity, double refillRate, double now) {
            this.tokens = initialCapacity;
            this.lastRefillTime = now;
        }

        synchronized boolean tryConsume(double capacity, double refillRate, double requested, double now) {
            double delta = Math.max(0, now - lastRefillTime);
            tokens = Math.min(capacity, tokens + (delta * refillRate));
            lastRefillTime = now;

            if (tokens >= requested) {
                tokens -= requested;
                return true;
            }
            return false;
        }
    }
}
