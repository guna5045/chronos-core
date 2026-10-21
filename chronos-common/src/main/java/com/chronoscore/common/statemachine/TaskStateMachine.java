package com.chronoscore.common.statemachine;

import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.common.exceptions.InvalidTaskTransitionException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic, side-effect free finite state machine governing Task lifecycle transitions.
 * <p>
 * Guarantees:
 * <ul>
 *   <li>Completely deterministic with zero external dependencies (no Spring, DB, Redis, or gRPC).</li>
 *   <li>Terminal states (SUCCEEDED, CANCELLED, DLQ) cannot transition to any other state.</li>
 *   <li>Invalid transitions are rejected immediately with {@link InvalidTaskTransitionException}.</li>
 * </ul>
 */
public final class TaskStateMachine {

    private static final Map<TaskStatus, Set<TaskStatus>> VALID_TRANSITIONS;

    static {
        Map<TaskStatus, Set<TaskStatus>> transitions = new EnumMap<>(TaskStatus.class);

        // CREATED can transition to QUEUED or be CANCELLED
        transitions.put(TaskStatus.CREATED, Collections.unmodifiableSet(
                EnumSet.of(TaskStatus.QUEUED, TaskStatus.CANCELLED)
        ));

        // QUEUED can be DISPATCHED to a worker or CANCELLED
        transitions.put(TaskStatus.QUEUED, Collections.unmodifiableSet(
                EnumSet.of(TaskStatus.DISPATCHED, TaskStatus.CANCELLED)
        ));

        // DISPATCHED can start RUNNING, FAIL (e.g. dispatch rejection/worker crash), or be CANCELLED
        transitions.put(TaskStatus.DISPATCHED, Collections.unmodifiableSet(
                EnumSet.of(TaskStatus.RUNNING, TaskStatus.FAILED, TaskStatus.CANCELLED)
        ));

        // RUNNING can succeed, fail, or be CANCELLED in-flight
        transitions.put(TaskStatus.RUNNING, Collections.unmodifiableSet(
                EnumSet.of(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.CANCELLED)
        ));

        // FAILED transitions: re-queued for retry or sent to DLQ upon retry exhaustion
        transitions.put(TaskStatus.FAILED, Collections.unmodifiableSet(
                EnumSet.of(TaskStatus.QUEUED, TaskStatus.DLQ)
        ));

        // Terminal states: SUCCEEDED and CANCELLED have no outgoing transitions
        transitions.put(TaskStatus.SUCCEEDED, Collections.emptySet());
        transitions.put(TaskStatus.CANCELLED, Collections.emptySet());

        // DLQ can be explicitly replayed by operator/service back to QUEUED
        transitions.put(TaskStatus.DLQ, Collections.unmodifiableSet(
                EnumSet.of(TaskStatus.QUEUED)
        ));

        VALID_TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    private TaskStateMachine() {
        // Utility state-machine component; no instantiation required
    }

    /**
     * Checks whether a transition between two statuses is permissible.
     *
     * @param fromStatus current task status.
     * @param toStatus   intended target status.
     * @return true if the transition is explicitly permitted by the state machine.
     */
    public static boolean isValidTransition(TaskStatus fromStatus, TaskStatus toStatus) {
        if (fromStatus == null || toStatus == null) {
            return false;
        }
        Set<TaskStatus> allowed = VALID_TRANSITIONS.get(fromStatus);
        return allowed != null && allowed.contains(toStatus);
    }

    /**
     * Validates and applies a state transition.
     *
     * @param taskId     the unique ID of the task being transitioned.
     * @param fromStatus current task status.
     * @param toStatus   target task status.
     * @return the verified target status.
     * @throws InvalidTaskTransitionException if the transition is not allowed.
     */
    public static TaskStatus transition(UUID taskId, TaskStatus fromStatus, TaskStatus toStatus) {
        if (!isValidTransition(fromStatus, toStatus)) {
            throw new InvalidTaskTransitionException(taskId, fromStatus, toStatus);
        }
        return toStatus;
    }

    /**
     * Validates and applies a state transition without a taskId reference.
     *
     * @param fromStatus current task status.
     * @param toStatus   target task status.
     * @return the verified target status.
     * @throws InvalidTaskTransitionException if the transition is not allowed.
     */
    public static TaskStatus transition(TaskStatus fromStatus, TaskStatus toStatus) {
        return transition(null, fromStatus, toStatus);
    }

    /**
     * Evaluates the post-failure destination for a task based on attempt counts.
     * <p>
     * If currentAttempt &lt; maxAttempts, the task transitions back to {@link TaskStatus#QUEUED} for retry.
     * If currentAttempt &gt;= maxAttempts, retries are exhausted and the task transitions to {@link TaskStatus#DLQ}.
     *
     * @param currentAttempt number of attempts completed so far (1-indexed).
     * @param maxAttempts    maximum allowable attempts (&gt;= 1).
     * @return {@link TaskStatus#QUEUED} if retry eligible, otherwise {@link TaskStatus#DLQ}.
     */
    public static TaskStatus determinePostFailureStatus(int currentAttempt, int maxAttempts) {
        if (currentAttempt < maxAttempts) {
            return TaskStatus.QUEUED;
        }
        return TaskStatus.DLQ;
    }

    /**
     * Executes a failure transition from FAILED or RUNNING to either QUEUED or DLQ based on attempt limit.
     *
     * @param taskId         the task identifier.
     * @param currentAttempt the attempt count just executed.
     * @param maxAttempts    the configured maximum attempts.
     * @return the resulting target status (QUEUED or DLQ).
     */
    public static TaskStatus transitionOnFailure(UUID taskId, int currentAttempt, int maxAttempts) {
        TaskStatus nextStatus = determinePostFailureStatus(currentAttempt, maxAttempts);
        return transition(taskId, TaskStatus.FAILED, nextStatus);
    }

    /**
     * Returns the unmodifiable set of allowed next states from the given status.
     *
     * @param fromStatus current task status.
     * @return set of valid target states.
     */
    public static Set<TaskStatus> getValidNextStates(TaskStatus fromStatus) {
        if (fromStatus == null) {
            return Collections.emptySet();
        }
        return VALID_TRANSITIONS.getOrDefault(fromStatus, Collections.emptySet());
    }
}
