package com.chronoscore.common.statemachine;

import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.common.exceptions.InvalidTaskTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskStateMachineTest {

    @ParameterizedTest(name = "Valid transition from {0} to {1}")
    @CsvSource({
            "CREATED, QUEUED",
            "CREATED, CANCELLED",
            "QUEUED, DISPATCHED",
            "QUEUED, CANCELLED",
            "DISPATCHED, RUNNING",
            "DISPATCHED, FAILED",
            "DISPATCHED, CANCELLED",
            "RUNNING, SUCCEEDED",
            "RUNNING, FAILED",
            "RUNNING, CANCELLED",
            "FAILED, QUEUED",
            "FAILED, DLQ",
            "DLQ, QUEUED"
    })
    void testPermittedTransitions(TaskStatus from, TaskStatus to) {
        assertThat(TaskStateMachine.isValidTransition(from, to)).isTrue();
        TaskStatus result = TaskStateMachine.transition(UUID.randomUUID(), from, to);
        assertThat(result).isEqualTo(to);
    }

    @ParameterizedTest(name = "Invalid transition from {0} to {1}")
    @CsvSource({
            "CREATED, RUNNING",
            "CREATED, SUCCEEDED",
            "CREATED, FAILED",
            "CREATED, DLQ",
            "QUEUED, CREATED",
            "QUEUED, RUNNING",
            "QUEUED, SUCCEEDED",
            "QUEUED, DLQ",
            "DISPATCHED, CREATED",
            "DISPATCHED, QUEUED",
            "DISPATCHED, SUCCEEDED",
            "DISPATCHED, DLQ",
            "RUNNING, CREATED",
            "RUNNING, QUEUED",
            "RUNNING, DISPATCHED",
            "RUNNING, DLQ",
            "FAILED, CREATED",
            "FAILED, DISPATCHED",
            "FAILED, RUNNING",
            "FAILED, SUCCEEDED",
            "DLQ, CREATED",
            "DLQ, DISPATCHED",
            "DLQ, RUNNING",
            "DLQ, SUCCEEDED",
            "DLQ, FAILED",
            "DLQ, CANCELLED"
    })
    void testInvalidTransitions(TaskStatus from, TaskStatus to) {
        assertThat(TaskStateMachine.isValidTransition(from, to)).isFalse();

        UUID taskId = UUID.randomUUID();
        assertThatThrownBy(() -> TaskStateMachine.transition(taskId, from, to))
                .isInstanceOf(InvalidTaskTransitionException.class)
                .satisfies(e -> {
                    InvalidTaskTransitionException ex = (InvalidTaskTransitionException) e;
                    assertThat(ex.getTaskId()).isEqualTo(taskId);
                    assertThat(ex.getFromStatus()).isEqualTo(from);
                    assertThat(ex.getToStatus()).isEqualTo(to);
                });
    }

    @Test
    @DisplayName("Terminal state SUCCEEDED must reject all outgoing transitions")
    void testTerminalSucceededRejectsAll() {
        for (TaskStatus target : TaskStatus.values()) {
            assertThat(TaskStateMachine.isValidTransition(TaskStatus.SUCCEEDED, target)).isFalse();
            assertThatThrownBy(() -> TaskStateMachine.transition(TaskStatus.SUCCEEDED, target))
                    .isInstanceOf(InvalidTaskTransitionException.class);
        }
    }

    @Test
    @DisplayName("Terminal state CANCELLED must reject all outgoing transitions")
    void testTerminalCancelledRejectsAll() {
        for (TaskStatus target : TaskStatus.values()) {
            assertThat(TaskStateMachine.isValidTransition(TaskStatus.CANCELLED, target)).isFalse();
            assertThatThrownBy(() -> TaskStateMachine.transition(TaskStatus.CANCELLED, target))
                    .isInstanceOf(InvalidTaskTransitionException.class);
        }
    }

    @Test
    @DisplayName("DLQ permits only QUEUED replay transition and rejects all others")
    void testDlqRejectsAllExceptQueued() {
        // DLQ -> QUEUED is valid (replay)
        assertThat(TaskStateMachine.isValidTransition(TaskStatus.DLQ, TaskStatus.QUEUED)).isTrue();
        assertThat(TaskStateMachine.transition(UUID.randomUUID(), TaskStatus.DLQ, TaskStatus.QUEUED))
                .isEqualTo(TaskStatus.QUEUED);

        for (TaskStatus target : TaskStatus.values()) {
            if (target == TaskStatus.QUEUED) continue;
            assertThat(TaskStateMachine.isValidTransition(TaskStatus.DLQ, target)).isFalse();
            assertThatThrownBy(() -> TaskStateMachine.transition(TaskStatus.DLQ, target))
                    .isInstanceOf(InvalidTaskTransitionException.class);
        }
    }

    @Test
    @DisplayName("Failure evaluation: retry eligible transitions to QUEUED")
    void testDeterminePostFailureStatusRetryAvailable() {
        // Current attempt 1 of 3 -> retry allowed
        assertThat(TaskStateMachine.determinePostFailureStatus(1, 3)).isEqualTo(TaskStatus.QUEUED);
        // Current attempt 2 of 3 -> retry allowed
        assertThat(TaskStateMachine.determinePostFailureStatus(2, 3)).isEqualTo(TaskStatus.QUEUED);

        TaskStatus result = TaskStateMachine.transitionOnFailure(UUID.randomUUID(), 1, 3);
        assertThat(result).isEqualTo(TaskStatus.QUEUED);
    }

    @Test
    @DisplayName("Failure evaluation: retry exhaustion transitions to DLQ")
    void testDeterminePostFailureStatusExhausted() {
        // Current attempt 3 of 3 -> max reached, DLQ
        assertThat(TaskStateMachine.determinePostFailureStatus(3, 3)).isEqualTo(TaskStatus.DLQ);
        // Current attempt 4 of 3 -> exceeded, DLQ
        assertThat(TaskStateMachine.determinePostFailureStatus(4, 3)).isEqualTo(TaskStatus.DLQ);

        TaskStatus result = TaskStateMachine.transitionOnFailure(UUID.randomUUID(), 3, 3);
        assertThat(result).isEqualTo(TaskStatus.DLQ);
    }

    @Test
    @DisplayName("Null arguments must return false and throw exception")
    void testNullArgumentsHandling() {
        assertThat(TaskStateMachine.isValidTransition(null, TaskStatus.QUEUED)).isFalse();
        assertThat(TaskStateMachine.isValidTransition(TaskStatus.CREATED, null)).isFalse();
        assertThat(TaskStateMachine.isValidTransition(null, null)).isFalse();

        assertThatThrownBy(() -> TaskStateMachine.transition(null, TaskStatus.QUEUED))
                .isInstanceOf(InvalidTaskTransitionException.class);
    }

    @Test
    @DisplayName("getValidNextStates returns accurate unmodifiable sets")
    void testGetValidNextStates() {
        Set<TaskStatus> fromCreated = TaskStateMachine.getValidNextStates(TaskStatus.CREATED);
        assertThat(fromCreated).containsExactlyInAnyOrder(TaskStatus.QUEUED, TaskStatus.CANCELLED);

        Set<TaskStatus> fromRunning = TaskStateMachine.getValidNextStates(TaskStatus.RUNNING);
        assertThat(fromRunning).containsExactlyInAnyOrder(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.CANCELLED);

        Set<TaskStatus> fromSucceeded = TaskStateMachine.getValidNextStates(TaskStatus.SUCCEEDED);
        assertThat(fromSucceeded).isEmpty();

        Set<TaskStatus> fromNull = TaskStateMachine.getValidNextStates(null);
        assertThat(fromNull).isEmpty();
    }
}
