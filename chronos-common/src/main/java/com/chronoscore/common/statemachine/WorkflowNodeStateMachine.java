package com.chronoscore.common.statemachine;

import com.chronoscore.common.enums.WorkflowNodeStatus;
import com.chronoscore.common.exceptions.InvalidWorkflowTransitionException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic finite state machine governing {@link WorkflowNodeStatus} transitions.
 */
public final class WorkflowNodeStateMachine {

    private static final Map<WorkflowNodeStatus, Set<WorkflowNodeStatus>> VALID_TRANSITIONS;

    static {
        Map<WorkflowNodeStatus, Set<WorkflowNodeStatus>> transitions = new EnumMap<>(WorkflowNodeStatus.class);

        // PENDING can become READY (when all dependencies satisfied), SKIPPED (upstream failed), or CANCELLED
        transitions.put(WorkflowNodeStatus.PENDING, Collections.unmodifiableSet(
                EnumSet.of(WorkflowNodeStatus.READY, WorkflowNodeStatus.SKIPPED, WorkflowNodeStatus.CANCELLED)
        ));

        // READY can transition to RUNNING, SUCCEEDED (completed directly), FAILED, or CANCELLED
        transitions.put(WorkflowNodeStatus.READY, Collections.unmodifiableSet(
                EnumSet.of(WorkflowNodeStatus.RUNNING, WorkflowNodeStatus.SUCCEEDED, WorkflowNodeStatus.FAILED, WorkflowNodeStatus.CANCELLED)
        ));

        // RUNNING can SUCCEED, FAIL (retries exhausted), or be CANCELLED
        transitions.put(WorkflowNodeStatus.RUNNING, Collections.unmodifiableSet(
                EnumSet.of(WorkflowNodeStatus.SUCCEEDED, WorkflowNodeStatus.FAILED, WorkflowNodeStatus.CANCELLED)
        ));

        // Terminal states have no outgoing transitions
        transitions.put(WorkflowNodeStatus.SUCCEEDED, Collections.emptySet());
        transitions.put(WorkflowNodeStatus.FAILED, Collections.emptySet());
        transitions.put(WorkflowNodeStatus.SKIPPED, Collections.emptySet());
        transitions.put(WorkflowNodeStatus.CANCELLED, Collections.emptySet());

        VALID_TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    private WorkflowNodeStateMachine() {
    }

    /**
     * Checks whether a transition between two workflow node statuses is permitted.
     *
     * @param from current status.
     * @param to   target status.
     * @return true if valid, false otherwise.
     */
    public static boolean isValidTransition(WorkflowNodeStatus from, WorkflowNodeStatus to) {
        if (from == null || to == null) {
            return false;
        }
        Set<WorkflowNodeStatus> allowed = VALID_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * Validates and asserts a workflow node transition, throwing {@link InvalidWorkflowTransitionException}
     * if the transition is illegal.
     *
     * @param executionId execution identifier.
     * @param nodeId      node identifier.
     * @param from        current status.
     * @param to          target status.
     * @throws InvalidWorkflowTransitionException if illegal transition.
     */
    public static void transition(UUID executionId, String nodeId, WorkflowNodeStatus from, WorkflowNodeStatus to) {
        Objects.requireNonNull(from, "from status must not be null");
        Objects.requireNonNull(to, "to status must not be null");

        if (!isValidTransition(from, to)) {
            throw new InvalidWorkflowTransitionException(executionId, nodeId, from, to);
        }
    }
}
