package com.chronoscore.coordinator.scheduler;

import com.chronoscore.coordinator.distribution.ConsistentHashRing;
import com.chronoscore.coordinator.distribution.DistributionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration enabling scheduler and distribution properties and registering core beans.
 */
@Configuration
@EnableConfigurationProperties({SchedulerProperties.class, DistributionProperties.class})
public class SchedulerConfiguration {

    @Bean
    public ConsistentHashRing consistentHashRing(DistributionProperties distributionProperties) {
        return new ConsistentHashRing(distributionProperties.getVirtualNodesPerWorker());
    }
}
