package com.chronoscore.coordinator.recovery.dlq;

import com.chronoscore.common.domain.ExecutionAttempt;
import com.chronoscore.common.domain.Task;
import com.chronoscore.coordinator.persistence.model.TaskAuditRecord;

import java.util.List;

/**
 * Diagnostic record returned by {@link DlqService#inspectTask(java.util.UUID)}.
 *
 * @param task         current task state.
 * @param attempts     historical physical execution attempts.
 * @param auditRecords immutable audit log history.
 */
public record DlqTaskInspection(
        Task task,
        List<ExecutionAttempt> attempts,
        List<TaskAuditRecord> auditRecords
) {}
