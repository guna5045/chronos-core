package com.chronoscore.coordinator.coordination;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.UUID;

/**
 * Configuration properties for the Chronos Core distributed coordination subsystem.
 */
@ConfigurationProperties(prefix = "chronos.coordination")
public class CoordinationProperties {

    /**
     * Unique identifier for this coordinator instance within the cluster.
     * Defaults to an auto-generated random ID if not explicitly configured.
     */
    private String coordinatorId;

    /**
     * Duration of the Redis lease acquired by the active cluster leader.
     * Default: 5 seconds.
     */
    private Duration leaderLeaseDuration = Duration.ofSeconds(5);

    /**
     * Interval at which the active leader attempts periodic lease renewal.
     * Must be substantially shorter than {@link #leaderLeaseDuration}.
     * Default: 1500 milliseconds.
     */
    private Duration leaderRenewalInterval = Duration.ofMillis(1500);

    /**
     * Default TTL applied when acquiring distributed task locks.
     * Default: 30 seconds.
     */
    private Duration taskLockTtl = Duration.ofSeconds(30);

    /**
     * Master toggle enabling distributed coordination loops.
     * Default: true.
     */
    private boolean enabled = true;

    public String getCoordinatorId() {
        if (coordinatorId == null || coordinatorId.isBlank()) {
            coordinatorId = "coordinator-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return coordinatorId;
    }

    public void setCoordinatorId(String coordinatorId) {
        this.coordinatorId = coordinatorId;
    }

    public Duration getLeaderLeaseDuration() {
        return leaderLeaseDuration;
    }

    public void setLeaderLeaseDuration(Duration leaderLeaseDuration) {
        this.leaderLeaseDuration = leaderLeaseDuration;
    }

    public Duration getLeaderRenewalInterval() {
        return leaderRenewalInterval;
    }

    public void setLeaderRenewalInterval(Duration leaderRenewalInterval) {
        this.leaderRenewalInterval = leaderRenewalInterval;
    }

    public Duration getTaskLockTtl() {
        return taskLockTtl;
    }

    public void setTaskLockTtl(Duration taskLockTtl) {
        this.taskLockTtl = taskLockTtl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
