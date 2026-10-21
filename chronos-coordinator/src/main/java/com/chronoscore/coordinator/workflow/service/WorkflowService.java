package com.chronoscore.coordinator.workflow.service;

import com.chronoscore.common.domain.workflow.WorkflowDefinition;
import com.chronoscore.common.domain.workflow.WorkflowEdge;
import com.chronoscore.common.domain.workflow.WorkflowNodeDefinition;
import com.chronoscore.common.domain.workflow.WorkflowVersion;
import com.chronoscore.common.enums.WorkflowVersionStatus;
import com.chronoscore.common.exceptions.WorkflowNotFoundException;
import com.chronoscore.common.validation.WorkflowDagValidator;
import com.chronoscore.coordinator.workflow.repository.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing workflow definitions, version snapshots, and DAG immutability invariants.
 */
@Service
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private final WorkflowRepository workflowRepository;

    public WorkflowService(WorkflowRepository workflowRepository) {
        this.workflowRepository = Objects.requireNonNull(workflowRepository, "workflowRepository must not be null");
    }

    /**
     * Creates a new reusable workflow definition header.
     *
     * @param name        unique workflow name.
     * @param description optional descriptive text.
     * @return persisted definition.
     */
    @Transactional
    public WorkflowDefinition createWorkflow(String name, String description) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Workflow name must not be blank");
        }

        WorkflowDefinition def = WorkflowDefinition.builder()
                .workflowId(UUID.randomUUID())
                .name(name.trim())
                .description(description != null ? description.trim() : "")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        WorkflowDefinition saved = workflowRepository.saveWorkflow(def);
        log.info("Created workflow definition '{}' [{}]", saved.getName(), saved.getWorkflowId());
        return saved;
    }

    public Optional<WorkflowDefinition> getWorkflow(UUID workflowId) {
        return workflowRepository.findWorkflowById(workflowId);
    }

    public Optional<WorkflowDefinition> getWorkflowByName(String name) {
        return workflowRepository.findWorkflowByName(name);
    }

    /**
     * Creates a new DRAFT version for a workflow.
     *
     * @param workflowId owning workflow ID.
     * @param nodes      DAG nodes.
     * @param edges      dependency edges.
     * @return created version in DRAFT status.
     */
    @Transactional
    public WorkflowVersion createDraftVersion(UUID workflowId,
                                              List<WorkflowNodeDefinition> nodes,
                                              List<WorkflowEdge> edges) {
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        WorkflowDefinition workflow = workflowRepository.findWorkflowById(workflowId)
                .orElseThrow(() -> new WorkflowNotFoundException(workflowId));

        // Determine next version number
        Optional<WorkflowVersion> latest = workflowRepository.findLatestPublishedVersion(workflowId);
        int nextVersion = latest.map(v -> v.getVersion() + 1).orElse(1);

        WorkflowVersion draft = WorkflowVersion.builder()
                .versionId(UUID.randomUUID())
                .workflowId(workflow.getWorkflowId())
                .version(nextVersion)
                .status(WorkflowVersionStatus.DRAFT)
                .nodes(nodes != null ? nodes : List.of())
                .edges(edges != null ? edges : List.of())
                .createdAt(Instant.now())
                .build();

        WorkflowVersion saved = workflowRepository.saveVersion(draft);
        log.info("Created draft version v{} [{}] for workflow '{}'", saved.getVersion(), saved.getVersionId(), workflow.getName());
        return saved;
    }

    /**
     * Validates and publishes a draft workflow version.
     * Once published, the version is strictly immutable.
     *
     * @param versionId version to publish.
     * @return published version.
     */
    @Transactional
    public WorkflowVersion publishVersion(UUID versionId) {
        Objects.requireNonNull(versionId, "versionId must not be null");

        WorkflowVersion version = workflowRepository.findVersionById(versionId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow version not found with ID: " + versionId));

        if (version.getStatus() == WorkflowVersionStatus.PUBLISHED) {
            log.debug("Workflow version [{}] is already published", versionId);
            return version;
        }

        if (version.getStatus() == WorkflowVersionStatus.DEPRECATED) {
            throw new IllegalStateException(String.format("Cannot publish deprecated workflow version [%s]", versionId));
        }

        // Validate DAG structure: cycles, orphan nodes, root node existence, etc.
        WorkflowDagValidator.validate(version.getNodes(), version.getEdges());

        workflowRepository.updateVersionStatus(versionId, WorkflowVersionStatus.PUBLISHED);
        log.info("Successfully published workflow version v{} [{}] for workflow [{}]",
                version.getVersion(), versionId, version.getWorkflowId());

        return version.toBuilder()
                .status(WorkflowVersionStatus.PUBLISHED)
                .build();
    }

    /**
     * Deprecates a published workflow version.
     * New executions will be prohibited from starting with this version.
     *
     * @param versionId version to deprecate.
     * @return deprecated version.
     */
    @Transactional
    public WorkflowVersion deprecateVersion(UUID versionId) {
        Objects.requireNonNull(versionId, "versionId must not be null");

        WorkflowVersion version = workflowRepository.findVersionById(versionId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow version not found with ID: " + versionId));

        workflowRepository.updateVersionStatus(versionId, WorkflowVersionStatus.DEPRECATED);
        log.info("Deprecated workflow version v{} [{}] for workflow [{}]",
                version.getVersion(), versionId, version.getWorkflowId());

        return version.toBuilder()
                .status(WorkflowVersionStatus.DEPRECATED)
                .build();
    }

    public Optional<WorkflowVersion> getVersion(UUID versionId) {
        return workflowRepository.findVersionById(versionId);
    }

    public Optional<WorkflowVersion> getLatestPublishedVersion(UUID workflowId) {
        return workflowRepository.findLatestPublishedVersion(workflowId);
    }

    public Optional<WorkflowVersion> getVersionByNumber(UUID workflowId, int version) {
        return workflowRepository.findVersionByNumber(workflowId, version);
    }

    public List<WorkflowDefinition> listWorkflows(int limit, int offset) {
        return workflowRepository.findAllWorkflows(limit, offset);
    }

    public int countWorkflows() {
        return workflowRepository.countWorkflows();
    }

    public List<WorkflowVersion> getWorkflowVersions(UUID workflowId) {
        return workflowRepository.findVersionsByWorkflowId(workflowId);
    }
}
