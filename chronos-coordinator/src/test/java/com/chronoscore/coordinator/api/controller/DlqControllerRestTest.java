package com.chronoscore.coordinator.api.controller;

import com.chronoscore.common.domain.ExecutionAttempt;
import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.ExecutionAttemptStatus;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.coordinator.api.advice.GlobalExceptionHandler;
import com.chronoscore.coordinator.api.dto.request.DlqArchiveRequest;
import com.chronoscore.coordinator.api.security.DevJwtTokenHelper;
import com.chronoscore.coordinator.api.security.JwtSecurityProperties;
import com.chronoscore.coordinator.api.security.SecurityConfig;
import com.chronoscore.coordinator.api.security.TenantSecurityContext;
import com.chronoscore.coordinator.persistence.model.TaskAuditRecord;
import com.chronoscore.coordinator.recovery.dlq.DlqService;
import com.chronoscore.coordinator.recovery.dlq.DlqTaskInspection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DlqController.class)
@Import({SecurityConfig.class, JwtSecurityProperties.class, DevJwtTokenHelper.class, TenantSecurityContext.class, GlobalExceptionHandler.class})
class DlqControllerRestTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DevJwtTokenHelper tokenHelper;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DlqService dlqService;

    private String getOperatorToken() {
        return tokenHelper.generateToken("op-user", "tenant-alpha", List.of("OPERATOR"));
    }

    @Test
    @DisplayName("GET /api/v1/dlq lists paginated DLQ tasks (200 OK)")
    void listDlqTasksSuccess() throws Exception {
        String token = getOperatorToken();
        Task dlqTask = Task.builder()
                .taskId(UUID.randomUUID())
                .taskType("BILLING")
                .payload("{}")
                .status(TaskStatus.DLQ)
                .tenantId("tenant-alpha")
                .build();

        when(dlqService.countDlqTasks("tenant-alpha")).thenReturn(1);
        when(dlqService.listDlqTasks("tenant-alpha", 20, 0)).thenReturn(List.of(dlqTask));

        mockMvc.perform(get("/api/v1/dlq?page=0&size=20")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].status").value("DLQ"))
                .andExpect(jsonPath("$.items[0].tenantId").value("tenant-alpha"));
    }

    @Test
    @DisplayName("GET /api/v1/dlq/{taskId} returns inspection details with attempts and audits")
    void inspectDlqTaskSuccess() throws Exception {
        String token = getOperatorToken();
        UUID taskId = UUID.randomUUID();

        Task dlqTask = Task.builder()
                .taskId(taskId)
                .taskType("BILLING")
                .payload("{}")
                .status(TaskStatus.DLQ)
                .tenantId("tenant-alpha")
                .build();

        ExecutionAttempt attempt = ExecutionAttempt.builder()
                .attemptId(UUID.randomUUID())
                .taskId(taskId)
                .workerId("worker-1")
                .attemptNumber(1)
                .status(ExecutionAttemptStatus.FAILED)
                .startedAt(Instant.now().minusSeconds(60))
                .completedAt(Instant.now().minusSeconds(55))
                .errorMessage("Connection refused")
                .build();

        TaskAuditRecord audit = TaskAuditRecord.builder()
                .taskId(taskId)
                .fromStatus(TaskStatus.RUNNING)
                .toStatus(TaskStatus.DLQ)
                .attemptNumber(3)
                .reason("Max attempts exhausted")
                .createdAt(Instant.now())
                .build();

        DlqTaskInspection inspection = new DlqTaskInspection(dlqTask, List.of(attempt), List.of(audit));
        when(dlqService.inspectTask(taskId)).thenReturn(Optional.of(inspection));

        mockMvc.perform(get("/api/v1/dlq/" + taskId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.task.status").value("DLQ"))
                .andExpect(jsonPath("$.attempts[0].workerId").value("worker-1"))
                .andExpect(jsonPath("$.attempts[0].errorMessage").value("Connection refused"))
                .andExpect(jsonPath("$.auditRecords[0].reason").value("Max attempts exhausted"));
    }

    @Test
    @DisplayName("POST /api/v1/dlq/{taskId}/replay replays DLQ task to QUEUED (200 OK)")
    void replayDlqTaskSuccess() throws Exception {
        String token = getOperatorToken();
        UUID taskId = UUID.randomUUID();

        Task dlqTask = Task.builder()
                .taskId(taskId)
                .taskType("BILLING")
                .payload("{}")
                .status(TaskStatus.DLQ)
                .tenantId("tenant-alpha")
                .build();

        Task replayedTask = dlqTask.toBuilder().status(TaskStatus.QUEUED).build();

        DlqTaskInspection inspection = new DlqTaskInspection(dlqTask, Collections.emptyList(), Collections.emptyList());
        when(dlqService.inspectTask(taskId)).thenReturn(Optional.of(inspection));
        when(dlqService.replayTask(eq(taskId), anyString())).thenReturn(replayedTask);

        mockMvc.perform(post("/api/v1/dlq/" + taskId + "/replay")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    @DisplayName("POST /api/v1/dlq/{taskId}/archive archives DLQ task (200 OK)")
    void archiveDlqTaskSuccess() throws Exception {
        String token = getOperatorToken();
        UUID taskId = UUID.randomUUID();

        Task dlqTask = Task.builder()
                .taskId(taskId)
                .taskType("BILLING")
                .payload("{}")
                .status(TaskStatus.DLQ)
                .tenantId("tenant-alpha")
                .build();

        Task archivedTask = dlqTask.toBuilder().status(TaskStatus.CANCELLED).build();

        DlqTaskInspection inspection = new DlqTaskInspection(dlqTask, Collections.emptyList(), Collections.emptyList());
        when(dlqService.inspectTask(taskId)).thenReturn(Optional.of(inspection));
        when(dlqService.archiveTask(eq(taskId), eq("Manual discard by ops"))).thenReturn(archivedTask);

        DlqArchiveRequest req = new DlqArchiveRequest("Manual discard by ops");

        mockMvc.perform(post("/api/v1/dlq/" + taskId + "/archive")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
