package com.chronoscore.coordinator.ai.adapter;

import com.chronoscore.ai.ports.AiDlqOperationsPort;
import com.chronoscore.common.domain.ExecutionAttempt;
import com.chronoscore.common.domain.Task;
import com.chronoscore.coordinator.recovery.dlq.DlqService;
import com.chronoscore.coordinator.recovery.dlq.DlqTaskInspection;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter implementing AiDlqOperationsPort by delegating to DlqService.
 */
@Component
public class DlqOperationsAdapter implements AiDlqOperationsPort {

    private final DlqService dlqService;

    public DlqOperationsAdapter(DlqService dlqService) {
        this.dlqService = Objects.requireNonNull(dlqService, "dlqService must not be null");
    }

    @Override
    public List<DlqSummary> listDlq(String tenantId, int limit) {
        int safeLimit = Math.min(50, Math.max(1, limit));
        List<Task> tasks = dlqService.listDlqTasks(tenantId, safeLimit, 0);
        return tasks.stream()
                .map(t -> new DlqSummary(
                        t.getTaskId(),
                        t.getTaskType(),
                        t.getTenantId(),
                        "Task exceeded maximum retry attempts",
                        t.getCurrentAttempt(),
                        t.getCreatedAt(),
                        t.getCreatedAt()
                ))
                .toList();
    }

    @Override
    public Optional<DlqDetail> inspectDlq(UUID taskId) {
        Optional<DlqTaskInspection> inspectionOpt = dlqService.inspectTask(taskId);
        if (inspectionOpt.isEmpty()) {
            return Optional.empty();
        }
        DlqTaskInspection inspection = inspectionOpt.get();
        Task t = inspection.task();
        List<String> errors = inspection.attempts().stream()
                .map(ExecutionAttempt::getErrorMessage)
                .filter(Objects::nonNull)
                .toList();

        return Optional.of(new DlqDetail(
                t.getTaskId(),
                t.getTaskType(),
                t.getTenantId(),
                "Dead-lettered after " + t.getCurrentAttempt() + " attempts",
                t.getCurrentAttempt(),
                t.getCreatedAt(),
                t.getCreatedAt(),
                errors
        ));
    }

    @Override
    public void replayDlq(UUID taskId, String principal) {
        dlqService.replayTask(taskId, "AI Action Approval by: " + principal);
    }

    @Override
    public void archiveDlq(UUID taskId, String principal, String reason) {
        dlqService.archiveTask(taskId, "AI Action Approval (" + principal + "): " + (reason != null ? reason : "Archived"));
    }
}
