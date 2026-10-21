import React, { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { createTask } from '../../api/tasks';
import { CreateTaskRequest, TaskPriority } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { AlertCircle, Plus, X } from 'lucide-react';
import { ApiClientError } from '../../api/client';

interface SubmitTaskModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const SubmitTaskModal: React.FC<SubmitTaskModalProps> = ({ isOpen, onClose }) => {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  const [taskType, setTaskType] = useState('DATA_PIPELINE');
  const [payload, setPayload] = useState('{\n  "batchId": "batch-1001",\n  "records": 500\n}');
  const [priority, setPriority] = useState<TaskPriority>('NORMAL');
  const [tenantId, setTenantId] = useState(user?.tenantId || 'tenant-alpha');
  const [idempotencyKey, setIdempotencyKey] = useState('');
  const [timeout, setTimeoutVal] = useState('PT15M');
  const [maxAttempts, setMaxAttempts] = useState(3);
  const [concurrencyKey, setConcurrencyKey] = useState('');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: (req: CreateTaskRequest) => createTask(req, idempotencyKey || undefined),
    onSuccess: (createdTask) => {
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
      queryClient.invalidateQueries({ queryKey: ['clusterOverview'] });
      onClose();
      navigate(`/tasks/${createdTask.taskId}`);
    },
    onError: (err) => {
      if (err instanceof ApiClientError) {
        setErrorMessage(`${err.code}: ${err.message}`);
      } else if (err instanceof Error) {
        setErrorMessage(err.message);
      }
    },
  });

  if (!isOpen) return null;

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMessage(null);

    const req: CreateTaskRequest = {
      taskType: taskType.trim(),
      payload: payload.trim(),
      priority,
      tenantId: tenantId.trim(),
      idempotencyKey: idempotencyKey.trim() || undefined,
      timeout: timeout.trim() || undefined,
      maxAttempts: Number(maxAttempts) || 3,
      concurrencyKey: concurrencyKey.trim() || undefined,
    };

    mutation.mutate(req);
  };

  return (
    <div
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        backgroundColor: 'rgba(5, 8, 15, 0.75)',
        backdropFilter: 'blur(3px)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 9999,
        padding: '1rem',
      }}
      onClick={onClose}
    >
      <div
        style={{
          backgroundColor: 'var(--bg-surface)',
          border: '1px solid var(--border-default)',
          borderRadius: '0.5rem',
          maxWidth: '560px',
          width: '100%',
          boxShadow: '0 20px 25px -5px rgba(0, 0, 0, 0.5)',
          overflow: 'hidden',
          maxHeight: '90vh',
          display: 'flex',
          flexDirection: 'column',
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <div
          style={{
            padding: '1.25rem 1.5rem',
            borderBottom: '1px solid var(--border-subtle)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <Plus size={18} color="var(--accent-cyan)" />
            <h3 style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--text-main)' }}>
              Submit Chronos Task
            </h3>
          </div>
          <button
            onClick={onClose}
            style={{
              background: 'none',
              border: 'none',
              color: 'var(--text-muted)',
              cursor: 'pointer',
              padding: '0.25rem',
            }}
          >
            <X size={18} />
          </button>
        </div>

        <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', flex: 1, overflowY: 'auto' }}>
          <div style={{ padding: '1.5rem', display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            {errorMessage && (
              <div
                style={{
                  backgroundColor: 'rgba(244, 63, 94, 0.1)',
                  border: '1px solid rgba(244, 63, 94, 0.3)',
                  borderRadius: '0.375rem',
                  padding: '0.75rem',
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

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
              <div>
                <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 500, color: 'var(--text-muted)', marginBottom: '0.35rem' }}>
                  Task Type *
                </label>
                <input
                  type="text"
                  required
                  className="input-field"
                  value={taskType}
                  onChange={(e) => setTaskType(e.target.value)}
                  placeholder="e.g. DATA_PIPELINE"
                />
              </div>

              <div>
                <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 500, color: 'var(--text-muted)', marginBottom: '0.35rem' }}>
                  Tenant ID *
                </label>
                <input
                  type="text"
                  required
                  className="input-field"
                  value={tenantId}
                  onChange={(e) => setTenantId(e.target.value)}
                />
              </div>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
              <div>
                <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 500, color: 'var(--text-muted)', marginBottom: '0.35rem' }}>
                  Priority
                </label>
                <select
                  className="select-field"
                  style={{ width: '100%' }}
                  value={priority}
                  onChange={(e) => setPriority(e.target.value as TaskPriority)}
                >
                  <option value="LOW">LOW (Weight 0)</option>
                  <option value="NORMAL">NORMAL (Weight 10)</option>
                  <option value="HIGH">HIGH (Weight 20)</option>
                  <option value="CRITICAL">CRITICAL (Weight 30)</option>
                </select>
              </div>

              <div>
                <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 500, color: 'var(--text-muted)', marginBottom: '0.35rem' }}>
                  Idempotency Key
                </label>
                <input
                  type="text"
                  className="input-field"
                  value={idempotencyKey}
                  onChange={(e) => setIdempotencyKey(e.target.value)}
                  placeholder="e.g. job-uuid-001"
                />
              </div>
            </div>

            <div>
              <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 500, color: 'var(--text-muted)', marginBottom: '0.35rem' }}>
                Task Payload (JSON / Text) *
              </label>
              <textarea
                required
                rows={4}
                className="input-field font-mono"
                style={{ resize: 'vertical' }}
                value={payload}
                onChange={(e) => setPayload(e.target.value)}
              />
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '0.75rem' }}>
              <div>
                <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 500, color: 'var(--text-muted)', marginBottom: '0.35rem' }}>
                  Timeout
                </label>
                <input
                  type="text"
                  className="input-field"
                  value={timeout}
                  onChange={(e) => setTimeoutVal(e.target.value)}
                  placeholder="e.g. PT15M"
                />
              </div>

              <div>
                <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 500, color: 'var(--text-muted)', marginBottom: '0.35rem' }}>
                  Max Attempts
                </label>
                <input
                  type="number"
                  min={1}
                  max={20}
                  className="input-field"
                  value={maxAttempts}
                  onChange={(e) => setMaxAttempts(parseInt(e.target.value, 10))}
                />
              </div>

              <div>
                <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 500, color: 'var(--text-muted)', marginBottom: '0.35rem' }}>
                  Concurrency Key
                </label>
                <input
                  type="text"
                  className="input-field"
                  value={concurrencyKey}
                  onChange={(e) => setConcurrencyKey(e.target.value)}
                  placeholder="e.g. cust-402"
                />
              </div>
            </div>
          </div>

          <div
            style={{
              padding: '1rem 1.5rem',
              backgroundColor: 'var(--bg-elevated)',
              borderTop: '1px solid var(--border-subtle)',
              display: 'flex',
              justifyContent: 'flex-end',
              gap: '0.75rem',
            }}
          >
            <button type="button" className="btn btn-secondary" onClick={onClose} disabled={mutation.isPending}>
              Cancel
            </button>
            <button type="submit" className="btn btn-primary" disabled={mutation.isPending}>
              {mutation.isPending ? 'Enqueuing...' : 'Submit Task'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
