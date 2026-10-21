package com.chronoscore.coordinator.cluster.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration enabling cluster management and coordination properties.
 */
@Configuration
@EnableConfigurationProperties(ClusterProperties.class)
public class ClusterConfiguration {
}
