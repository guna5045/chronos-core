import React, { useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { cancelTask, getTask, retryTask } from '../api/tasks';
import { StatusBadge } from '../components/common/StatusBadge';
import { CopyableId } from '../components/common/CopyableId';
import { ConfirmModal } from '../components/common/ConfirmModal';
import { LoadingSkeleton } from '../components/common/LoadingSkeleton';
import { ErrorAlert } from '../components/common/ErrorAlert';
import { formatDate, formatDuration } from '../utils/formatters';
import { useAuth } from '../auth/AuthContext';
import {
  ArrowLeft,
  Clock,
  Layers,
  RefreshCw,
  Slash,
  Tag,
} from 'lucide-react';

export const TaskDetailPage: React.FC = () => {
  const { taskId } = useParams<{ taskId: string }>();
  const { canMutateTasks } = useAuth();
  const queryClient = useQueryClient();

  const [isRetryOpen, setIsRetryOpen] = useState(false);
  const [isCancelOpen, setIsCancelOpen] = useState(false);

  const { data: task, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['task', taskId],
    queryFn: () => getTask(taskId!),
    enabled: !!taskId,
    refetchInterval: 5000,
  });

  const retryMutation = useMutation({
    mutationFn: (reason?: string) => retryTask(taskId!, { reason }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['task', taskId] });
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
      setIsRetryOpen(false);
    },
  });

  const cancelMutation = useMutation({
    mutationFn: (reason?: string) => cancelTask(taskId!, { reason }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['task', taskId] });
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
      setIsCancelOpen(false);
    },
  });

  if (isLoading) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
        <LoadingSkeleton rows={6} height="3rem" />
      </div>
    );
  }

  if (isError || !task) {
    return (
      <div>
        <Link to="/tasks" className="btn btn-secondary btn-sm" style={{ marginBottom: '1rem' }}>
          <ArrowLeft size={14} />
          <span>Back to Tasks</span>
        </Link>
        <ErrorAlert error={error || new Error('Task not found')} onRetry={() => refetch()} />
      </div>
    );
  }

  const canRetry = canMutateTasks && (task.status === 'FAILED' || task.status === 'DLQ');
  const canCancel =
    canMutateTasks &&
    (task.status === 'CREATED' ||
      task.status === 'QUEUED' ||
      task.status === 'DISPATCHED' ||
      task.status === 'RUNNING');

  // Format payload JSON safely if possible
  let formattedPayload = task.payload || '';
  try {
    if (task.payload && task.payload.startsWith('{')) {
      formattedPayload = JSON.stringify(JSON.parse(task.payload), null, 2);
    }
  } catch {
    // leave as raw string
  }

  const lifecycleStages = ['CREATED', 'QUEUED', 'DISPATCHED', 'RUNNING'];
  const terminalStages = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'DLQ'];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
      {/* Top Header & Actions */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '1rem' }}>
        <div>
          <Link
            to="/tasks"
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
            <span>Task Explorer</span>
          </Link>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', flexWrap: 'wrap' }}>
            <h2 style={{ fontSize: '1.25rem', fontWeight: 700, color: 'var(--text-main)', letterSpacing: '0.01em' }}>
              Task Details
            </h2>
            <CopyableId id={task.taskId} truncate={false} />
            <StatusBadge status={task.status} />
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
          {task.status === 'DLQ' && (
            <Link to={`/dlq`} className="btn btn-secondary">
              <Layers size={14} />
              <span>Inspect in DLQ</span>
            </Link>
          )}

          {canRetry && (
            <button className="btn btn-primary" onClick={() => setIsRetryOpen(true)}>
              <RefreshCw size={14} />
              <span>Retry Task</span>
            </button>
          )}

          {canCancel && (
            <button className="btn btn-danger" onClick={() => setIsCancelOpen(true)}>
              <Slash size={14} />
              <span>Cancel Task</span>
            </button>
          )}
        </div>
      </div>

      {/* State Machine Lifecycle Progress */}
      <div
        style={{
          backgroundColor: 'var(--bg-surface)',
          border: '1px solid var(--border-subtle)',
          borderRadius: '0.5rem',
          padding: '1.25rem',
        }}
      >
        <span style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
          Lifecycle Progression
        </span>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: '0.75rem',
            marginTop: '1rem',
            overflowX: 'auto',
            paddingBottom: '0.5rem',
          }}
        >
          {lifecycleStages.map((stage, idx) => {
            const isCurrent = task.status === stage;
            const isTerminal = terminalStages.includes(task.status);
            const isPast =
              isTerminal ||
              lifecycleStages.indexOf(task.status) > idx;

            return (
              <React.Fragment key={stage}>
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '0.5rem',
                    padding: '0.45rem 0.75rem',
                    borderRadius: '0.375rem',
                    backgroundColor: isCurrent
                      ? 'rgba(2, 132, 199, 0.15)'
                      : isPast
                      ? 'rgba(16, 185, 129, 0.08)'
                      : 'var(--bg-elevated)',
                    border: isCurrent
                      ? '1px solid var(--accent-cyan)'
                      : isPast
                      ? '1px solid rgba(16, 185, 129, 0.3)'
                      : '1px solid var(--border-subtle)',
                    fontSize: '0.75rem',
                    fontFamily: 'var(--font-mono)',
                    color: isCurrent
                      ? 'var(--accent-cyan)'
                      : isPast
                      ? '#34d399'
                      : 'var(--text-muted)',
                  }}
                >
                  <span style={{ width: '6px', height: '6px', borderRadius: '50%', backgroundColor: isCurrent ? 'var(--accent-cyan)' : isPast ? '#10b981' : 'var(--text-faint)' }} />
                  <span>{stage}</span>
                </div>
                {idx < lifecycleStages.length - 1 && (
                  <span style={{ color: 'var(--text-faint)', fontSize: '0.75rem' }}>&rarr;</span>
                )}
              </React.Fragment>
            );
          })}

          {terminalStages.includes(task.status) && (
            <>
              <span style={{ color: 'var(--text-faint)', fontSize: '0.75rem' }}>&rarr;</span>
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: '0.5rem',
                  padding: '0.45rem 0.75rem',
                  borderRadius: '0.375rem',
                  backgroundColor:
                    task.status === 'SUCCEEDED'
                      ? 'rgba(16, 185, 129, 0.15)'
                      : task.status === 'DLQ'
                      ? 'rgba(245, 158, 11, 0.15)'
                      : 'rgba(244, 63, 94, 0.15)',
                  border:
                    task.status === 'SUCCEEDED'
                      ? '1px solid var(--accent-emerald)'
                      : task.status === 'DLQ'
                      ? '1px solid var(--accent-amber)'
                      : '1px solid var(--accent-rose)',
                  fontSize: '0.75rem',
                  fontFamily: 'var(--font-mono)',
                  color:
                    task.status === 'SUCCEEDED'
                      ? '#34d399'
                      : task.status === 'DLQ'
                      ? '#fbbf24'
                      : '#fda4af',
                  fontWeight: 600,
                }}
              >
                <span>{task.status} (TERMINAL)</span>
              </div>
            </>
          )}
        </div>
      </div>

      {/* Metadata Grid */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: '1.25rem' }}>
        <div
          style={{
            backgroundColor: 'var(--bg-surface)',
            border: '1px solid var(--border-subtle)',
            borderRadius: '0.5rem',
            padding: '1.25rem',
            display: 'flex',
            flexDirection: 'column',
            gap: '0.85rem',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', color: 'var(--text-muted)', fontSize: '0.75rem', fontWeight: 600, textTransform: 'uppercase' }}>
            <Tag size={14} />
            <span>Execution Specification</span>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '120px 1fr', gap: '0.5rem', fontSize: '0.8125rem' }}>
            <span style={{ color: 'var(--text-muted)' }}>Task Type:</span>
            <span className="font-mono" style={{ color: 'var(--accent-cyan)' }}>{task.taskType}</span>

            <span style={{ color: 'var(--text-muted)' }}>Tenant:</span>
            <span className="font-mono">{task.tenantId}</span>

            <span style={{ color: 'var(--text-muted)' }}>Priority:</span>
            <span className="font-mono">{task.priority}</span>

            <span style={{ color: 'var(--text-muted)' }}>Attempt:</span>
            <span className="font-mono">{task.currentAttempt} of {task.maxAttempts}</span>

            <span style={{ color: 'var(--text-muted)' }}>Row Version:</span>
            <span className="font-mono">v{task.version} (optimistic lock)</span>

            <span style={{ color: 'var(--text-muted)' }}>Idempotency Key:</span>
            <span className="font-mono">{task.idempotencyKey || '—'}</span>

            <span style={{ color: 'var(--text-muted)' }}>Concurrency Key:</span>
            <span className="font-mono">{task.concurrencyKey || '—'}</span>
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
            gap: '0.85rem',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', color: 'var(--text-muted)', fontSize: '0.75rem', fontWeight: 600, textTransform: 'uppercase' }}>
            <Clock size={14} />
            <span>Temporal &amp; Scheduling Constraints</span>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '120px 1fr', gap: '0.5rem', fontSize: '0.8125rem' }}>
            <span style={{ color: 'var(--text-muted)' }}>Created At:</span>
            <span>{formatDate(task.createdAt)}</span>

            <span style={{ color: 'var(--text-muted)' }}>Scheduled At:</span>
            <span>{formatDate(task.scheduledAt)}</span>

            <span style={{ color: 'var(--text-muted)' }}>Deadline:</span>
            <span>{formatDate(task.deadline)}</span>

            <span style={{ color: 'var(--text-muted)' }}>Timeout:</span>
            <span>{formatDuration(task.timeout)}</span>

            <span style={{ color: 'var(--text-muted)' }}>Assigned Worker:</span>
            <span>
              {task.assignedWorkerId ? (
                <Link to={`/workers/${task.assignedWorkerId}`} style={{ color: 'var(--accent-cyan)', textDecoration: 'none' }}>
                  <CopyableId id={task.assignedWorkerId} />
                </Link>
              ) : (
                'Not assigned'
              )}
            </span>
          </div>
        </div>
      </div>

      {/* Task Payload View */}
      <div
        style={{
          backgroundColor: 'var(--bg-surface)',
          border: '1px solid var(--border-subtle)',
          borderRadius: '0.5rem',
          padding: '1.25rem',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '0.75rem' }}>
          <span style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
            Task Payload
          </span>
          <span style={{ fontSize: '0.7rem', color: 'var(--text-faint)' }}>JSON / Text Deserialized</span>
        </div>
        <pre
          style={{
            backgroundColor: 'var(--bg-canvas)',
            border: '1px solid var(--border-default)',
            borderRadius: '0.375rem',
            padding: '1rem',
            fontSize: '0.8125rem',
            overflowX: 'auto',
            maxHeight: '350px',
            color: 'var(--text-main)',
          }}
        >
          {formattedPayload || '/* Empty payload */'}
        </pre>
      </div>

      {/* Confirmation Modals */}
      {isRetryOpen && (
        <ConfirmModal
          isOpen={isRetryOpen}
          title="Requeue Task Execution"
          description={`Are you sure you want to requeue task ${task.taskId}? The task will transition from ${task.status} to QUEUED and be assigned to an available worker.`}
          confirmLabel="Requeue Task"
          requireReason
          isLoading={retryMutation.isPending}
          onConfirm={(reason) => retryMutation.mutate(reason)}
          onClose={() => setIsRetryOpen(false)}
        />
      )}

      {isCancelOpen && (
        <ConfirmModal
          isOpen={isCancelOpen}
          title="Cancel Chronos Task"
          description={`Are you sure you want to cooperatively cancel task ${task.taskId}? If actively running, the assigned worker thread will be interrupted.`}
          confirmLabel="Cancel Task"
          variant="danger"
          requireReason
          isLoading={cancelMutation.isPending}
          onConfirm={(reason) => cancelMutation.mutate(reason)}
          onClose={() => setIsCancelOpen(false)}
        />
      )}
    </div>
  );
};
