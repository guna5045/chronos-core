package com.chronoscore.common.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskPriorityTest {

    @Test
    @DisplayName("Priority levels must follow strict monotonic ordering")
    void testPriorityLevels() {
        assertThat(TaskPriority.LOW.getLevel()).isLessThan(TaskPriority.NORMAL.getLevel());
        assertThat(TaskPriority.NORMAL.getLevel()).isLessThan(TaskPriority.HIGH.getLevel());
        assertThat(TaskPriority.HIGH.getLevel()).isLessThan(TaskPriority.CRITICAL.getLevel());
    }

    @Test
    @DisplayName("isHigherPriorityThan should correctly compare priority levels")
    void testIsHigherPriorityThan() {
        assertThat(TaskPriority.CRITICAL.isHigherPriorityThan(TaskPriority.HIGH)).isTrue();
        assertThat(TaskPriority.HIGH.isHigherPriorityThan(TaskPriority.NORMAL)).isTrue();
        assertThat(TaskPriority.NORMAL.isHigherPriorityThan(TaskPriority.LOW)).isTrue();

        assertThat(TaskPriority.LOW.isHigherPriorityThan(TaskPriority.HIGH)).isFalse();
        assertThat(TaskPriority.NORMAL.isHigherPriorityThan(TaskPriority.NORMAL)).isFalse();

        // Null comparison: any explicit priority is higher than null
        assertThat(TaskPriority.LOW.isHigherPriorityThan(null)).isTrue();
    }
}
