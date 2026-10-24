import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { getWorkers } from '../api/workers';
import { StatusBadge } from '../components/common/StatusBadge';
import { CopyableId } from '../components/common/CopyableId';
import { MetricCard } from '../components/common/MetricCard';
import { LoadingSkeleton } from '../components/common/LoadingSkeleton';
import { EmptyState } from '../components/common/EmptyState';
import { ErrorAlert } from '../components/common/ErrorAlert';
import { formatDate, formatTimeRelative } from '../utils/formatters';
import { Cpu, RefreshCw, Server, Zap } from 'lucide-react';
import { LearnAboutPageButton } from '../components/docs/LearnAboutPageButton';

export const WorkersPage: React.FC = () => {
  const workersQuery = useQuery({
    queryKey: ['workers'],
    queryFn: getWorkers,
    refetchInterval: 5000,
  });

  const isLoading = workersQuery.isLoading;
  const workers = workersQuery.data || [];

  const totalWorkers = workers.length;
  const activeWorkers = workers.filter((w) => w.state === 'ACTIVE').length;
  const totalCapacity = workers.reduce((acc, w) => acc + w.maxConcurrency, 0);
  const activeTasks = workers.reduce((acc, w) => acc + w.activeTasks, 0);
  const utilization = totalCapacity > 0 ? Math.round((activeTasks / totalCapacity) * 100) : 0;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '1rem' }}>
        <div>
          <h2 style={{ fontSize: '1.35rem', fontWeight: 700, color: 'var(--text-main)', letterSpacing: '0.01em' }}>
            Worker Fleet
          </h2>
          <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
            Real-time Java 21 Virtual Thread worker registrations and capacity distribution
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
          <LearnAboutPageButton pageId="workers" />
          <button className="btn btn-secondary btn-sm" onClick={() => workersQuery.refetch()}>
            <RefreshCw size={14} />
            <span>Refresh</span>
          </button>
        </div>
      </div>

      {workersQuery.isError && (
        <ErrorAlert error={workersQuery.error} onRetry={() => workersQuery.refetch()} />
      )}

      {/* Fleet Telemetry Metrics */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '1rem' }}>
        <MetricCard
          title="Fleet Health"
          value={`${activeWorkers} / ${totalWorkers}`}
          subtitle={`${totalWorkers - activeWorkers} inactive / suspected`}
          icon={<Cpu size={20} />}
          accentColor="var(--accent-cyan)"
        />
        <MetricCard
          title="Total Concurrency"
          value={`${totalCapacity} slots`}
          subtitle="Cluster-wide worker execution limit"
          icon={<Server size={20} />}
          accentColor="var(--accent-blue)"
        />
        <MetricCard
          title="Active Workloads"
          value={`${activeTasks} tasks`}
          subtitle={`Overall utilization: ${utilization}%`}
          icon={<Zap size={20} />}
          accentColor="var(--accent-emerald)"
        />
      </div>

      {/* Workers Table */}
      {isLoading ? (
        <LoadingSkeleton rows={6} height="3rem" />
      ) : workers.length === 0 ? (
        <EmptyState
          title="No workers are currently registered"
          description="Start a chronos-worker process to register with the coordinator cluster over gRPC."
        />
      ) : (
        <div className="data-table-container">
          <table className="data-table">
            <thead>
              <tr>
                <th>Worker ID</th>
                <th>Host &amp; Port</th>
                <th>State</th>
                <th>Active / Capacity</th>
                <th>Utilization</th>
                <th>Supported Tasks</th>
                <th>Last Heartbeat</th>
                <th>Registration</th>
                <th style={{ textAlign: 'right' }}>Action</th>
              </tr>
            </thead>
            <tbody>
              {workers.map((w) => {
                const util = Math.round((w.activeTasks / Math.max(1, w.maxConcurrency)) * 100);
                return (
                  <tr key={w.workerId}>
                    <td>
                      <Link to={`/workers/${w.workerId}`} style={{ textDecoration: 'none' }}>
                        <CopyableId id={w.workerId} />
                      </Link>
                    </td>
                    <td className="font-mono">
                      {w.hostname}:{w.port}
                    </td>
                    <td>
                      <StatusBadge status={w.state} type="worker" />
                    </td>
                    <td className="font-mono">
                      {w.activeTasks} / {w.maxConcurrency}
                    </td>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', width: '110px' }}>
                        <div
                          style={{
                            flex: 1,
                            height: '6px',
                            backgroundColor: 'rgba(255, 255, 255, 0.08)',
                            borderRadius: '3px',
                            overflow: 'hidden',
                          }}
                        >
                          <div
                            style={{
                              width: `${Math.min(100, util)}%`,
                              height: '100%',
                              backgroundColor:
                                util > 80
                                  ? 'var(--accent-rose)'
                                  : util > 50
                                  ? 'var(--accent-amber)'
                                  : 'var(--accent-cyan)',
                            }}
                          />
                        </div>
                        <span className="font-mono" style={{ fontSize: '0.7rem' }}>
                          {util}%
                        </span>
                      </div>
                    </td>
                    <td>
                      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.25rem', maxWidth: '240px' }}>
                        {w.supportedTaskTypes && w.supportedTaskTypes.length > 0 ? (
                          w.supportedTaskTypes.map((type) => (
                            <span
                              key={type}
                              className="font-mono"
                              style={{
                                fontSize: '0.65rem',
                                padding: '0.1rem 0.35rem',
                                borderRadius: '3px',
                                backgroundColor: 'rgba(255, 255, 255, 0.05)',
                                border: '1px solid var(--border-subtle)',
                                color: 'var(--text-muted)',
                              }}
                            >
                              {type}
                            </span>
                          ))
                        ) : (
                          <span style={{ color: 'var(--text-faint)', fontSize: '0.75rem' }}>All Types</span>
                        )}
                      </div>
                    </td>
                    <td>
                      <div style={{ display: 'flex', flexDirection: 'column' }}>
                        <span style={{ fontSize: '0.75rem', color: 'var(--text-main)' }}>
                          {formatTimeRelative(w.lastHeartbeatAt)}
                        </span>
                        <span style={{ fontSize: '0.65rem', color: 'var(--text-faint)' }}>
                          {formatDate(w.lastHeartbeatAt)}
                        </span>
                      </div>
                    </td>
                    <td style={{ color: 'var(--text-muted)', fontSize: '0.75rem' }}>
                      {formatDate(w.registeredAt)}
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <Link to={`/workers/${w.workerId}`} className="btn btn-secondary btn-sm">
                        Inspect
                      </Link>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};
