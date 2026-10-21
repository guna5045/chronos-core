package com.chronoscore.common.domain.workflow;

import com.chronoscore.common.enums.WorkflowVersionStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable entity representing a specific, versioned snapshot of a workflow DAG.
 */
public final class WorkflowVersion {

    private final UUID versionId;
    private final UUID workflowId;
    private final int version;
    private final WorkflowVersionStatus status;
    private final List<WorkflowNodeDefinition> nodes;
    private final List<WorkflowEdge> edges;
    private final Instant createdAt;

    private WorkflowVersion(Builder builder) {
        this.versionId = builder.versionId != null ? builder.versionId : UUID.randomUUID();
        this.workflowId = Objects.requireNonNull(builder.workflowId, "workflowId must not be null");
        this.version = builder.version > 0 ? builder.version : 1;
        this.status = builder.status != null ? builder.status : WorkflowVersionStatus.DRAFT;
        this.nodes = Collections.unmodifiableList(new ArrayList<>(builder.nodes));
        this.edges = Collections.unmodifiableList(new ArrayList<>(builder.edges));
        this.createdAt = builder.createdAt != null ? builder.createdAt : Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID getVersionId() {
        return versionId;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public int getVersion() {
        return version;
    }

    public WorkflowVersionStatus getStatus() {
        return status;
    }

    public List<WorkflowNodeDefinition> getNodes() {
        return nodes;
    }

    public List<WorkflowEdge> getEdges() {
        return edges;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Builder toBuilder() {
        return new Builder()
                .versionId(versionId)
                .workflowId(workflowId)
                .version(version)
                .status(status)
                .nodes(nodes)
                .edges(edges)
                .createdAt(createdAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WorkflowVersion that = (WorkflowVersion) o;
        return Objects.equals(versionId, that.versionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(versionId);
    }

    @Override
    public String toString() {
        return "WorkflowVersion{" +
                "versionId=" + versionId +
                ", workflowId=" + workflowId +
                ", version=" + version +
                ", status=" + status +
                ", nodesCount=" + nodes.size() +
                ", edgesCount=" + edges.size() +
                '}';
    }

    public static final class Builder {
        private UUID versionId;
        private UUID workflowId;
        private int version = 1;
        private WorkflowVersionStatus status = WorkflowVersionStatus.DRAFT;
        private List<WorkflowNodeDefinition> nodes = Collections.emptyList();
        private List<WorkflowEdge> edges = Collections.emptyList();
        private Instant createdAt;

        private Builder() {
        }

        public Builder versionId(UUID versionId) {
            this.versionId = versionId;
            return this;
        }

        public Builder workflowId(UUID workflowId) {
            this.workflowId = workflowId;
            return this;
        }

        public Builder version(int version) {
            this.version = version;
            return this;
        }

        public Builder status(WorkflowVersionStatus status) {
            this.status = status;
            return this;
        }

        public Builder nodes(List<WorkflowNodeDefinition> nodes) {
            this.nodes = nodes != null ? nodes : Collections.emptyList();
            return this;
        }

        public Builder edges(List<WorkflowEdge> edges) {
            this.edges = edges != null ? edges : Collections.emptyList();
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public WorkflowVersion build() {
            return new WorkflowVersion(this);
        }
    }
}
