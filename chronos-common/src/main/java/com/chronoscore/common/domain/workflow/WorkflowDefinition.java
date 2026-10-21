package com.chronoscore.common.domain.workflow;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Core entity representing a registered workflow definition.
 */
public final class WorkflowDefinition {

    private final UUID workflowId;
    private final String name;
    private final String description;
    private final Instant createdAt;
    private final Instant updatedAt;

    private WorkflowDefinition(Builder builder) {
        this.workflowId = builder.workflowId != null ? builder.workflowId : UUID.randomUUID();
        this.name = Objects.requireNonNull(builder.name, "name must not be null");
        this.description = builder.description != null ? builder.description : "";
        this.createdAt = builder.createdAt != null ? builder.createdAt : Instant.now();
        this.updatedAt = builder.updatedAt != null ? builder.updatedAt : this.createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Builder toBuilder() {
        return new Builder()
                .workflowId(workflowId)
                .name(name)
                .description(description)
                .createdAt(createdAt)
                .updatedAt(updatedAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WorkflowDefinition that = (WorkflowDefinition) o;
        return Objects.equals(workflowId, that.workflowId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workflowId);
    }

    @Override
    public String toString() {
        return "WorkflowDefinition{" +
                "workflowId=" + workflowId +
                ", name='" + name + '\'' +
                '}';
    }

    public static final class Builder {
        private UUID workflowId;
        private String name;
        private String description;
        private Instant createdAt;
        private Instant updatedAt;

        private Builder() {
        }

        public Builder workflowId(UUID workflowId) {
            this.workflowId = workflowId;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public WorkflowDefinition build() {
            return new WorkflowDefinition(this);
        }
    }
}
