import React from 'react';
import './App.css';

interface ModuleCardProps {
  name: string;
  port: number | string;
  role: string;
  phase: string;
}

const ModuleCard: React.FC<ModuleCardProps> = ({ name, port, role, phase }) => {
  return (
    <div className="module-card">
      <div className="module-header">
        <span className="module-name">{name}</span>
        <span className="module-port">:{port}</span>
      </div>
      <p className="module-desc">{role}</p>
      <div className="module-meta">
        <span>Status:</span>
        <span>{phase}</span>
      </div>
    </div>
  );
};

export const App: React.FC = () => {
  return (
    <div className="app-container">
      {/* Top Header */}
      <header className="top-nav">
        <div className="brand-section">
          <svg
            className="brand-logo"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <circle cx="12" cy="12" r="10" />
            <polyline points="12 6 12 12 16 14" />
          </svg>
          <div className="brand-title-group">
            <h1>Chronos Core</h1>
            <p>Distributed Task &amp; Workflow Orchestration Platform</p>
          </div>
        </div>
        <div className="status-badge">
          <span className="status-dot"></span>
          <span>CONTROL PLANE : PHASE 01</span>
        </div>
      </header>

      {/* Main Container */}
      <main className="main-content">
        <div className="phase-banner">
          <span className="phase-tag">Active State</span>
          <h2>Phase 01: Project Foundation &amp; Repository Setup</h2>
          <p>
            The clean architectural foundation for Chronos Core is established. All service boundaries,
            Java 21 Virtual Thread runtimes, Spring Boot micro-modules, and Git repository structures
            have been compiled and verified. Business logic, cluster scheduling, and coordination
            mechanisms will be introduced sequentially across subsequent development phases.
          </p>
        </div>

        <h3 className="section-title">Architectural Service Boundaries</h3>
        <div className="grid-cards">
          <ModuleCard
            name="chronos-coordinator"
            port="8080"
            role="Active/passive cluster leader, lease coordination, task state machine, and workflow DAG orchestration."
            phase="Foundation Initialized"
          />
          <ModuleCard
            name="chronos-worker"
            port="8081"
            role="Execution runners, registration, and bidirectional gRPC streaming heartbeat telemetry."
            phase="Foundation Initialized"
          />
          <ModuleCard
            name="chronos-ai"
            port="8082"
            role="Dedicated boundary reserved for AI Workflow and Operations agents. Strict isolation from core mechanics."
            phase="Boundary Established"
          />
          <ModuleCard
            name="chronos-common"
            port="shared-jar"
            role="Shared domain contracts, error hierarchies, and Protocol Buffer stubs with zero business logic."
            phase="Contracts Compiled"
          />
        </div>

        <div className="rules-panel">
          <h3 className="section-title" style={{ marginBottom: '0.25rem' }}>
            Persistent Engineering Constraints (AGENTS.md)
          </h3>
          <ul>
            <li>At-least-once delivery with idempotency and fencing-based stale-write protection.</li>
            <li>Redis lease-based leader election (not Raft or distributed consensus).</li>
            <li>Deterministic distributed mechanisms take absolute priority over AI or heuristics.</li>
            <li>AI is optional and strictly scoped to Workflow Authoring and Operations Triage.</li>
            <li>No synthetic metrics, fabricated benchmark numbers, or premature marketing UI fluff.</li>
          </ul>
        </div>
      </main>

      <footer className="footer">
        Chronos Core &mdash; Distributed Task &amp; Workflow Orchestration Platform &bull; Phase 01 Foundation
      </footer>
    </div>
  );
};

export default App;
