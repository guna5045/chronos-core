package com.chronoscore.coordinator.api.dto.response;

import com.chronoscore.common.domain.ExecutionAttempt;
import com.chronoscore.coordinator.persistence.model.TaskAuditRecord;
import com.chronoscore.coordinator.recovery.dlq.DlqTaskInspection;

import java.util.List;

/**
 * Diagnostic response for inspecting a dead-lettered task along with
 * its historical execution attempts and audit log records.
 */
public record DlqInspectionResponse(
        TaskResponse task,
        List<ExecutionAttempt> attempts,
        List<TaskAuditRecord> auditRecords
) {

    public static DlqInspectionResponse from(DlqTaskInspection inspection) {
        if (inspection == null) {
            return null;
        }
        return new DlqInspectionResponse(
                TaskResponse.from(inspection.task()),
                inspection.attempts() != null ? inspection.attempts() : List.of(),
                inspection.auditRecords() != null ? inspection.auditRecords() : List.of()
        );
    }
}
