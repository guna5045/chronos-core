package com.chronoscore.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChronosConstantsTest {

    @Test
    @DisplayName("Verify Chronos Core platform branding constants")
    void testPlatformConstants() {
        assertThat(ChronosConstants.PROJECT_NAME).isEqualTo("Chronos Core");
        assertThat(ChronosConstants.PROJECT_SUBTITLE)
                .isEqualTo("Distributed Task & Workflow Orchestration Platform");
        assertThat(ChronosConstants.DEFAULT_COORDINATOR_PORT).isEqualTo(8080);
        assertThat(ChronosConstants.DEFAULT_WORKER_PORT).isEqualTo(8081);
        assertThat(ChronosConstants.DEFAULT_AI_PORT).isEqualTo(8082);
    }
}
