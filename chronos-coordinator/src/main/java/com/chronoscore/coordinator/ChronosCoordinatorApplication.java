package com.chronoscore.coordinator;

import com.chronoscore.common.ChronosConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Chronos Coordinator service.
 * <p>
 * Responsible for cluster leadership coordination, task state machine transitions,
 * and DAG execution in subsequent phases.
 */
@SpringBootApplication(scanBasePackages = {"com.chronoscore.coordinator", "com.chronoscore.ai"})
public class ChronosCoordinatorApplication {

    private static final Logger log = LoggerFactory.getLogger(ChronosCoordinatorApplication.class);

    public static void main(String[] args) {
        log.info("Starting {} Coordinator (Phase 01 Foundation)...", ChronosConstants.PROJECT_NAME);
        SpringApplication.run(ChronosCoordinatorApplication.class, args);
        log.info("{} Coordinator started successfully.", ChronosConstants.PROJECT_NAME);
    }
}
