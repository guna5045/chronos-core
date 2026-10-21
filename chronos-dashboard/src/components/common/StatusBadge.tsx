import React from 'react';

interface StatusBadgeProps {
  status: string;
  type?: 'task' | 'worker' | 'workflow' | 'version';
}

export const StatusBadge: React.FC<StatusBadgeProps> = ({ status }) => {
  const normalized = (status || '').toUpperCase();

  let bg = 'rgba(100, 116, 139, 0.15)';
  let text = '#94a3b8';
  let border = 'rgba(100, 116, 139, 0.3)';
  let dot = '#64748b';

  switch (normalized) {
    case 'SUCCEEDED':
    case 'ACTIVE':
    case 'PUBLISHED':
      bg = 'rgba(16, 185, 129, 0.12)';
      text = '#34d399';
      border = 'rgba(16, 185, 129, 0.35)';
      dot = '#10b981';
      break;

    case 'RUNNING':
      bg = 'rgba(2, 132, 199, 0.12)';
      text = '#38bdf8';
      border = 'rgba(2, 132, 199, 0.35)';
      dot = '#0284c7';
      break;

    case 'QUEUED':
    case 'READY':
      bg = 'rgba(99, 102, 241, 0.12)';
      text = '#818cf8';
      border = 'rgba(99, 102, 241, 0.35)';
      dot = '#6366f1';
      break;

    case 'DISPATCHED':
      bg = 'rgba(59, 130, 246, 0.12)';
      text = '#60a5fa';
      border = 'rgba(59, 130, 246, 0.35)';
      dot = '#3b82f6';
      break;

    case 'FAILED':
    case 'SUSPECTED':
      bg = 'rgba(244, 63, 94, 0.12)';
      text = '#fb7185';
      border = 'rgba(244, 63, 94, 0.35)';
      dot = '#f43f5e';
      break;

    case 'DLQ':
      bg = 'rgba(245, 158, 11, 0.12)';
      text = '#fbbf24';
      border = 'rgba(245, 158, 11, 0.35)';
      dot = '#f59e0b';
      break;

    case 'CANCELLED':
    case 'REMOVED':
    case 'EVICTED':
    case 'SKIPPED':
    case 'DEPRECATED':
      bg = 'rgba(71, 85, 105, 0.2)';
      text = '#94a3b8';
      border = 'rgba(71, 85, 105, 0.4)';
      dot = '#64748b';
      break;

    case 'CREATED':
    case 'PENDING':
    case 'DRAFT':
    case 'REGISTERING':
      bg = 'rgba(148, 163, 184, 0.12)';
      text = '#cbd5e1';
      border = 'rgba(148, 163, 184, 0.25)';
      dot = '#94a3b8';
      break;
  }

  return (
    <span
      className="status-badge"
      style={{
        backgroundColor: bg,
        color: text,
        borderColor: border,
      }}
    >
      <span className="status-dot" style={{ backgroundColor: dot }} />
      <span>{normalized}</span>
    </span>
  );
};
