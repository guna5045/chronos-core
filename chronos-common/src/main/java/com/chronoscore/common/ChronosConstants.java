package com.chronoscore.common;

/**
 * Global constants for the Chronos Core platform.
 * <p>
 * This class resides in chronos-common and contains zero business logic.
 */
public final class ChronosConstants {

    public static final String PROJECT_NAME = "Chronos Core";
    public static final String PROJECT_SUBTITLE = "Distributed Task & Workflow Orchestration Platform";
    public static final String VERSION = "0.1.0-SNAPSHOT";

    // Standard Port Definitions
    public static final int DEFAULT_COORDINATOR_PORT = 8080;
    public static final int DEFAULT_WORKER_PORT = 8081;
    public static final int DEFAULT_AI_PORT = 8082;
    public static final int DEFAULT_GRPC_PORT = 9090;

    // Distributed Headers & Metadata Keys
    public static final String HEADER_FENCING_TOKEN = "X-Chronos-Epoch";
    public static final String HEADER_WORKER_ID = "X-Chronos-Worker-Id";
    public static final String HEADER_TRACE_ID = "X-Chronos-Trace-Id";

    private ChronosConstants() {
        // Prevent instantiation of utility constant class
    }
}
