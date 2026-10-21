package com.chronoscore.coordinator.ai.adapter;

import com.chronoscore.ai.ports.AiTenantOperationsPort;
import com.chronoscore.common.domain.TenantExecutionPolicy;
import com.chronoscore.coordinator.persistence.repository.TenantPolicyRepository;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * Adapter implementing AiTenantOperationsPort by delegating to TenantPolicyRepository.
 */
@Component
public class TenantOperationsAdapter implements AiTenantOperationsPort {

    private final TenantPolicyRepository policyRepository;

    public TenantOperationsAdapter(TenantPolicyRepository policyRepository) {
        this.policyRepository = Objects.requireNonNull(policyRepository, "policyRepository must not be null");
    }

    @Override
    public Optional<TenantPolicyDetail> getTenantPolicy(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        TenantExecutionPolicy policy = policyRepository.getEffectivePolicy(tenantId);
        return Optional.of(new TenantPolicyDetail(
                policy.getTenantId(),
                policy.getMaxConcurrentTasks(),
                policy.getMaxQueuedTasks(),
                policy.getTasksPerSecond(),
                policy.getBurstCapacity(),
                policy.getMaxWorkflowExecutions()
        ));
    }
}
