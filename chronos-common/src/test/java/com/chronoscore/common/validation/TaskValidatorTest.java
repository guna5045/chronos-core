package com.chronoscore.common.validation;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.common.exceptions.InvalidTaskException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskValidatorTest {

    private Task.Builder createValidBuilder() {
        return Task.builder()
                .taskId(UUID.randomUUID())
                .taskType("DATA_SYNC")
                .payload("{\"key\":\"value\"}")
                .priority(TaskPriority.NORMAL)
                .status(TaskStatus.CREATED)
                .tenantId("tenant-primary")
                .createdAt(Instant.now())
                .maxAttempts(3)
                .currentAttempt(0)
                .version(0L);
    }

    @Test
    @DisplayName("Valid task definition should pass validation without exception")
    void testValidTaskPasses() {
        Task task = createValidBuilder().build();
        assertThatCode(() -> TaskValidator.validate(task)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Null task must throw InvalidTaskException")
    void testNullTaskThrows() {
        assertThatThrownBy(() -> TaskValidator.validate(null))
                .isInstanceOf(InvalidTaskException.class)
                .hasMessageContaining("Task must not be null");
    }

    @Test
    @DisplayName("Blank taskType must throw InvalidTaskException")
    void testBlankTaskTypeThrows() {
        Task task = createValidBuilder().taskType("   ").build();
        assertThatThrownBy(() -> TaskValidator.validate(task))
                .isInstanceOf(InvalidTaskException.class)
                .hasMessageContaining("taskType must not be null or blank");
    }

    @Test
    @DisplayName("Overly long taskType must throw InvalidTaskException")
    void testLongTaskTypeThrows() {
        String longType = "A".repeat(129);
        Task task = createValidBuilder().taskType(longType).build();
        assertThatThrownBy(() -> TaskValidator.validate(task))
                .isInstanceOf(InvalidTaskException.class)
                .hasMessageContaining("exceeds maximum length of 128");
    }

    @Test
    @DisplayName("Blank tenantId must throw InvalidTaskException")
    void testBlankTenantIdThrows() {
        Task task = createValidBuilder().tenantId("").build();
        assertThatThrownBy(() -> TaskValidator.validate(task))
                .isInstanceOf(InvalidTaskException.class)
                .hasMessageContaining("tenantId must not be null or blank");
    }

    @Test
    @DisplayName("Negative timeout must throw InvalidTaskException")
    void testNegativeTimeoutThrows() {
        Task task = createValidBuilder().timeout(Duration.ofSeconds(-10)).build();
        assertThatThrownBy(() -> TaskValidator.validate(task))
                .isInstanceOf(InvalidTaskException.class)
                .hasMessageContaining("timeout must not be negative");
    }

    @Test
    @DisplayName("scheduledAt before createdAt must throw InvalidTaskException")
    void testScheduledAtBeforeCreatedAtThrows() {
        Instant now = Instant.now();
        Task task = createValidBuilder()
                .createdAt(now)
                .scheduledAt(now.minusSeconds(10))
                .build();

        assertThatThrownBy(() -> TaskValidator.validate(task))
                .isInstanceOf(InvalidTaskException.class)
                .hasMessageContaining("scheduledAt")
                .hasMessageContaining("cannot be before createdAt");
    }

    @Test
    @DisplayName("deadline before createdAt must throw InvalidTaskException")
    void testDeadlineBeforeCreatedAtThrows() {
        Instant now = Instant.now();
        Task task = createValidBuilder()
                .createdAt(now)
                .deadline(now.minusSeconds(30))
                .build();

        assertThatThrownBy(() -> TaskValidator.validate(task))
                .isInstanceOf(InvalidTaskException.class)
                .hasMessageContaining("deadline")
                .hasMessageContaining("cannot be before createdAt");
    }

    @Test
    @DisplayName("deadline before scheduledAt must throw InvalidTaskException")
    void testDeadlineBeforeScheduledAtThrows() {
        Instant now = Instant.now();
        Task task = createValidBuilder()
                .createdAt(now)
                .scheduledAt(now.plusSeconds(100))
                .deadline(now.plusSeconds(50))
                .build();

        assertThatThrownBy(() -> TaskValidator.validate(task))
                .isInstanceOf(InvalidTaskException.class)
                .hasMessageContaining("deadline")
                .hasMessageContaining("cannot be before scheduledAt");
    }

    @Test
    @DisplayName("Blank idempotencyKey must throw InvalidTaskException")
    void testBlankIdempotencyKeyThrows() {
        Task task = createValidBuilder().idempotencyKey("   ").build();
        assertThatThrownBy(() -> TaskValidator.validate(task))
                .isInstanceOf(InvalidTaskException.class)
                .hasMessageContaining("idempotencyKey cannot be empty or whitespace");
    }

    @Test
    @DisplayName("Overly long idempotencyKey must throw InvalidTaskException")
    void testLongIdempotencyKeyThrows() {
        String longKey = "k".repeat(257);
        Task task = createValidBuilder().idempotencyKey(longKey).build();
        assertThatThrownBy(() -> TaskValidator.validate(task))
                .isInstanceOf(InvalidTaskException.class)
                .hasMessageContaining("idempotencyKey exceeds maximum length of 256");
    }
}
