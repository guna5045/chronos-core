package com.chronoscore.common.proto;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProtobufContractTest {

    @Test
    @DisplayName("ProtoTask should serialize to binary and deserialize without loss")
    void testProtoTaskSerialization() throws InvalidProtocolBufferException {
        Instant now = Instant.now();
        Timestamp timestamp = Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano())
                .build();

        String taskId = UUID.randomUUID().toString();

        ProtoTask original = ProtoTask.newBuilder()
                .setTaskId(taskId)
                .setTaskType("BILLING_AGGREGATION")
                .setPayload("{\"month\":\"2026-10\"}")
                .setPriority(ProtoTaskPriority.TASK_PRIORITY_HIGH)
                .setStatus(ProtoTaskStatus.TASK_STATUS_QUEUED)
                .setTenantId("fintech-tenant")
                .setIdempotencyKey("bill-2026-10")
                .setCreatedAt(timestamp)
                .setMaxAttempts(5)
                .setCurrentAttempt(1)
                .setVersion(2L)
                .build();

        byte[] serialized = original.toByteArray();
        assertThat(serialized).isNotEmpty();

        ProtoTask deserialized = ProtoTask.parseFrom(serialized);
        assertThat(deserialized.getTaskId()).isEqualTo(taskId);
        assertThat(deserialized.getTaskType()).isEqualTo("BILLING_AGGREGATION");
        assertThat(deserialized.getPayload()).isEqualTo("{\"month\":\"2026-10\"}");
        assertThat(deserialized.getPriority()).isEqualTo(ProtoTaskPriority.TASK_PRIORITY_HIGH);
        assertThat(deserialized.getStatus()).isEqualTo(ProtoTaskStatus.TASK_STATUS_QUEUED);
        assertThat(deserialized.getTenantId()).isEqualTo("fintech-tenant");
        assertThat(deserialized.getIdempotencyKey()).isEqualTo("bill-2026-10");
        assertThat(deserialized.getCreatedAt().getSeconds()).isEqualTo(now.getEpochSecond());
        assertThat(deserialized.getMaxAttempts()).isEqualTo(5);
        assertThat(deserialized.getCurrentAttempt()).isEqualTo(1);
        assertThat(deserialized.getVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("ProtoTaskAssignment should serialize and preserve nested ProtoTask")
    void testProtoTaskAssignment() throws InvalidProtocolBufferException {
        String taskId = UUID.randomUUID().toString();
        ProtoTask task = ProtoTask.newBuilder()
                .setTaskId(taskId)
                .setTaskType("IMAGE_RESIZE")
                .setPayload("{\"width\":800}")
                .setTenantId("media-tenant")
                .build();

        ProtoTaskAssignment assignment = ProtoTaskAssignment.newBuilder()
                .setTaskId(taskId)
                .setWorkerId("worker-gpu-01")
                .setAttemptNumber(1)
                .setTask(task)
                .build();

        byte[] bytes = assignment.toByteArray();
        ProtoTaskAssignment parsed = ProtoTaskAssignment.parseFrom(bytes);

        assertThat(parsed.getTaskId()).isEqualTo(taskId);
        assertThat(parsed.getWorkerId()).isEqualTo("worker-gpu-01");
        assertThat(parsed.getAttemptNumber()).isEqualTo(1);
        assertThat(parsed.getTask().getTaskType()).isEqualTo("IMAGE_RESIZE");
    }

    @Test
    @DisplayName("ProtoHeartbeatPing and Pong contracts should serialize accurately")
    void testHeartbeatContracts() throws InvalidProtocolBufferException {
        ProtoHeartbeatPing ping = ProtoHeartbeatPing.newBuilder()
                .setWorkerId("worker-node-7")
                .setActiveTasks(4)
                .setCpuUsagePercent(38.5)
                .setMemoryUsagePercent(54.2)
                .build();

        ProtoHeartbeatPong pong = ProtoHeartbeatPong.newBuilder()
                .setCoordinatorId("coordinator-leader-1")
                .setEpochFencingToken(42L)
                .setAcknowledged(true)
                .build();

        ProtoHeartbeatPing parsedPing = ProtoHeartbeatPing.parseFrom(ping.toByteArray());
        ProtoHeartbeatPong parsedPong = ProtoHeartbeatPong.parseFrom(pong.toByteArray());

        assertThat(parsedPing.getWorkerId()).isEqualTo("worker-node-7");
        assertThat(parsedPing.getActiveTasks()).isEqualTo(4);
        assertThat(parsedPing.getCpuUsagePercent()).isEqualTo(38.5);

        assertThat(parsedPong.getCoordinatorId()).isEqualTo("coordinator-leader-1");
        assertThat(parsedPong.getEpochFencingToken()).isEqualTo(42L);
        assertThat(parsedPong.getAcknowledged()).isTrue();
    }
}
