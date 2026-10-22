import React, { useState } from 'react';
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
  BarChart2,
  Cpu,
  RefreshCw,
  Server,
  Zap,
} from 'lucide-react';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell } from 'recharts';

export const DashboardPage: React.FC = () => {
  const [isRefreshing, setIsRefreshing] = useState(false);

  // Core API Queries
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
    queryFn: () => getWorkflows(),
    refetchInterval: 30000,
  });

  const dlqQuery = useQuery({
    queryKey: ['dlq', { page: 0, size: 5 }],
    queryFn: () => getDlqTasks(0, 5),
    refetchInterval: 15000,
  });

  const handleRefreshAll = async () => {
    setIsRefreshing(true);
    try {
      await Promise.allSettled([
        clusterQuery.refetch(),
        tasksQuery.refetch(),
        workersQuery.refetch(),
        workflowsQuery.refetch(),
        dlqQuery.refetch(),
      ]);
    } finally {
      setIsRefreshing(false);
    }
  };

  const isLoadingInitial =
    clusterQuery.isLoading &&
    tasksQuery.isLoading &&
    workersQuery.isLoading;

  if (isLoadingInitial) {
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

  // Determine query errors
  const anyError =
    clusterQuery.isError ? clusterQuery.error :
    tasksQuery.isError ? tasksQuery.error :
    workersQuery.isError ? workersQuery.error : null;

  const cluster = clusterQuery.data;
  const tasks = tasksQuery.data?.items || [];
  const workers = workersQuery.data || [];
  const workflowCount = Array.isArray(workflowsQuery.data)
    ? workflowsQuery.data.length
    : (workflowsQuery.data?.totalElements ?? workflowsQuery.data?.items?.length ?? 0);
  const dlqCount = dlqQuery.data?.totalElements;

  // Derive status counts from actual backend dataset
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

  if (typeof dlqCount === 'number') {
    statusCounts.DLQ = dlqCount;
  }

  const totalExecutedOrQueued = Object.values(statusCounts).reduce((a, b) => a + b, 0);

  const chartData = [
    { status: 'QUEUED', count: statusCounts.QUEUED, fill: '#6366f1' },
    { status: 'DISPATCHED', count: statusCounts.DISPATCHED, fill: '#3b82f6' },
    { status: 'RUNNING', count: statusCounts.RUNNING, fill: '#0284c7' },
    { status: 'SUCCEEDED', count: statusCounts.SUCCEEDED, fill: '#10b981' },
    { status: 'FAILED', count: statusCounts.FAILED, fill: '#f43f5e' },
    { status: 'DLQ', count: statusCounts.DLQ, fill: '#f59e0b' },
    { status: 'CANCELLED', count: statusCounts.CANCELLED, fill: '#64748b' },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1.75rem' }}>
      {/* Page Title & Control Bar */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div>
          <h2 style={{ fontSize: '1.35rem', fontWeight: 700, color: 'var(--text-main)', letterSpacing: '0.01em' }}>
            Operational Overview
          </h2>
          <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
            Cluster coordination, worker fleet utilization, and task throughput
          </p>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
          <button
            onClick={handleRefreshAll}
            disabled={isRefreshing}
            className="btn btn-secondary btn-sm"
            title="Refresh cluster telemetry"
          >
            <RefreshCw
              size={13}
              style={{ animation: isRefreshing ? 'spin 1s linear infinite' : 'none' }}
            />
            <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
          </button>
          <span style={{ fontSize: '0.75rem', color: 'var(--text-faint)' }}>Polling: 10s</span>
        </div>
      </div>

      {/* Surface error with interactive Retry if any main query failed */}
      {anyError && (
        <ErrorAlert
          error={anyError}
          onRetry={handleRefreshAll}
          isRetrying={isRefreshing}
        />
      )}

      {/* Cluster Metrics Row - Distinguish real 0 from Data Unavailable */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(210px, 1fr))', gap: '1rem' }}>
        <MetricCard
          title="Cluster Leadership"
          value={
            clusterQuery.isError
              ? 'DATA UNAVAILABLE'
              : cluster?.isLeader
              ? 'ACTIVE LEADER'
              : 'STANDBY FOLLOWER'
          }
          subtitle={
            clusterQuery.isError
              ? 'Coordinator unreachable'
              : `Epoch #${cluster?.leaderEpoch ?? 0} • ${cluster?.coordinatorId ?? 'unknown'}`
          }
          icon={<Server size={20} />}
          accentColor={
            clusterQuery.isError
              ? 'var(--text-muted)'
              : cluster?.isLeader
              ? 'var(--accent-emerald)'
              : 'var(--accent-amber)'
          }
        />

        <MetricCard
          title="Active Workers"
          value={
            workersQuery.isError
              ? 'DATA UNAVAILABLE'
              : `${cluster?.activeWorkers ?? workers.filter(w => w.state === 'ACTIVE').length} / ${cluster?.totalWorkers ?? workers.length}`
          }
          subtitle={
            workersQuery.isError
              ? 'Worker status unavailable'
              : `${cluster?.suspectedWorkers ?? workers.filter(w => w.state === 'SUSPECTED').length} suspected node(s)`
          }
          icon={<Cpu size={20} />}
          accentColor={workersQuery.isError ? 'var(--text-muted)' : 'var(--accent-cyan)'}
        />

        <MetricCard
          title="Cluster Capacity"
          value={
            clusterQuery.isError
              ? 'DATA UNAVAILABLE'
              : `${cluster?.availableClusterCapacity ?? 0} slots`
          }
          subtitle={
            clusterQuery.isError
              ? 'Capacity metrics unreachable'
              : `Total: ${cluster?.totalClusterCapacity ?? 0} • Running: ${cluster?.activeClusterTasks ?? 0}`
          }
          icon={<Zap size={20} />}
          accentColor={clusterQuery.isError ? 'var(--text-muted)' : 'var(--accent-blue)'}
        />

        <MetricCard
          title="Dead-Letter Queue"
          value={
            dlqQuery.isError
              ? 'DATA UNAVAILABLE'
              : dlqCount ?? 0
          }
          subtitle={
            workflowsQuery.isError
              ? 'Workflow telemetry unreachable'
              : `Tasks in DLQ • ${workflowCount ?? 0} workflow(s)`
          }
          icon={<AlertTriangle size={20} />}
          accentColor={
            dlqQuery.isError
              ? 'var(--text-muted)'
              : (dlqCount && dlqCount > 0)
              ? 'var(--accent-amber)'
              : 'var(--accent-emerald)'
          }
        />
      </div>

      {/* Real Task Distribution Chart & Live Telemetry Grid */}
      <div style={{ display: 'grid', gridTemplateColumns: '1.4fr 1fr', gap: '1.25rem' }}>
        {/* Real Task Distribution Chart (Supporting all 5 States) */}
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
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <h3 style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-main)' }}>
                Recent Task Distribution
              </h3>
              {tasksQuery.isSuccess && (
                <span className="font-mono" style={{ fontSize: '0.7rem', color: 'var(--text-muted)' }}>
                  (Sampled N={tasks.length})
                </span>
              )}
            </div>
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

          {/* Chart State Handling */}
          <div style={{ height: '220px', width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            {tasksQuery.isLoading ? (
              <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '0.5rem', color: 'var(--text-muted)', fontSize: '0.8125rem' }}>
                <RefreshCw size={18} className="spin" color="var(--accent-cyan)" />
                <span>Loading execution distribution...</span>
              </div>
            ) : tasksQuery.isError ? (
              <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '0.65rem', padding: '1rem', textAlign: 'center' }}>
                <AlertTriangle size={24} color="var(--accent-rose)" />
                <span style={{ fontSize: '0.8125rem', color: 'var(--text-main)' }}>Unable to load execution distribution</span>
                <button onClick={() => tasksQuery.refetch()} className="btn btn-secondary btn-sm">
                  <RefreshCw size={12} />
                  <span>Retry</span>
                </button>
              </div>
            ) : totalExecutedOrQueued === 0 ? (
              <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '100%', gap: '0.5rem', textAlign: 'center', padding: '1rem' }}>
                <BarChart2 size={32} color="var(--text-faint)" />
                <span style={{ fontSize: '0.8125rem', fontWeight: 500, color: 'var(--text-main)' }}>
                  No task executions in the selected interval.
                </span>
                <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                  Submit a task using &ldquo;Submit Task&rdquo; to begin tracking lifecycle transitions.
                </span>
              </div>
            ) : (
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={chartData} margin={{ top: 10, right: 10, left: -20, bottom: 20 }}>
                  <XAxis
                    dataKey="status"
                    stroke="var(--chart-text)"
                    fontSize={10}
                    fontFamily="var(--font-mono)"
                    tickLine={false}
                  />
                  <YAxis
                    stroke="var(--chart-text)"
                    fontSize={10}
                    fontFamily="var(--font-mono)"
                    allowDecimals={false}
                    tickLine={false}
                  />
                  <Tooltip
                    contentStyle={{
                      backgroundColor: 'var(--chart-tooltip-bg)',
                      borderColor: 'var(--chart-tooltip-border)',
                      borderRadius: '0.375rem',
                      fontSize: '0.75rem',
                      color: 'var(--text-main)',
                    }}
                    cursor={{ fill: 'var(--bg-hover)' }}
                  />
                  <Bar dataKey="count" radius={[4, 4, 0, 0]}>
                    {chartData.map((entry, index) => (
                      <Cell key={`cell-${index}`} fill={entry.fill} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            )}
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
              Registered Workers {workersQuery.isSuccess ? `(${workers.length})` : ''}
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

          {workersQuery.isError ? (
            <div style={{ padding: '2rem 1rem', textAlign: 'center', color: 'var(--accent-rose)', fontSize: '0.8125rem' }}>
              Worker fleet data unavailable. Coordinator connection failed.
            </div>
          ) : workers.length === 0 ? (
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

        {tasksQuery.isError ? (
          <div style={{ padding: '2rem 1rem', textAlign: 'center', color: 'var(--accent-rose)', fontSize: '0.8125rem' }}>
            Task persistence query failed. Check coordinator connectivity.
          </div>
        ) : tasks.length === 0 ? (
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
