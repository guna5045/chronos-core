import React, { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { createWorkflow, getWorkflows } from '../api/workflows';
import { CreateWorkflowRequest } from '../api/types';
import { CopyableId } from '../components/common/CopyableId';
import { StatusBadge } from '../components/common/StatusBadge';
import { LoadingSkeleton } from '../components/common/LoadingSkeleton';
import { EmptyState } from '../components/common/EmptyState';
import { ErrorAlert } from '../components/common/ErrorAlert';
import { formatDate } from '../utils/formatters';
import { useAuth } from '../auth/AuthContext';
import { GitBranch, Plus, RefreshCw, X } from 'lucide-react';

export const WorkflowsPage: React.FC = () => {
  const { canMutateTasks } = useAuth();
  const queryClient = useQueryClient();
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [formError, setFormError] = useState<string | null>(null);

  const { data: workflows, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['workflows'],
    queryFn: getWorkflows,
    refetchInterval: 15000,
  });

  const createMutation = useMutation({
    mutationFn: (req: CreateWorkflowRequest) => createWorkflow(req),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['workflows'] });
      setIsCreateOpen(false);
      setName('');
      setDescription('');
    },
    onError: (err: Error) => {
      setFormError(err.message);
    },
  });

  const handleCreate = (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return;
    setFormError(null);
    createMutation.mutate({ name: name.trim(), description: description.trim() || undefined });
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '1rem' }}>
        <div>
          <h2 style={{ fontSize: '1.35rem', fontWeight: 700, color: 'var(--text-main)', letterSpacing: '0.01em' }}>
            Workflow Definitions
          </h2>
          <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
            Directed Acyclic Graph (DAG) templates and immutable version namespaces
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
          {canMutateTasks && (
            <button className="btn btn-primary" onClick={() => setIsCreateOpen(true)}>
              <Plus size={16} />
              <span>Register Workflow</span>
            </button>
          )}
          <button className="btn btn-secondary btn-sm" onClick={() => refetch()}>
            <RefreshCw size={14} />
          </button>
        </div>
      </div>

      {isError && <ErrorAlert error={error} onRetry={() => refetch()} />}

      {/* Main Table */}
      {isLoading ? (
        <LoadingSkeleton rows={6} height="3rem" />
      ) : !workflows || workflows.length === 0 ? (
        <EmptyState
          title="No workflow definitions registered"
          description="Register a workflow namespace to author versioned DAG graphs and dependency pipelines."
          action={
            canMutateTasks ? (
              <button className="btn btn-primary" onClick={() => setIsCreateOpen(true)}>
                <Plus size={14} />
                <span>Register First Workflow</span>
              </button>
            ) : undefined
          }
        />
      ) : (
        <div className="data-table-container">
          <table className="data-table">
            <thead>
              <tr>
                <th>Workflow ID</th>
                <th>Name</th>
                <th>Description</th>
                <th>Active Version</th>
                <th>Created</th>
                <th style={{ textAlign: 'right' }}>Action</th>
              </tr>
            </thead>
            <tbody>
              {workflows.map((wf) => (
                <tr key={wf.workflowId}>
                  <td>
                    <Link to={`/workflows/${wf.workflowId}`} style={{ textDecoration: 'none' }}>
                      <CopyableId id={wf.workflowId} />
                    </Link>
                  </td>
                  <td style={{ fontWeight: 600, color: 'var(--text-main)' }}>{wf.name}</td>
                  <td style={{ color: 'var(--text-muted)', maxWidth: '300px' }}>
                    {wf.description || '—'}
                  </td>
                  <td>
                    {wf.activeVersion ? (
                      <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                        <span className="font-mono" style={{ color: 'var(--accent-cyan)' }}>
                          v{wf.activeVersion.versionNumber}
                        </span>
                        <StatusBadge status={wf.activeVersion.status} type="version" />
                      </div>
                    ) : (
                      <span style={{ color: 'var(--text-faint)', fontSize: '0.75rem' }}>No published version</span>
                    )}
                  </td>
                  <td style={{ color: 'var(--text-muted)', fontSize: '0.75rem' }}>
                    {formatDate(wf.createdAt)}
                  </td>
                  <td style={{ textAlign: 'right' }}>
                    <Link to={`/workflows/${wf.workflowId}`} className="btn btn-secondary btn-sm">
                      Inspect DAG
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Register Workflow Modal */}
      {isCreateOpen && (
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
          onClick={() => setIsCreateOpen(false)}
        >
          <div
            style={{
              backgroundColor: 'var(--bg-surface)',
              border: '1px solid var(--border-default)',
              borderRadius: '0.5rem',
              maxWidth: '480px',
              width: '100%',
              padding: '1.5rem',
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '1.25rem' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                <GitBranch size={18} color="var(--accent-cyan)" />
                <h3 style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--text-main)' }}>Register Workflow Namespace</h3>
              </div>
              <button
                onClick={() => setIsCreateOpen(false)}
                style={{ background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer' }}
              >
                <X size={18} />
              </button>
            </div>

            {formError && (
              <div style={{ color: 'var(--accent-rose)', fontSize: '0.8125rem', marginBottom: '1rem' }}>
                {formError}
              </div>
            )}

            <form onSubmit={handleCreate} style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
              <div>
                <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 500, color: 'var(--text-muted)', marginBottom: '0.35rem' }}>
                  Workflow Name *
                </label>
                <input
                  type="text"
                  required
                  className="input-field"
                  placeholder="e.g. data-etl-pipeline"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                />
              </div>

              <div>
                <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 500, color: 'var(--text-muted)', marginBottom: '0.35rem' }}>
                  Description
                </label>
                <textarea
                  rows={3}
                  className="input-field"
                  placeholder="Operational role and scope of this workflow DAG"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                />
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.75rem', marginTop: '0.5rem' }}>
                <button type="button" className="btn btn-secondary" onClick={() => setIsCreateOpen(false)}>
                  Cancel
                </button>
                <button type="submit" className="btn btn-primary" disabled={createMutation.isPending}>
                  {createMutation.isPending ? 'Registering...' : 'Register Workflow'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
