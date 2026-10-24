import React, { useState } from 'react';
import { Play, RotateCcw } from 'lucide-react';

export const ObservabilityTraceVisualizer: React.FC = () => {
  const [step, setStep] = useState(0);

  const steps = [
    {
      title: '1. Inbound HTTP Request',
      boundary: 'Edge / Ingress Gateway',
      detail: 'Client sets X-Request-ID: "req-8917" and X-Correlation-ID: "corr-f4b2".',
      tag: 'X-Request-ID / X-Correlation-ID',
    },
    {
      title: '2. Coordinator MDC Logging',
      boundary: 'Coordinator Thread (Tomcat / Virtual)',
      detail: 'MDC.put("correlationId", "corr-f4b2"). All log statements automatically include [corr-f4b2] for central log tracing.',
      tag: 'MDC Context Propagation',
    },
    {
      title: '3. gRPC Metadata Header Forwarding',
      boundary: 'Coordinator -> Worker gRPC Transport',
      detail: 'GrpcWorkerDispatchClient injects correlation ID into gRPC metadata Metadata.Key("x-correlation-id").',
      tag: 'gRPC Metadata Key',
    },
    {
      title: '4. Worker Virtual Thread Execution',
      boundary: 'Worker Thread (Loom)',
      detail: 'TaskCoordinationGrpcService extracts header into worker thread context. Worker logs error or success with matching ID.',
      tag: 'Worker Thread MDC',
    },
    {
      title: '5. Audit Trail & Prometheus Increments',
      boundary: 'PostgreSQL & Micrometer / Prometheus',
      detail: 'PostgreSQL chronos_task_audit_log stores correlation_id; Micrometer counter chronos_tasks_succeeded_total increments.',
      tag: 'Audit Log & Metric Counter',
    },
  ];

  const current = steps[step];

  return (
    <div
      style={{
        backgroundColor: 'var(--bg-elevated)',
        border: '1px solid var(--border-default)',
        borderRadius: '0.5rem',
        padding: '1.25rem',
        display: 'flex',
        flexDirection: 'column',
        gap: '1rem',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '0.75rem' }}>
        <div>
          <span style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase' }}>
            Distributed Trace &amp; Context Propagation
          </span>
          <p style={{ fontSize: '0.75rem', color: 'var(--text-faint)' }}>
            Request ID • Correlation ID • gRPC Metadata • Logging MDC • Micrometer Metrics
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
          <button
            onClick={() => setStep(Math.min(steps.length - 1, step + 1))}
            disabled={step === steps.length - 1}
            className="btn btn-secondary btn-sm"
          >
            <Play size={13} />
            <span>Next Boundary</span>
          </button>
          <button onClick={() => setStep(0)} className="btn btn-secondary btn-sm">
            <RotateCcw size={13} />
            <span>Reset</span>
          </button>
        </div>
      </div>

      {/* Trace Timeline Steps */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
        {steps.map((s, idx) => {
          const isActive = idx === step;
          const isPassed = idx < step;
          return (
            <div
              key={idx}
              onClick={() => setStep(idx)}
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                padding: '0.6rem 0.85rem',
                borderRadius: '0.375rem',
                border: `1px solid ${isActive ? 'var(--accent-cyan)' : isPassed ? 'var(--accent-emerald)' : 'var(--border-subtle)'}`,
                backgroundColor: isActive ? 'rgba(56, 189, 248, 0.1)' : 'var(--bg-surface)',
                cursor: 'pointer',
                transition: 'all 0.15s ease',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
                <span
                  style={{
                    width: '20px',
                    height: '20px',
                    borderRadius: '50%',
                    backgroundColor: isActive ? 'var(--accent-cyan)' : isPassed ? 'var(--accent-emerald)' : 'var(--border-default)',
                    color: '#fff',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    fontSize: '0.65rem',
                    fontWeight: 'bold',
                  }}
                >
                  {idx + 1}
                </span>
                <span style={{ fontSize: '0.8rem', fontWeight: 600, color: 'var(--text-main)' }}>
                  {s.title}
                </span>
              </div>
              <span
                className="font-mono"
                style={{
                  fontSize: '0.65rem',
                  padding: '0.1rem 0.4rem',
                  borderRadius: '3px',
                  backgroundColor: 'rgba(100, 116, 139, 0.15)',
                  color: 'var(--text-muted)',
                }}
              >
                {s.tag}
              </span>
            </div>
          );
        })}
      </div>

      {/* Description Box */}
      <div
        style={{
          padding: '0.75rem 1rem',
          borderRadius: '0.375rem',
          backgroundColor: 'var(--bg-surface)',
          border: '1px solid var(--border-subtle)',
          fontSize: '0.8125rem',
          lineHeight: 1.5,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.2rem' }}>
          <strong style={{ color: 'var(--accent-cyan)' }}>{current.boundary}:</strong>
        </div>
        <p style={{ color: 'var(--text-muted)' }}>{current.detail}</p>
      </div>
    </div>
  );
};
