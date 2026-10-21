package com.chronoscore.coordinator.api.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Context helper for resolving authenticated principal identity, RBAC authorities,
 * and enforcing server-side tenant isolation boundaries (IDOR prevention).
 */
@Component
public class TenantSecurityContext {

    /**
     * Resolves the current authentication principal.
     */
    public Optional<Authentication> getAuthentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Optional.empty();
        }
        return Optional.of(auth);
    }

    /**
     * Retrieves the username/subject of the authenticated user.
     */
    public String getUsername() {
        return getAuthentication()
                .map(Authentication::getName)
                .orElse("anonymous");
    }

    /**
     * Retrieves the tenant identifier claim bound to the authenticated JWT.
     */
    public Optional<String> getTenantId() {
        return getAuthentication().flatMap(auth -> {
            if (auth instanceof JwtAuthenticationToken jwtAuth) {
                Jwt jwt = jwtAuth.getToken();
                String tenant = jwt.getClaimAsString("tenant_id");
                if (tenant == null || tenant.isBlank()) {
                    tenant = jwt.getClaimAsString("tenantId");
                }
                return Optional.ofNullable(tenant);
            }
            return Optional.empty();
        });
    }

    /**
     * Returns the set of granted authorities (e.g. ROLE_ADMIN, ROLE_OPERATOR, ROLE_VIEWER).
     */
    public Set<String> getAuthorities() {
        return getAuthentication()
                .map(auth -> auth.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    public boolean isAdmin() {
        return getAuthorities().contains(ChronosRoles.AUTHORITY_ADMIN);
    }

    public boolean isOperator() {
        return isAdmin() || getAuthorities().contains(ChronosRoles.AUTHORITY_OPERATOR);
    }

    public boolean isViewer() {
        return isOperator() || getAuthorities().contains(ChronosRoles.AUTHORITY_VIEWER);
    }

    /**
     * Asserts that the authenticated user has legitimate access to the given tenant partition.
     * <p>
     * Invariants:
     * <ul>
     *   <li>ADMIN users are authorized across all tenants.</li>
     *   <li>Non-ADMIN users are strictly confined to their own tenant claim.</li>
     *   <li>Any cross-tenant attempt throws {@link AccessDeniedException} (HTTP 403 Forbidden).</li>
     * </ul>
     *
     * @param targetTenantId the tenant ID of the resource being accessed.
     * @throws AccessDeniedException if cross-tenant isolation is violated.
     */
    public void assertCanAccessTenant(String targetTenantId) {
        if (targetTenantId == null || targetTenantId.isBlank()) {
            return;
        }
        if (isAdmin()) {
            return; // Admins have cross-tenant platform visibility
        }

        String userTenant = getTenantId().orElse(null);
        if (userTenant == null || !userTenant.equals(targetTenantId)) {
            throw new AccessDeniedException(String.format(
                    "Tenant isolation violation: user '%s' bound to tenant '%s' cannot access tenant '%s'",
                    getUsername(), userTenant, targetTenantId
            ));
        }
    }

    /**
     * Resolves the effective tenant ID for submission or scoped query.
     * Non-admins are forced to their claim tenant; admins may target any tenant.
     *
     * @param requestedTenantId optional tenant ID from request body or query parameter.
     * @return resolved authoritative tenant ID.
     */
    public String resolveEffectiveTenantId(String requestedTenantId) {
        if (isAdmin()) {
            if (requestedTenantId != null && !requestedTenantId.isBlank()) {
                return requestedTenantId.trim();
            }
            return getTenantId().orElse("default");
        }

        String userTenant = getTenantId().orElseThrow(() ->
                new AccessDeniedException("Authenticated user token is missing a valid 'tenant_id' claim"));

        if (requestedTenantId != null && !requestedTenantId.isBlank() && !requestedTenantId.equals(userTenant)) {
            throw new AccessDeniedException(String.format(
                    "Cross-tenant submission forbidden: authenticated as tenant '%s' but requested tenant '%s'",
                    userTenant, requestedTenantId
            ));
        }

        return userTenant;
    }
}
