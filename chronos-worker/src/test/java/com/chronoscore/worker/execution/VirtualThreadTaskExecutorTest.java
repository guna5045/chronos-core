package com.chronoscore.worker.execution;

import com.chronoscore.common.proto.ProtoExecutionStatus;
import com.chronoscore.common.proto.ProtoTask;
import com.chronoscore.common.proto.ProtoTaskAssignment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VirtualThreadTaskExecutorTest {

    private VirtualThreadTaskExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new VirtualThreadTaskExecutor(4);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    private ProtoTaskAssignment createAssignment(String taskId, String payload) {
        ProtoTask task = ProtoTask.newBuilder()
                .setTaskId(taskId)
                .setTaskType("COMPUTE")
                .setPayload(payload != null ? payload : "{\"sample\":123}")
                .build();

        return ProtoTaskAssignment.newBuilder()
                .setTaskId(taskId)
                .setWorkerId("worker-test-1")
                .setAttemptNumber(1)
                .setTask(task)
                .build();
    }

    @Test
    @DisplayName("VirtualThreadTaskExecutor executes task to completion on virtual thread")
    void testSuccessfulTaskExecution() throws ExecutionException, InterruptedException, TimeoutException {
        String taskId = UUID.randomUUID().toString();
        ProtoTaskAssignment assignment = createAssignment(taskId, "{\"mode\":\"fast\"}");

        CompletableFuture<TaskExecutionResult> future = executor.executeTask(assignment);

        TaskExecutionResult result = future.get(3, TimeUnit.SECONDS);

        assertThat(result).isNotNull();
        assertThat(result.taskId()).isEqualTo(taskId);
        assertThat(result.status()).isEqualTo(ProtoExecutionStatus.EXECUTION_STATUS_SUCCEEDED);
        assertThat(result.outputPayload()).contains("COMPLETED");
        assertThat(executor.getActiveTaskCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("VirtualThreadTaskExecutor rejects task when maxConcurrency is saturated")
    void testCapacityExhaustion() {
        VirtualThreadTaskExecutor smallExecutor = new VirtualThreadTaskExecutor(2);

        // Saturate capacity with blocking tasks
        smallExecutor.executeTask(createAssignment(UUID.randomUUID().toString(), "{\"sleep\":2000}"));
        smallExecutor.executeTask(createAssignment(UUID.randomUUID().toString(), "{\"sleep\":2000}"));

        // 3rd task must throw CapacityExhaustedException
        assertThatThrownBy(() -> smallExecutor.executeTask(createAssignment(UUID.randomUUID().toString(), "")))
                .isInstanceOf(CapacityExhaustedException.class)
                .hasMessageContaining("Worker capacity exhausted");

        smallExecutor.shutdown();
    }

    @Test
    @DisplayName("VirtualThreadTaskExecutor supports task cancellation")
    void testTaskCancellation() {
        String taskId = UUID.randomUUID().toString();
        ProtoTaskAssignment assignment = createAssignment(taskId, "{\"sleep\":10000}");

        executor.executeTask(assignment);
        boolean cancelled = executor.cancelTask(taskId, "User requested cancellation");

        assertThat(cancelled).isTrue();
    }

    @Test
    @DisplayName("VirtualThreadTaskExecutor returns failure on payload intentional failure flag")
    void testIntentionalFailureSimulation() throws ExecutionException, InterruptedException, TimeoutException {
        String taskId = UUID.randomUUID().toString();
        ProtoTaskAssignment assignment = createAssignment(taskId, "{\"fail\":true}");

        CompletableFuture<TaskExecutionResult> future = executor.executeTask(assignment);
        TaskExecutionResult result = future.get(3, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(ProtoExecutionStatus.EXECUTION_STATUS_FAILED);
        assertThat(result.errorCode()).isEqualTo("TASK_INTENTIONAL_FAILURE");
    }
}
