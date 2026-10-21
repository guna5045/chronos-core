import React, { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getTenantPolicy, updateTenantPolicy } from '../api/tenants';
import { UpdateTenantPolicyRequest } from '../api/types';
import { ConfirmModal } from '../components/common/ConfirmModal';
import { LoadingSkeleton } from '../components/common/LoadingSkeleton';
import { ErrorAlert } from '../components/common/ErrorAlert';
import { useAuth } from '../auth/AuthContext';
import {
  AlertCircle,
  CheckCircle2,
  Lock,
  RefreshCw,
  Save,
  Sliders,
} from 'lucide-react';
import { ApiClientError } from '../api/client';

export const TenantPolicyPage: React.FC = () => {
  const { tenantId } = useParams<{ tenantId: string }>();
  const { canManagePolicies, user } = useAuth();
  const queryClient = useQueryClient();

  const effectiveTenantId = tenantId || user?.tenantId || 'tenant-alpha';

  const { data: policy, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['tenantPolicy', effectiveTenantId],
    queryFn: () => getTenantPolicy(effectiveTenantId),
    enabled: !!effectiveTenantId,
  });

  // Form state
  const [maxConcurrentTasks, setMaxConcurrentTasks] = useState(10);
  const [maxQueuedTasks, setMaxQueuedTasks] = useState(1000);
  const [tasksPerSecond, setTasksPerSecond] = useState(20.0);
  const [burstCapacity, setBurstCapacity] = useState(40);
  const [maxWorkflowExecutions, setMaxWorkflowExecutions] = useState(5);

  const [isConfirmOpen, setIsConfirmOpen] = useState(false);
  const [feedbackMessage, setFeedbackMessage] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    if (policy) {
      setMaxConcurrentTasks(policy.maxConcurrentTasks);
      setMaxQueuedTasks(policy.maxQueuedTasks);
      setTasksPerSecond(policy.tasksPerSecond);
      setBurstCapacity(policy.burstCapacity);
      setMaxWorkflowExecutions(policy.maxWorkflowExecutions);
    }
  }, [policy]);

  const updateMutation = useMutation({
    mutationFn: (body: UpdateTenantPolicyRequest) =>
      updateTenantPolicy(effectiveTenantId, body),
    onSuccess: (updated) => {
      queryClient.setQueryData(['tenantPolicy', effectiveTenantId], updated);
      setIsConfirmOpen(false);
      setFeedbackMessage('Tenant execution policy successfully updated.');
      setTimeout(() => setFeedbackMessage(null), 5000);
    },
    onError: (err) => {
      if (err instanceof ApiClientError) {
        setErrorMessage(`${err.code}: ${err.message}`);
      } else if (err instanceof Error) {
        setErrorMessage(err.message);
      }
    },
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMessage(null);
    setIsConfirmOpen(true);
  };

  const handleConfirmSave = () => {
    updateMutation.mutate({
      maxConcurrentTasks: Number(maxConcurrentTasks),
      maxQueuedTasks: Number(maxQueuedTasks),
      tasksPerSecond: Number(tasksPerSecond),
      burstCapacity: Number(burstCapacity),
      maxWorkflowExecutions: Number(maxWorkflowExecutions),
    });
  };

  if (isLoading) {
    return <LoadingSkeleton rows={6} height="3rem" />;
  }

  if (isError && !policy) {
    return <ErrorAlert error={error} onRetry={() => refetch()} />;
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem', maxWidth: '800px' }}>
      {/* Top Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '1rem' }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <Sliders size={20} color="var(--accent-cyan)" />
            <h2 style={{ fontSize: '1.35rem', fontWeight: 700, color: 'var(--text-main)', letterSpacing: '0.01em' }}>
              Tenant Policy: {effectiveTenantId}
            </h2>
          </div>
          <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
            Admission control quotas, token bucket rate limits, and concurrency accounting
          </p>
        </div>

        <button className="btn btn-secondary btn-sm" onClick={() => refetch()}>
          <RefreshCw size={14} />
          <span>Refresh</span>
        </button>
      </div>

      {feedbackMessage && (
        <div
          style={{
            backgroundColor: 'rgba(16, 185, 129, 0.1)',
            border: '1px solid rgba(16, 185, 129, 0.3)',
            borderRadius: '0.375rem',
            padding: '0.75rem 1rem',
            display: 'flex',
            alignItems: 'center',
            gap: '0.5rem',
            fontSize: '0.8125rem',
            color: '#6ee7b7',
          }}
        >
          <CheckCircle2 size={16} />
          <span>{feedbackMessage}</span>
        </div>
      )}

      {errorMessage && (
        <div
          style={{
            backgroundColor: 'rgba(244, 63, 94, 0.1)',
            border: '1px solid rgba(244, 63, 94, 0.3)',
            borderRadius: '0.375rem',
            padding: '0.75rem 1rem',
            display: 'flex',
            alignItems: 'center',
            gap: '0.5rem',
            fontSize: '0.8125rem',
            color: '#fda4af',
          }}
        >
          <AlertCircle size={16} />
          <span>{errorMessage}</span>
        </div>
      )}

      {!canManagePolicies && (
        <div
          style={{
            backgroundColor: 'var(--bg-surface)',
            border: '1px solid var(--border-default)',
            borderRadius: '0.375rem',
            padding: '0.75rem 1rem',
            display: 'flex',
            alignItems: 'center',
            gap: '0.65rem',
            fontSize: '0.8125rem',
            color: 'var(--text-muted)',
          }}
        >
          <Lock size={16} color="var(--accent-amber)" />
          <span>
            You are viewing this policy with <code className="font-mono">VIEWER/OPERATOR</code> role. Administrative privileges (<code className="font-mono">ADMIN</code>) are required to update execution limits.
          </span>
        </div>
      )}

      {/* Policy Form */}
      <form
        onSubmit={handleSubmit}
        style={{
          backgroundColor: 'var(--bg-surface)',
          border: '1px solid var(--border-subtle)',
          borderRadius: '0.5rem',
          padding: '1.5rem',
          display: 'flex',
          flexDirection: 'column',
          gap: '1.25rem',
        }}
      >
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1.25rem' }}>
          <div>
            <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-main)', marginBottom: '0.35rem' }}>
              Max Concurrent Tasks
            </label>
            <p style={{ fontSize: '0.7rem', color: 'var(--text-muted)', marginBottom: '0.5rem' }}>
              Simultaneous active execution slots permitted in Redis Set accounting
            </p>
            <input
              type="number"
              min={1}
              required
              disabled={!canManagePolicies}
              className="input-field font-mono"
              value={maxConcurrentTasks}
              onChange={(e) => setMaxConcurrentTasks(parseInt(e.target.value, 10))}
            />
          </div>

          <div>
            <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-main)', marginBottom: '0.35rem' }}>
              Max Queued Tasks
            </label>
            <p style={{ fontSize: '0.7rem', color: 'var(--text-muted)', marginBottom: '0.5rem' }}>
              PostgreSQL ingestion boundary limit before submission rejection
            </p>
            <input
              type="number"
              min={1}
              required
              disabled={!canManagePolicies}
              className="input-field font-mono"
              value={maxQueuedTasks}
              onChange={(e) => setMaxQueuedTasks(parseInt(e.target.value, 10))}
            />
          </div>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1.25rem' }}>
          <div>
            <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-main)', marginBottom: '0.35rem' }}>
              Token Bucket Rate (tasks / sec)
            </label>
            <p style={{ fontSize: '0.7rem', color: 'var(--text-muted)', marginBottom: '0.5rem' }}>
              Refill rate for atomic Redis Lua token bucket rate limiter
            </p>
            <input
              type="number"
              step="0.1"
              min={0.1}
              required
              disabled={!canManagePolicies}
              className="input-field font-mono"
              value={tasksPerSecond}
              onChange={(e) => setTasksPerSecond(parseFloat(e.target.value))}
            />
          </div>

          <div>
            <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-main)', marginBottom: '0.35rem' }}>
              Burst Capacity (tokens)
            </label>
            <p style={{ fontSize: '0.7rem', color: 'var(--text-muted)', marginBottom: '0.5rem' }}>
              Maximum token reservoir for absorbing transient spikes
            </p>
            <input
              type="number"
              min={1}
              required
              disabled={!canManagePolicies}
              className="input-field font-mono"
              value={burstCapacity}
              onChange={(e) => setBurstCapacity(parseInt(e.target.value, 10))}
            />
          </div>
        </div>

        <div>
          <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-main)', marginBottom: '0.35rem' }}>
            Max Concurrent Workflow Executions
          </label>
          <p style={{ fontSize: '0.7rem', color: 'var(--text-muted)', marginBottom: '0.5rem' }}>
            Simultaneous active workflow DAG instances permitted for this tenant
          </p>
          <input
            type="number"
            min={1}
            required
            disabled={!canManagePolicies}
            className="input-field font-mono"
            style={{ maxWidth: '300px' }}
            value={maxWorkflowExecutions}
            onChange={(e) => setMaxWorkflowExecutions(parseInt(e.target.value, 10))}
          />
        </div>

        {canManagePolicies && (
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '0.5rem' }}>
            <button
              type="submit"
              className="btn btn-primary"
              disabled={updateMutation.isPending}
            >
              <Save size={14} />
              <span>{updateMutation.isPending ? 'Applying...' : 'Apply Policy Updates'}</span>
            </button>
          </div>
        )}
      </form>

      {/* Confirmation Modal */}
      {isConfirmOpen && (
        <ConfirmModal
          isOpen={isConfirmOpen}
          title="Apply Tenant Policy Changes"
          description={`Updating policy for tenant ${effectiveTenantId} will modify admission control quotas immediately. Running tasks will continue to completion without retroactive cancellation.`}
          confirmLabel="Confirm Policy Update"
          isLoading={updateMutation.isPending}
          onConfirm={handleConfirmSave}
          onClose={() => setIsConfirmOpen(false)}
        />
      )}
    </div>
  );
};
