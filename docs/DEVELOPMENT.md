# Chronos Core - Developer Setup & Contribution Guide

## Status
- **Current Phase**: Phase 01 (Foundation)

---

## 1. Prerequisites

- **Java**: OpenJDK 21 (LTS)
- **Maven**: Apache Maven 3.9+
- **Node.js**: v20+ LTS / v24+
- **Git**: 2.40+

---

## 2. Backend Build Instructions

To clean, compile, and run tests across all Maven modules:

```bash
mvn clean test
```

To build module jars without running tests:

```bash
mvn clean package -DskipTests
```

### Module Ports:
- `chronos-coordinator`: `http://localhost:8080`
- `chronos-worker`: `http://localhost:8081`
- `chronos-ai`: `http://localhost:8082`

---

## 3. Frontend Development Instructions

Navigate to the dashboard directory:

```bash
cd chronos-dashboard
npm install
npm run dev
```

The control plane dashboard will be accessible at: `http://localhost:5173`.

To run TypeScript verification and production build:

```bash
npm run build
```
