package com.chronoscore.coordinator.api.controller;

import com.chronoscore.common.domain.workflow.WorkflowDefinition;
import com.chronoscore.common.domain.workflow.WorkflowExecution;
import com.chronoscore.common.domain.workflow.WorkflowExecutionNode;
import com.chronoscore.common.domain.workflow.WorkflowVersion;
import com.chronoscore.common.exceptions.WorkflowNotFoundException;
import com.chronoscore.coordinator.api.dto.request.CreateWorkflowRequest;
import com.chronoscore.coordinator.api.dto.request.CreateWorkflowVersionRequest;
import com.chronoscore.coordinator.api.dto.request.ExecuteWorkflowRequest;
import com.chronoscore.coordinator.api.dto.response.PagedResponse;
import com.chronoscore.coordinator.api.dto.response.WorkflowDefinitionResponse;
import com.chronoscore.coordinator.api.dto.response.WorkflowExecutionDetailsResponse;
import com.chronoscore.coordinator.api.dto.response.WorkflowExecutionResponse;
import com.chronoscore.coordinator.api.dto.response.WorkflowNodeResponse;
import com.chronoscore.coordinator.api.dto.response.WorkflowVersionResponse;
import com.chronoscore.coordinator.api.security.TenantSecurityContext;
import com.chronoscore.coordinator.workflow.service.WorkflowExecutionService;
import com.chronoscore.coordinator.workflow.service.WorkflowService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST controller exposing workflow definition, version management, and DAG execution operations.
 */
@RestController
@RequestMapping
public class WorkflowController {

    private final WorkflowService workflowService;
    private final WorkflowExecutionService executionService;
    private final TenantSecurityContext securityContext;

    public WorkflowController(WorkflowService workflowService,
                              WorkflowExecutionService executionService,
                              TenantSecurityContext securityContext) {
        this.workflowService = Objects.requireNonNull(workflowService, "workflowService must not be null");
        this.executionService = Objects.requireNonNull(executionService, "executionService must not be null");
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext must not be null");
    }

    @PostMapping("/api/v1/workflows")
    public ResponseEntity<WorkflowDefinitionResponse> createWorkflow(@Valid @RequestBody CreateWorkflowRequest request) {
        WorkflowDefinition def = workflowService.createWorkflow(request.name(), request.description());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(def.getWorkflowId())
                .toUri();
        return ResponseEntity.created(location).body(WorkflowDefinitionResponse.from(def));
    }

    @GetMapping("/api/v1/workflows")
    public ResponseEntity<PagedResponse<WorkflowDefinitionResponse>> listWorkflows(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        int offset = safePage * safeSize;

        List<WorkflowDefinition> workflows = workflowService.listWorkflows(safeSize, offset);
        int total = workflowService.countWorkflows();

        List<WorkflowDefinitionResponse> items = workflows.stream()
                .map(WorkflowDefinitionResponse::from)
                .toList();

        return ResponseEntity.ok(PagedResponse.of(items, safePage, safeSize, total));
    }

    @GetMapping("/api/v1/workflows/{workflowId}")
    public ResponseEntity<WorkflowDefinitionResponse> getWorkflow(@PathVariable UUID workflowId) {
        WorkflowDefinition def = workflowService.getWorkflow(workflowId)
                .orElseThrow(() -> new WorkflowNotFoundException(workflowId));
        return ResponseEntity.ok(WorkflowDefinitionResponse.from(def));
    }

    @GetMapping("/api/v1/workflows/{workflowId}/versions")
    public ResponseEntity<List<WorkflowVersionResponse>> getWorkflowVersions(@PathVariable UUID workflowId) {
        // Verify workflow exists
        workflowService.getWorkflow(workflowId)
                .orElseThrow(() -> new WorkflowNotFoundException(workflowId));

        List<WorkflowVersion> versions = workflowService.getWorkflowVersions(workflowId);
        List<WorkflowVersionResponse> items = versions.stream()
                .map(WorkflowVersionResponse::from)
                .toList();
        return ResponseEntity.ok(items);
    }

    @PostMapping("/api/v1/workflows/{workflowId}/versions")
    public ResponseEntity<WorkflowVersionResponse> createDraftVersion(
            @PathVariable UUID workflowId,
            @Valid @RequestBody CreateWorkflowVersionRequest request) {

        WorkflowVersion draft = workflowService.createDraftVersion(
                workflowId,
                request.toNodeDefinitions(),
                request.toEdgeDefinitions()
        );

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{version}")
                .buildAndExpand(draft.getVersion())
                .toUri();

        return ResponseEntity.created(location).body(WorkflowVersionResponse.from(draft));
    }

    @PostMapping("/api/v1/workflows/{workflowId}/versions/{version}/publish")
    public ResponseEntity<WorkflowVersionResponse> publishVersion(
            @PathVariable UUID workflowId,
            @PathVariable int version) {

        WorkflowVersion targetVersion = workflowService.getVersionByNumber(workflowId, version)
                .orElseThrow(() -> new WorkflowNotFoundException(
                        String.format("Version v%d not found for workflow %s", version, workflowId)));

        WorkflowVersion published = workflowService.publishVersion(targetVersion.getVersionId());
        return ResponseEntity.ok(WorkflowVersionResponse.from(published));
    }

    @PostMapping("/api/v1/workflows/{workflowId}/versions/{version}/deprecate")
    public ResponseEntity<WorkflowVersionResponse> deprecateVersion(
            @PathVariable UUID workflowId,
            @PathVariable int version) {

        WorkflowVersion targetVersion = workflowService.getVersionByNumber(workflowId, version)
                .orElseThrow(() -> new WorkflowNotFoundException(
                        String.format("Version v%d not found for workflow %s", version, workflowId)));

        WorkflowVersion deprecated = workflowService.deprecateVersion(targetVersion.getVersionId());
        return ResponseEntity.ok(WorkflowVersionResponse.from(deprecated));
    }

    @PostMapping("/api/v1/workflows/{workflowId}/execute")
    public ResponseEntity<WorkflowExecutionResponse> executeWorkflow(
            @PathVariable UUID workflowId,
            @RequestBody(required = false) ExecuteWorkflowRequest request) {

        Integer versionNum = (request != null) ? request.version() : null;
        if (versionNum == null && request != null && request.versionId() != null) {
            versionNum = workflowService.getVersion(request.versionId())
                    .map(WorkflowVersion::getVersion)
                    .orElse(null);
        }

        String targetTenantId = (request != null) ? request.tenantId() : null;
        String effectiveTenantId = securityContext.resolveEffectiveTenantId(targetTenantId);

        WorkflowExecution execution = executionService.startExecution(workflowId, versionNum, effectiveTenantId);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/workflow-executions/{id}")
                .buildAndExpand(execution.getExecutionId())
                .toUri();

        return ResponseEntity.created(location).body(WorkflowExecutionResponse.from(execution));
    }

    @GetMapping("/api/v1/workflow-executions/{executionId}")
    public ResponseEntity<WorkflowExecutionDetailsResponse> getExecutionDetails(@PathVariable UUID executionId) {
        WorkflowExecution exec = executionService.getExecution(executionId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow execution not found: " + executionId));

        // IDOR prevention: verify tenant ownership
        securityContext.assertCanAccessTenant(exec.getTenantId());

        List<WorkflowExecutionNode> nodes = executionService.getExecutionNodes(executionId);
        List<WorkflowNodeResponse> nodeResponses = nodes.stream()
                .map(WorkflowNodeResponse::from)
                .toList();

        return ResponseEntity.ok(new WorkflowExecutionDetailsResponse(
                WorkflowExecutionResponse.from(exec),
                nodeResponses
        ));
    }

    @PostMapping("/api/v1/workflow-executions/{executionId}/cancel")
    public ResponseEntity<WorkflowExecutionResponse> cancelExecution(
            @PathVariable UUID executionId,
            @RequestParam(required = false, defaultValue = "Operator cancelled workflow execution via REST") String reason) {

        WorkflowExecution exec = executionService.getExecution(executionId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow execution not found: " + executionId));

        securityContext.assertCanAccessTenant(exec.getTenantId());

        WorkflowExecution cancelled = executionService.cancelExecution(executionId, reason);
        return ResponseEntity.ok(WorkflowExecutionResponse.from(cancelled));
    }
}
