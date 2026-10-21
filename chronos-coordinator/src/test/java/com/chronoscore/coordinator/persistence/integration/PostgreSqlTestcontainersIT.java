package com.chronoscore.coordinator.persistence.integration;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers PostgreSQL integration test verifying Flyway migrations and
 * repository operations against an isolated, real PostgreSQL 16 container.
 * <p>
 * Conditionally executes when a local or CI Docker daemon is available.
 */
@SpringBootTest
@Testcontainers
@EnabledIf("isDockerAvailable")
class PostgreSqlTestcontainersIT {

    private static final Logger log = LoggerFactory.getLogger(PostgreSqlTestcontainersIT.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("chronos_core_test")
            .withUsername("chronos_test_user")
            .withPassword("chronos_test_pass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (isDockerAvailable()) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        }
    }

    static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            log.info("Docker daemon not available; Testcontainers PostgreSQL tests will be skipped: {}", t.getMessage());
            return false;
        }
    }

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private Flyway flyway;

    @Test
    @DisplayName("Testcontainers: verifies real PostgreSQL 16 Flyway migrations and task persistence")
    void testRealPostgreSqlMigrationsAndPersistence() {
        // Verify Flyway applied all production migrations (V1 through V4)
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");

        UUID taskId = UUID.randomUUID();
        Task task = Task.builder()
                .taskId(taskId)
                .taskType("PG_REAL_TEST")
                .payload("{\"testcontainers\":true}")
                .priority(TaskPriority.HIGH)
                .status(TaskStatus.CREATED)
                .tenantId("tenant-docker")
                .idempotencyKey("docker-idem-01")
                .createdAt(Instant.now())
                .timeout(Duration.ofMinutes(5))
                .maxAttempts(3)
                .build();

        taskRepository.save(task);

        Optional<Task> retrieved = taskRepository.findById(taskId);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getTaskType()).isEqualTo("PG_REAL_TEST");
        assertThat(retrieved.get().getVersion()).isEqualTo(0L);
    }
}
