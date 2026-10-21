package com.chronoscore.common.validation;

import com.chronoscore.common.domain.workflow.WorkflowEdge;
import com.chronoscore.common.domain.workflow.WorkflowNodeDefinition;
import com.chronoscore.common.exceptions.WorkflowValidationException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic validator and topological analyzer for Directed Acyclic Graph (DAG) workflows.
 * <p>
 * Key Invariants Enforced:
 * <ul>
 *   <li>Node list must not be empty and must not exceed maximum capacity (1,000 nodes).</li>
 *   <li>Node IDs must be unique, non-blank, and within length limits.</li>
 *   <li>Edges must connect known nodes; orphaned/dangling references are rejected.</li>
 *   <li>Self-dependencies (A -&gt; A) are strictly prohibited.</li>
 *   <li>Cycles (e.g. A -&gt; B -&gt; A or A -&gt; B -&gt; C -&gt; A) are detected and rejected.</li>
 *   <li>At least one root node (in-degree == 0) must exist.</li>
 *   <li>Topological ordering is 100% deterministic using lexicographical tie-breaking.</li>
 * </ul>
 */
public final class WorkflowDagValidator {

    public static final int MAX_NODES = 1000;
    public static final int MAX_EDGES = 5000;
    public static final int MAX_NODE_ID_LENGTH = 128;

    private WorkflowDagValidator() {
    }

    /**
     * Validates that the provided nodes and edges constitute a valid, acyclic, well-formed DAG.
     *
     * @param nodes workflow node definitions.
     * @param edges dependency edges.
     * @throws WorkflowValidationException if any DAG invariant is violated.
     */
    public static void validate(List<WorkflowNodeDefinition> nodes, List<WorkflowEdge> edges) {
        if (nodes == null || nodes.isEmpty()) {
            throw new WorkflowValidationException("Workflow definition must contain at least one node");
        }

        if (nodes.size() > MAX_NODES) {
            throw new WorkflowValidationException(String.format("Workflow node count [%d] exceeds maximum allowed [%d]",
                    nodes.size(), MAX_NODES));
        }

        if (edges != null && edges.size() > MAX_EDGES) {
            throw new WorkflowValidationException(String.format("Workflow edge count [%d] exceeds maximum allowed [%d]",
                    edges.size(), MAX_EDGES));
        }

        Set<String> nodeIds = new HashSet<>();
        for (WorkflowNodeDefinition node : nodes) {
            if (node == null) {
                throw new WorkflowValidationException("Workflow node definition must not be null");
            }
            String id = node.getNodeId();
            if (id == null || id.isBlank()) {
                throw new WorkflowValidationException("Workflow node ID must not be null or blank");
            }
            if (id.length() > MAX_NODE_ID_LENGTH) {
                throw new WorkflowValidationException(String.format("Node ID [%s] exceeds maximum length [%d]",
                        id, MAX_NODE_ID_LENGTH));
            }
            if (node.getTaskType() == null || node.getTaskType().isBlank()) {
                throw new WorkflowValidationException(String.format("Node [%s] taskType must not be null or blank", id));
            }
            if (!nodeIds.add(id)) {
                throw new WorkflowValidationException(String.format("Duplicate node ID detected: [%s]", id));
            }
        }

        if (edges == null || edges.isEmpty()) {
            // Standalone nodes without edges are a valid trivial DAG
            return;
        }

        Set<WorkflowEdge> uniqueEdges = new HashSet<>();
        Map<String, Set<String>> adjacency = new HashMap<>();
        Map<String, Integer> inDegrees = new HashMap<>();

        for (String id : nodeIds) {
            adjacency.put(id, new TreeSet<>());
            inDegrees.put(id, 0);
        }

        for (WorkflowEdge edge : edges) {
            if (edge == null) {
                throw new WorkflowValidationException("Workflow edge must not be null");
            }
            String from = edge.getFromNodeId();
            String to = edge.getToNodeId();

            if (!nodeIds.contains(from)) {
                throw new WorkflowValidationException(String.format("Edge references non-existent source node: [%s]", from));
            }
            if (!nodeIds.contains(to)) {
                throw new WorkflowValidationException(String.format("Edge references non-existent target node: [%s]", to));
            }
            if (from.equals(to)) {
                throw new WorkflowValidationException(String.format("Self-dependency detected on node [%s]", from));
            }
            if (!uniqueEdges.add(edge)) {
                throw new WorkflowValidationException(String.format("Duplicate edge detected: [%s -> %s]", from, to));
            }

            adjacency.get(from).add(to);
            inDegrees.put(to, inDegrees.get(to) + 1);
        }

        // Verify at least one root node exists
        boolean hasRoot = false;
        for (Map.Entry<String, Integer> entry : inDegrees.entrySet()) {
            if (entry.getValue() == 0) {
                hasRoot = true;
                break;
            }
        }
        if (!hasRoot) {
            throw new WorkflowValidationException("Workflow DAG has no root node (every node has incoming dependencies)");
        }

        // Cycle detection using DFS with 3-coloring (WHITE=0, GRAY=1, BLACK=2)
        Map<String, Integer> colors = new HashMap<>();
        for (String id : nodeIds) {
            colors.put(id, 0);
        }

        List<String> cyclePath = new ArrayList<>();
        for (String id : nodeIds) {
            if (colors.get(id) == 0) {
                if (detectCycleDfs(id, adjacency, colors, cyclePath)) {
                    throw new WorkflowValidationException(String.format("Cycle detected in workflow DAG: %s",
                            formatCycle(cyclePath)));
                }
            }
        }
    }

    private static boolean detectCycleDfs(String current,
                                          Map<String, Set<String>> adjacency,
                                          Map<String, Integer> colors,
                                          List<String> path) {
        colors.put(current, 1); // Mark GRAY (visiting)
        path.add(current);

        for (String neighbor : adjacency.get(current)) {
            int neighborColor = colors.get(neighbor);
            if (neighborColor == 1) {
                // Found back-edge to visiting node -> cycle!
                path.add(neighbor);
                return true;
            }
            if (neighborColor == 0) {
                if (detectCycleDfs(neighbor, adjacency, colors, path)) {
                    return true;
                }
            }
        }

        colors.put(current, 2); // Mark BLACK (visited)
        path.remove(path.size() - 1);
        return false;
    }

    private static String formatCycle(List<String> path) {
        if (path.isEmpty()) {
            return "cycle";
        }
        String last = path.get(path.size() - 1);
        int startIndex = path.indexOf(last);
        if (startIndex >= 0 && startIndex < path.size() - 1) {
            return String.join(" -> ", path.subList(startIndex, path.size()));
        }
        return String.join(" -> ", path);
    }

    /**
     * Produces a deterministic topological ordering of workflow nodes.
     * Uses Kahn's algorithm with a lexicographical priority queue for tie-breaking.
     *
     * @param nodes workflow node definitions.
     * @param edges dependency edges.
     * @return list of node IDs in valid topological order.
     * @throws WorkflowValidationException if the graph is invalid or contains cycles.
     */
    public static List<String> getTopologicalOrder(List<WorkflowNodeDefinition> nodes, List<WorkflowEdge> edges) {
        validate(nodes, edges);

        Map<String, Set<String>> adjacency = new TreeMap<>();
        Map<String, Integer> inDegrees = new TreeMap<>();

        for (WorkflowNodeDefinition node : nodes) {
            adjacency.put(node.getNodeId(), new TreeSet<>());
            inDegrees.put(node.getNodeId(), 0);
        }

        if (edges != null) {
            for (WorkflowEdge edge : edges) {
                adjacency.get(edge.getFromNodeId()).add(edge.getToNodeId());
                inDegrees.put(edge.getToNodeId(), inDegrees.get(edge.getToNodeId()) + 1);
            }
        }

        // Use PriorityQueue with natural String order for deterministic tie-breaking
        PriorityQueue<String> readyQueue = new PriorityQueue<>();
        for (Map.Entry<String, Integer> entry : inDegrees.entrySet()) {
            if (entry.getValue() == 0) {
                readyQueue.add(entry.getKey());
            }
        }

        List<String> sorted = new ArrayList<>(nodes.size());
        while (!readyQueue.isEmpty()) {
            String current = readyQueue.poll();
            sorted.add(current);

            for (String neighbor : adjacency.get(current)) {
                int updatedInDegree = inDegrees.get(neighbor) - 1;
                inDegrees.put(neighbor, updatedInDegree);
                if (updatedInDegree == 0) {
                    readyQueue.add(neighbor);
                }
            }
        }

        if (sorted.size() != nodes.size()) {
            throw new WorkflowValidationException("Failed to order DAG topologically: cycle detected");
        }

        return Collections.unmodifiableList(sorted);
    }

    /**
     * Identifies all root nodes (in-degree == 0) in the DAG.
     *
     * @param nodes workflow nodes.
     * @param edges dependency edges.
     * @return sorted list of root node IDs.
     */
    public static List<String> getRootNodeIds(List<WorkflowNodeDefinition> nodes, List<WorkflowEdge> edges) {
        validate(nodes, edges);

        Set<String> targets = new HashSet<>();
        if (edges != null) {
            for (WorkflowEdge edge : edges) {
                targets.add(edge.getToNodeId());
            }
        }

        List<String> roots = new ArrayList<>();
        for (WorkflowNodeDefinition node : nodes) {
            if (!targets.contains(node.getNodeId())) {
                roots.add(node.getNodeId());
            }
        }
        Collections.sort(roots);
        return Collections.unmodifiableList(roots);
    }
}
