package com.chronoscore.common.statemachine;

import com.chronoscore.common.enums.WorkflowStatus;
import com.chronoscore.common.exceptions.InvalidWorkflowTransitionException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic finite state machine governing top-level {@link WorkflowStatus} transitions.
 */
public final class WorkflowStateMachine {

    private static final Map<WorkflowStatus, Set<WorkflowStatus>> VALID_TRANSITIONS;

    static {
        Map<WorkflowStatus, Set<WorkflowStatus>> transitions = new EnumMap<>(WorkflowStatus.class);

        // PENDING can transition to RUNNING when first root nodes are evaluated, or be CANCELLED before start
        transitions.put(WorkflowStatus.PENDING, Collections.unmodifiableSet(
                EnumSet.of(WorkflowStatus.RUNNING, WorkflowStatus.CANCELLED)
        ));

        // RUNNING can conclude with SUCCEEDED, FAILED (due to unhandled node failure), or CANCELLED
        transitions.put(WorkflowStatus.RUNNING, Collections.unmodifiableSet(
                EnumSet.of(WorkflowStatus.SUCCEEDED, WorkflowStatus.FAILED, WorkflowStatus.CANCELLED)
        ));

        // Terminal states: no outgoing transitions permitted
        transitions.put(WorkflowStatus.SUCCEEDED, Collections.emptySet());
        transitions.put(WorkflowStatus.FAILED, Collections.emptySet());
        transitions.put(WorkflowStatus.CANCELLED, Collections.emptySet());

        VALID_TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    private WorkflowStateMachine() {
    }

    /**
     * Checks whether a transition between two workflow execution states is permitted.
     *
     * @param from current status.
     * @param to   target status.
     * @return true if valid, false otherwise.
     */
    public static boolean isValidTransition(WorkflowStatus from, WorkflowStatus to) {
        if (from == null || to == null) {
            return false;
        }
        Set<WorkflowStatus> allowed = VALID_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * Validates and asserts a workflow transition, throwing {@link InvalidWorkflowTransitionException}
     * if the transition is illegal.
     *
     * @param executionId execution identifier.
     * @param from        current status.
     * @param to          target status.
     * @throws InvalidWorkflowTransitionException if illegal transition.
     */
    public static void transition(UUID executionId, WorkflowStatus from, WorkflowStatus to) {
        Objects.requireNonNull(from, "from status must not be null");
        Objects.requireNonNull(to, "to status must not be null");

        if (!isValidTransition(from, to)) {
            throw new InvalidWorkflowTransitionException(executionId, from, to);
        }
    }
}
