package com.chronoscore.common.statemachine;

import com.chronoscore.common.enums.WorkflowNodeStatus;
import com.chronoscore.common.exceptions.InvalidWorkflowTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WorkflowNodeStateMachine Tests")
class WorkflowNodeStateMachineTest {

    private final UUID executionId = UUID.randomUUID();
    private final String nodeId = "node-1";

    @ParameterizedTest(name = "{0} -> {1} should be valid")
    @CsvSource({
            "PENDING, READY",
            "PENDING, SKIPPED",
            "PENDING, CANCELLED",
            "READY, RUNNING",
            "READY, SUCCEEDED",
            "READY, FAILED",
            "READY, CANCELLED",
            "RUNNING, SUCCEEDED",
            "RUNNING, FAILED",
            "RUNNING, CANCELLED"
    })
    void validTransitionsSucceed(WorkflowNodeStatus from, WorkflowNodeStatus to) {
        assertTrue(WorkflowNodeStateMachine.isValidTransition(from, to));
        assertDoesNotThrow(() -> WorkflowNodeStateMachine.transition(executionId, nodeId, from, to));
    }

    @ParameterizedTest(name = "{0} -> {1} should be invalid")
    @CsvSource({
            "PENDING, RUNNING",
            "PENDING, SUCCEEDED",
            "READY, PENDING",
            "RUNNING, READY",
            "RUNNING, PENDING",
            "SUCCEEDED, RUNNING",
            "SUCCEEDED, READY",
            "FAILED, RUNNING",
            "FAILED, READY",
            "SKIPPED, READY",
            "SKIPPED, RUNNING",
            "CANCELLED, READY",
            "CANCELLED, RUNNING"
    })
    void invalidTransitionsThrowException(WorkflowNodeStatus from, WorkflowNodeStatus to) {
        assertFalse(WorkflowNodeStateMachine.isValidTransition(from, to));
        InvalidWorkflowTransitionException ex = assertThrows(
                InvalidWorkflowTransitionException.class,
                () -> WorkflowNodeStateMachine.transition(executionId, nodeId, from, to)
        );
        assertEquals(executionId, ex.getExecutionId());
    }

    @Test
    @DisplayName("Terminal states have isTerminal() == true")
    void terminalStatesAreFlagged() {
        assertFalse(WorkflowNodeStatus.PENDING.isTerminal());
        assertFalse(WorkflowNodeStatus.READY.isTerminal());
        assertFalse(WorkflowNodeStatus.RUNNING.isTerminal());
        assertTrue(WorkflowNodeStatus.SUCCEEDED.isTerminal());
        assertTrue(WorkflowNodeStatus.FAILED.isTerminal());
        assertTrue(WorkflowNodeStatus.SKIPPED.isTerminal());
        assertTrue(WorkflowNodeStatus.CANCELLED.isTerminal());
    }
}
