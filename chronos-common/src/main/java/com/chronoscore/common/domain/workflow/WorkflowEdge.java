package com.chronoscore.common.domain.workflow;

import java.util.Objects;

/**
 * Immutable directed edge representing a dependency between two workflow nodes.
 * <p>
 * {@code fromNodeId} must successfully conclude before {@code toNodeId} becomes eligible.
 */
public final class WorkflowEdge {

    private final String fromNodeId;
    private final String toNodeId;

    public WorkflowEdge(String fromNodeId, String toNodeId) {
        this.fromNodeId = Objects.requireNonNull(fromNodeId, "fromNodeId must not be null");
        this.toNodeId = Objects.requireNonNull(toNodeId, "toNodeId must not be null");
    }

    public static WorkflowEdge of(String fromNodeId, String toNodeId) {
        return new WorkflowEdge(fromNodeId, toNodeId);
    }

    public String getFromNodeId() {
        return fromNodeId;
    }

    public String getToNodeId() {
        return toNodeId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WorkflowEdge that = (WorkflowEdge) o;
        return Objects.equals(fromNodeId, that.fromNodeId) && Objects.equals(toNodeId, that.toNodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fromNodeId, toNodeId);
    }

    @Override
    public String toString() {
        return fromNodeId + " -> " + toNodeId;
    }
}
