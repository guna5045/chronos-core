package com.chronoscore.coordinator.persistence.repository;

import com.chronoscore.coordinator.persistence.model.TaskAuditRecord;

import java.util.List;
import java.util.UUID;

/**
 * Append-only repository interface for task state transition audit logs.
 */
public interface TaskAuditRepository {

    /**
     * Appends an immutable audit log record.
     *
     * @param record audit log record to append.
     * @return persisted audit log record.
     */
    TaskAuditRecord append(TaskAuditRecord record);

    /**
     * Retrieves all audit records for a given task ordered by creation time ascending.
     *
     * @param taskId task UUID.
     * @return ordered list of audit records.
     */
    List<TaskAuditRecord> findByTaskId(UUID taskId);
}
