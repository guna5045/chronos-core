package com.chronoscore.coordinator.api.controller;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.coordinator.api.dto.request.CreateTaskRequest;
import com.chronoscore.coordinator.api.dto.request.TaskCancelRequest;
import com.chronoscore.coordinator.api.dto.request.TaskRetryRequest;
import com.chronoscore.coordinator.api.dto.response.PagedResponse;
import com.chronoscore.coordinator.api.dto.response.TaskResponse;
import com.chronoscore.coordinator.api.security.TenantSecurityContext;
import com.chronoscore.coordinator.persistence.exception.TaskNotFoundException;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import com.chronoscore.coordinator.persistence.service.TransactionalTaskService;
import com.chronoscore.coordinator.recovery.dlq.DlqService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST controller exposing task ingestion, query, cancellation, and retry operations.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TransactionalTaskService taskService;
    private final TaskRepository taskRepository;
    private final DlqService dlqService;
    private final TenantSecurityContext securityContext;
    private final com.chronoscore.coordinator.observability.ChronosMetricsService metricsService;

    public TaskController(TransactionalTaskService taskService,
                          TaskRepository taskRepository,
                          DlqService dlqService,
                          TenantSecurityContext securityContext) {
        this(taskService, taskRepository, dlqService, securityContext, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TaskController(TransactionalTaskService taskService,
                          TaskRepository taskRepository,
                          DlqService dlqService,
                          TenantSecurityContext securityContext,
                          @org.springframework.beans.factory.annotation.Autowired(required = false)
                          com.chronoscore.coordinator.observability.ChronosMetricsService metricsService) {
        this.taskService = Objects.requireNonNull(taskService, "taskService must not be null");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        this.dlqService = Objects.requireNonNull(dlqService, "dlqService must not be null");
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext must not be null");
        this.metricsService = metricsService;
    }

    @PostMapping
    public ResponseEntity<TaskResponse> createTask(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @Valid @RequestBody CreateTaskRequest request) {

        // Idempotency precedence: HTTP header takes precedence over request body
        String effectiveIdempotencyKey = (idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank())
                ? idempotencyKeyHeader.trim()
                : (request.idempotencyKey() != null ? request.idempotencyKey().trim() : null);

        // Server-side tenant verification (non-admins are confined to their JWT tenant claim)
        String effectiveTenantId = securityContext.resolveEffectiveTenantId(request.tenantId());

        Instant now = Instant.now();
        Instant scheduledAt = request.scheduledAt() != null ? request.scheduledAt() : now;

        if (request.deadline() != null && request.deadline().isBefore(scheduledAt)) {
            throw new IllegalArgumentException("Task deadline must not be before scheduledAt timestamp");
        }

        UUID taskId = UUID.randomUUID();
        Task task = Task.builder()
                .taskId(taskId)
                .taskType(request.taskType().trim())
                .payload(request.payload())
                .priority(request.priority())
                .status(TaskStatus.QUEUED)
                .tenantId(effectiveTenantId)
                .idempotencyKey(effectiveIdempotencyKey)
                .concurrencyKey(request.concurrencyKey() != null && !request.concurrencyKey().isBlank()
                        ? request.concurrencyKey().trim()
                        : null)
                .createdAt(now)
                .scheduledAt(scheduledAt)
                .deadline(request.deadline())
                .timeout(request.timeout())
                .maxAttempts(request.maxAttempts() != null ? request.maxAttempts() : 3)
                .build();

        String clientInfo = "user=" + securityContext.getUsername();
        Task persisted = taskService.submitTask(task, clientInfo, "api=rest-v1");
        if (metricsService != null) {
            metricsService.recordTaskSubmitted(persisted.getTaskType());
        }

        boolean isNew = persisted.getTaskId().equals(taskId);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(persisted.getTaskId())
                .toUri();

        return isNew
                ? ResponseEntity.created(location).body(TaskResponse.from(persisted))
                : ResponseEntity.ok(TaskResponse.from(persisted));
    }

    @GetMapping
    public ResponseEntity<PagedResponse<TaskResponse>> listTasks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) TaskPriority priority) {

        // Bounded pagination
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        int offset = safePage * safeSize;

        // Scoped tenant resolution
        String effectiveTenantId = securityContext.isAdmin()
                ? (tenantId != null && !tenantId.isBlank() ? tenantId.trim() : null)
                : securityContext.resolveEffectiveTenantId(tenantId);

        List<Task> tasks = taskRepository.findTasksWithFilter(effectiveTenantId, status, taskType, priority, safeSize, offset);
        int total = taskRepository.countTasksWithFilter(effectiveTenantId, status, taskType, priority);

        List<TaskResponse> items = tasks.stream().map(TaskResponse::from).toList();
        return ResponseEntity.ok(PagedResponse.of(items, safePage, safeSize, total));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<TaskResponse> getTask(@PathVariable UUID taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        // IDOR prevention: non-admins cannot inspect other tenants' tasks
        securityContext.assertCanAccessTenant(task.getTenantId());

        return ResponseEntity.ok(TaskResponse.from(task));
    }

    @PostMapping("/{taskId}/retry")
    public ResponseEntity<TaskResponse> retryTask(
            @PathVariable UUID taskId,
            @RequestBody(required = false) TaskRetryRequest request) {

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        securityContext.assertCanAccessTenant(task.getTenantId());

        String reason = (request != null && request.reason() != null && !request.reason().isBlank())
                ? request.reason()
                : "Operator triggered task retry via REST";

        Task replayed = dlqService.replayTask(taskId, reason);
        if (metricsService != null) {
            metricsService.recordTaskRetried(replayed.getTaskType());
        }
        return ResponseEntity.ok(TaskResponse.from(replayed));
    }

    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<TaskResponse> cancelTask(
            @PathVariable UUID taskId,
            @RequestBody(required = false) TaskCancelRequest request) {

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        securityContext.assertCanAccessTenant(task.getTenantId());

        String reason = (request != null && request.reason() != null && !request.reason().isBlank())
                ? request.reason()
                : "Operator cancelled task via REST";

        Task cancelled = taskService.cancelTask(taskId, reason);
        return ResponseEntity.ok(TaskResponse.from(cancelled));
    }
}
