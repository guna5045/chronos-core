package com.chronoscore.ai.ports;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Operational port for dead-letter queue inspection and approved replay/archival.
 */
public interface AiDlqOperationsPort {

    record DlqSummary(
            UUID taskId,
            String taskType,
            String tenantId,
            String failureReason,
            int attemptCount,
            Instant createdAt,
            Instant failedAt
    ) {}

    record DlqDetail(
            UUID taskId,
            String taskType,
            String tenantId,
            String failureReason,
            int attemptCount,
            Instant createdAt,
            Instant failedAt,
            List<String> attemptErrors
    ) {}

    List<DlqSummary> listDlq(String tenantId, int limit);

    Optional<DlqDetail> inspectDlq(UUID taskId);

    void replayDlq(UUID taskId, String principal);

    void archiveDlq(UUID taskId, String principal, String reason);
}
