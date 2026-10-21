package com.chronoscore.coordinator.policy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test suite verifying deterministic token bucket rate limiting, burst capacity,
 * continuous token refill, and multi-tenant isolation.
 */
class TenantRateLimiterTest {

    private InMemoryTenantRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new InMemoryTenantRateLimiter();
    }

    @Test
    @DisplayName("Rate Limit: Exactly at limit and burst capacity")
    void testBurstCapacityAndExhaustion() {
        String tenantId = "tenant-burst";
        double rate = 10.0; // 10 tokens per second
        int burst = 5;

        // Bucket starts full with burst capacity = 5
        for (int i = 0; i < 5; i++) {
            boolean acquired = rateLimiter.tryAcquire(tenantId, rate, burst);
            assertThat(acquired)
                    .as("Token %d should be admitted under burst capacity", i + 1)
                    .isTrue();
        }

        // 6th immediate request must be rejected (exhausted)
        boolean exceeded = rateLimiter.tryAcquire(tenantId, rate, burst);
        assertThat(exceeded)
                .as("Request beyond burst capacity must be rate limited")
                .isFalse();
    }

    @Test
    @DisplayName("Rate Limit: Token refill over time restores admission")
    void testTokenRefill() {
        String tenantId = "tenant-refill";
        double rate = 20.0; // 20 tokens per sec -> 1 token every 50ms
        int burst = 2;

        // Exhaust burst capacity
        assertThat(rateLimiter.tryAcquire(tenantId, rate, burst)).isTrue();
        assertThat(rateLimiter.tryAcquire(tenantId, rate, burst)).isTrue();
        assertThat(rateLimiter.tryAcquire(tenantId, rate, burst)).isFalse();

        // Advance deterministic clock by 100ms (refills 2 tokens)
        rateLimiter.advanceClock(Duration.ofMillis(100));

        assertThat(rateLimiter.tryAcquire(tenantId, rate, burst)).isTrue();
        assertThat(rateLimiter.tryAcquire(tenantId, rate, burst)).isTrue();
        assertThat(rateLimiter.tryAcquire(tenantId, rate, burst)).isFalse();
    }

    @Test
    @DisplayName("Rate Limit: Strict isolation between distinct tenants")
    void testMultiTenantIsolation() {
        String tenantA = "tenant-A";
        String tenantB = "tenant-B";
        double rate = 5.0;
        int burst = 3;

        // Exhaust Tenant A
        for (int i = 0; i < burst; i++) {
            assertThat(rateLimiter.tryAcquire(tenantA, rate, burst)).isTrue();
        }
        assertThat(rateLimiter.tryAcquire(tenantA, rate, burst)).isFalse();

        // Tenant B must still have full burst capacity
        for (int i = 0; i < burst; i++) {
            assertThat(rateLimiter.tryAcquire(tenantB, rate, burst)).isTrue();
        }
        assertThat(rateLimiter.tryAcquire(tenantB, rate, burst)).isFalse();
    }

    @Test
    @DisplayName("Rate Limit: Disabled / infinite quota (tasksPerSecond <= 0)")
    void testDisabledRateLimit() {
        String tenant = "tenant-unlimited";
        for (int i = 0; i < 100; i++) {
            assertThat(rateLimiter.tryAcquire(tenant, 0.0, 0)).isTrue();
            assertThat(rateLimiter.tryAcquire(tenant, -1.0, -1)).isTrue();
        }
    }
}
