import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { getClusterOverview } from '../api/cluster';
import { getTasks } from '../api/tasks';
import { getWorkers } from '../api/workers';
import { getWorkflows } from '../api/workflows';
import { getDlqTasks } from '../api/dlq';
import { MetricCard } from '../components/common/MetricCard';
import { StatusBadge } from '../components/common/StatusBadge';
import { CopyableId } from '../components/common/CopyableId';
import { LoadingSkeleton } from '../components/common/LoadingSkeleton';
import { ErrorAlert } from '../components/common/ErrorAlert';
import { formatDate } from '../utils/formatters';
import {
  AlertTriangle,
  ArrowRight,
  Cpu,
  Server,
  Zap,
} from 'lucide-react';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell } from 'recharts';

export const DashboardPage: React.FC = () => {
  // Real API queries with moderate polling
  const clusterQuery = useQuery({
    queryKey: ['clusterOverview'],
    queryFn: getClusterOverview,
    refetchInterval: 10000,
  });

  const tasksQuery = useQuery({
    queryKey: ['tasks', { page: 0, size: 50 }],
    queryFn: () => getTasks({ page: 0, size: 50 }),
    refetchInterval: 10000,
  });

  const workersQuery = useQuery({
    queryKey: ['workers'],
    queryFn: getWorkers,
    refetchInterval: 10000,
  });

  const workflowsQuery = useQuery({
    queryKey: ['workflows'],
    queryFn: getWorkflows,
    refetchInterval: 30000,
  });

  const dlqQuery = useQuery({
    queryKey: ['dlq', { page: 0, size: 5 }],
    queryFn: () => getDlqTasks(0, 5),
    refetchInterval: 15000,
  });

  const isLoading =
    clusterQuery.isLoading ||
    tasksQuery.isLoading ||
    workersQuery.isLoading ||
    workflowsQuery.isLoading;

  if (isLoading) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
        <div>
          <h2 style={{ fontSize: '1.35rem', fontWeight: 700, color: 'var(--text-main)' }}>
            Operational Overview
          </h2>
          <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
            Connecting to Chronos Core cluster...
          </p>
        </div>
        <LoadingSkeleton rows={6} height="3rem" />
      </div>
    );
  }

  const cluster = clusterQuery.data;
  const tasks = tasksQuery.data?.items || [];
  const workers = workersQuery.data || [];
  const workflowCount = workflowsQuery.data?.length ?? 0;
  const dlqCount = dlqQuery.data?.totalElements ?? 0;

  // Derive real status counts from actual returned dataset
  const statusCounts: Record<string, number> = {
    QUEUED: 0,
    DISPATCHED: 0,
    RUNNING: 0,
    SUCCEEDED: 0,
    FAILED: 0,
    DLQ: 0,
    CANCELLED: 0,
  };

  tasks.forEach((t) => {
    if (statusCounts[t.status] !== undefined) {
      statusCounts[t.status]++;
    }
  });

  const chartData = [
    { status: 'QUEUED', count: statusCounts.QUEUED, fill: '#6366f1' },
    { status: 'DISPATCHED', count: statusCounts.DISPATCHED, fill: '#3b82f6' },
    { status: 'RUNNING', count: statusCounts.RUNNING, fill: '#0284c7' },
    { status: 'SUCCEEDED', count: statusCounts.SUCCEEDED, fill: '#10b981' },
    { status: 'FAILED', count: statusCounts.FAILED, fill: '#f43f5e' },
    { status: 'DLQ', count: dlqCount, fill: '#f59e0b' },
    { status: 'CANCELLED', count: statusCounts.CANCELLED, fill: '#64748b' },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1.75rem' }}>
      {/* Page Title */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div>
          <h2 style={{ fontSize: '1.35rem', fontWeight: 700, color: 'var(--text-main)', letterSpacing: '0.01em' }}>
            Operational Overview
          </h2>
          <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
            Cluster coordination, worker fleet utilization, and task throughput
          </p>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.75rem', color: 'var(--text-faint)' }}>
          <span>Polling interval: 10s</span>
        </div>
      </div>

      {clusterQuery.isError && (
        <ErrorAlert error={clusterQuery.error} onRetry={() => clusterQuery.refetch()} />
      )}

      {/* Cluster Metrics Row */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(210px, 1fr))', gap: '1rem' }}>
        <MetricCard
          title="Cluster Leadership"
          value={cluster?.isLeader ? 'ACTIVE LEADER' : 'STANDBY'}
          subtitle={`Epoch #${cluster?.leaderEpoch ?? 0} • ${cluster?.coordinatorId ?? 'unknown'}`}
          icon={<Server size={20} />}
          accentColor={cluster?.isLeader ? 'var(--accent-emerald)' : 'var(--accent-amber)'}
        />
        <MetricCard
          title="Active Workers"
          value={`${cluster?.activeWorkers ?? 0} / ${cluster?.totalWorkers ?? 0}`}
          subtitle={`${cluster?.suspectedWorkers ?? 0} suspected node(s)`}
          icon={<Cpu size={20} />}
          accentColor="var(--accent-cyan)"
        />
        <MetricCard
          title="Cluster Capacity"
          value={`${cluster?.availableClusterCapacity ?? 0} slots`}
          subtitle={`Total: ${cluster?.totalClusterCapacity ?? 0} • Running: ${cluster?.activeClusterTasks ?? 0}`}
          icon={<Zap size={20} />}
          accentColor="var(--accent-blue)"
        />
        <MetricCard
          title="Dead-Letter Queue"
          value={dlqCount}
          subtitle={`Tasks in DLQ • ${workflowCount} registered workflow(s)`}
          icon={<AlertTriangle size={20} />}
          accentColor={dlqCount > 0 ? 'var(--accent-amber)' : 'var(--text-muted)'}
        />
      </div>

      {/* Real Task Distribution Chart & Live Telemetry Grid */}
      <div style={{ display: 'grid', gridTemplateColumns: '1.4fr 1fr', gap: '1.25rem' }}>
        {/* Real Task Distribution Chart */}
        <div
          style={{
            backgroundColor: 'var(--bg-surface)',
            border: '1px solid var(--border-subtle)',
            borderRadius: '0.5rem',
            padding: '1.25rem',
            display: 'flex',
            flexDirection: 'column',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '1rem' }}>
            <h3 style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-main)' }}>
              Recent Tasks Distribution (Sampled N={tasks.length})
            </h3>
            <Link
              to="/tasks"
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '0.25rem',
                fontSize: '0.75rem',
                color: 'var(--accent-cyan)',
                textDecoration: 'none',
              }}
            >
              <span>View All</span>
              <ArrowRight size={12} />
            </Link>
          </div>

          <div style={{ height: '220px', width: '100%' }}>
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={chartData} margin={{ top: 10, right: 10, left: -20, bottom: 20 }}>
                <XAxis
                  dataKey="status"
                  stroke="var(--text-faint)"
                  fontSize={10}
                  fontFamily="var(--font-mono)"
                  tickLine={false}
                />
                <YAxis
                  stroke="var(--text-faint)"
                  fontSize={10}
                  fontFamily="var(--font-mono)"
                  allowDecimals={false}
                  tickLine={false}
                />
                <Tooltip
                  contentStyle={{
                    backgroundColor: 'var(--bg-surface)',
                    border: '1px solid var(--border-default)',
                    borderRadius: '0.375rem',
                    fontSize: '0.75rem',
                    color: 'var(--text-main)',
                  }}
                  cursor={{ fill: 'rgba(255, 255, 255, 0.04)' }}
                />
                <Bar dataKey="count" radius={[4, 4, 0, 0]}>
                  {chartData.map((entry, index) => (
                    <Cell key={`cell-${index}`} fill={entry.fill} />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>
        </div>

        {/* Worker Fleet Quick Status */}
        <div
          style={{
            backgroundColor: 'var(--bg-surface)',
            border: '1px solid var(--border-subtle)',
            borderRadius: '0.5rem',
            padding: '1.25rem',
            display: 'flex',
            flexDirection: 'column',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '1rem' }}>
            <h3 style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-main)' }}>
              Registered Workers ({workers.length})
            </h3>
            <Link
              to="/workers"
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '0.25rem',
                fontSize: '0.75rem',
                color: 'var(--accent-cyan)',
                textDecoration: 'none',
              }}
            >
              <span>Manage</span>
              <ArrowRight size={12} />
            </Link>
          </div>

          {workers.length === 0 ? (
            <div style={{ padding: '2rem 1rem', textAlign: 'center', color: 'var(--text-muted)', fontSize: '0.8125rem' }}>
              No workers currently registered. Start a <code className="font-mono">chronos-worker</code> instance to begin executing tasks.
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.65rem' }}>
              {workers.slice(0, 4).map((w) => {
                const util = Math.round((w.activeTasks / Math.max(1, w.maxConcurrency)) * 100);
                return (
                  <div
                    key={w.workerId}
                    style={{
                      padding: '0.75rem',
                      borderRadius: '0.375rem',
                      backgroundColor: 'var(--bg-elevated)',
                      border: '1px solid var(--border-subtle)',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                    }}
                  >
                    <div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                        <span className="font-mono" style={{ fontSize: '0.8125rem', fontWeight: 600, color: 'var(--text-main)' }}>
                          {w.workerId}
                        </span>
                        <StatusBadge status={w.state} />
                      </div>
                      <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: '0.2rem' }}>
                        {w.hostname}:{w.port} • Cap: {w.activeTasks}/{w.maxConcurrency} ({util}%)
                      </div>
                    </div>
                    <Link to={`/workers/${w.workerId}`} className="btn btn-secondary btn-sm">
                      Inspect
                    </Link>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>

      {/* Recent Submissions Table */}
      <div
        style={{
          backgroundColor: 'var(--bg-surface)',
          border: '1px solid var(--border-subtle)',
          borderRadius: '0.5rem',
          padding: '1.25rem',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '1rem' }}>
          <div>
            <h3 style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-main)' }}>
              Recent Task Submissions
            </h3>
            <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
              Real-time state machine progression across tenant partitions
            </p>
          </div>
          <Link to="/tasks" className="btn btn-secondary btn-sm">
            <span>Explore All Tasks</span>
            <ArrowRight size={14} />
          </Link>
        </div>

        {tasks.length === 0 ? (
          <div style={{ padding: '2rem 1rem', textAlign: 'center', color: 'var(--text-muted)', fontSize: '0.8125rem' }}>
            No tasks found in cluster database. Click &ldquo;Submit Task&rdquo; to launch work.
          </div>
        ) : (
          <div className="data-table-container">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Task ID</th>
                  <th>Type</th>
                  <th>Tenant</th>
                  <th>Priority</th>
                  <th>Status</th>
                  <th>Worker</th>
                  <th>Attempts</th>
                  <th>Created</th>
                </tr>
              </thead>
              <tbody>
                {tasks.slice(0, 6).map((t) => (
                  <tr key={t.taskId}>
                    <td>
                      <Link to={`/tasks/${t.taskId}`} style={{ textDecoration: 'none' }}>
                        <CopyableId id={t.taskId} />
                      </Link>
                    </td>
                    <td className="font-mono" style={{ color: 'var(--accent-cyan)' }}>{t.taskType}</td>
                    <td className="font-mono">{t.tenantId}</td>
                    <td>
                      <span className="font-mono" style={{ fontSize: '0.75rem' }}>{t.priority}</span>
                    </td>
                    <td><StatusBadge status={t.status} /></td>
                    <td className="font-mono" style={{ fontSize: '0.75rem' }}>
                      {t.assignedWorkerId ? <CopyableId id={t.assignedWorkerId} /> : '—'}
                    </td>
                    <td>
                      <span className="font-mono">{t.currentAttempt} / {t.maxAttempts}</span>
                    </td>
                    <td style={{ color: 'var(--text-muted)', fontSize: '0.75rem' }}>
                      {formatDate(t.createdAt)}
                    </td>
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
