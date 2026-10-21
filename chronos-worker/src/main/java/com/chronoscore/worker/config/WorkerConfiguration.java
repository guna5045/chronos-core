package com.chronoscore.worker.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration enabling worker properties.
 */
@Configuration
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerConfiguration {
}
