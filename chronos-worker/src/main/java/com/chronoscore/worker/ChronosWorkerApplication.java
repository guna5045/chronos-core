package com.chronoscore.worker;

import com.chronoscore.common.ChronosConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Chronos Worker node service.
 * <p>
 * Responsible for task execution runners, registration, and heartbeat reporting
 * in subsequent phases.
 */
@SpringBootApplication
public class ChronosWorkerApplication {

    private static final Logger log = LoggerFactory.getLogger(ChronosWorkerApplication.class);

    public static void main(String[] args) {
        log.info("Starting {} Worker (Phase 01 Foundation)...", ChronosConstants.PROJECT_NAME);
        SpringApplication.run(ChronosWorkerApplication.class, args);
        log.info("{} Worker started successfully.", ChronosConstants.PROJECT_NAME);
    }
}
