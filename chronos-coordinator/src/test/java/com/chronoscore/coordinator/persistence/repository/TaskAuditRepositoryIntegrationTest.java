package com.chronoscore.coordinator.persistence.repository;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.coordinator.persistence.model.TaskAuditRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TaskAuditRepositoryIntegrationTest {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskAuditRepository auditRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID taskId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM chronos_outbox_events");
        jdbcTemplate.execute("DELETE FROM chronos_task_audit_log");
        jdbcTemplate.execute("DELETE FROM chronos_execution_attempts");
        jdbcTemplate.execute("DELETE FROM chronos_tasks");

        taskId = UUID.randomUUID();
        taskRepository.save(Task.builder()
                .taskId(taskId)
                .taskType("AUDIT_TEST")
                .payload("{}")
                .priority(TaskPriority.NORMAL)
                .status(TaskStatus.CREATED)
                .tenantId("tenant-audit")
                .createdAt(Instant.now())
                .maxAttempts(3)
                .currentAttempt(0)
                .build());
    }

    @Test
    @DisplayName("append and findByTaskId: appends records and retrieves them chronologically")
    void testAppendAndFindByTaskId() {
        Instant t1 = Instant.now().minusSeconds(10);
        Instant t2 = Instant.now().minusSeconds(5);
        Instant t3 = Instant.now();

        TaskAuditRecord record1 = TaskAuditRecord.builder()
                .auditId(UUID.randomUUID())
                .taskId(taskId)
                .fromStatus(null)
                .toStatus(TaskStatus.CREATED)
                .attemptNumber(0)
                .reason("Task created")
                .createdAt(t1)
                .build();

        TaskAuditRecord record2 = TaskAuditRecord.builder()
                .auditId(UUID.randomUUID())
                .taskId(taskId)
                .fromStatus(TaskStatus.CREATED)
                .toStatus(TaskStatus.QUEUED)
                .attemptNumber(0)
                .reason("Enqueued for scheduling")
                .createdAt(t2)
                .build();

        TaskAuditRecord record3 = TaskAuditRecord.builder()
                .auditId(UUID.randomUUID())
                .taskId(taskId)
                .fromStatus(TaskStatus.QUEUED)
                .toStatus(TaskStatus.RUNNING)
                .attemptNumber(1)
                .reason("Dispatched to worker-alpha")
                .metadata("{\"workerId\":\"worker-alpha\"}")
                .createdAt(t3)
                .build();

        auditRepository.append(record1);
        auditRepository.append(record2);
        auditRepository.append(record3);

        List<TaskAuditRecord> history = auditRepository.findByTaskId(taskId);
        assertThat(history).hasSize(3);

        assertThat(history.get(0).getToStatus()).isEqualTo(TaskStatus.CREATED);
        assertThat(history.get(0).getReason()).isEqualTo("Task created");

        assertThat(history.get(1).getFromStatus()).isEqualTo(TaskStatus.CREATED);
        assertThat(history.get(1).getToStatus()).isEqualTo(TaskStatus.QUEUED);

        assertThat(history.get(2).getFromStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(history.get(2).getToStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(history.get(2).getMetadata()).contains("worker-alpha");
    }
}
