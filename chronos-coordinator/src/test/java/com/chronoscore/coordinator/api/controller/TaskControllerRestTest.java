package com.chronoscore.coordinator.api.controller;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.common.exceptions.InvalidTaskTransitionException;
import com.chronoscore.common.exceptions.TenantRateLimitExceededException;
import com.chronoscore.coordinator.api.advice.GlobalExceptionHandler;
import com.chronoscore.coordinator.api.dto.request.CreateTaskRequest;
import com.chronoscore.coordinator.api.dto.request.TaskCancelRequest;
import com.chronoscore.coordinator.api.dto.request.TaskRetryRequest;
import com.chronoscore.coordinator.api.security.DevJwtTokenHelper;
import com.chronoscore.coordinator.api.security.JwtSecurityProperties;
import com.chronoscore.coordinator.api.security.SecurityConfig;
import com.chronoscore.coordinator.api.security.TenantSecurityContext;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import com.chronoscore.coordinator.persistence.service.TransactionalTaskService;
import com.chronoscore.coordinator.recovery.dlq.DlqService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TaskController.class)
@Import({SecurityConfig.class, JwtSecurityProperties.class, DevJwtTokenHelper.class, TenantSecurityContext.class, GlobalExceptionHandler.class})
class TaskControllerRestTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DevJwtTokenHelper tokenHelper;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TransactionalTaskService taskService;

    @MockBean
    private TaskRepository taskRepository;

    @MockBean
    private DlqService dlqService;

    private String getOperatorToken() {
        return tokenHelper.generateToken("test-operator", "tenant-alpha", List.of("OPERATOR"));
    }

    @Test
    @DisplayName("POST /api/v1/tasks creates a task and returns 201 Created with Location header")
    void createTaskSuccess() throws Exception {
        String token = getOperatorToken();
        CreateTaskRequest request = new CreateTaskRequest(
                "IMAGE_PROCESS",
                "{\"size\":\"1080p\"}",
                TaskPriority.HIGH,
                "tenant-alpha",
                "client-idemp-1",
                null,
                null,
                Duration.ofSeconds(60),
                3,
                "user-42"
        );

        when(taskService.submitTask(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.taskType").value("IMAGE_PROCESS"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.tenantId").value("tenant-alpha"))
                .andExpect(jsonPath("$.idempotencyKey").value("client-idemp-1"))
                .andExpect(jsonPath("$.concurrencyKey").value("user-42"))
                .andExpect(jsonPath("$.maxAttempts").value(3));
    }

    @Test
    @DisplayName("POST /api/v1/tasks fails validation when taskType is blank (400 Bad Request)")
    void createTaskValidationFailure() throws Exception {
        String token = getOperatorToken();
        CreateTaskRequest request = new CreateTaskRequest(
                "", // Blank taskType
                "{}",
                TaskPriority.NORMAL,
                null, null, null, null, null, null, null
        );

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    @DisplayName("POST /api/v1/tasks with Idempotency-Key header takes precedence over body key")
    void idempotencyKeyHeaderPrecedence() throws Exception {
        String token = getOperatorToken();
        CreateTaskRequest request = new CreateTaskRequest(
                "EXPORT",
                "{}",
                TaskPriority.NORMAL,
                null,
                "body-key-xyz",
                null, null, null, null, null
        );

        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        when(taskService.submitTask(taskCaptor.capture(), any(), any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "header-key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idempotencyKey").value("header-key-123"));

        assertThat(taskCaptor.getValue().getIdempotencyKey()).isEqualTo("header-key-123");
    }

    @Test
    @DisplayName("POST /api/v1/tasks duplicate idempotency submission returns 200 OK")
    void duplicateTaskReturns200Ok() throws Exception {
        String token = getOperatorToken();
        CreateTaskRequest request = new CreateTaskRequest(
                "EXPORT",
                "{}",
                TaskPriority.NORMAL,
                null,
                "dedup-key-999",
                null, null, null, null, null
        );

        UUID originalTaskId = UUID.randomUUID();
        Task existingTask = Task.builder()
                .taskId(originalTaskId)
                .taskType("EXPORT")
                .payload("{}")
                .status(TaskStatus.SUCCEEDED)
                .tenantId("tenant-alpha")
                .idempotencyKey("dedup-key-999")
                .build();

        // submitTask returns the existing task (its taskId won't match newly generated one)
        when(taskService.submitTask(any(), any(), any())).thenReturn(existingTask);

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(originalTaskId.toString()))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    @DisplayName("POST /api/v1/tasks fails when deadline is before scheduledAt (400 Bad Request)")
    void deadlineBeforeScheduledAtFails() throws Exception {
        String token = getOperatorToken();
        Instant now = Instant.now();
        CreateTaskRequest request = new CreateTaskRequest(
                "BATCH",
                "{}",
                TaskPriority.NORMAL,
                null,
                null,
                now.plusSeconds(3600), // scheduled in 1 hour
                now.minusSeconds(60),  // deadline in past
                null, null, null
        );

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("GET /api/v1/tasks lists paginated tasks with filters")
    void listTasksPaginated() throws Exception {
        String token = getOperatorToken();
        Task t1 = Task.builder()
                .taskId(UUID.randomUUID())
                .taskType("BATCH")
                .payload("{}")
                .tenantId("tenant-alpha")
                .status(TaskStatus.QUEUED)
                .build();

        when(taskRepository.countTasksWithFilter(eq("tenant-alpha"), eq(TaskStatus.QUEUED), any(), any())).thenReturn(1);
        when(taskRepository.findTasksWithFilter(eq("tenant-alpha"), eq(TaskStatus.QUEUED), any(), any(), eq(20), eq(0)))
                .thenReturn(List.of(t1));

        mockMvc.perform(get("/api/v1/tasks?page=0&size=20&status=QUEUED")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].taskType").value("BATCH"));
    }

    @Test
    @DisplayName("GET /api/v1/tasks/{taskId} returns task details")
    void getTaskDetails() throws Exception {
        String token = getOperatorToken();
        UUID taskId = UUID.randomUUID();
        Task task = Task.builder()
                .taskId(taskId)
                .taskType("BILLING")
                .payload("{\"inv\":\"123\"}")
                .tenantId("tenant-alpha")
                .status(TaskStatus.RUNNING)
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        mockMvc.perform(get("/api/v1/tasks/" + taskId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.taskType").value("BILLING"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    @DisplayName("POST /api/v1/tasks/{taskId}/cancel cancels task successfully (200 OK)")
    void cancelTaskSuccess() throws Exception {
        String token = getOperatorToken();
        UUID taskId = UUID.randomUUID();
        Task runningTask = Task.builder()
                .taskId(taskId)
                .taskType("REPORT")
                .payload("{}")
                .tenantId("tenant-alpha")
                .status(TaskStatus.RUNNING)
                .build();

        Task cancelledTask = runningTask.toBuilder().status(TaskStatus.CANCELLED).build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(runningTask));
        when(taskService.cancelTask(eq(taskId), eq("operator requested cancel"))).thenReturn(cancelledTask);

        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/cancel")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"operator requested cancel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("POST /api/v1/tasks/{taskId}/cancel on already completed task returns 409 Conflict")
    void cancelTaskConflictOnTerminalState() throws Exception {
        String token = getOperatorToken();
        UUID taskId = UUID.randomUUID();
        Task terminalTask = Task.builder()
                .taskId(taskId)
                .taskType("REPORT")
                .payload("{}")
                .tenantId("tenant-alpha")
                .status(TaskStatus.SUCCEEDED)
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(terminalTask));
        when(taskService.cancelTask(eq(taskId), anyString()))
                .thenThrow(new InvalidTaskTransitionException(taskId, TaskStatus.SUCCEEDED, TaskStatus.CANCELLED));

        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("Task submission rejected by admission rate limit returns 429 Too Many Requests")
    void taskAdmissionRateLimitExceeded() throws Exception {
        String token = getOperatorToken();
        CreateTaskRequest request = new CreateTaskRequest(
                "BATCH",
                "{}",
                TaskPriority.NORMAL,
                null, null, null, null, null, null, null
        );

        when(taskService.submitTask(any(), any(), any()))
                .thenThrow(new TenantRateLimitExceededException("tenant-alpha", 10.0));

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
