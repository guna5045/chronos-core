# Chronos Core — CI/CD Pipeline Specification

## 1. Pipeline Architecture

The Continuous Integration (CI) pipeline is defined in [`.github/workflows/ci.yml`](file:///c:/Users/sampa/OneDrive/ドキュメント/chronoscore/.github/workflows/ci.yml). It provides automated quality, compilation, testing, and container build validation on all pull requests and pushes to `main`.

```text
               Git Push / Pull Request to main
                             │
            ┌────────────────┴────────────────┐
            ▼                                 ▼
   ┌──────────────────┐             ┌──────────────────┐
   │    Backend CI    │             │   Frontend CI    │
   │ Java 21 (Temurin)│             │  Node 20 (LTS)   │
   │ Maven Dependency │             │  npm ci          │
   │ Reactor Test     │             │  vitest test     │
   │ (419 Unit/Chaos) │             │  tsc -b && build │
   └────────┬─────────┘             └────────┬─────────┘
            │                                │
            └────────────────┬───────────────┘
                             ▼
                ┌─────────────────────────┐
                │     Docker Build CI     │
                │ Coordinator Dockerfile  │
                │ Worker Dockerfile       │
                │ Dashboard Dockerfile    │
                │ (Validation Only)       │
                └─────────────────────────┘
```

---

## 2. Jobs Breakdown

### Job 1: `backend-ci`
- **Environment**: Ubuntu Latest, Eclipse Temurin JDK 21.
- **Dependency Cache**: `~/.m2/repository` keyed by `pom.xml` hash.
- **Execution Command**:
  ```bash
  mvn -B clean test
  ```
- **Validation Scope**:
  - `chronos-common`: Domain models, contracts, DTOs (132 tests).
  - `chronos-ai`: AI agent schema, tools, and failure isolation boundaries (20 tests).
  - `chronos-coordinator`: Leader election, scheduling, FSM, and 7 in-process chaos scenarios (260 tests).
  - `chronos-worker`: Virtual thread executor, capacity limits, and gRPC service (7 tests).
- **Artifacts**: Archives Surefire XML reports on failure for diagnostic analysis.

### Job 2: `frontend-ci`
- **Environment**: Ubuntu Latest, Node.js 20.
- **Dependency Cache**: `~/.npm` keyed by `chronos-dashboard/package-lock.json`.
- **Execution Steps**:
  1. `npm ci`: Deterministic, clean dependency installation.
  2. `npm test`: Runs Vitest across all 6 test suites including 13 `/workflows` regression tests.
  3. `npm run build`: Executes `tsc -b` and `vite build` to guarantee zero TypeScript or bundle errors.

### Job 3: `docker-build-ci`
- **Dependency**: Runs only after `backend-ci` and `frontend-ci` succeed (`needs: [backend-ci, frontend-ci]`).
- **Validation Steps**:
  - Validates `infrastructure/docker/coordinator.Dockerfile`.
  - Validates `infrastructure/docker/worker.Dockerfile`.
  - Validates `infrastructure/docker/dashboard.Dockerfile`.
- **Image Publishing Status**: **DISABLED / NOT EXECUTED**
  - Images are built with `push: false`.
  - No synthetic Docker Hub or GitHub Container Registry (GHCR) tokens are used.
  - Image builds are validated for compilation, layer ordering, and syntax without publishing.

---

## 3. Security & Quality Controls

- **Least Privilege**: The workflow operates under `permissions: contents: read`.
- **No Inlined Secrets**: Zero tokens, passwords, or private keys are embedded in workflow definitions or log statements.
- **Concurrency Control**: `concurrency: group: ${{ github.workflow }}-${{ github.ref }}, cancel-in-progress: true` prevents redundant resource consumption by canceling outdated in-flight runs.
- **Pinned Actions**: Uses official, major-version pinned GitHub Actions (`actions/checkout@v4`, `actions/setup-java@v4`, `actions/setup-node@v4`, `docker/build-push-action@v5`).
