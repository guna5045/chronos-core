package com.chronoscore.coordinator.recovery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration properties for the background task recovery scheduler.
 */
@Component
@ConfigurationProperties(prefix = "chronos.recovery")
public class RecoveryProperties {

    /**
     * Whether the background task recovery process is enabled.
     * Default: true.
     */
    private boolean enabled = true;

    /**
     * Polling interval between consecutive recovery sweeps.
     * Default: 1 second.
     */
    private Duration pollInterval = Duration.ofSeconds(1);

    /**
     * Maximum number of expired leases or timed-out tasks recovered per iteration.
     * Default: 50.
     */
    private int batchSize = 50;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval != null ? pollInterval : Duration.ofSeconds(1);
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize > 0 ? batchSize : 50;
    }
}
