# Chronos Core — Production Configuration Reference

## 1. Overview & Profiles

Chronos Core uses Spring Boot profile-based configuration:
- `application.yml`: Base configuration containing defaults and local development fallbacks.
- `application-dev.yml`: Local developer profile using embedded H2 and in-memory coordination.
- `application-prod.yml`: Hardened production profile requiring external PostgreSQL, Redis, and environment-driven secrets.

Activate production configuration by passing:
```bash
--spring.profiles.active=prod
```
or via the environment variable:
```bash
SPRING_PROFILES_ACTIVE=prod
```

---

## 2. Environment Variables Catalog

### A. Required Production Variables (Fail-Fast)

In `docker-compose.prod.yml`, the following variables have no fallback defaults and **must** be provided by the operator in `.env.production`:

| Variable Name | Example Generation / Value | Description |
|---|---|---|
| `CHRONOS_DB_PASSWORD` | `openssl rand -base64 24` | PostgreSQL database user password |
| `CHRONOS_JWT_SECRET` | `openssl rand -hex 32` | 256-bit (32 bytes minimum) HMAC-SHA256 signing key for JWT auth |
| `GRAFANA_ADMIN_PASSWORD` | `openssl rand -base64 20` | Strong administrative password for Grafana |

### B. Core Infrastructure & Security Configuration

| Variable Name | Production Default | Description |
|---|---|---|
| `CHRONOS_DB_URL` | *Derived from DB parameters* | JDBC connection URL for PostgreSQL 16 |
| `CHRONOS_DB_HOST` | `postgres` | PostgreSQL host |
| `CHRONOS_DB_PORT` | `5432` | PostgreSQL TCP port |
| `CHRONOS_DB_NAME` | `chronos_core` | Database name |
| `CHRONOS_DB_USERNAME` | `chronos_user` | PostgreSQL database user |
| `CHRONOS_DB_SSL_MODE` | `require` | Transport SSL encryption mode (`require`, `verify-ca`, `verify-full`) |
| `CHRONOS_DB_POOL_MAX` | `20` | Maximum HikariCP database connections |
| `CHRONOS_DB_POOL_MIN_IDLE` | `5` | Minimum idle HikariCP connections |
| `CHRONOS_REDIS_HOST` | `redis` | Redis host or cluster endpoint |
| `CHRONOS_REDIS_PORT` | `6379` | Redis TCP port |
| `CHRONOS_REDIS_PASSWORD` | `(empty)` | Redis AUTH password (enforced if supplied) |
| `CHRONOS_JWT_ISSUER` | `chronos-core` | Expected JWT issuer claim (`iss`) |
| `CHRONOS_JWT_VALIDITY` | `3600` | Token validity duration in seconds |
| `CHRONOS_DEV_AUTH_ENABLED`| `false` | **Mandatory Hardening**: Dev token issuance endpoint disabled in prod |
| `CHRONOS_CORS_ALLOWED_ORIGINS` | `http://localhost:80` | Exact HTTPS origin of dashboard (e.g. `https://chronos.domain.com`) |

### C. Coordination & Distributed Timing

| Variable Name | Default Value | Description |
|---|---|---|
| `CHRONOS_COORDINATOR_PORT` | `8080` | Internal HTTP port for REST API and Actuator |
| `CHRONOS_COORDINATOR_GRPC_PORT` | `9090` | Internal gRPC port for worker fleet communication |
| `CHRONOS_COORDINATOR_ID` | `coordinator-node-1` | Unique coordinator instance identifier |
| `CHRONOS_LEADER_LEASE_DURATION` | `5s` | Redis leader election lease duration |
| `CHRONOS_LEADER_RENEWAL_INTERVAL`| `1500ms` | Leader lease heartbeat renewal rate |
| `CHRONOS_TASK_LOCK_TTL` | `30s` | Distributed lock TTL for task dispatch |
| `CHRONOS_LEASE_DURATION` | `30s` | Task execution lease granted to workers |
| `CHRONOS_LEASE_RENEWAL_INTERVAL` | `10s` | Worker task lease renewal cadence |
| `CHRONOS_WORKER_HEARTBEAT_TIMEOUT`| `3s` | Time before worker is marked `SUSPECTED` |
| `CHRONOS_WORKER_EVICTION_TIMEOUT` | `6s` | Time before worker is marked `REMOVED` |

### D. Optional AI Subsystem Variables

The core platform remains fully functional with AI disabled:

| Variable Name | Production Default | Description |
|---|---|---|
| `CHRONOS_AI_ENABLED` | `false` | Enable/disable AI Workflow & Operations agents |
| `CHRONOS_AI_PROVIDER` | `stub` | Upstream provider (`stub`, `openai`, `anthropic`, `gemini`) |
| `CHRONOS_AI_API_KEY` | `(empty)` | API key for upstream LLM provider (never commit) |
| `CHRONOS_AI_ENDPOINT` | `(empty)` | Custom endpoint URL for private or proxy AI |
| `CHRONOS_AI_MODEL` | `(empty)` | Target model identifier |

---

## 3. Infrastructure Component Tuning

### HikariCP Connection Pool
In production (`application-prod.yml`), the pool is tuned for high throughput while preventing connection starvation:
- `maximum-pool-size: 20`: Bounded to prevent PostgreSQL socket exhaustion.
- `minimum-idle: 5`: Maintains warm connections ready for sudden bursts.
- `leak-detection-threshold: 30000`: Logs a warning if a thread holds a connection longer than 30 seconds.
- `connection-timeout: 10000`: Fails fast (10s) rather than letting worker threads hang indefinitely.

### Redis Coordination & Persistence
- Persistence: `appendonly yes` and `save 60 1` ensure leader leases and cluster locks survive Redis restarts.
- Authentication: If `CHRONOS_REDIS_PASSWORD` is defined, Redis is started with `--requirepass` and the coordinator authenticates automatically via Lettuce.
- Lettuce Pool: `max-active: 16`, `max-idle: 8`, `min-idle: 2`, and `max-wait: 2000ms`.

### Network Isolation & Port Exposure
- **Reverse Proxy**: Only the Nginx dashboard container publishes port `80` to the host in `docker-compose.prod.yml`.
- **Internal Only**: PostgreSQL (`5432`), Redis (`6379`), Coordinator REST (`8080`), Coordinator gRPC (`9090`), and Workers (`8081-8083`) communicate exclusively across the internal bridge network (`chronos-network`).
- **Grafana Protected**: Grafana binds strictly to `127.0.0.1:3000` to prevent exposure on public IP interfaces.

### JVM Container Options
All Docker images configure Java virtual machine flags:
```text
-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError
```
- Respects container memory cgroups.
- Limits JVM heap to 75% of container RAM, reserving 25% for Metaspace and OS threads.
- Immediately terminates the process on `OutOfMemoryError` to trigger container orchestrator restart.
