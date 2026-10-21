package com.chronoscore.coordinator.coordination;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisKeyFactoryTest {

    @Test
    @DisplayName("leaderLockKey: returns expected namespaced key")
    void testLeaderLockKey() {
        assertThat(RedisKeyFactory.leaderLockKey()).isEqualTo("chronos:leader:lock");
    }

    @Test
    @DisplayName("leaderEpochKey: returns expected namespaced key")
    void testLeaderEpochKey() {
        assertThat(RedisKeyFactory.leaderEpochKey()).isEqualTo("chronos:leader:epoch");
    }

    @Test
    @DisplayName("taskLockKey with UUID: formats resource-scoped lock key")
    void testTaskLockKeyWithUuid() {
        UUID taskId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        assertThat(RedisKeyFactory.taskLockKey(taskId))
                .isEqualTo("chronos:task:lock:550e8400-e29b-41d4-a716-446655440000");
    }

    @Test
    @DisplayName("taskLockKey with String: formats resource-scoped lock key")
    void testTaskLockKeyWithString() {
        assertThat(RedisKeyFactory.taskLockKey("task-12345"))
                .isEqualTo("chronos:task:lock:task-12345");
    }

    @Test
    @DisplayName("taskLockKey: rejects null parameters")
    void testTaskLockKeyNullChecks() {
        assertThatThrownBy(() -> RedisKeyFactory.taskLockKey((UUID) null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> RedisKeyFactory.taskLockKey((String) null))
                .isInstanceOf(NullPointerException.class);
    }
}
