import { Component, ErrorInfo, ReactNode } from 'react';
import { AlertCircle, RefreshCw, Home } from 'lucide-react';

interface Props {
  children: ReactNode;
  fallbackTitle?: string;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

export class ErrorBoundary extends Component<Props, State> {
  public state: State = {
    hasError: false,
    error: null,
  };

  public static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  public componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error('Unhandled Control Plane React Error caught by ErrorBoundary:', error, errorInfo);
  }

  public handleReset = () => {
    this.setState({ hasError: false, error: null });
  };

  public render() {
    if (this.state.hasError) {
      return (
        <div
          style={{
            margin: '2rem auto',
            maxWidth: '650px',
            backgroundColor: 'var(--bg-surface)',
            border: '1px solid rgba(244, 63, 94, 0.4)',
            borderRadius: '0.5rem',
            padding: '1.75rem',
            boxShadow: '0 4px 12px rgba(0, 0, 0, 0.25)',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', marginBottom: '1rem' }}>
            <AlertCircle size={24} color="var(--accent-rose)" />
            <h3 style={{ fontSize: '1.1rem', fontWeight: 700, color: 'var(--text-main)' }}>
              {this.props.fallbackTitle || 'Control Plane Component Rendering Error'}
            </h3>
          </div>

          <p style={{ fontSize: '0.875rem', color: 'var(--text-muted)', lineHeight: 1.5, marginBottom: '1rem' }}>
            A client-side error occurred while rendering this interface. Chronos Core backend services continue operating normally.
          </p>

          {this.state.error && (
            <pre
              className="font-mono"
              style={{
                fontSize: '0.75rem',
                padding: '0.75rem',
                borderRadius: '0.375rem',
                backgroundColor: 'var(--bg-elevated)',
                border: '1px solid var(--border-default)',
                color: 'var(--accent-rose)',
                overflowX: 'auto',
                marginBottom: '1.25rem',
              }}
            >
              {this.state.error.message}
            </pre>
          )}

          <div style={{ display: 'flex', gap: '0.75rem' }}>
            <button className="btn btn-secondary btn-sm" onClick={this.handleReset}>
              <RefreshCw size={13} />
              <span>Retry Rendering</span>
            </button>
            <a href="/" className="btn btn-primary btn-sm">
              <Home size={13} />
              <span>Back to Overview</span>
            </a>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}
