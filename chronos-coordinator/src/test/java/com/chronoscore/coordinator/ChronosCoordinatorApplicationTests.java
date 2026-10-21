package com.chronoscore.coordinator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ChronosCoordinatorApplicationTests {

    @Test
    @DisplayName("Context loads successfully for Chronos Coordinator")
    void contextLoads() {
        // Verifies Spring Boot coordinator context starts without error
    }
}
