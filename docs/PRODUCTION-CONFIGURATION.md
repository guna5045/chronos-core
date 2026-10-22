# Chronos Core — Production Configuration Reference

## 1. Overview & Profiles

Chronos Core uses Spring Boot profile-based configuration:
- `application.yml`: Base configuration containing default property structures and property placeholders.
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

### A. Required Production Variables

| Variable Name | Example Value | Description |
|---|---|---|
| `CHRONOS_DB_URL` | `jdbc:postgresql://postgres:5432/chronos_core` | JDBC connection URL for PostgreSQL 16 |
| `CHRONOS_DB_USERNAME` | `chronos_user` | PostgreSQL database user |
| `CHRONOS_DB_PASSWORD` | `[SECURE_PASSWORD]` | PostgreSQL database password |
| `CHRONOS_REDIS_HOST` | `redis` | Redis host or cluster endpoint |
| `CHRONOS_REDIS_PORT` | `6379` | Redis TCP port |
| `CHRONOS_JWT_SECRET` | `[SECURE_32_BYTE_STRING]` | Minimum 256-bit signing key for JWT auth |

### B. Optional Tuning Variables

| Variable Name | Default Value | Description |
|---|---|---|
| `CHRONOS_COORDINATOR_PORT` | `8080` | HTTP port for REST API and Actuator |
| `CHRONOS_COORDINATOR_GRPC_PORT` | `9090` | gRPC port for worker fleet communication |
| `CHRONOS_COORDINATOR_ID` | `coordinator-node-1` | Unique coordinator instance identifier |
| `CHRONOS_DB_POOL_MAX` | `20` | Maximum HikariCP database connections |
| `CHRONOS_DB_POOL_MIN_IDLE` | `5` | Minimum idle HikariCP connections |
| `CHRONOS_REDIS_PASSWORD` | `(empty)` | Redis password if auth is enabled |
| `CHRONOS_LEADER_LEASE_DURATION` | `5s` | Redis leader election lease duration |
| `CHRONOS_LEADER_RENEWAL_INTERVAL`| `1500ms` | Leader lease heartbeat renewal rate |
| `CHRONOS_TASK_LOCK_TTL` | `30s` | Distributed lock TTL for task dispatch |
| `CHRONOS_LEASE_DURATION` | `30s` | Task execution lease granted to workers |
| `CHRONOS_LEASE_RENEWAL_INTERVAL` | `10s` | Worker task lease renewal cadence |
| `CHRONOS_WORKER_HEARTBEAT_TIMEOUT`| `3s` | Time before worker is marked `SUSPECTED` |
| `CHRONOS_WORKER_EVICTION_TIMEOUT` | `6s` | Time before worker is marked `REMOVED` |
| `CHRONOS_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Comma-separated allowed CORS origins |

### C. Optional AI Subsystem Variables

| Variable Name | Default Value | Description |
|---|---|---|
| `CHRONOS_AI_ENABLED` | `false` | Enable/disable AI Workflow & Operations agents |
| `CHRONOS_AI_PROVIDER` | `STUB` | Upstream provider (`STUB`, `OPENAI`, `ANTHROPIC`, `GEMINI`) |
| `CHRONOS_AI_API_KEY` | `(empty)` | API key for upstream LLM provider |
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

### Redis Coordination
- Persistence: `appendonly yes` and `save 60 1` ensure leader leases and cluster locks survive Redis restarts.
- Pool: Lettuce client configured with `max-active: 16` and connection timeout of `3000ms`.

### JVM Container Options
All Docker images configure Java virtual machine flags:
```text
-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError
```
- Respects container memory cgroups.
- Limits JVM heap to 75% of container RAM, reserving 25% for Metaspace and OS threads.
- Immediately terminates the process on `OutOfMemoryError` to trigger container orchestrator restart.
