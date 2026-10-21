package com.chronoscore.common.statemachine;

import com.chronoscore.common.enums.WorkflowStatus;
import com.chronoscore.common.exceptions.InvalidWorkflowTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WorkflowStateMachine Tests")
class WorkflowStateMachineTest {

    private final UUID executionId = UUID.randomUUID();

    @ParameterizedTest(name = "{0} -> {1} should be valid")
    @CsvSource({
            "PENDING, RUNNING",
            "PENDING, CANCELLED",
            "RUNNING, SUCCEEDED",
            "RUNNING, FAILED",
            "RUNNING, CANCELLED"
    })
    void validTransitionsSucceed(WorkflowStatus from, WorkflowStatus to) {
        assertTrue(WorkflowStateMachine.isValidTransition(from, to));
        assertDoesNotThrow(() -> WorkflowStateMachine.transition(executionId, from, to));
    }

    @ParameterizedTest(name = "{0} -> {1} should be invalid")
    @CsvSource({
            "PENDING, SUCCEEDED",
            "PENDING, FAILED",
            "RUNNING, PENDING",
            "SUCCEEDED, RUNNING",
            "SUCCEEDED, PENDING",
            "FAILED, RUNNING",
            "FAILED, PENDING",
            "CANCELLED, RUNNING",
            "CANCELLED, PENDING"
    })
    void invalidTransitionsThrowException(WorkflowStatus from, WorkflowStatus to) {
        assertFalse(WorkflowStateMachine.isValidTransition(from, to));
        InvalidWorkflowTransitionException ex = assertThrows(
                InvalidWorkflowTransitionException.class,
                () -> WorkflowStateMachine.transition(executionId, from, to)
        );
        assertEquals(executionId, ex.getExecutionId());
    }

    @Test
    @DisplayName("Terminal states have isTerminal() == true")
    void terminalStatesAreFlagged() {
        assertFalse(WorkflowStatus.PENDING.isTerminal());
        assertFalse(WorkflowStatus.RUNNING.isTerminal());
        assertTrue(WorkflowStatus.SUCCEEDED.isTerminal());
        assertTrue(WorkflowStatus.FAILED.isTerminal());
        assertTrue(WorkflowStatus.CANCELLED.isTerminal());
    }

    @Test
    @DisplayName("Null arguments return false or throw NPE")
    void nullArgumentsHandled() {
        assertFalse(WorkflowStateMachine.isValidTransition(null, WorkflowStatus.RUNNING));
        assertFalse(WorkflowStateMachine.isValidTransition(WorkflowStatus.RUNNING, null));
        assertThrows(NullPointerException.class, () -> WorkflowStateMachine.transition(executionId, null, WorkflowStatus.RUNNING));
        assertThrows(NullPointerException.class, () -> WorkflowStateMachine.transition(executionId, WorkflowStatus.RUNNING, null));
    }
}
