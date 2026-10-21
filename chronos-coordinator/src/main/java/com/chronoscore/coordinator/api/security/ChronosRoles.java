package com.chronoscore.coordinator.api.security;

/**
 * Role-Based Access Control (RBAC) role and authority constants for Chronos Core.
 */
public final class ChronosRoles {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_OPERATOR = "OPERATOR";
    public static final String ROLE_VIEWER = "VIEWER";

    public static final String AUTHORITY_ADMIN = "ROLE_ADMIN";
    public static final String AUTHORITY_OPERATOR = "ROLE_OPERATOR";
    public static final String AUTHORITY_VIEWER = "ROLE_VIEWER";

    public static final String HAS_ROLE_ADMIN = "hasRole('ADMIN')";
    public static final String HAS_ROLE_OPERATOR = "hasAnyRole('OPERATOR', 'ADMIN')";
    public static final String HAS_ROLE_VIEWER = "hasAnyRole('VIEWER', 'OPERATOR', 'ADMIN')";

    private ChronosRoles() {
    }
}
