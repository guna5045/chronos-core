# Chronos Core - Control Plane Architecture & Operational Manual

## Status
- **Current Phase**: Phase 11 (Production Control Plane)
- **Specification Status**: React 18 / TypeScript / Vite / TanStack Query Production Engineering Control Plane

---

## 1. Overview & Architectural Philosophy

The **Chronos Core Control Plane** is a specialized engineering operations console engineered for observing, inspecting, and controlling distributed task and workflow execution across Chronos Core clusters.

It is strictly an infrastructure console:
- **No Marketing / Generic SaaS**: Dense, technical metadata, monospaced identifiers, timestamps, and real-time state machine progression.
- **Zero Synthetic Data**: Every metric, card, table row, and chart value originates exclusively from the authoritative Phase 10 REST API (`/api/v1`).
- **Separation of Concerns**: The frontend is a control plane; it never duplicates scheduling, locking, retry evaluation, DAG validation, or recovery logic.

```text
                         USER
                           │
                           ▼
              ┌────────────────────────┐
              │   CHRONOS CONTROL      │
              │        PLANE           │
              │ React + TypeScript     │
              │ TanStack Query         │
              └────────────┬───────────┘
                           │
                     REST /api/v1
                           │
                           ▼
              ┌────────────────────────┐
              │ Spring Boot REST API   │
              │ JWT + RBAC + Tenant    │
              │ Validation + Errors    │
              └────────────┬───────────┘
                           │
                           ▼
                 CHRONOS CORE SERVICES
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
       Scheduler       Workflow          Recovery
          │                │                │
          └────────────────┼────────────────┘
                           │
                    ┌──────┴──────┐
                    ▼             ▼
                PostgreSQL      Redis
```

---

## 2. Route Directory & Operational Navigation

| Route | View Component | Description | Access Control |
| :--- | :--- | :--- | :--- |
| `/login` | `LoginPage` | Authentication gateway supporting dev profile token minting and raw Bearer JWT input. | Public |
| `/` | `DashboardPage` | Operational overview: cluster leadership, fleet utilization, real task distribution chart, recent submissions. | Protected (`VIEWER+`) |
| `/tasks` | `TasksPage` | Task explorer table with status/tenant/type/worker filters, pagination, and direct task jump. | Protected (`VIEWER+`) |
| `/tasks/:taskId` | `TaskDetailPage` | Deep task inspection, execution parameters, lifecycle timeline, payload viewer, retry, and cancellation. | Protected (`VIEWER+`) |
| `/workers` | `WorkersPage` | Registered worker fleet listing, heartbeat freshness, active tasks, and capacity distribution. | Protected (`VIEWER+`) |
| `/workers/:workerId` | `WorkerDetailPage` | Worker node inspection, session identity, registered capabilities, and currently allocated tasks. | Protected (`VIEWER+`) |
| `/workflows` | `WorkflowsPage` | Workflow definition catalog, version status, and namespace registration modal. | Protected (`VIEWER+`) |
| `/workflows/:workflowId`| `WorkflowDetailPage` | Interactive DAG visualizer, versions list, version publication, and execution trigger. | Protected (`VIEWER+`) |
| `/workflow-executions/:executionId` | `WorkflowExecutionPage` | Live workflow execution state, DAG with real node execution statuses, and task associations. | Protected (`VIEWER+`) |
| `/dlq` | `DlqPage` | Dead-letter queue manager: deep inspection of worker attempt failures, audit trail, replay, and archive. | Protected (`VIEWER+`) |
| `/cluster` | `ClusterPage` | Coordinator leadership role, lease epoch, total concurrency, and cluster-to-worker dispatch topology. | Protected (`VIEWER+`) |
| `/tenants/:tenantId/policy` | `TenantPolicyPage` | Tenant execution quota and token bucket rate limit view (editable by `ADMIN`). | Protected (`VIEWER+`) |

---

## 3. Frontend Authentication & Token Flow

1. **Authentication Gateway**:
   - `LoginPage` calls `POST /api/v1/auth/token` with selected subject, role (`ADMIN`, `OPERATOR`, `VIEWER`), and tenant context, receiving a cryptographically signed HMAC-SHA256 JWT.
   - Alternatively, operators may supply external Bearer JWTs minted by OpenID Connect identity providers.
2. **Storage & State**:
   - Token is stored in `localStorage('chronos_auth_token')`.
   - Principal metadata is stored in `localStorage('chronos_auth_user')`.
   - `AuthProvider` exposes reactive authentication state, roles, and capability flags throughout the tree.
3. **Automatic Bearer Injection**:
   - The centralized `request()` client (`src/api/client.ts`) attaches `Authorization: Bearer <token>` to every HTTP request.
4. **401 Unauthorized Interception**:
   - When any API call returns `401 Unauthorized`, an event `'chronos:unauthorized'` is dispatched, which clears the session and navigates the user back to `/login`.
5. **403 Forbidden Feedback**:
   - Mapped directly to clear user feedback: *"Access denied for tenant resource"* or *"This action requires administrative privileges"*.

---

## 4. Role-Based Access Control (RBAC) in UI

| Capability / Operational Action | `ROLE_VIEWER` | `ROLE_OPERATOR` | `ROLE_ADMIN` |
| :--- | :---: | :---: | :---: |
| Inspect Dashboard, Tasks, Workers, Workflows, DLQ, Cluster | YES | YES | YES |
| Submit New Task (`POST /api/v1/tasks`) | Hidden | Visible | Visible |
| Retry Failed/DLQ Task (`POST /tasks/{id}/retry`) | Hidden | Enabled | Enabled |
| Cancel Active Task (`POST /tasks/{id}/cancel`) | Hidden | Enabled | Enabled |
| Register Workflow Namespace | Hidden | Enabled | Enabled |
| Draft / Publish / Deprecate Workflow Versions | Hidden | Hidden | Enabled |
| Execute Workflow DAG (`POST /workflows/{id}/execute`) | Hidden | Enabled | Enabled |
| Cancel Workflow Execution | Hidden | Enabled | Enabled |
| Replay Dead-Letter Task (`POST /dlq/{id}/replay`) | Hidden | Enabled | Enabled |
| Archive Dead-Letter Task (`POST /dlq/{id}/archive`) | Hidden | Enabled | Enabled |
| Update Tenant Execution Policy (`PUT /tenants/{id}/policy`)| Read-only | Read-only | Enabled |

> **Security Note**: Frontend role checks are for operator ergonomics. Backend authorization via Spring Security remains authoritative on every single request.

---

## 5. DAG Visualization Architecture

The workflow DAG visualizer (`src/components/workflow/DagVisualizer.tsx`) is a deterministic, pure SVG component:
- **Topological Ranking**: Computes longest dependency paths using Kahn's algorithm, grouping nodes into level columns.
- **Bézier Connectors**: Renders smooth cubic Bézier curves (`<path d="M... C..." />`) with directional arrow markers between predecessor and successor nodes.
- **Execution State Mapping**:
  - `SUCCEEDED`: Emerald border and glow (`#10b981`)
  - `RUNNING`: Sky cyan border and pulsing dasharray (`#0284c7`)
  - `READY` / `QUEUED`: Indigo border (`#6366f1`)
  - `FAILED`: Rose/Red border (`#f43f5e`)
  - `SKIPPED` / `CANCELLED`: Slate muted border (`#64748b`)
  - `PENDING`: Faint surface border (`#94a3b8`)
- **Node Selection**: Clicking any node in the SVG opens a dedicated inspection drawer showing task type, timeout, and associated Chronos Task ID.

---

## 6. Dead-Letter Queue (DLQ) Operational Recovery

The DLQ view (`/dlq`) enables operators to:
1. **Inspect Historical Execution Attempts**: Review sequence numbers, assigned worker IDs, start/completion timestamps, failure error codes, and exception messages across all retries.
2. **Review State Audit Trail**: Chronological record of state machine transitions.
3. **Replay Task**: Prompts for an operational reason and requeues the task into PostgreSQL durable state (`TaskStatus.QUEUED`). If the task is a node in a workflow execution, downstream dependencies are automatically evaluated for activation upon successful completion.
4. **Archive Task**: Prompts for an operational reason and tags the task as archived, preserving execution attempt history for compliance without re-scheduling.

---

## 7. Environment Configuration & Local Development

The control plane is configurable via Vite environment variables:

| Variable | Default Value | Description |
| :--- | :--- | :--- |
| `VITE_API_BASE_URL` | `/api/v1` | Base URL for Chronos Core REST API. In development, Vite proxies `/api` to `http://localhost:8080`. |

### Local Development Startup
```bash
cd chronos-dashboard
npm install
npm run dev
```
Accessible at: `http://localhost:5173`.

### Automated Test Suite
```bash
npm test
```
Runs 22 behavioral unit/integration tests with Vitest and `@testing-library/react`.

### Production Build
```bash
npm run build
```
Executes `tsc -b` and builds minified Rollup chunks into `dist/`.

---

## 8. AI Workflow & Operations Control Plane Views (Phase 12)

Phase 12 integrates dedicated, task-oriented AI assistant consoles into the control plane under `/ai/workflows` and `/ai/operations`. These interfaces are designed as structured engineering tools rather than open-ended chat interfaces.

### 8.1 AI Workflow Agent (`/ai/workflows`)
- **Natural Language Input**: Textarea for describing multi-step pipelines with prompt length meter (capped at 4,000 characters to prevent prompt injection abuse) and quick template buttons (e.g. *Image Pipeline*, *ETL Pipeline*, *ML Model Inference*).
- **DAG Synthesis & Kahn Topological Preview**:
  - Reuses the SVG `DagVisualizer` to render proposed nodes, dependency edges, and topological ranks.
  - Displays deterministic validation badge (`VALID DAG` / `INVALID`) verified by Chronos Core's acyclicity checker.
  - Node specifications table listing node IDs, task types, max attempt bounds, and execution timeouts.
- **Human-In-The-Loop Approval Gate**:
  - `Approve & Materialize Workflow`: Opens a confirmation modal. When confirmed by an `OPERATOR` or `ADMIN`, creates a new workflow definition with Draft Version 1 in the workflow registry.
  - `Reject Proposal`: Marks proposal as discarded.
  - Disabled for `VIEWER` users with explicit role requirement tooltips.

### 8.2 AI Operations Agent (`/ai/operations`)
- **Telemetry Correlation & Incident Triage Console**:
  - Scoped diagnosis trigger: Allows scoping analysis to `CLUSTER`, `WORKER`, `TASK`, or `DLQ` with target identifier input.
  - Diagnostic summary with deterministic severity badge (`INFO`, `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`).
  - Likely root causes list derived from correlated telemetry patterns.
- **Verified Telemetry Evidence Table**:
  - Shows concrete observed system evidence (e.g. worker heartbeat failures, task timeout counts, DLQ poison pill classifications).
  - Deep links directly to corresponding resource views (`/workers/:id`, `/tasks/:id`, `/dlq`).
- **Controlled Operational Recommendations**:
  - Action cards displaying action type (`RETRY_TASK`, `CANCEL_TASK`, `REPLAY_DLQ`, `ARCHIVE_DLQ`, `CANCEL_WORKFLOW`, `DRAIN_WORKER`), risk level (`LOW`, `MEDIUM`, `HIGH`), expiration deadline, and rationale.
  - Human approval gate requiring an operational reason logged to audit trail.
  - **Stale State Protection**: If the target state has evolved (e.g. task already succeeded before approval), the UI displays a clear conflict warning preventing duplicate or invalid execution.

---

## 7. Phase 13 Control Plane Hardening & Design System

### 7.1 Global Theme System (Light & Dark Mode)
The Control Plane features a semantic design token architecture in `index.css`:
- **Default Dark Theme**: Clean deep neutral background (`#090d16`), elevated slate surfaces (`#0f172a`), muted borders (`#1e293b`), and cyan operational accents.
- **Independent Light Theme**: Clean high-contrast background (`#f8fafc`), crisp card surfaces (`#ffffff`), and slate text (`#0f172a`), with **zero dark leftovers** on cards, tooltips, tables, or navigation.
- **Persistence**: User selection persists in `localStorage` under `chronos_theme` with instant switching via the Header Moon/Sun button.

### 7.2 Five-State Recharts Task Distribution
The execution distribution bar chart handles five explicit operational states:
1. **Loading**: Renders dedicated loading skeleton with spinning indicators.
2. **Error**: Renders structured `ErrorAlert` with interactive retry button.
3. **No Data**: Renders clear message (`"No task executions in the selected interval."`) rather than an empty broken graph.
4. **Data Available**: Renders high-performance bar chart with real-time category counts (`QUEUED`, `DISPATCHED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `DLQ`, `CANCELLED`).
5. **Partial Data**: Displays telemetry with clear partial indicators if satellite endpoints are degraded.

### 7.3 Observability & Telemetry View (`/observability`)
Dedicated operator console displaying:
- Prometheus scrape link (`/actuator/prometheus`).
- Health probes: Liveness and Readiness state.
- Distributed tracing bridge architecture and W3C trace context propagation.
- Micrometer metric catalog and Chaos Engineering invariant verification table.


