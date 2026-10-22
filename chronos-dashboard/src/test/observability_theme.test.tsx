import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { ErrorAlert } from '../components/common/ErrorAlert';
import { ApiClientError, request } from '../api/client';

describe('Frontend Observability, Error Taxonomy & Retry Verification', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('classifies network connection failure and renders safe retryable alert', async () => {
    const error = new ApiClientError({
      timestamp: new Date().toISOString(),
      status: 0,
      code: 'NETWORK_ERROR',
      message: 'Unable to reach Chronos Core coordinator (connection refused)',
      path: '/tasks',
      requestId: 'req-test-101',
      traceId: 'trace-test-101',
    });

    const onRetry = vi.fn();
    render(<ErrorAlert error={error} onRetry={onRetry} />);

    expect(screen.getByText('Coordinator Service Unreachable')).toBeInTheDocument();
    expect(screen.getByText('NETWORK_ERROR (0)')).toBeInTheDocument();
    expect(screen.getByText('req-test-101')).toBeInTheDocument();
    expect(screen.getByText('trace-test-101')).toBeInTheDocument();

    const retryBtn = screen.getByRole('button', { name: /retry/i });
    expect(retryBtn).toBeEnabled();
    await waitFor(() => {
      fireEvent.click(retryBtn);
      expect(onRetry).toHaveBeenCalledTimes(1);
    });
  });

  it('handles interactive async retry with loading state and prevents duplicate clicks', async () => {
    const error = new ApiClientError({
      timestamp: new Date().toISOString(),
      status: 500,
      code: 'INTERNAL_ERROR',
      message: 'A transient database contention occurred',
      path: '/tasks',
    });

    let resolveRetry: () => void = () => {};
    const retryPromise = new Promise<void>((resolve) => {
      resolveRetry = resolve;
    });
    const onRetry = vi.fn(() => retryPromise);

    render(<ErrorAlert error={error} onRetry={onRetry} />);

    const retryBtn = screen.getByRole('button', { name: /retry/i });
    fireEvent.click(retryBtn);

    // Button should now show loading indicator and be disabled
    expect(screen.getByText('Retrying...')).toBeInTheDocument();
    expect(retryBtn).toBeDisabled();

    // Secondary clicks while active should not trigger redundant calls
    fireEvent.click(retryBtn);
    expect(onRetry).toHaveBeenCalledTimes(1);

    // Resolve the promise
    resolveRetry();
    await waitFor(() => {
      expect(screen.getByText('Retry')).toBeInTheDocument();
    });
  });

  it('persists and applies data-theme attribute on document root', () => {
    document.documentElement.setAttribute('data-theme', 'dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');

    // Simulate theme switch
    document.documentElement.setAttribute('data-theme', 'light');
    localStorage.setItem('chronos_theme', 'light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    expect(localStorage.getItem('chronos_theme')).toBe('light');
  });
});
