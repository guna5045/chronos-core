package com.chronoscore.coordinator.recovery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration properties for task execution leases.
 */
@Component
@ConfigurationProperties(prefix = "chronos.lease")
public class LeaseProperties {

    /**
     * Duration for which a task execution lease remains valid before expiring.
     * Default: 30 seconds.
     */
    private Duration duration = Duration.ofSeconds(30);

    /**
     * Interval at which active worker tasks should renew their execution lease.
     * Default: 10 seconds.
     */
    private Duration renewalInterval = Duration.ofSeconds(10);

    public Duration getDuration() {
        return duration;
    }

    public void setDuration(Duration duration) {
        this.duration = duration != null ? duration : Duration.ofSeconds(30);
    }

    public Duration getRenewalInterval() {
        return renewalInterval;
    }

    public void setRenewalInterval(Duration renewalInterval) {
        this.renewalInterval = renewalInterval != null ? renewalInterval : Duration.ofSeconds(10);
    }
}
