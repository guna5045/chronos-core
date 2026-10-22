# Chronos Core — Production & Local Deployment Guide

## 1. Overview & Architecture

Chronos Core is a distributed task and workflow orchestration platform with deterministic concurrency guarantees. A standard deployment consists of:
- **PostgreSQL 16**: Durable state store, task repository, audit log, outbox events, and workflow definitions.
- **Redis 7.2**: Distributed lease-based leader election, distributed locks, generational fencing tokens, and worker heartbeats.
- **Chronos Coordinator**: Spring Boot 3 / Java 21 leader instance orchestrating scheduling, admission control, and failure recovery.
- **Chronos Worker Fleet**: Multi-node worker instances executing task workloads via Java virtual threads over gRPC.
- **Chronos Dashboard**: Production React/TypeScript control plane served via Nginx with SPA routing and API reverse proxying.
- **Prometheus & Grafana**: Low-cardinality metric scraping and operational dashboard visualization.

```text
                  Client / Operator
                          │
                   (HTTP Port 5173 / 80)
                          ▼
              ┌────────────────────────┐
              │    Chronos Dashboard   │ (Nginx)
              │ /api/ ──► Coordinator  │
              └───────────┬────────────┘
                          │ (HTTP 8080)
                          ▼
              ┌────────────────────────┐
              │   Chronos Coordinator  │
              │  (REST / Scheduler)    │
              └─────┬────────────┬─────┘
                    │            │ (gRPC 9090)
    (JDBC 5432)     │            ▼
         ┌──────────┤     ┌──────────────┐
         ▼          ▼     │ Worker Fleet │
   ┌──────────┐ ┌───────┐ │  (Nodes 1-3) │
   │Postgres16│ │Redis7 │ └──────────────┘
   └──────────┘ └───────┘
```

---

## 2. Service Port Matrix

| Service | Internal Port | External Port | Protocol | Purpose |
|---|---|---|---|---|
| **Chronos Coordinator** | `8080` | `8080` | HTTP / REST | Control plane REST API, Actuator health & metrics |
| **Chronos Coordinator gRPC** | `9090` | `9090` | gRPC / HTTP2 | Worker registration, heartbeat streams, task dispatch |
| **Chronos Worker 1** | `8081` | `8081` | HTTP | Worker 1 actuator / management |
| **Chronos Worker 2** | `8081` | `8082` | HTTP | Worker 2 actuator / management |
| **Chronos Worker 3** | `8081` | `8083` | HTTP | Worker 3 actuator / management |
| **Chronos Dashboard** | `80` | `5173` | HTTP | Web Control Plane (Nginx SPA + Reverse Proxy) |
| **PostgreSQL** | `5432` | `5432` | TCP | Relational state store |
| **Redis** | `6379` | `6379` | TCP | Distributed locks and lease election |
| **Prometheus** | `9090` | `9095` | HTTP | Time-series scraper (mapped to 9095 to avoid gRPC collision) |
| **Grafana** | `3000` | `3000` | HTTP | Metrics visualization |

---

## 3. Local Deployment Options

### Option A: Full Docker Compose Deployment (Production-Like)

#### Prerequisites
- Docker Engine 24+ and Docker Compose v2+
- 4 GB+ free RAM

#### Step 1: Environment Setup
```bash
# Clone repository
git clone https://github.com/chronoscore/chronoscore.git
cd chronoscore

# Copy configuration template
cp .env.example .env
```

#### Step 2: Launch Stack
```bash
# Launch PostgreSQL, Redis, Coordinator, 3 Workers, Dashboard, Prometheus, Grafana
docker compose up -d --build
```

#### Step 3: Verify Container Health
```bash
docker compose ps
```
Expected output:
- `chronos-postgres`: Up (healthy)
- `chronos-redis`: Up (healthy)
- `chronos-coordinator`: Up (healthy)
- `chronos-worker-1`: Up
- `chronos-worker-2`: Up
- `chronos-worker-3`: Up
- `chronos-dashboard`: Up (healthy)
- `chronos-prometheus`: Up
- `chronos-grafana`: Up

#### Step 4: Access Interfaces
- **Web Control Plane**: `http://localhost:5173`
- **Coordinator REST API**: `http://localhost:8080/api/v1`
- **Prometheus Scraper**: `http://localhost:9095`
- **Grafana Dashboards**: `http://localhost:3000` (credentials: `admin` / `chronos_admin`)

---

### Option B: Local Developer Mode (Host Execution)

#### Prerequisites
- Java 21 JDK (`temurin-21`)
- Maven 3.9+
- Node.js 20+ & npm 10+
- Running PostgreSQL (or H2 in-memory dev profile)
- Running Redis (or in-memory mock dev profile)

#### Step 1: Run Coordinator
```bash
# Compile and run coordinator with development profile (embedded H2 / in-memory coordination)
java -jar chronos-coordinator/target/chronos-coordinator-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
```

#### Step 2: Run Control Plane Dashboard
```bash
cd chronos-dashboard
npm install
npm run dev
# Dashboard available on http://localhost:5173
```

---

## 4. Database Migrations (Flyway)

Chronos Core uses automated Flyway schema migrations on coordinator startup:
- **Location**: `chronos-coordinator/src/main/resources/db/migration/`
- **Order of Migrations**:
  - `V1__create_tasks.sql`: Core task lifecycle schema with check constraints and index partitioning.
  - `V2__create_execution_attempts.sql`: Execution history and worker attempts.
  - `V3__create_task_audit_log.sql`: Immutable transition audit logs.
  - `V4__create_outbox_events.sql`: Transactional outbox event store.
  - `V5__create_task_leases.sql`: Fencing token leases.
  - `V6__create_workflow_orchestration.sql`: Workflow definitions, versioning, DAG node mappings.
  - `V7__create_advanced_platform_capabilities.sql`: Multi-tenant rate limits and admission policies.

Flyway runs automatically on startup:
- `validate-on-migrate: true` enforces checksum integrity.
- Never modify applied migration scripts directly; add a new sequential migration (`V8__...`).

---

## 5. Health Probes & Readiness Lifecycle

Chronos Core exposes standardized Spring Boot Actuator endpoints:

### Liveness Probe (`/actuator/health/liveness`)
- Checks internal JVM health and event loop responsiveness.
- Returns `HTTP 200 {"status":"UP"}`.
- Transient loss of Redis or database connectivity does **not** fail the liveness probe, avoiding restart storm thrashing.

### Readiness Probe (`/actuator/health/readiness`)
- Validates that the coordinator is connected to PostgreSQL and Redis.
- If the database or Redis connection drops, returns `HTTP 503 {"status":"DOWN"}` so Kubernetes or load balancers halt routing new inbound traffic.
- **AI Fault Isolation**: The optional AI subsystem does **not** impact readiness; if the AI provider is down or unconfigured, the readiness probe remains `UP`.

---

## 6. Troubleshooting Common Failure Modes

### Failure 1: Database Connection Refused
- **Symptom**: Coordinator fails on startup with `Cannot create PoolableConnectionFactory (Connection refused)`.
- **Root Cause**: PostgreSQL container not running or listening on expected host/port.
- **Remediation**:
  ```bash
  docker compose logs postgres
  docker compose restart postgres
  ```

### Failure 2: Redis Unavailable
- **Symptom**: Coordinator logs `RedisConnectionException: Unable to connect to redis:6379`.
- **Root Cause**: Redis service not started or firewall blocking port 6379.
- **Remediation**:
  ```bash
  docker compose logs redis
  docker compose restart redis
  ```

### Failure 3: Coordinator Unreachable on Control Plane
- **Symptom**: Dashboard displays red alert banner: `COORDINATOR_UNREACHABLE (HTTP 503)`.
- **Root Cause**: Backend coordinator is restarting, crashed, or Vite/Nginx proxy cannot reach port 8080.
- **Remediation**:
  1. Check coordinator health: `curl -i http://localhost:8080/actuator/health/liveness`.
  2. Once coordinator recovers, click **Retry** on the dashboard without reloading the browser.

### Failure 4: Worker Fails to Register
- **Symptom**: `WorkerRegistry` logs no active workers; task queue depth increases without dispatch.
- **Root Cause**: Worker cannot resolve `CHRONOS_COORDINATOR_HOST:9090` or gRPC channel dropped.
- **Remediation**:
  ```bash
  docker compose logs chronos-worker-1
  ```
  Verify `CHRONOS_COORDINATOR_HOST=chronos-coordinator` and `CHRONOS_COORDINATOR_PORT=9090`.

### Failure 5: Flyway Migration Checksum Mismatch
- **Symptom**: Coordinator startup halts with `Migration checksum mismatch for migration version X`.
- **Root Cause**: An already applied SQL migration script was modified in source code.
- **Remediation**: Do not edit past migration files. Roll back changes or run `flyway:repair` if authorized in development.
