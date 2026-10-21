import React from 'react';
import { AlertCircle, RefreshCw } from 'lucide-react';
import { ApiClientError } from '../../api/client';

interface ErrorAlertProps {
  error: unknown;
  onRetry?: () => void;
  title?: string;
}

export const ErrorAlert: React.FC<ErrorAlertProps> = ({
  error,
  onRetry,
  title = 'API Communication Error',
}) => {
  let message = 'An unexpected failure occurred while communicating with Chronos Core API';
  let code: string | undefined;
  let status: number | undefined;

  if (error instanceof ApiClientError) {
    message = error.message;
    code = error.code;
    status = error.status;
  } else if (error instanceof Error) {
    message = error.message;
  }

  return (
    <div
      style={{
        backgroundColor: 'rgba(244, 63, 94, 0.08)',
        border: '1px solid rgba(244, 63, 94, 0.25)',
        borderRadius: '0.5rem',
        padding: '1.25rem',
        display: 'flex',
        alignItems: 'flex-start',
        justifyContent: 'space-between',
        gap: '1rem',
        margin: '1rem 0',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: '0.75rem' }}>
        <AlertCircle size={20} color="var(--accent-rose)" style={{ marginTop: '0.15rem', flexShrink: 0 }} />
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <h4 style={{ fontSize: '0.9rem', fontWeight: 600, color: '#fda4af' }}>{title}</h4>
            {code && (
              <span
                className="font-mono"
                style={{
                  fontSize: '0.7rem',
                  padding: '0.1rem 0.4rem',
                  borderRadius: '0.25rem',
                  backgroundColor: 'rgba(244, 63, 94, 0.15)',
                  color: '#fb7185',
                }}
              >
                {code} {status ? `(${status})` : ''}
              </span>
            )}
          </div>
          <p style={{ fontSize: '0.8125rem', color: '#cbd5e1', marginTop: '0.25rem', lineHeight: 1.4 }}>
            {message}
          </p>
        </div>
      </div>

      {onRetry && (
        <button
          className="btn btn-secondary btn-sm"
          onClick={onRetry}
          style={{ flexShrink: 0 }}
        >
          <RefreshCw size={12} />
          <span>Retry</span>
        </button>
      )}
    </div>
  );
};
