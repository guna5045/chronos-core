import React, { useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import {
  Activity,
  CheckCircle2,
  Cpu,
  GitBranch,
  Layers,
  ListTodo,
  LogOut,
  Plus,
  Shield,
  Sliders,
  Terminal,
} from 'lucide-react';
import { SubmitTaskModal } from '../tasks/SubmitTaskModal';

export const AppShell: React.FC = () => {
  const { user, logout, canMutateTasks } = useAuth();
  const navigate = useNavigate();
  const [isSubmitModalOpen, setIsSubmitModalOpen] = useState(false);

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const navItems = [
    { label: 'Overview', to: '/', icon: <Activity size={18} /> },
    { label: 'Tasks', to: '/tasks', icon: <ListTodo size={18} /> },
    { label: 'Workers', to: '/workers', icon: <Cpu size={18} /> },
    { label: 'Workflows', to: '/workflows', icon: <GitBranch size={18} /> },
    { label: 'Dead-Letter Queue', to: '/dlq', icon: <Layers size={18} /> },
    { label: 'Cluster', to: '/cluster', icon: <Terminal size={18} /> },
    {
      label: 'Tenant Policy',
      to: `/tenants/${user?.tenantId || 'tenant-alpha'}/policy`,
      icon: <Sliders size={18} />,
    },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: '100vh', backgroundColor: 'var(--bg-canvas)' }}>
      {/* Top Navigation Header */}
      <header
        style={{
          height: '60px',
          backgroundColor: 'var(--bg-surface)',
          borderBottom: '1px solid var(--border-subtle)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '0 1.5rem',
          position: 'sticky',
          top: 0,
          zIndex: 100,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.65rem' }}>
            <div
              style={{
                width: '32px',
                height: '32px',
                borderRadius: '6px',
                backgroundColor: 'rgba(2, 132, 199, 0.15)',
                border: '1px solid rgba(56, 189, 248, 0.4)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: 'var(--accent-cyan)',
              }}
            >
              <Activity size={18} />
            </div>
            <div>
              <span style={{ fontSize: '0.95rem', fontWeight: 700, letterSpacing: '0.02em', color: 'var(--text-main)' }}>
                CHRONOS CORE
              </span>
              <span
                style={{
                  fontSize: '0.65rem',
                  fontFamily: 'var(--font-mono)',
                  color: 'var(--accent-cyan)',
                  marginLeft: '0.5rem',
                  padding: '0.1rem 0.35rem',
                  borderRadius: '3px',
                  backgroundColor: 'rgba(56, 189, 248, 0.1)',
                }}
              >
                CONTROL PLANE
              </span>
            </div>
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
          {canMutateTasks && (
            <button
              className="btn btn-primary btn-sm"
              onClick={() => setIsSubmitModalOpen(true)}
            >
              <Plus size={14} />
              <span>Submit Task</span>
            </button>
          )}

          {/* Tenant Badge */}
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '0.4rem',
              padding: '0.25rem 0.65rem',
              borderRadius: '0.375rem',
              backgroundColor: 'var(--bg-elevated)',
              border: '1px solid var(--border-default)',
              fontSize: '0.75rem',
            }}
          >
            <span style={{ color: 'var(--text-muted)' }}>Tenant:</span>
            <span className="font-mono" style={{ fontWeight: 600, color: 'var(--accent-cyan)' }}>
              {user?.tenantId || 'unscoped'}
            </span>
          </div>

          {/* User Profile & Role */}
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '0.5rem',
              padding: '0.25rem 0.65rem',
              borderRadius: '0.375rem',
              backgroundColor: 'var(--bg-elevated)',
              border: '1px solid var(--border-default)',
              fontSize: '0.75rem',
            }}
          >
            <Shield size={14} color="var(--accent-amber)" />
            <span style={{ color: 'var(--text-main)', fontWeight: 500 }}>{user?.subject}</span>
            <span
              className="font-mono"
              style={{
                fontSize: '0.65rem',
                padding: '0.1rem 0.35rem',
                borderRadius: '3px',
                backgroundColor: 'rgba(245, 158, 11, 0.15)',
                color: 'var(--accent-amber)',
              }}
            >
              {user?.roles?.[0] || 'VIEWER'}
            </span>
          </div>

          <button
            onClick={handleLogout}
            title="Log out"
            style={{
              background: 'none',
              border: 'none',
              color: 'var(--text-muted)',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              padding: '0.35rem',
              borderRadius: '0.25rem',
            }}
          >
            <LogOut size={16} />
          </button>
        </div>
      </header>

      {/* Main Layout Container (Sidebar + Content) */}
      <div style={{ display: 'flex', flex: 1 }}>
        {/* Sidebar */}
        <aside
          style={{
            width: '230px',
            backgroundColor: 'var(--bg-surface)',
            borderRight: '1px solid var(--border-subtle)',
            display: 'flex',
            flexDirection: 'column',
            justifyContent: 'space-between',
            padding: '1.25rem 0.75rem',
            flexShrink: 0,
          }}
        >
          <nav style={{ display: 'flex', flexDirection: 'column', gap: '0.25rem' }}>
            <span
              style={{
                fontSize: '0.65rem',
                textTransform: 'uppercase',
                letterSpacing: '0.06em',
                fontWeight: 600,
                color: 'var(--text-faint)',
                padding: '0 0.75rem 0.5rem',
              }}
            >
              Orchestration
            </span>
            {navItems.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                style={({ isActive }) => ({
                  display: 'flex',
                  alignItems: 'center',
                  gap: '0.75rem',
                  padding: '0.55rem 0.75rem',
                  borderRadius: '0.375rem',
                  fontSize: '0.8125rem',
                  fontWeight: 500,
                  color: isActive ? '#ffffff' : 'var(--text-muted)',
                  backgroundColor: isActive ? 'var(--bg-active)' : 'transparent',
                  border: isActive ? '1px solid var(--border-default)' : '1px solid transparent',
                  textDecoration: 'none',
                  transition: 'all 0.15s ease',
                })}
              >
                {item.icon}
                <span>{item.label}</span>
              </NavLink>
            ))}
          </nav>

          <div
            style={{
              padding: '0.85rem',
              borderRadius: '0.375rem',
              backgroundColor: 'var(--bg-elevated)',
              border: '1px solid var(--border-subtle)',
              fontSize: '0.7rem',
              color: 'var(--text-muted)',
              display: 'flex',
              flexDirection: 'column',
              gap: '0.35rem',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.4rem', color: 'var(--accent-emerald)' }}>
              <CheckCircle2 size={12} />
              <span style={{ fontWeight: 600 }}>API CONNECTED</span>
            </div>
            <div>Namespace: <code className="font-mono">/api/v1</code></div>
          </div>
        </aside>

        {/* Content Area */}
        <main style={{ flex: 1, padding: '1.75rem 2rem', overflowY: 'auto', minWidth: 0 }}>
          <Outlet />
        </main>
      </div>

      {/* Task Submission Modal */}
      {isSubmitModalOpen && (
        <SubmitTaskModal
          isOpen={isSubmitModalOpen}
          onClose={() => setIsSubmitModalOpen(false)}
        />
      )}
    </div>
  );
};
