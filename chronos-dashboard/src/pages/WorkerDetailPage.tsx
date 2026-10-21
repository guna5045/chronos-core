import React from 'react';
import { useParams, Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { getWorker } from '../api/workers';
import { getTasks } from '../api/tasks';
import { StatusBadge } from '../components/common/StatusBadge';
import { CopyableId } from '../components/common/CopyableId';
import { LoadingSkeleton } from '../components/common/LoadingSkeleton';
import { ErrorAlert } from '../components/common/ErrorAlert';
import { formatDate, formatTimeRelative } from '../utils/formatters';
import {
  ArrowLeft,
  Heart,
  RefreshCw,
  Server,
} from 'lucide-react';

export const WorkerDetailPage: React.FC = () => {
  const { workerId } = useParams<{ workerId: string }>();

  const workerQuery = useQuery({
    queryKey: ['worker', workerId],
    queryFn: () => getWorker(workerId!),
    enabled: !!workerId,
    refetchInterval: 5000,
  });

  const workerTasksQuery = useQuery({
    queryKey: ['workerTasks', workerId],
    queryFn: () => getTasks({ workerId: workerId!, page: 0, size: 25 }),
    enabled: !!workerId,
    refetchInterval: 5000,
  });

  if (workerQuery.isLoading) {
    return <LoadingSkeleton rows={6} height="3rem" />;
  }

  if (workerQuery.isError || !workerQuery.data) {
    return (
      <div>
        <Link to="/workers" className="btn btn-secondary btn-sm" style={{ marginBottom: '1rem' }}>
          <ArrowLeft size={14} />
          <span>Back to Workers</span>
        </Link>
        <ErrorAlert error={workerQuery.error || new Error('Worker not found')} />
      </div>
    );
  }

  const worker = workerQuery.data;
  const assignedTasks = workerTasksQuery.data?.items || [];
  const util = Math.round((worker.activeTasks / Math.max(1, worker.maxConcurrency)) * 100);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
      {/* Top Header */}
      <div>
        <Link
          to="/workers"
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: '0.35rem',
            color: 'var(--accent-cyan)',
            fontSize: '0.8125rem',
            textDecoration: 'none',
            marginBottom: '0.5rem',
          }}
        >
          <ArrowLeft size={14} />
          <span>Worker Fleet</span>
        </Link>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '1rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
            <h2 style={{ fontSize: '1.25rem', fontWeight: 700, color: 'var(--text-main)', letterSpacing: '0.01em' }}>
              Worker Node
            </h2>
            <CopyableId id={worker.workerId} truncate={false} />
            <StatusBadge status={worker.state} type="worker" />
          </div>

          <button className="btn btn-secondary btn-sm" onClick={() => workerQuery.refetch()}>
            <RefreshCw size={14} />
            <span>Refresh Telemetry</span>
          </button>
        </div>
      </div>

      {/* Worker Specs Grid */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: '1.25rem' }}>
        <div
          style={{
            backgroundColor: 'var(--bg-surface)',
            border: '1px solid var(--border-subtle)',
            borderRadius: '0.5rem',
            padding: '1.25rem',
            display: 'flex',
            flexDirection: 'column',
            gap: '0.75rem',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', color: 'var(--text-muted)', fontSize: '0.75rem', fontWeight: 600, textTransform: 'uppercase' }}>
            <Server size={14} />
            <span>Network &amp; Session Binding</span>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '120px 1fr', gap: '0.5rem', fontSize: '0.8125rem' }}>
            <span style={{ color: 'var(--text-muted)' }}>Hostname:</span>
            <span className="font-mono">{worker.hostname}</span>

            <span style={{ color: 'var(--text-muted)' }}>gRPC Port:</span>
            <span className="font-mono">{worker.port}</span>

            <span style={{ color: 'var(--text-muted)' }}>Session ID:</span>
            <span className="font-mono">{worker.sessionId}</span>

            <span style={{ color: 'var(--text-muted)' }}>Registered:</span>
            <span>{formatDate(worker.registeredAt)}</span>
          </div>
        </div>

        <div
          style={{
            backgroundColor: 'var(--bg-surface)',
            border: '1px solid var(--border-subtle)',
            borderRadius: '0.5rem',
            padding: '1.25rem',
            display: 'flex',
            flexDirection: 'column',
            gap: '0.75rem',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', color: 'var(--text-muted)', fontSize: '0.75rem', fontWeight: 600, textTransform: 'uppercase' }}>
            <Heart size={14} color="var(--accent-rose)" />
            <span>Liveness &amp; Capacity</span>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '130px 1fr', gap: '0.5rem', fontSize: '0.8125rem' }}>
            <span style={{ color: 'var(--text-muted)' }}>Last Heartbeat:</span>
            <div>
              <span style={{ color: 'var(--accent-cyan)' }}>{formatTimeRelative(worker.lastHeartbeatAt)}</span>{' '}
              <span style={{ color: 'var(--text-faint)', fontSize: '0.75rem' }}>({formatDate(worker.lastHeartbeatAt)})</span>
            </div>

            <span style={{ color: 'var(--text-muted)' }}>Max Concurrency:</span>
            <span className="font-mono">{worker.maxConcurrency} virtual threads</span>

            <span style={{ color: 'var(--text-muted)' }}>Active Tasks:</span>
            <span className="font-mono">{worker.activeTasks}</span>

            <span style={{ color: 'var(--text-muted)' }}>Available Slots:</span>
            <span className="font-mono" style={{ color: 'var(--accent-emerald)' }}>{worker.availableCapacity}</span>

            <span style={{ color: 'var(--text-muted)' }}>Utilization:</span>
            <span className="font-mono">{util}%</span>
          </div>
        </div>
      </div>

      {/* Supported Capabilities */}
      <div
        style={{
          backgroundColor: 'var(--bg-surface)',
          border: '1px solid var(--border-subtle)',
          borderRadius: '0.5rem',
          padding: '1.25rem',
        }}
      >
        <div style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '0.75rem' }}>
          Registered Worker Capabilities
        </div>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.5rem' }}>
          {worker.supportedTaskTypes && worker.supportedTaskTypes.length > 0 ? (
            worker.supportedTaskTypes.map((type) => (
              <span
                key={type}
                className="font-mono"
                style={{
                  fontSize: '0.75rem',
                  padding: '0.2rem 0.5rem',
                  borderRadius: '4px',
                  backgroundColor: 'var(--bg-elevated)',
                  border: '1px solid var(--border-default)',
                  color: 'var(--accent-cyan)',
                }}
              >
                {type}
              </span>
            ))
          ) : (
            <span style={{ color: 'var(--text-muted)', fontSize: '0.8125rem' }}>
              Accepts all task types (Unrestricted worker capability)
            </span>
          )}
        </div>
      </div>

      {/* Assigned Tasks */}
      <div
        style={{
          backgroundColor: 'var(--bg-surface)',
          border: '1px solid var(--border-subtle)',
          borderRadius: '0.5rem',
          padding: '1.25rem',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '1rem' }}>
          <h3 style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-main)' }}>
            Tasks Allocated to Worker ({assignedTasks.length})
          </h3>
          <span style={{ fontSize: '0.75rem', color: 'var(--text-faint)' }}>
            Actively dispatched or running
          </span>
        </div>

        {assignedTasks.length === 0 ? (
          <div style={{ padding: '2rem 1rem', textAlign: 'center', color: 'var(--text-muted)', fontSize: '0.8125rem' }}>
            No tasks currently allocated to this worker node.
          </div>
        ) : (
          <div className="data-table-container">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Task ID</th>
                  <th>Type</th>
                  <th>Tenant</th>
                  <th>Status</th>
                  <th>Attempt</th>
                  <th>Created</th>
                </tr>
              </thead>
              <tbody>
                {assignedTasks.map((t) => (
                  <tr key={t.taskId}>
                    <td>
                      <Link to={`/tasks/${t.taskId}`} style={{ textDecoration: 'none' }}>
                        <CopyableId id={t.taskId} />
                      </Link>
                    </td>
                    <td className="font-mono" style={{ color: 'var(--accent-cyan)' }}>{t.taskType}</td>
                    <td className="font-mono">{t.tenantId}</td>
                    <td><StatusBadge status={t.status} /></td>
                    <td className="font-mono">{t.currentAttempt} / {t.maxAttempts}</td>
                    <td style={{ color: 'var(--text-muted)', fontSize: '0.75rem' }}>{formatDate(t.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
};
