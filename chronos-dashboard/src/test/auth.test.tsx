import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { AuthProvider, useAuth } from '../auth/AuthContext';
import { ProtectedRoute } from '../auth/ProtectedRoute';
import { request, ApiClientError } from '../api/client';

const TestAuthConsumer: React.FC = () => {
  const { user, isAuthenticated, isAdmin, isOperator, isViewer, login, logout } = useAuth();
  return (
    <div>
      <div data-testid="auth-status">{isAuthenticated ? 'AUTHENTICATED' : 'ANONYMOUS'}</div>
      <div data-testid="user-subject">{user?.subject || 'NONE'}</div>
      <div data-testid="user-tenant">{user?.tenantId || 'NONE'}</div>
      <div data-testid="is-admin">{isAdmin ? 'YES' : 'NO'}</div>
      <div data-testid="is-operator">{isOperator ? 'YES' : 'NO'}</div>
      <div data-testid="is-viewer">{isViewer ? 'YES' : 'NO'}</div>
      <button
        onClick={() => login('mock.jwt.token', 'test-user', ['OPERATOR'], 'tenant-beta')}
        data-testid="login-btn"
      >
        Login
      </button>
      <button onClick={() => logout()} data-testid="logout-btn">
        Logout
      </button>
    </div>
  );
};

describe('Frontend Authentication & Context', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('initializes in anonymous state when localStorage is empty', () => {
    render(
      <AuthProvider>
        <TestAuthConsumer />
      </AuthProvider>
    );

    expect(screen.getByTestId('auth-status').textContent).toBe('ANONYMOUS');
    expect(screen.getByTestId('user-subject').textContent).toBe('NONE');
    expect(screen.getByTestId('is-admin').textContent).toBe('NO');
  });

  it('updates state and persists token upon login', () => {
    render(
      <AuthProvider>
        <TestAuthConsumer />
      </AuthProvider>
    );

    act(() => {
      screen.getByTestId('login-btn').click();
    });

    expect(screen.getByTestId('auth-status').textContent).toBe('AUTHENTICATED');
    expect(screen.getByTestId('user-subject').textContent).toBe('test-user');
    expect(screen.getByTestId('user-tenant').textContent).toBe('tenant-beta');
    expect(screen.getByTestId('is-operator').textContent).toBe('YES');
    expect(screen.getByTestId('is-admin').textContent).toBe('NO');
    expect(localStorage.getItem('chronos_auth_token')).toBe('mock.jwt.token');
  });

  it('clears state on logout', () => {
    render(
      <AuthProvider>
        <TestAuthConsumer />
      </AuthProvider>
    );

    act(() => {
      screen.getByTestId('login-btn').click();
    });
    expect(screen.getByTestId('auth-status').textContent).toBe('AUTHENTICATED');

    act(() => {
      screen.getByTestId('logout-btn').click();
    });
    expect(screen.getByTestId('auth-status').textContent).toBe('ANONYMOUS');
    expect(localStorage.getItem('chronos_auth_token')).toBeNull();
  });

  it('attaches Bearer token in request() helper', async () => {
    localStorage.setItem('chronos_auth_token', 'sample.jwt.header.body.sig');

    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ isLeader: true }),
    });
    global.fetch = mockFetch;

    await request('/cluster');

    expect(mockFetch).toHaveBeenCalledTimes(1);
    const calledHeaders = mockFetch.mock.calls[0][1].headers as Headers;
    expect(calledHeaders.get('Authorization')).toBe('Bearer sample.jwt.header.body.sig');
  });

  it('dispatches chronos:unauthorized event and throws ApiClientError on 401 response', async () => {
    const unauthorizedListener = vi.fn();
    window.addEventListener('chronos:unauthorized', unauthorizedListener);

    const mockFetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      statusText: 'Unauthorized',
      json: async () => ({
        timestamp: '2026-10-21T18:00:00Z',
        status: 401,
        code: 'UNAUTHORIZED',
        message: 'JWT expired or missing',
        path: '/tasks',
      }),
    });
    global.fetch = mockFetch;

    await expect(request('/tasks')).rejects.toThrow(ApiClientError);
    expect(unauthorizedListener).toHaveBeenCalledTimes(1);

    window.removeEventListener('chronos:unauthorized', unauthorizedListener);
  });

  it('redirects unauthenticated users to /login in ProtectedRoute', () => {
    render(
      <AuthProvider>
        <MemoryRouter initialEntries={['/protected']}>
          <Routes>
            <Route
              path="/protected"
              element={
                <ProtectedRoute>
                  <div data-testid="protected-content">Secret Content</div>
                </ProtectedRoute>
              }
            />
            <Route path="/login" element={<div data-testid="login-page">Login Page</div>} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    );

    expect(screen.queryByTestId('protected-content')).toBeNull();
    expect(screen.getByTestId('login-page')).toBeInTheDocument();
  });

  it('blocks non-admin users when ADMIN role is required in ProtectedRoute', () => {
    localStorage.setItem('chronos_auth_token', 'mock.token');
    localStorage.setItem(
      'chronos_auth_user',
      JSON.stringify({ subject: 'operator-1', roles: ['OPERATOR'], tenantId: 'tenant-alpha' })
    );

    render(
      <AuthProvider>
        <MemoryRouter initialEntries={['/admin-only']}>
          <Routes>
            <Route
              path="/admin-only"
              element={
                <ProtectedRoute requiredRole="ADMIN">
                  <div data-testid="admin-content">Admin Settings</div>
                </ProtectedRoute>
              }
            />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    );

    expect(screen.queryByTestId('admin-content')).toBeNull();
    expect(screen.getByText('Access Restricted')).toBeInTheDocument();
  });
});
