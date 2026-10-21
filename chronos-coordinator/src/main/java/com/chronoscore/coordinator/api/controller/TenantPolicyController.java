package com.chronoscore.coordinator.api.controller;

import com.chronoscore.common.domain.TenantExecutionPolicy;
import com.chronoscore.coordinator.api.dto.request.UpdateTenantPolicyRequest;
import com.chronoscore.coordinator.api.dto.response.TenantPolicyResponse;
import com.chronoscore.coordinator.api.security.TenantSecurityContext;
import com.chronoscore.coordinator.persistence.repository.TenantPolicyRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * REST controller for inspecting and configuring tenant execution quotas and rate limits.
 */
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantPolicyController {

    private final TenantPolicyRepository policyRepository;
    private final TenantSecurityContext securityContext;

    public TenantPolicyController(TenantPolicyRepository policyRepository, TenantSecurityContext securityContext) {
        this.policyRepository = Objects.requireNonNull(policyRepository, "policyRepository must not be null");
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext must not be null");
    }

    @GetMapping("/{tenantId}/policy")
    public ResponseEntity<TenantPolicyResponse> getTenantPolicy(@PathVariable String tenantId) {
        // IDOR prevention: non-admins can only view their own policy
        securityContext.assertCanAccessTenant(tenantId);

        TenantExecutionPolicy policy = policyRepository.getEffectivePolicy(tenantId);
        return ResponseEntity.ok(TenantPolicyResponse.from(policy));
    }

    @PutMapping("/{tenantId}/policy")
    public ResponseEntity<TenantPolicyResponse> updateTenantPolicy(
            @PathVariable String tenantId,
            @Valid @RequestBody UpdateTenantPolicyRequest request) {

        // ADMIN only endpoint (enforced via SecurityConfig)
        TenantExecutionPolicy existing = policyRepository.getEffectivePolicy(tenantId);
        Instant now = Instant.now();

        TenantExecutionPolicy.Builder builder = existing.toBuilder()
                .tenantId(tenantId)
                .updatedAt(now);

        if (request.maxConcurrentTasks() != null) {
            builder.maxConcurrentTasks(request.maxConcurrentTasks());
        }
        if (request.maxQueuedTasks() != null) {
            builder.maxQueuedTasks(request.maxQueuedTasks());
        }
        if (request.tasksPerSecond() != null) {
            builder.tasksPerSecond(request.tasksPerSecond());
        }
        if (request.burstCapacity() != null) {
            builder.burstCapacity(request.burstCapacity());
        }
        if (request.maxWorkflowExecutions() != null) {
            builder.maxWorkflowExecutions(request.maxWorkflowExecutions());
        }

        TenantExecutionPolicy updatedPolicy = builder.build();

        Optional<TenantExecutionPolicy> persistedOpt = policyRepository.findByTenantId(tenantId);
        TenantExecutionPolicy saved = persistedOpt.isPresent()
                ? policyRepository.update(updatedPolicy)
                : policyRepository.save(updatedPolicy);

        return ResponseEntity.ok(TenantPolicyResponse.from(saved));
    }
}
