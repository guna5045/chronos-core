package com.chronoscore.common.validation;

import com.chronoscore.common.domain.workflow.WorkflowEdge;
import com.chronoscore.common.domain.workflow.WorkflowNodeDefinition;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.exceptions.WorkflowValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WorkflowDagValidator Tests")
class WorkflowDagValidatorTest {

    private WorkflowNodeDefinition node(String id) {
        return WorkflowNodeDefinition.builder()
                .nodeId(id)
                .taskType("TEST_TASK")
                .priority(TaskPriority.NORMAL)
                .build();
    }

    @Test
    @DisplayName("Single node DAG is valid and returns single element topological order")
    void singleNodeDagValid() {
        List<WorkflowNodeDefinition> nodes = List.of(node("A"));
        List<WorkflowEdge> edges = List.of();

        assertDoesNotThrow(() -> WorkflowDagValidator.validate(nodes, edges));
        assertEquals(List.of("A"), WorkflowDagValidator.getTopologicalOrder(nodes, edges));
        assertEquals(List.of("A"), WorkflowDagValidator.getRootNodeIds(nodes, edges));
    }

    @Test
    @DisplayName("Linear chain A -> B is valid")
    void linearChainValid() {
        List<WorkflowNodeDefinition> nodes = List.of(node("A"), node("B"));
        List<WorkflowEdge> edges = List.of(WorkflowEdge.of("A", "B"));

        assertDoesNotThrow(() -> WorkflowDagValidator.validate(nodes, edges));
        assertEquals(List.of("A", "B"), WorkflowDagValidator.getTopologicalOrder(nodes, edges));
        assertEquals(List.of("A"), WorkflowDagValidator.getRootNodeIds(nodes, edges));
    }

    @Test
    @DisplayName("Diamond DAG (fan-out and fan-in) is valid with deterministic topological order")
    void diamondDagValid() {
        // A -> B, A -> C, B -> D, C -> D
        List<WorkflowNodeDefinition> nodes = List.of(node("D"), node("B"), node("A"), node("C"));
        List<WorkflowEdge> edges = List.of(
                WorkflowEdge.of("A", "B"),
                WorkflowEdge.of("A", "C"),
                WorkflowEdge.of("B", "D"),
                WorkflowEdge.of("C", "D")
        );

        assertDoesNotThrow(() -> WorkflowDagValidator.validate(nodes, edges));
        // B and C are tie-broken lexicographically
        assertEquals(List.of("A", "B", "C", "D"), WorkflowDagValidator.getTopologicalOrder(nodes, edges));
        assertEquals(List.of("A"), WorkflowDagValidator.getRootNodeIds(nodes, edges));
    }

    @Test
    @DisplayName("Multiple root nodes are detected and ordered deterministically")
    void multipleRootsValid() {
        // Roots: R1, R2. Both point to M. M points to E.
        List<WorkflowNodeDefinition> nodes = List.of(node("M"), node("R2"), node("E"), node("R1"));
        List<WorkflowEdge> edges = List.of(
                WorkflowEdge.of("R1", "M"),
                WorkflowEdge.of("R2", "M"),
                WorkflowEdge.of("M", "E")
        );

        assertDoesNotThrow(() -> WorkflowDagValidator.validate(nodes, edges));
        assertEquals(List.of("R1", "R2"), WorkflowDagValidator.getRootNodeIds(nodes, edges));
        assertEquals(List.of("R1", "R2", "M", "E"), WorkflowDagValidator.getTopologicalOrder(nodes, edges));
    }

    @Test
    @DisplayName("Rejects self-dependency A -> A")
    void rejectsSelfDependency() {
        List<WorkflowNodeDefinition> nodes = List.of(node("A"));
        List<WorkflowEdge> edges = List.of(WorkflowEdge.of("A", "A"));

        WorkflowValidationException ex = assertThrows(
                WorkflowValidationException.class,
                () -> WorkflowDagValidator.validate(nodes, edges)
        );
        assertTrue(ex.getMessage().contains("Self-dependency detected"));
    }

    @Test
    @DisplayName("Rejects 2-node cycle A -> B -> A")
    void rejectsTwoNodeCycle() {
        List<WorkflowNodeDefinition> nodes = List.of(node("A"), node("B"));
        List<WorkflowEdge> edges = List.of(
                WorkflowEdge.of("A", "B"),
                WorkflowEdge.of("B", "A")
        );

        WorkflowValidationException ex = assertThrows(
                WorkflowValidationException.class,
                () -> WorkflowDagValidator.validate(nodes, edges)
        );
        assertTrue(ex.getMessage().contains("Cycle detected") || ex.getMessage().contains("no root node"));
    }

    @Test
    @DisplayName("Rejects 3-node cycle A -> B -> C -> A")
    void rejectsThreeNodeCycle() {
        List<WorkflowNodeDefinition> nodes = List.of(node("A"), node("B"), node("C"));
        List<WorkflowEdge> edges = List.of(
                WorkflowEdge.of("A", "B"),
                WorkflowEdge.of("B", "C"),
                WorkflowEdge.of("C", "A")
        );

        WorkflowValidationException ex = assertThrows(
                WorkflowValidationException.class,
                () -> WorkflowDagValidator.validate(nodes, edges)
        );
        assertTrue(ex.getMessage().contains("Cycle detected") || ex.getMessage().contains("no root node"));
    }

    @Test
    @DisplayName("Rejects cycle in subgraph where a root exists")
    void rejectsCycleWithSeparateRoot() {
        // Root R -> A. And A -> B -> C -> A
        List<WorkflowNodeDefinition> nodes = List.of(node("R"), node("A"), node("B"), node("C"));
        List<WorkflowEdge> edges = List.of(
                WorkflowEdge.of("R", "A"),
                WorkflowEdge.of("A", "B"),
                WorkflowEdge.of("B", "C"),
                WorkflowEdge.of("C", "A")
        );

        WorkflowValidationException ex = assertThrows(
                WorkflowValidationException.class,
                () -> WorkflowDagValidator.validate(nodes, edges)
        );
        assertTrue(ex.getMessage().contains("Cycle detected"));
    }

    @Test
    @DisplayName("Rejects edge referencing unknown target node")
    void rejectsUnknownTargetNode() {
        List<WorkflowNodeDefinition> nodes = List.of(node("A"));
        List<WorkflowEdge> edges = List.of(WorkflowEdge.of("A", "NON_EXISTENT"));

        WorkflowValidationException ex = assertThrows(
                WorkflowValidationException.class,
                () -> WorkflowDagValidator.validate(nodes, edges)
        );
        assertTrue(ex.getMessage().contains("non-existent target node"));
    }

    @Test
    @DisplayName("Rejects edge referencing unknown source node")
    void rejectsUnknownSourceNode() {
        List<WorkflowNodeDefinition> nodes = List.of(node("A"));
        List<WorkflowEdge> edges = List.of(WorkflowEdge.of("NON_EXISTENT", "A"));

        WorkflowValidationException ex = assertThrows(
                WorkflowValidationException.class,
                () -> WorkflowDagValidator.validate(nodes, edges)
        );
        assertTrue(ex.getMessage().contains("non-existent source node"));
    }

    @Test
    @DisplayName("Rejects duplicate node IDs")
    void rejectsDuplicateNodeIds() {
        List<WorkflowNodeDefinition> nodes = List.of(node("A"), node("A"));

        WorkflowValidationException ex = assertThrows(
                WorkflowValidationException.class,
                () -> WorkflowDagValidator.validate(nodes, List.of())
        );
        assertTrue(ex.getMessage().contains("Duplicate node ID detected"));
    }

    @Test
    @DisplayName("Rejects empty node list")
    void rejectsEmptyNodes() {
        assertThrows(WorkflowValidationException.class, () -> WorkflowDagValidator.validate(List.of(), List.of()));
        assertThrows(WorkflowValidationException.class, () -> WorkflowDagValidator.validate(null, List.of()));
    }
}
