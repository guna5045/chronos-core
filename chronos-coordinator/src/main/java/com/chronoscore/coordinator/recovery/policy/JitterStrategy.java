package com.chronoscore.coordinator.recovery.policy;

import java.time.Duration;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Strategy interface for applying randomized jitter to backoff durations.
 */
public interface JitterStrategy {

    /**
     * Applies jitter to the calculated base backoff duration.
     *
     * @param calculatedDelay un-jittered exponential backoff duration.
     * @return duration with jitter applied.
     */
    Duration applyJitter(Duration calculatedDelay);

    /**
     * Returns a strategy that applies no jitter (deterministic identity).
     */
    static JitterStrategy none() {
        return calculatedDelay -> calculatedDelay != null ? calculatedDelay : Duration.ZERO;
    }

    /**
     * Returns a Full Jitter strategy: {@code delay = random(0, calculatedDelay)}.
     * Prevents synchronized retry storms while maintaining expected backoff spread.
     */
    static JitterStrategy full() {
        return new FullJitterStrategy(null);
    }

    /**
     * Returns a Full Jitter strategy with a deterministic Random provider for testing.
     */
    static JitterStrategy full(Random random) {
        return new FullJitterStrategy(Objects.requireNonNull(random, "random must not be null"));
    }

    /**
     * Implementation of AWS/Full Jitter algorithm: uniform random in [0, calculatedDelay].
     */
    class FullJitterStrategy implements JitterStrategy {
        private final Random random;

        public FullJitterStrategy(Random random) {
            this.random = random;
        }

        @Override
        public Duration applyJitter(Duration calculatedDelay) {
            if (calculatedDelay == null || calculatedDelay.isZero() || calculatedDelay.isNegative()) {
                return Duration.ZERO;
            }
            long millis = calculatedDelay.toMillis();
            if (millis <= 1) {
                return calculatedDelay;
            }

            long jitteredMillis;
            if (random != null) {
                // Generates uniform value in [1, millis]
                jitteredMillis = 1 + (Math.abs(random.nextLong()) % millis);
            } else {
                jitteredMillis = ThreadLocalRandom.current().nextLong(1, millis + 1);
            }
            return Duration.ofMillis(jitteredMillis);
        }
    }
}
