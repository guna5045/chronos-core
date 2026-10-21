# Chronos Core - Engineering Rules & Agent Guidelines

## 1. Project Philosophy & Core Identity
- **Distributed Systems First**: Chronos Core is fundamentally a distributed systems engineering platform. Architecture, concurrency, fault tolerance, and data integrity take absolute precedence.
- **Deterministic Guarantees**: Never replace deterministic distributed systems mechanisms (e.g., lease-based leader election, fencing tokens, consistent hashing, heartbeats, exponential backoff) with heuristics or AI.
- **Role of AI**: AI is optional and strictly scoped. Only two focused AI capabilities are planned:
  1. **AI Workflow Agent**: Assisting engineers in authoring and validating complex workflow DAG definitions.
  2. **AI Operations Agent**: Assisting operators with log root-cause analysis and operational triage recommendations.
  - Do not introduce generic conversational chatbots or extraneous LLM integrations.

---

## 2. Distributed Systems Accuracy & Terminology
- **Execution Semantics**: Do not claim "exactly-once execution" unless an end-to-end distributed transaction with verifiable two-phase commit or proven deduplication is implemented. In all other scenarios, describe the semantics accurately as **"at-least-once delivery with idempotency and fencing-based stale-write protection"**.
- **Leader Election & Coordination**: Do not describe Redis lease-based leader election or distributed locks as "Raft", "Paxos", or "distributed consensus". Term it accurately as **lease-based leader election with TTL expiration**.
- **Metrics & Benchmarks**:
  - Never invent, fabricate, or hallucinate benchmark numbers, latency percentiles, throughput claims, or test coverage percentages.
  - Performance numbers must be derived directly from reproducible k6 or JMeter test runs.
- **Feature Status Integrity**: Never claim a feature is implemented, tested, or operational until its code exists, compiles, and passes automated verification.

---

## 3. Architecture & Dependency Rules
- **Module Separation**:
  - `chronos-common`: Shared data transfer objects, contracts, and constants. **No business logic or heavyweight dependencies.**
  - `chronos-coordinator`: Cluster management, lease election, task scheduling, state machine transitions.
  - `chronos-worker`: Task execution runners, heartbeat reporters, local resource monitoring.
  - `chronos-ai`: Isolated module boundary reserved for AI Workflow and Operations agents.
- **Dependency Direction**:
  - Clean upstream dependency flow: `chronos-common` is the root dependency for `chronos-coordinator`, `chronos-worker`, and `chronos-ai`.
  - Never introduce circular Maven dependencies.
  - Submodules must never depend on each other horizontally (e.g., `chronos-coordinator` must not depend on `chronos-worker`, and vice-versa).
- **Security & Secrets**:
  - Never hardcode secrets, tokens, passwords, private keys, or credentials into source code or Git history.
  - All sensitive configuration must be externalized via environment variables.

---

## 4. Frontend & Control Plane Rules
- **Engineering Control Plane**: The frontend (`chronos-dashboard`) is an operator control plane for distributed system visibility, not a generic SaaS landing page or AI marketing showcase.
- **No Deceptive / Fake Content**:
  - Do not add fake production metrics, fake customer stats, or synthetic mock graphs unless explicitly wrapped in a development mock fixture.
  - Do not force UI components into views without clear operational or monitoring utility.
- **Component Discipline**:
  - Avoid unnecessary, distracting visual effects.
  - When introducing shadcn/ui or specialized components in later phases, select only components that directly enhance cluster visibility, task tracking, DAG visualization, or incident triage.

---

## 5. Verification & Quality Standards
- **Build Verification**: Every modification must compile without errors in Maven (Java 21) and TypeScript (`tsc`).
- **Test Integrity**: Automated tests must run and pass. Unit tests must test actual behavior rather than merely satisfying coverage metrics.
- **Simplicity**: Prefer clear, robust, maintainable implementations over convoluted abstractions.
