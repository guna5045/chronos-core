package com.chronoscore.coordinator.distribution;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for consistent hash task distribution.
 */
@ConfigurationProperties(prefix = "chronos.distribution")
public class DistributionProperties {

    /**
     * Number of virtual nodes allocated per active worker on the consistent hash ring.
     * Default: 128 virtual nodes per worker.
     */
    private int virtualNodesPerWorker = 128;

    public int getVirtualNodesPerWorker() {
        return virtualNodesPerWorker;
    }

    public void setVirtualNodesPerWorker(int virtualNodesPerWorker) {
        if (virtualNodesPerWorker <= 0) {
            throw new IllegalArgumentException("virtualNodesPerWorker must be positive");
        }
        this.virtualNodesPerWorker = virtualNodesPerWorker;
    }
}
