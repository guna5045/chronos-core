package com.chronoscore.common.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStatusTest {

    @Test
    @DisplayName("isTerminal must identify SUCCEEDED, CANCELLED, and DLQ")
    void testIsTerminal() {
        assertThat(TaskStatus.SUCCEEDED.isTerminal()).isTrue();
        assertThat(TaskStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(TaskStatus.DLQ.isTerminal()).isTrue();

        assertThat(TaskStatus.CREATED.isTerminal()).isFalse();
        assertThat(TaskStatus.QUEUED.isTerminal()).isFalse();
        assertThat(TaskStatus.DISPATCHED.isTerminal()).isFalse();
        assertThat(TaskStatus.RUNNING.isTerminal()).isFalse();
        assertThat(TaskStatus.FAILED.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("isActive must identify QUEUED, DISPATCHED, and RUNNING")
    void testIsActive() {
        assertThat(TaskStatus.QUEUED.isActive()).isTrue();
        assertThat(TaskStatus.DISPATCHED.isActive()).isTrue();
        assertThat(TaskStatus.RUNNING.isActive()).isTrue();

        assertThat(TaskStatus.CREATED.isActive()).isFalse();
        assertThat(TaskStatus.SUCCEEDED.isActive()).isFalse();
        assertThat(TaskStatus.FAILED.isActive()).isFalse();
        assertThat(TaskStatus.CANCELLED.isActive()).isFalse();
        assertThat(TaskStatus.DLQ.isActive()).isFalse();
    }
}
