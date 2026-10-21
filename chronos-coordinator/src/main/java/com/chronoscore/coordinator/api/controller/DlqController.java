package com.chronoscore.coordinator.api.controller;

import com.chronoscore.common.domain.Task;
import com.chronoscore.coordinator.api.dto.request.DlqArchiveRequest;
import com.chronoscore.coordinator.api.dto.request.DlqReplayRequest;
import com.chronoscore.coordinator.api.dto.response.DlqInspectionResponse;
import com.chronoscore.coordinator.api.dto.response.PagedResponse;
import com.chronoscore.coordinator.api.dto.response.TaskResponse;
import com.chronoscore.coordinator.api.security.TenantSecurityContext;
import com.chronoscore.coordinator.persistence.exception.TaskNotFoundException;
import com.chronoscore.coordinator.recovery.dlq.DlqService;
import com.chronoscore.coordinator.recovery.dlq.DlqTaskInspection;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST controller exposing Dead-Letter Queue (DLQ) task inspection, archival, and safe replay.
 */
@RestController
@RequestMapping("/api/v1/dlq")
public class DlqController {

    private final DlqService dlqService;
    private final TenantSecurityContext securityContext;

    public DlqController(DlqService dlqService, TenantSecurityContext securityContext) {
        this.dlqService = Objects.requireNonNull(dlqService, "dlqService must not be null");
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext must not be null");
    }

    @GetMapping
    public ResponseEntity<PagedResponse<TaskResponse>> listDlq(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String tenantId) {

        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        int offset = safePage * safeSize;

        String effectiveTenantId = securityContext.isAdmin()
                ? (tenantId != null && !tenantId.isBlank() ? tenantId.trim() : null)
                : securityContext.resolveEffectiveTenantId(tenantId);

        List<Task> tasks = dlqService.listDlqTasks(effectiveTenantId, safeSize, offset);
        int total = dlqService.countDlqTasks(effectiveTenantId);

        List<TaskResponse> items = tasks.stream().map(TaskResponse::from).toList();
        return ResponseEntity.ok(PagedResponse.of(items, safePage, safeSize, total));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<DlqInspectionResponse> inspectDlqTask(@PathVariable UUID taskId) {
        DlqTaskInspection inspection = dlqService.inspectTask(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        // IDOR prevention: non-admins cannot inspect other tenants' dead-letter tasks
        securityContext.assertCanAccessTenant(inspection.task().getTenantId());

        return ResponseEntity.ok(DlqInspectionResponse.from(inspection));
    }

    @PostMapping("/{taskId}/replay")
    public ResponseEntity<TaskResponse> replayDlqTask(
            @PathVariable UUID taskId,
            @RequestBody(required = false) DlqReplayRequest request) {

        // Inspect to verify existence and tenant authorization
        DlqTaskInspection inspection = dlqService.inspectTask(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        securityContext.assertCanAccessTenant(inspection.task().getTenantId());

        String reason = (request != null && request.reason() != null && !request.reason().isBlank())
                ? request.reason().trim()
                : "Operator triggered DLQ task replay via REST";

        Task replayed = dlqService.replayTask(taskId, reason);
        return ResponseEntity.ok(TaskResponse.from(replayed));
    }

    @PostMapping("/{taskId}/archive")
    public ResponseEntity<TaskResponse> archiveDlqTask(
            @PathVariable UUID taskId,
            @RequestBody(required = false) DlqArchiveRequest request) {

        // Inspect to verify existence and tenant authorization
        DlqTaskInspection inspection = dlqService.inspectTask(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        securityContext.assertCanAccessTenant(inspection.task().getTenantId());

        String reason = (request != null && request.reason() != null && !request.reason().isBlank())
                ? request.reason().trim()
                : "Operator archived DLQ task via REST";

        Task archived = dlqService.archiveTask(taskId, reason);
        return ResponseEntity.ok(TaskResponse.from(archived));
    }
}
