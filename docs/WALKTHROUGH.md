# Chronos Core - System Walkthrough & Operational Lifecycle

## Status
- **Current Phase**: Phase 02 (Core Domain Model & Shared Contracts)

---

## 1. Task Lifecycle Sequence

In Chronos Core, every task progresses through a strictly validated finite state machine:

```text
Task Submission (TaskSubmissionDTO)
    │
    ▼
[ CREATED ]
    │ (validation passed, enqueued)
    ▼
 [ QUEUED ]
    │ (priority scheduler matches available worker)
    ▼
[ DISPATCHED ]
    │ (worker picks up task and begins run)
    ▼
 [ RUNNING ]
    │
    ├──► [ SUCCEEDED ] ──► (Terminal state; task completed)
    │
    ├──► [ CANCELLED ] ──► (Terminal state; operator/timeout abort)
    │
    └──► [ FAILED ]
            │
            ▼ (evaluate attempts against maxAttempts)
     ┌──────────────┴──────────────┐
     │                             │
(currentAttempt < maxAttempts)     (currentAttempt >= maxAttempts)
     │                             │
     ▼                             ▼
 [ QUEUED ] (Retry backoff)     [ DLQ ] (Dead-Letter Queue; Terminal)
```

---

## 2. Phase-by-Phase Implementation Note

> **Important**: In Phase 02, only the domain models, validation contracts, state machine transitions, and Protobuf serialization definitions are active.
>
> The actual runtime components:
> - **Durable persistence**: PostgreSQL tables and schema migrations (Phase 03)
> - **Cluster coordination & locking**: Redis lease-based leader election (Phase 04)
> - **Execution engine & state transitions in DB**: Task state machine persistence (Phase 05)
> - **Bidirectional network communication**: gRPC worker heartbeats & dispatching (Phase 06)
> - **Priority queue scheduling**: Worker capacity matching with aging (Phase 07)
>
> will be implemented in their respective subsequent phases.
