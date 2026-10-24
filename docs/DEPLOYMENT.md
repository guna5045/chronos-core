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
                   (HTTP Port 80 / 443)
                          ▼
              ┌────────────────────────┐
              │    Chronos Dashboard   │ (Nginx Reverse Proxy)
              │ /api/ ──► Coordinator  │
              └───────────┬────────────┘
                          │ (HTTP 8080 - Private Docker Network)
                          ▼
              ┌────────────────────────┐
              │   Chronos Coordinator  │
              │  (REST / Scheduler)    │
              └─────┬────────────┬─────┘
                    │            │ (gRPC 9090 - Private Docker Network)
    (JDBC 5432)     │            ▼
         ┌──────────┤     ┌──────────────┐
         ▼          ▼     │ Worker Fleet │
   ┌──────────┐ ┌───────┐ │  (Nodes 1-3) │
   │Postgres16│ │Redis7 │ └──────────────┘
   └──────────┘ └───────┘
```

---

## 2. Service Port Matrix & Exposure Policy

| Service | Internal Port | Local Dev Host Port (`docker-compose.yml`) | Production Host Port (`docker-compose.prod.yml`) | Exposure Classification |
|---|---|---|---|---|
| **Chronos Dashboard** | `80` | `5173` | `${CHRONOS_DASHBOARD_PORT:-80}` | **PUBLIC**: External operator HTTP/HTTPS ingress |
| **Chronos Coordinator REST** | `8080` | `8080` | **None** (internal network only) | **PRIVATE**: Routed via Dashboard Nginx reverse proxy |
| **Chronos Coordinator gRPC** | `9090` | `9090` | **None** (internal network only) | **PRIVATE**: Internal worker fleet communication |
| **Chronos Workers (1-3)** | `8081` | `8081-8083` | **None** (internal network only) | **PRIVATE**: Internal worker fleet metrics |
| **PostgreSQL 16** | `5432` | `5432` | **None** (internal network only) | **PRIVATE**: State persistence |
| **Redis 7.2** | `6379` | `6379` | **None** (internal network only) | **PRIVATE**: Coordination leases & locks |
| **Prometheus** | `9090` | `9095` | **None** (internal network only) | **PRIVATE**: Cluster internal telemetry scraping |
| **Grafana** | `3000` | `3000` | `${GRAFANA_BIND_ADDRESS:-127.0.0.1}:3000` | **PROTECTED**: Bound strictly to loopback (bastion/VPN) |
| **OTel Collector** | `4317/4318` | `4317/4318` (profile) | **None** (internal network only) | **PRIVATE**: Cluster internal distributed tracing |

---

## 3. Production Deployment Hardening (`docker-compose.prod.yml`)

### Step 1: Prepare Environment File (`.env.production`)
Create `.env.production` from the template. Never commit this file to Git.

```bash
cp .env.production.example .env.production
chmod 600 .env.production
```

### Step 2: Generate Cryptographically Secure Secrets
Production Compose uses **fail-fast syntax** (`:?variable is required`). The stack will refuse to start if secrets are missing.

1. **JWT Signing Secret (256-bit / 32 bytes minimum)**:
   ```bash
   openssl rand -hex 32
   ```
   Set as `CHRONOS_JWT_SECRET` in `.env.production`.

2. **PostgreSQL Database Password**:
   ```bash
   openssl rand -base64 24
   ```
   Set as `CHRONOS_DB_PASSWORD` in `.env.production`.

3. **Grafana Admin Password**:
   ```bash
   openssl rand -base64 20
   ```
   Set as `GRAFANA_ADMIN_PASSWORD` in `.env.production`.

4. **Redis Authentication (Optional / Recommended)**:
   ```bash
   openssl rand -base64 24
   ```
   Set as `CHRONOS_REDIS_PASSWORD` in `.env.production`.

5. **CORS Origin**:
   Set `CHRONOS_CORS_ALLOWED_ORIGINS` to the exact HTTPS domain of your production dashboard:
   ```env
   CHRONOS_CORS_ALLOWED_ORIGINS=https://chronos.yourcompany.com
   ```
   *Do NOT use wildcard (`*`) when credentials/cookies are enabled.*

6. **Database SSL Transport Encryption**:
   By default, `CHRONOS_DB_SSL_MODE` is set to `require` in production:
   ```env
   CHRONOS_DB_SSL_MODE=require
   ```

### Step 3: Launch Production Stack
```bash
docker compose -f docker-compose.prod.yml --env-file .env.production up -d --build
```

### Step 4: Health & Readiness Verification
Verify all containers are healthy:
```bash
docker compose -f docker-compose.prod.yml ps
```

Verify service probes:
```bash
# 1. Dashboard Nginx Liveness
curl -I http://localhost:80/

# 2. Coordinator Liveness (via Dashboard Reverse Proxy)
curl http://localhost:80/actuator/health/liveness

# 3. Coordinator Readiness (Validates Postgres + Redis connectivity)
curl http://localhost:80/actuator/health/readiness
```

Verify DevAuth is disabled in production:
```bash
# Must return 404 Not Found (DevAuth controller disabled)
curl -X POST http://localhost:80/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username":"attacker","roles":["ADMIN"]}'
```

### Step 5: Stop Production Stack
```bash
docker compose -f docker-compose.prod.yml down
```
To preserve persistent data, do not pass `-v` unless an explicit wipe is intended.

---

## 4. Local Development Deployment (`docker-compose.yml`)

The standard `docker-compose.yml` remains fully functional for developer convenience and automated integration tests:

```bash
# Copy local development template
cp .env.example .env

# Launch local stack with host ports published for easy debugging
docker compose up -d

# Verify services
docker compose ps
```

Interfaces available locally:
- **Web Control Plane**: `http://localhost:5173`
- **Coordinator REST**: `http://localhost:8080/api/v1`
- **Prometheus Scraper**: `http://localhost:9095`
- **Grafana Dashboards**: `http://localhost:3000` (defaults: `admin` / `chronos_admin`)
- **Dev Token Issuance**: `POST http://localhost:8080/api/v1/auth/token` (enabled for dev workflow)

---

## 5. Database Migrations (Flyway)

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

In `application-prod.yml`:
- `baseline-on-migrate: false` (Prevents baseline masking on uninitialized databases).
- `validate-on-migrate: true` (Enforces checksum integrity).
- Never modify applied migration scripts directly; add a new sequential migration (`V8__...`).

---

## 6. Health Probes & Readiness Lifecycle

Chronos Core exposes standardized Spring Boot Actuator endpoints:

### Liveness Probe (`/actuator/health/liveness`)
- Checks internal JVM health and event loop responsiveness.
- Returns `HTTP 200 {"status":"UP"}`.
- Transient loss of Redis or database connectivity does **not** fail the liveness probe, avoiding restart storm thrashing.

### Readiness Probe (`/actuator/health/readiness`)
- Validates that the coordinator is connected to PostgreSQL and Redis.
- If the database or Redis connection drops, returns `HTTP 503 {"status":"DOWN"}` so load balancers halt routing inbound traffic.
- **AI Fault Isolation**: The optional AI subsystem does **not** impact readiness; if the AI provider is down or unconfigured, the readiness probe remains `UP`.

---

## 7. Troubleshooting Common Failure Modes

### Failure 1: Missing Required Production Secrets
- **Symptom**: `docker compose -f docker-compose.prod.yml up` exits with error: `variable CHRONOS_JWT_SECRET is required`.
- **Root Cause**: `.env.production` was not created or does not define all required secrets.
- **Remediation**: Copy `.env.production.example` to `.env.production` and generate required secrets as shown in Section 3.

### Failure 2: Redis Connection Refused / Auth Failed
- **Symptom**: Coordinator logs `RedisAuthException` or `Unable to connect to redis:6379`.
- **Root Cause**: Redis password mismatch between coordinator and Redis service.
- **Remediation**: Verify that `CHRONOS_REDIS_PASSWORD` in `.env.production` is identical across both services.

### Failure 3: Database SSL Handshake Failure
- **Symptom**: Coordinator logs `PSQLException: SSL error: ...`.
- **Root Cause**: Upstream PostgreSQL server does not have SSL enabled or certificate verification failed.
- **Remediation**: Verify database SSL support. If running containerized PostgreSQL without custom certs, verify `CHRONOS_DB_SSL_MODE=require`.
