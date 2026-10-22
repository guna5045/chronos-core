import React, { useState } from 'react';
import { AlertCircle, RefreshCw, ShieldAlert, KeyRound, Clock, ServerOff } from 'lucide-react';
import { ApiClientError } from '../../api/client';

interface ErrorAlertProps {
  error: unknown;
  onRetry?: () => void | Promise<unknown>;
  title?: string;
  isRetrying?: boolean;
}

export const ErrorAlert: React.FC<ErrorAlertProps> = ({
  error,
  onRetry,
  title,
  isRetrying: externalRetrying = false,
}) => {
  const [internalRetrying, setInternalRetrying] = useState(false);
  const retrying = externalRetrying || internalRetrying;

  let message = 'An unexpected failure occurred while communicating with Chronos Core API';
  let code: string | undefined;
  let status: number | undefined;
  let requestId: string | undefined;
  let traceId: string | undefined;

  if (error instanceof ApiClientError) {
    message = error.message;
    code = error.code;
    status = error.status;
    requestId = error.requestId;
    traceId = error.traceId;
  } else if (error instanceof Error) {
    message = error.message;
  }

  // Determine semantic title and icon based on error classification
  let derivedTitle = title;
  let IconComponent = AlertCircle;
  let borderColor = 'rgba(244, 63, 94, 0.3)';
  let bgColor = 'rgba(244, 63, 94, 0.08)';
  let accentColor = 'var(--accent-rose)';

  if (code === 'NETWORK_ERROR' || code === 'COORDINATOR_UNREACHABLE' || status === 0 || status === 503) {
    if (!title) derivedTitle = 'Coordinator Service Unreachable';
    IconComponent = ServerOff;
    accentColor = 'var(--accent-amber)';
    borderColor = 'rgba(245, 158, 11, 0.35)';
    bgColor = 'rgba(245, 158, 11, 0.08)';
  } else if (status === 401) {
    if (!title) derivedTitle = 'Authentication Required';
    IconComponent = KeyRound;
    accentColor = 'var(--accent-amber)';
    borderColor = 'rgba(245, 158, 11, 0.35)';
    bgColor = 'rgba(245, 158, 11, 0.08)';
  } else if (status === 403) {
    if (!title) derivedTitle = 'Authorization Denied';
    IconComponent = ShieldAlert;
    accentColor = 'var(--accent-rose)';
  } else if (status === 429) {
    if (!title) derivedTitle = 'Rate Limit / Backpressure Exceeded';
    IconComponent = Clock;
    accentColor = 'var(--accent-amber)';
  } else if (!derivedTitle) {
    derivedTitle = status === 500 ? 'Internal Server Failure' : 'API Communication Failure';
  }

  const handleRetryClick = async () => {
    if (retrying || !onRetry) return;
    setInternalRetrying(true);
    try {
      await Promise.resolve(onRetry());
    } finally {
      setInternalRetrying(false);
    }
  };

  return (
    <div
      role="alert"
      style={{
        backgroundColor: bgColor,
        border: `1px solid ${borderColor}`,
        borderRadius: '0.5rem',
        padding: '1.15rem 1.25rem',
        display: 'flex',
        alignItems: 'flex-start',
        justifyContent: 'space-between',
        gap: '1rem',
        margin: '1rem 0',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: '0.85rem', flex: 1, minWidth: 0 }}>
        <IconComponent
          size={20}
          style={{ color: accentColor, marginTop: '0.15rem', flexShrink: 0 }}
        />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap' }}>
            <h4 style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-main)' }}>
              {derivedTitle}
            </h4>
            {code && (
              <span
                className="font-mono"
                style={{
                  fontSize: '0.7rem',
                  padding: '0.1rem 0.4rem',
                  borderRadius: '0.25rem',
                  backgroundColor: 'var(--bg-elevated)',
                  border: '1px solid var(--border-default)',
                  color: accentColor,
                }}
              >
                {code} {typeof status === 'number' ? `(${status})` : ''}
              </span>
            )}
          </div>
          <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', marginTop: '0.35rem', lineHeight: 1.45 }}>
            {message}
          </p>

          {(requestId || traceId) && (
            <div
              style={{
                display: 'flex',
                gap: '0.75rem',
                marginTop: '0.65rem',
                fontSize: '0.7rem',
                color: 'var(--text-faint)',
                flexWrap: 'wrap',
              }}
            >
              {requestId && (
                <span>
                  Request ID: <code className="font-mono" style={{ color: 'var(--text-muted)' }}>{requestId}</code>
                </span>
              )}
              {traceId && (
                <span>
                  Trace ID: <code className="font-mono" style={{ color: 'var(--text-muted)' }}>{traceId}</code>
                </span>
              )}
            </div>
          )}
        </div>
      </div>

      {onRetry && (
        <button
          type="button"
          className="btn btn-secondary btn-sm"
          onClick={handleRetryClick}
          disabled={retrying}
          style={{
            flexShrink: 0,
            display: 'flex',
            alignItems: 'center',
            gap: '0.4rem',
          }}
        >
          <RefreshCw
            size={13}
            style={{
              animation: retrying ? 'spin 1s linear infinite' : 'none',
            }}
          />
          <span>{retrying ? 'Retrying...' : 'Retry'}</span>
        </button>
      )}
    </div>
  );
};
