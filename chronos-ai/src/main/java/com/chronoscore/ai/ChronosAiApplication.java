package com.chronoscore.ai;

import com.chronoscore.common.ChronosConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Chronos AI Service Boundary.
 * <p>
 * This module is reserved exclusively for:
 * 1. AI Workflow Agent (authoring and validating workflow DAGs)
 * 2. AI Operations Agent (incident triage and log analysis)
 * <p>
 * In Phase 01, this exists purely as a clean architectural boundary without
 * active LLM connections, keys, or agents.
 */
@SpringBootApplication
public class ChronosAiApplication {

    private static final Logger log = LoggerFactory.getLogger(ChronosAiApplication.class);

    public static void main(String[] args) {
        log.info("Starting {} AI Service Boundary (Phase 01 Foundation)...", ChronosConstants.PROJECT_NAME);
        SpringApplication.run(ChronosAiApplication.class, args);
        log.info("{} AI Service started successfully.", ChronosConstants.PROJECT_NAME);
    }
}
