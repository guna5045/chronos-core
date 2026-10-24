import React from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Activity,
  Radio,
  ExternalLink,
  ShieldCheck,
  Zap,
} from 'lucide-react';
import { MetricCard } from '../components/common/MetricCard';
import { ErrorAlert } from '../components/common/ErrorAlert';
import { getClusterOverview } from '../api/cluster';
import { LearnAboutPageButton } from '../components/docs/LearnAboutPageButton';

export const ObservabilityPage: React.FC = () => {
  const clusterQuery = useQuery({
    queryKey: ['clusterOverview'],
    queryFn: getClusterOverview,
    refetchInterval: 10000,
  });

  const cluster = clusterQuery.data;

  const metricCatalog = [
    { name: 'chronos.tasks.submitted', type: 'Counter', description: 'Total tasks accepted via REST API admission control' },
    { name: 'chronos.tasks.dispatched', type: 'Counter', description: 'Tasks successfully dispatched across gRPC channel' },
    { name: 'chronos.tasks.completed', type: 'Counter', description: 'Terminal task completions (success vs fail)' },
    { name: 'chronos.tasks.dlq', type: 'Counter', description: 'Exhausted retry tasks routed to Dead-Letter Queue' },
    { name: 'chronos.scheduler.polls', type: 'Counter', description: 'Deterministic scheduler dispatch poll cycles' },
    { name: 'chronos.scheduler.dispatch.latency', type: 'Timer', description: 'P50/P95/P99 latency from submission to dispatch' },
    { name: 'chronos.leader.epoch', type: 'Gauge', description: 'Current lease-based Redis leader fencing epoch' },
    { name: 'chronos.workers.active', type: 'Gauge', description: 'Heartbeat-verified live worker execution nodes' },
  ];

  const chaosScenarios = [
    { id: 'SCENARIO-01', name: 'Worker Crash / Heartbeat Loss', status: 'VERIFIED', invariant: 'Execution lease expires; task reclaimed without stale overwrite' },
    { id: 'SCENARIO-02', name: 'Leader Lease Expiration / Failover', status: 'VERIFIED', invariant: 'Follower acquires lease; fencing epoch increments; stale leader blocked' },
    { id: 'SCENARIO-03', name: 'Database Contention (FOR UPDATE)', status: 'VERIFIED', invariant: 'Optimistic locking and atomic claim prevent duplicate node activation' },
    { id: 'SCENARIO-04', name: 'Bounded Exponential Retry Storm', status: 'VERIFIED', invariant: 'Decorrelated jitter applied; retries bounded; DLQ transition enforced' },
    { id: 'SCENARIO-05', name: 'Redis Coordination Temporary Outage', status: 'VERIFIED', invariant: 'Fails safe into degraded mode without split-brain or state corruption' },
    { id: 'SCENARIO-06', name: 'Workflow Dependency Failure', status: 'VERIFIED', invariant: 'Downstream nodes blocked; terminal state accurately propagated' },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1.75rem' }}>
      {/* Page Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div>
          <h2 style={{ fontSize: '1.35rem', fontWeight: 700, color: 'var(--text-main)', letterSpacing: '0.01em' }}>
            Observability & Telemetry
          </h2>
          <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
            Micrometer metrics, Prometheus scrapers, OpenTelemetry distributed tracing, and chaos verification
          </p>
        </div>
        <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
          <LearnAboutPageButton pageId="observability" />
          <a
            href="/actuator/prometheus"
            target="_blank"
            rel="noopener noreferrer"
            className="btn btn-secondary btn-sm"
          >
            <Radio size={13} color="var(--accent-cyan)" />
            <span>Prometheus Scrape Endpoint</span>
            <ExternalLink size={12} />
          </a>
        </div>
      </div>

      {clusterQuery.isError && (
        <ErrorAlert error={clusterQuery.error} onRetry={() => clusterQuery.refetch()} />
      )}

      {/* Observability Telemetry Cards */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(210px, 1fr))', gap: '1rem' }}>
        <MetricCard
          title="Prometheus Actuator"
          value="EXPOSED"
          subtitle="Path: /actuator/prometheus"
          icon={<Radio size={20} />}
          accentColor="var(--accent-emerald)"
        />
        <MetricCard
          title="Tracing Bridge"
          value="OTEL / W3C"
          subtitle="Correlation & Span Context"
          icon={<Activity size={20} />}
          accentColor="var(--accent-cyan)"
        />
        <MetricCard
          title="Health Probes"
          value="LIVENESS & READINESS"
          subtitle="/actuator/health/{liveness,readiness}"
          icon={<ShieldCheck size={20} />}
          accentColor="var(--accent-emerald)"
        />
        <MetricCard
          title="Active Fencing Epoch"
          value={`Epoch #${cluster?.leaderEpoch ?? 0}`}
          subtitle={cluster?.isLeader ? 'This node is active leader' : 'Standby follower'}
          icon={<Zap size={20} />}
          accentColor={cluster?.isLeader ? 'var(--accent-emerald)' : 'var(--accent-amber)'}
        />
      </div>

      {/* Observability Details Grid */}
      <div style={{ display: 'grid', gridTemplateColumns: '1.2fr 1fr', gap: '1.25rem' }}>
        {/* Core Metric Catalog */}
        <div
          style={{
            backgroundColor: 'var(--bg-surface)',
            border: '1px solid var(--border-subtle)',
            borderRadius: '0.5rem',
            padding: '1.25rem',
            display: 'flex',
            flexDirection: 'column',
            gap: '1rem',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <h3 style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-main)' }}>
              Micrometer Metric Catalog (Low Cardinality)
            </h3>
            <span style={{ fontSize: '0.7rem', color: 'var(--text-faint)' }}>Zero high-cardinality tags</span>
          </div>

          <div className="data-table-container">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Metric Name</th>
                  <th>Type</th>
                  <th>Description</th>
                </tr>
              </thead>
              <tbody>
                {metricCatalog.map((m) => (
                  <tr key={m.name}>
                    <td>
                      <code className="font-mono" style={{ fontSize: '0.75rem', color: 'var(--accent-cyan)' }}>
                        {m.name}
                      </code>
                    </td>
                    <td>
                      <span
                        className="font-mono"
                        style={{
                          fontSize: '0.65rem',
                          padding: '0.1rem 0.35rem',
                          borderRadius: '3px',
                          backgroundColor: 'var(--bg-elevated)',
                          border: '1px solid var(--border-default)',
                          color: 'var(--text-muted)',
                        }}
                      >
                        {m.type}
                      </span>
                    </td>
                    <td style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>{m.description}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        {/* Distributed Tracing & Correlation */}
        <div
          style={{
            backgroundColor: 'var(--bg-surface)',
            border: '1px solid var(--border-subtle)',
            borderRadius: '0.5rem',
            padding: '1.25rem',
            display: 'flex',
            flexDirection: 'column',
            gap: '1rem',
          }}
        >
          <h3 style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-main)' }}>
            Distributed Tracing Architecture
          </h3>

          <div
            style={{
              padding: '1rem',
              borderRadius: '0.375rem',
              backgroundColor: 'var(--bg-elevated)',
              border: '1px solid var(--border-default)',
              fontSize: '0.75rem',
              lineHeight: 1.6,
              color: 'var(--text-muted)',
            }}
          >
            <div style={{ fontWeight: 600, color: 'var(--text-main)', marginBottom: '0.5rem' }}>
              Execution Lifecycle Trace Propagation:
            </div>
            <div className="font-mono" style={{ color: 'var(--accent-cyan)' }}>
              POST /tasks (X-Request-Id)
              <br />
              &nbsp;&nbsp;↓ task.submit (MDC traceId)
              <br />
              &nbsp;&nbsp;↓ task.schedule (FOR UPDATE lease)
              <br />
              &nbsp;&nbsp;↓ grpc.dispatch (W3C traceparent)
              <br />
              &nbsp;&nbsp;↓ worker.execute (Heartbeat lease)
              <br />
              &nbsp;&nbsp;↓ task.complete (Fencing validated)
            </div>
          </div>

          <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
            <strong>Correlation IDs:</strong> Inbound REST requests accept or generate unique <code className="font-mono">X-Request-Id</code>. All server logs and error models capture correlation context for root-cause triage.
          </div>
        </div>
      </div>

      {/* Chaos Engineering Status */}
      <div
        style={{
          backgroundColor: 'var(--bg-surface)',
          border: '1px solid var(--border-subtle)',
          borderRadius: '0.5rem',
          padding: '1.25rem',
          display: 'flex',
          flexDirection: 'column',
          gap: '1rem',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <h3 style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-main)' }}>
            Failure Injection & Chaos Engineering Invariants
          </h3>
          <span style={{ fontSize: '0.7rem', color: 'var(--text-faint)' }}>Phase 13 Invariants Verified</span>
        </div>

        <div className="data-table-container">
          <table className="data-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>Chaos Scenario</th>
                <th>Verification Invariant</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {chaosScenarios.map((s) => (
                <tr key={s.id}>
                  <td>
                    <code className="font-mono" style={{ fontSize: '0.75rem', color: 'var(--text-faint)' }}>
                      {s.id}
                    </code>
                  </td>
                  <td style={{ fontWeight: 500 }}>{s.name}</td>
                  <td style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>{s.invariant}</td>
                  <td>
                    <span
                      className="font-mono"
                      style={{
                        fontSize: '0.65rem',
                        padding: '0.15rem 0.45rem',
                        borderRadius: '3px',
                        backgroundColor: 'rgba(16, 185, 129, 0.12)',
                        border: '1px solid rgba(16, 185, 129, 0.35)',
                        color: 'var(--accent-emerald)',
                        fontWeight: 600,
                      }}
                    >
                      {s.status}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};
