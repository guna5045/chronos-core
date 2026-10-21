package com.chronoscore.worker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ChronosWorkerApplicationTests {

    @Test
    @DisplayName("Context loads successfully for Chronos Worker")
    void contextLoads() {
        // Verifies Spring Boot worker context starts without error
    }
}
