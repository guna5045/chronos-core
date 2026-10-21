package com.chronoscore.ai.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Audit service logging and recording all AI interactions, tool invocations, and approvals.
 */
public class AiAuditService {

    private static final Logger log = LoggerFactory.getLogger(AiAuditService.class);
    private static final int MAX_AUDIT_HISTORY = 1000;

    private final Queue<AiAuditRecord> history = new ConcurrentLinkedQueue<>();

    public void record(String agent, String principal, String tenantId, String action,
                       String targetId, boolean success, String details) {
        AiAuditRecord record = new AiAuditRecord(
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                agent != null ? agent : "UNKNOWN_AGENT",
                principal != null ? principal : "anonymous",
                tenantId != null ? tenantId : "global",
                action != null ? action : "UNKNOWN_ACTION",
                targetId != null ? targetId : "N/A",
                success,
                details != null ? details : "",
                Instant.now()
        );

        history.add(record);
        while (history.size() > MAX_AUDIT_HISTORY) {
            history.poll();
        }

        log.info("[AI-AUDIT] agent={} principal={} tenant={} action={} target={} success={}",
                record.agent(), record.principal(), record.tenantId(), record.action(), record.targetId(), record.success());
    }

    public List<AiAuditRecord> getRecentAuditRecords(int limit) {
        List<AiAuditRecord> list = new ArrayList<>(history);
        Collections.reverse(list);
        if (list.size() > limit && limit > 0) {
            return list.subList(0, limit);
        }
        return list;
    }
}
