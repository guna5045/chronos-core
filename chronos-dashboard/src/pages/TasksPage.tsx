import React, { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { cancelTask, getTasks, retryTask } from '../api/tasks';
import { StatusBadge } from '../components/common/StatusBadge';
import { CopyableId } from '../components/common/CopyableId';
import { ConfirmModal } from '../components/common/ConfirmModal';
import { LoadingSkeleton } from '../components/common/LoadingSkeleton';
import { EmptyState } from '../components/common/EmptyState';
import { ErrorAlert } from '../components/common/ErrorAlert';
import { formatDate } from '../utils/formatters';
import { useAuth } from '../auth/AuthContext';
import {
  ChevronLeft,
  ChevronRight,
  Filter,
  Plus,
  RefreshCw,
  Search,
  Slash,
} from 'lucide-react';
import { SubmitTaskModal } from '../components/tasks/SubmitTaskModal';
import { LearnAboutPageButton } from '../components/docs/LearnAboutPageButton';

export const TasksPage: React.FC = () => {
  const { canMutateTasks } = useAuth();
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  // Filters state
  const [page, setPage] = useState(0);
  const [size] = useState(20);
  const [statusFilter, setStatusFilter] = useState('');
  const [tenantFilter, setTenantFilter] = useState('');
  const [taskTypeFilter, setTaskTypeFilter] = useState('');
  const [workerIdFilter, setWorkerIdFilter] = useState('');
  const [directSearchId, setDirectSearchId] = useState('');

  // Modals state
  const [isSubmitOpen, setIsSubmitOpen] = useState(false);
  const [retryTargetTaskId, setRetryTargetTaskId] = useState<string | null>(null);
  const [cancelTargetTaskId, setCancelTargetTaskId] = useState<string | null>(null);

  const queryParams = {
    page,
    size,
    status: statusFilter || undefined,
    tenantId: tenantFilter || undefined,
    taskType: taskTypeFilter || undefined,
    workerId: workerIdFilter || undefined,
  };

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['tasks', queryParams],
    queryFn: () => getTasks(queryParams),
    refetchInterval: 10000,
  });

  const retryMutation = useMutation({
    mutationFn: ({ taskId, reason }: { taskId: string; reason?: string }) =>
      retryTask(taskId, { reason }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
      setRetryTargetTaskId(null);
    },
  });

  const cancelMutation = useMutation({
    mutationFn: ({ taskId, reason }: { taskId: string; reason?: string }) =>
      cancelTask(taskId, { reason }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
      setCancelTargetTaskId(null);
    },
  });

  const handleDirectSearch = (e: React.FormEvent) => {
    e.preventDefault();
    if (directSearchId.trim()) {
      navigate(`/tasks/${directSearchId.trim()}`);
    }
  };

  const tasks = data?.items || [];
  const totalElements = data?.totalElements ?? 0;
  const totalPages = data?.totalPages ?? 1;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
      {/* Top Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '1rem' }}>
        <div>
          <h2 style={{ fontSize: '1.35rem', fontWeight: 700, color: 'var(--text-main)', letterSpacing: '0.01em' }}>
            Task Explorer
          </h2>
          <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
            Real-time query and lifecycle management across distributed tenant partitions
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
          <LearnAboutPageButton pageId="tasks" />
          {canMutateTasks && (
            <button className="btn btn-primary" onClick={() => setIsSubmitOpen(true)}>
              <Plus size={16} />
              <span>Submit Task</span>
            </button>
          )}

          <form onSubmit={handleDirectSearch} style={{ display: 'flex', alignItems: 'center', position: 'relative' }}>
            <input
              type="text"
              placeholder="Jump to Task UUID..."
              className="input-field font-mono"
              style={{ width: '220px', paddingLeft: '2rem', height: '34px', fontSize: '0.75rem' }}
              value={directSearchId}
              onChange={(e) => setDirectSearchId(e.target.value)}
            />
            <Search size={14} color="var(--text-faint)" style={{ position: 'absolute', left: '0.65rem' }} />
          </form>
        </div>
      </div>

      {/* Filter Toolbar */}
      <div
        style={{
          backgroundColor: 'var(--bg-surface)',
          border: '1px solid var(--border-subtle)',
          borderRadius: '0.5rem',
          padding: '0.85rem 1.25rem',
          display: 'flex',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: '0.75rem',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.35rem', color: 'var(--text-muted)', fontSize: '0.8125rem' }}>
          <Filter size={14} />
          <span>Filters:</span>
        </div>

        <select
          className="select-field"
          value={statusFilter}
          onChange={(e) => {
            setStatusFilter(e.target.value);
            setPage(0);
          }}
        >
          <option value="">All Statuses</option>
          <option value="CREATED">CREATED</option>
          <option value="QUEUED">QUEUED</option>
          <option value="DISPATCHED">DISPATCHED</option>
          <option value="RUNNING">RUNNING</option>
          <option value="SUCCEEDED">SUCCEEDED</option>
          <option value="FAILED">FAILED</option>
          <option value="CANCELLED">CANCELLED</option>
          <option value="DLQ">DLQ</option>
        </select>

        <input
          type="text"
          placeholder="Filter by Tenant ID..."
          className="input-field font-mono"
          style={{ width: '170px', height: '32px', fontSize: '0.75rem' }}
          value={tenantFilter}
          onChange={(e) => {
            setTenantFilter(e.target.value);
            setPage(0);
          }}
        />

        <input
          type="text"
          placeholder="Filter by Task Type..."
          className="input-field font-mono"
          style={{ width: '170px', height: '32px', fontSize: '0.75rem' }}
          value={taskTypeFilter}
          onChange={(e) => {
            setTaskTypeFilter(e.target.value);
            setPage(0);
          }}
        />

        <input
          type="text"
          placeholder="Filter by Worker ID..."
          className="input-field font-mono"
          style={{ width: '170px', height: '32px', fontSize: '0.75rem' }}
          value={workerIdFilter}
          onChange={(e) => {
            setWorkerIdFilter(e.target.value);
            setPage(0);
          }}
        />

        {(statusFilter || tenantFilter || taskTypeFilter || workerIdFilter) && (
          <button
            className="btn btn-secondary btn-sm"
            onClick={() => {
              setStatusFilter('');
              setTenantFilter('');
              setTaskTypeFilter('');
              setWorkerIdFilter('');
              setPage(0);
            }}
          >
            Clear Filters
          </button>
        )}
      </div>

      {isError && <ErrorAlert error={error} onRetry={() => refetch()} />}

      {/* Main Table */}
      {isLoading ? (
        <LoadingSkeleton rows={8} height="2.8rem" />
      ) : tasks.length === 0 ? (
        <EmptyState
          title="No tasks found matching criteria"
          description="Try broadening query filters or click 'Submit Task' to schedule new workloads."
        />
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
                <th>Scheduled</th>
                <th style={{ textAlign: 'right' }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {tasks.map((t) => {
                const canRetry =
                  canMutateTasks && (t.status === 'FAILED' || t.status === 'DLQ');
                const canCancel =
                  canMutateTasks &&
                  (t.status === 'CREATED' ||
                    t.status === 'QUEUED' ||
                    t.status === 'DISPATCHED' ||
                    t.status === 'RUNNING');

                return (
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
                      {t.assignedWorkerId ? (
                        <Link to={`/workers/${t.assignedWorkerId}`} style={{ textDecoration: 'none' }}>
                          <CopyableId id={t.assignedWorkerId} />
                        </Link>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td>
                      <span className="font-mono">{t.currentAttempt} / {t.maxAttempts}</span>
                    </td>
                    <td style={{ color: 'var(--text-muted)', fontSize: '0.75rem' }}>
                      {formatDate(t.createdAt)}
                    </td>
                    <td style={{ color: 'var(--text-muted)', fontSize: '0.75rem' }}>
                      {formatDate(t.scheduledAt)}
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: '0.4rem' }}>
                        <Link to={`/tasks/${t.taskId}`} className="btn btn-secondary btn-sm">
                          Inspect
                        </Link>

                        {canRetry && (
                          <button
                            className="btn btn-primary btn-sm"
                            title="Retry task execution"
                            onClick={() => setRetryTargetTaskId(t.taskId)}
                          >
                            <RefreshCw size={12} />
                          </button>
                        )}

                        {canCancel && (
                          <button
                            className="btn btn-danger btn-sm"
                            title="Cancel task"
                            onClick={() => setCancelTargetTaskId(t.taskId)}
                          >
                            <Slash size={12} />
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>

          {/* Pagination Controls */}
          <div
            style={{
              padding: '0.85rem 1.25rem',
              borderTop: '1px solid var(--border-subtle)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              fontSize: '0.8125rem',
              color: 'var(--text-muted)',
            }}
          >
            <div>
              Showing <span style={{ color: 'var(--text-main)', fontWeight: 600 }}>{tasks.length}</span> of{' '}
              <span style={{ color: 'var(--text-main)', fontWeight: 600 }}>{totalElements}</span> tasks (Page {page + 1} of {Math.max(1, totalPages)})
            </div>

            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <button
                className="btn btn-secondary btn-sm"
                disabled={page <= 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
              >
                <ChevronLeft size={14} />
                <span>Prev</span>
              </button>
              <button
                className="btn btn-secondary btn-sm"
                disabled={page >= totalPages - 1}
                onClick={() => setPage((p) => p + 1)}
              >
                <span>Next</span>
                <ChevronRight size={14} />
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Submit Task Modal */}
      {isSubmitOpen && (
        <SubmitTaskModal isOpen={isSubmitOpen} onClose={() => setIsSubmitOpen(false)} />
      )}

      {/* Confirmation Modals for Retry & Cancel */}
      {retryTargetTaskId && (
        <ConfirmModal
          isOpen={!!retryTargetTaskId}
          title="Retry Task Execution"
          description={`Are you sure you want to requeue task ${retryTargetTaskId}? It will be scheduled for execution based on tenant admission limits.`}
          confirmLabel="Requeue Task"
          variant="primary"
          requireReason
          isLoading={retryMutation.isPending}
          onConfirm={(reason) => retryMutation.mutate({ taskId: retryTargetTaskId, reason })}
          onClose={() => setRetryTargetTaskId(null)}
        />
      )}

      {cancelTargetTaskId && (
        <ConfirmModal
          isOpen={!!cancelTargetTaskId}
          title="Cancel Chronos Task"
          description={`Are you sure you want to cancel task ${cancelTargetTaskId}? If actively executing, cooperative gRPC cancellation will be dispatched to the assigned worker.`}
          confirmLabel="Confirm Cancellation"
          variant="danger"
          requireReason
          isLoading={cancelMutation.isPending}
          onConfirm={(reason) => cancelMutation.mutate({ taskId: cancelTargetTaskId, reason })}
          onClose={() => setCancelTargetTaskId(null)}
        />
      )}
    </div>
  );
};
