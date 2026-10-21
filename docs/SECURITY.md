# Chronos Core - Security & Authorization Architecture

## Status
- **Current Phase**: Phase 10 (REST API & Security Layer)
- **Specification Status**: Production REST API Security, OAuth2 Resource Server, JWT Authentication, RBAC, Multi-Tenant Isolation, and IDOR Protection

---

## 1. Security Overview

Chronos Core exposes an engineering control plane REST API under the `/api/v1` namespace. Security is designed with strict multi-tenancy, defense-in-depth, and principle of least privilege:

```text
                    HTTP CLIENT
                         │
                         ▼
             ┌─────────────────────────┐
             │ REST Gateway (/api/v1)  │
             │ Spring Security Filter  │
             └───────────┬─────────────┘
                         │
        ┌────────────────┴────────────────┐
        │ 1. JWT Signature & Expiry Check │
        │ 2. Claims Extraction            │
        │ 3. RBAC Role Resolution         │
        │ 4. Tenant Isolation Enforcement │
        └────────────────┬────────────────┘
                         │
                         ▼
              Authorized Controller
                         │
                         ▼
               Application Services
```

---

## 2. Authentication Architecture

### 2.1 OAuth2 Resource Server with JWT
The REST API operates as a stateless OAuth2 Resource Server validating standard Bearer tokens (`Authorization: Bearer <token>`):
- **Signature Algorithm**: HMAC-SHA256 (`HS256`) for local dev/testing; compatible with external OpenID Connect identity providers in production via JWK Set URI or signed secret.
- **Claims Verified**:
  - `sub`: Principal subject / user identifier.
  - `roles`: Assigned RBAC roles array (e.g., `["ADMIN"]`, `["OPERATOR"]`, `["VIEWER"]`).
  - `tenant_id`: Mandatory tenant identifier for non-admin principals.
  - `iss`: Issuer verification (`chronos-core`).
  - `exp`: Expiration timestamp check.

### 2.2 Local Development & Testing Token Generation
For local development and automated testing without external OAuth2/IdP infrastructure:
- **Dev Auth Endpoint**:
  ```http
  POST /api/v1/auth/token
  Content-Type: application/json

  {
    "subject": "operator-user",
    "roles": ["OPERATOR"],
    "tenantId": "tenant-alpha"
  }
  ```
  Returns a cryptographically signed HMAC-SHA256 JWT valid for development testing.
- **Dev Token Helper**: `DevJwtTokenHelper` provides programmatic token minting for automated JUnit tests.

---

## 3. Role-Based Access Control (RBAC)

Chronos Core enforces 3 explicit roles:

| Role | Hierarchy & Responsibilities | Scope of Operations |
| :--- | :--- | :--- |
| `ROLE_VIEWER` | Read-only visibility | List & inspect tasks, workflows, executions, workers, cluster overview, DLQ, and tenant policies. |
| `ROLE_OPERATOR` | Operational task & execution management | All `VIEWER` permissions + submit tasks, retry tasks, cancel tasks, execute workflows, cancel workflow executions, replay DLQ tasks, archive DLQ tasks. |
| `ROLE_ADMIN` | Administrative governance & cluster control | All `OPERATOR` permissions + manage workflow versions (create, publish, deprecate), configure tenant policies (`PUT /api/v1/tenants/{id}/policy`), cross-tenant override. |

### 3.1 Authorization Matrix

| Endpoint | Method | Required Role | Tenant Enforcement |
| :--- | :--- | :--- | :--- |
| `/api/v1/tasks` | `POST` | `OPERATOR`, `ADMIN` | Task tenant must match token `tenant_id` (unless Admin). |
| `/api/v1/tasks` | `GET` | `VIEWER`, `OPERATOR`, `ADMIN` | Automatically scoped to caller's `tenant_id`. |
| `/api/v1/tasks/{taskId}` | `GET` | `VIEWER`, `OPERATOR`, `ADMIN` | Target task's tenant must match caller's `tenant_id`. |
| `/api/v1/tasks/{taskId}/retry` | `POST` | `OPERATOR`, `ADMIN` | Target task's tenant must match caller's `tenant_id`. |
| `/api/v1/tasks/{taskId}/cancel` | `POST` | `OPERATOR`, `ADMIN` | Target task's tenant must match caller's `tenant_id`. |
| `/api/v1/workflows` | `POST` | `OPERATOR`, `ADMIN` | Workflow namespace registration. |
| `/api/v1/workflows` | `GET` | `VIEWER`, `OPERATOR`, `ADMIN` | Global workflow definition listing. |
| `/api/v1/workflows/{id}` | `GET` | `VIEWER`, `OPERATOR`, `ADMIN` | Workflow inspection. |
| `/api/v1/workflows/{id}/versions` | `GET` | `VIEWER`, `OPERATOR`, `ADMIN` | Workflow version inspection. |
| `/api/v1/workflows/{id}/versions` | `POST` | `ADMIN` | Version drafting. |
| `/api/v1/workflows/{id}/versions/{v}/publish` | `POST` | `ADMIN` | Version publication & DAG immutability lock. |
| `/api/v1/workflows/{id}/versions/{v}/deprecate` | `POST` | `ADMIN` | Version deprecation. |
| `/api/v1/workflows/{id}/execute` | `POST` | `OPERATOR`, `ADMIN` | Execution tenant must match token `tenant_id`. |
| `/api/v1/workflow-executions/{id}` | `GET` | `VIEWER`, `OPERATOR`, `ADMIN` | Execution's tenant must match caller's `tenant_id`. |
| `/api/v1/workflow-executions/{id}/cancel` | `POST` | `OPERATOR`, `ADMIN` | Execution's tenant must match caller's `tenant_id`. |
| `/api/v1/workers` | `GET` | `VIEWER`, `OPERATOR`, `ADMIN` | Operational cluster worker listing. |
| `/api/v1/workers/{workerId}` | `GET` | `VIEWER`, `OPERATOR`, `ADMIN` | Worker node inspection. |
| `/api/v1/cluster` | `GET` | `VIEWER`, `OPERATOR`, `ADMIN` | Cluster overview (leader status, capacity, stats). |
| `/api/v1/dlq` | `GET` | `VIEWER`, `OPERATOR`, `ADMIN` | Automatically scoped to caller's `tenant_id`. |
| `/api/v1/dlq/{taskId}` | `GET` | `VIEWER`, `OPERATOR`, `ADMIN` | DLQ task tenant must match caller's `tenant_id`. |
| `/api/v1/dlq/{taskId}/replay` | `POST` | `OPERATOR`, `ADMIN` | DLQ task tenant must match caller's `tenant_id`. |
| `/api/v1/dlq/{taskId}/archive` | `POST` | `OPERATOR`, `ADMIN` | DLQ task tenant must match caller's `tenant_id`. |
| `/api/v1/tenants/{id}/policy` | `GET` | `VIEWER`, `OPERATOR`, `ADMIN` | Caller must match tenant `id` or be `ADMIN`. |
| `/api/v1/tenants/{id}/policy` | `PUT` | `ADMIN` | Explicit administrator quota & rate limit update. |

---

## 4. Multi-Tenant Isolation & IDOR Protection

Insecure Direct Object Reference (IDOR) attacks occur when an authenticated caller modifies an entity ID in a URL path to access or manipulate another tenant's data.

### 4.1 Server-Side Context Assertion
Chronos Core enforces server-side tenant isolation via `TenantSecurityContext`:
1. **Token Claim Extraction**: The `tenant_id` claim is extracted directly from the validated JWT principal.
2. **Context Resolution**: When non-administrative users query listings without an explicit tenant, the API automatically binds the query to `TenantSecurityContext.resolveEffectiveTenantId(...)`.
3. **IDOR Assertion (`assertCanAccessTenant`)**:
   - Before returning or mutating any resource (`Task`, `WorkflowExecution`, `DlqTask`, `TenantPolicy`), the controller loads the entity and invokes:
     ```java
     TenantSecurityContext.assertCanAccessTenant(resource.getTenantId());
     ```
   - If the caller's `tenant_id` does not match the resource's `tenantId`, an `AccessDeniedException` is thrown immediately.
   - The centralized exception handler translates this to `403 Forbidden` with error code `FORBIDDEN`.
4. **Administrative Cross-Tenant Scope**:
   - Principals holding `ROLE_ADMIN` are permitted to inspect and manage across tenants for platform administration and troubleshooting.

---

## 5. Standard Error Model & Information Leakage Prevention

Chronos Core strictly suppresses internal database schemas, SQL exceptions, Redis connection details, and Java stack traces:

### 5.1 Error Payload Schema
```json
{
  "timestamp": "2026-10-21T18:00:00Z",
  "status": 403,
  "code": "FORBIDDEN",
  "message": "Access denied for tenant resource",
  "path": "/api/v1/tasks/550e8400-e29b-41d4-a716-446655440000",
  "fieldErrors": {}
}
```

### 5.2 Error Code Mapping

| HTTP Status | Error Code | Triggering Scenario |
| :--- | :--- | :--- |
| `400 Bad Request` | `VALIDATION_ERROR` | Bean validation failure, missing required fields, negative limits. |
| `400 Bad Request` | `INVALID_DAG` | Workflow DAG cycle, self-loop, orphaned dependency, graph size limit exceeded. |
| `401 Unauthorized` | `UNAUTHORIZED` | Missing Bearer token, expired JWT, invalid/tampered cryptographic signature. |
| `403 Forbidden` | `FORBIDDEN` | Insufficient role, cross-tenant IDOR access attempt. |
| `404 Not Found` | `NOT_FOUND` | Task, workflow, version, execution, worker, or DLQ task does not exist. |
| `409 Conflict` | `STATE_CONFLICT` | Invalid task/workflow state transition, duplicate execution version, already terminal. |
| `429 Too Many Requests`| `RATE_LIMITED` | Tenant task submission or scheduling rate limit exceeded. |
| `500 Internal Error` | `INTERNAL_ERROR` | Unhandled unexpected exceptions (stack traces logged internally, never sent to client). |

---

## 6. HTTP Security Protections & Headers

Spring Security enforces defense-in-depth HTTP headers on all API responses:
- **Session Management**: `SessionCreationPolicy.STATELESS` (no HTTP cookies or sessions stored on server).
- **CSRF**: Disabled for token-authenticated stateless REST APIs.
- **Frame Options**: `DENY` to prevent clickjacking attacks.
- **Content Type Options**: `nosniff` preventing MIME-type sniffing.
- **XSS Protection**: Sanitized JSON responses without raw HTML rendering.
- **CORS Configuration**: Configurable via `chronos.security.cors-allowed-origins` (defaults to local Vite dev server `http://localhost:5173`, never wildcard `*` in production).

---

## 7. Configuration & Secret Management

All security properties are externalized via environment variables:

| Property | Default Value | Environment Variable Override |
| :--- | :--- | :--- |
| `chronos.security.jwt-secret` | Development default secret (HMAC 256) | `CHRONOS_SECURITY_JWT_SECRET` |
| `chronos.security.jwt-issuer` | `chronos-core` | `CHRONOS_SECURITY_JWT_ISSUER` |
| `chronos.security.jwt-expiration-seconds` | `3600` (1 hour) | `CHRONOS_SECURITY_JWT_EXPIRATION_SECONDS` |
| `chronos.security.cors-allowed-origins` | `http://localhost:5173,http://localhost:3000` | `CHRONOS_SECURITY_CORS_ALLOWED_ORIGINS` |

> **Security Rule**: Never commit production private keys or secret values into version control. In production environments, set `CHRONOS_SECURITY_JWT_SECRET` via secure external secret vaults (e.g., AWS Secrets Manager, HashiCorp Vault, Kubernetes Secrets).

---

## 8. AI Security Controls & Safety Guarantees (Phase 12)

Phase 12 introduces AI Workflow and Operations capabilities governed by strict security boundaries. The LLM is strictly advisory and operates behind deterministic authorization gates.

### 8.1 Role-Based Access Control for AI Features
| Capability / Endpoint | Minimum Role | Enforced Rule |
|---|---|---|
| `GET /api/v1/ai/status` | Public / Authenticated | Only exposes engine status and configured model. Never reveals API keys or provider secrets. |
| `POST /api/v1/ai/incidents/analyze` | `VIEWER` | Read-only analysis. Operates solely through read-only tools scoped to the user's tenant. |
| `GET /api/v1/ai/workflows`, `GET /api/v1/ai/actions` | `VIEWER` | Read-only inspection of proposals and recommendations for the authenticated tenant. |
| `POST /api/v1/ai/workflows/generate` | `OPERATOR` | Natural language proposal generation. Prompt bounded to 4,000 characters. |
| `POST /api/v1/ai/workflows/{id}/approve` | `OPERATOR` | Approves DAG proposal and creates new draft version in `WorkflowService`. Requires deterministic DAG validation pass (`valid == true`). |
| `POST /api/v1/ai/workflows/{id}/reject` | `OPERATOR` | Discards proposal and marks status as `REJECTED`. |
| `POST /api/v1/ai/actions/{id}/approve` | `OPERATOR` | Approves operational recommendation. Enforces stale-state re-check before executing mutation. |
| `POST /api/v1/ai/actions/{id}/reject` | `OPERATOR` | Discards operational recommendation. |
| Cross-tenant AI analysis / approvals | `ADMIN` | Strictly restricted to `ROLE_ADMIN`; regular tenants cannot access other tenants' data. |

### 8.2 Server-Side Tenant Isolation
- **No Reliance on LLM Filtering**: The application server enforces tenant scoping directly in `AiToolRegistry` and `AiActionApprovalService`. Even if a user prompts the AI to "inspect worker data for tenant-beta", the backend asserts that `requestedTenantId.equals(authenticatedUserTenantId)`.
- **IDOR Protection**: Attempting to approve or reject a workflow proposal or operational action belonging to another tenant returns `403 Forbidden` (`FORBIDDEN`).

### 8.3 Stale Action State Protection
- When an AI recommendation is generated (e.g. "Retry Task X"), the target entity's state at diagnosis time is recorded.
- Before executing approval, the approval service re-fetches the live entity:
  - If the task has already transitioned to `SUCCEEDED`, `RUNNING`, or `CANCELLED`, the execution is aborted immediately with `409 Conflict` (`STALE_ACTION_STATE`).
  - Stale recommendations are never blindly executed against evolved system state.

### 8.4 Action Bounded Lifetime (TTL Expiration)
- Operational recommendations are assigned a strict expiration deadline (default 30 minutes).
- Attempting to approve an expired action is rejected with `410 Gone` (`ACTION_EXPIRED`).

### 8.5 Prompt Injection Defense
- System prompts, tool schemas, and untrusted operational data are logically and structurally separated.
- User-provided workflow descriptions are encapsulated within strict `<user_workflow_description>` boundaries and capped at 4,000 characters.
- Operational payloads, error logs, and task inputs retrieved from persistence are treated strictly as untrusted data values and sanitized before injection into prompt templates.

### 8.6 Absolute Operational Prohibitions
The AI subsystem is strictly forbidden from:
- Executing arbitrary SQL or communicating directly with PostgreSQL.
- Executing arbitrary Redis commands.
- Calling `Runtime.getRuntime().exec()` or spawning system processes.
- Making unrestricted outbound HTTP requests (`httpRequest()` tools are prohibited).
- Bypassing the `WorkflowDagValidator` (Kahn's algorithm) or `TaskStateMachine`.
- Executing autonomous destructive operations without human approval.

