import React, { useState, useEffect } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import {
  Activity,
  Bot,
  CheckCircle2,
  Cpu,
  GitBranch,
  Layers,
  ListTodo,
  LogOut,
  Moon,
  Plus,
  Radio,
  Shield,
  ShieldAlert,
  Sliders,
  Sun,
  Terminal,
} from 'lucide-react';
import { SubmitTaskModal } from '../tasks/SubmitTaskModal';

export const AppShell: React.FC = () => {
  const { user, logout, canMutateTasks } = useAuth();
  const navigate = useNavigate();
  const [isSubmitModalOpen, setIsSubmitModalOpen] = useState(false);

  // Theme management: persisted in localStorage, defaults to dark
  const [theme, setTheme] = useState<'dark' | 'light'>(() => {
    const saved = localStorage.getItem('chronos_theme');
    if (saved === 'light' || saved === 'dark') return saved;
    return 'dark';
  });

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('chronos_theme', theme);
  }, [theme]);

  const toggleTheme = () => {
    setTheme((prev) => (prev === 'dark' ? 'light' : 'dark'));
  };

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const operationsNav = [
    { label: 'Overview', to: '/', icon: <Activity size={17} /> },
    { label: 'Tasks', to: '/tasks', icon: <ListTodo size={17} /> },
    { label: 'Workers', to: '/workers', icon: <Cpu size={17} /> },
    { label: 'Workflows', to: '/workflows', icon: <GitBranch size={17} /> },
    { label: 'Dead-Letter Queue', to: '/dlq', icon: <Layers size={17} /> },
  ];

  const clusterPolicyNav = [
    { label: 'Cluster Coordination', to: '/cluster', icon: <Terminal size={17} /> },
    {
      label: 'Tenant Policy',
      to: `/tenants/${user?.tenantId || 'tenant-alpha'}/policy`,
      icon: <Sliders size={17} />,
    },
  ];

  const observabilityNav = [
    { label: 'Observability & Metrics', to: '/observability', icon: <Radio size={17} /> },
  ];

  const aiNavItems = [
    { label: 'Workflow Agent', to: '/ai/workflows', icon: <Bot size={17} /> },
    { label: 'Operations Agent', to: '/ai/operations', icon: <ShieldAlert size={17} /> },
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

        <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
          {canMutateTasks && (
            <button
              className="btn btn-primary btn-sm"
              onClick={() => setIsSubmitModalOpen(true)}
            >
              <Plus size={14} />
              <span>Submit Task</span>
            </button>
          )}

          {/* Theme Toggle Button */}
          <button
            onClick={toggleTheme}
            className="btn btn-secondary btn-sm"
            title={`Switch to ${theme === 'dark' ? 'Light' : 'Dark'} Mode`}
            style={{ padding: '0.35rem 0.65rem' }}
          >
            {theme === 'dark' ? <Sun size={15} color="var(--accent-amber)" /> : <Moon size={15} color="var(--accent-blue)" />}
            <span style={{ fontSize: '0.75rem' }}>{theme === 'dark' ? 'Light' : 'Dark'}</span>
          </button>

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
            width: '240px',
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
            {/* Section 1: Operations */}
            <span
              style={{
                fontSize: '0.65rem',
                textTransform: 'uppercase',
                letterSpacing: '0.06em',
                fontWeight: 700,
                color: 'var(--text-faint)',
                padding: '0 0.75rem 0.4rem',
              }}
            >
              Operations
            </span>
            {operationsNav.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.to === '/'}
                style={({ isActive }) => ({
                  display: 'flex',
                  alignItems: 'center',
                  gap: '0.75rem',
                  padding: '0.5rem 0.75rem',
                  borderRadius: '0.375rem',
                  fontSize: '0.8125rem',
                  fontWeight: 500,
                  color: isActive ? 'var(--text-main)' : 'var(--text-muted)',
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

            {/* Section 2: Cluster & Policy */}
            <span
              style={{
                fontSize: '0.65rem',
                textTransform: 'uppercase',
                letterSpacing: '0.06em',
                fontWeight: 700,
                color: 'var(--text-faint)',
                padding: '0.85rem 0.75rem 0.4rem',
              }}
            >
              Cluster & Policy
            </span>
            {clusterPolicyNav.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                style={({ isActive }) => ({
                  display: 'flex',
                  alignItems: 'center',
                  gap: '0.75rem',
                  padding: '0.5rem 0.75rem',
                  borderRadius: '0.375rem',
                  fontSize: '0.8125rem',
                  fontWeight: 500,
                  color: isActive ? 'var(--text-main)' : 'var(--text-muted)',
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

            {/* Section 3: Observability */}
            <span
              style={{
                fontSize: '0.65rem',
                textTransform: 'uppercase',
                letterSpacing: '0.06em',
                fontWeight: 700,
                color: 'var(--text-faint)',
                padding: '0.85rem 0.75rem 0.4rem',
              }}
            >
              Observability
            </span>
            {observabilityNav.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                style={({ isActive }) => ({
                  display: 'flex',
                  alignItems: 'center',
                  gap: '0.75rem',
                  padding: '0.5rem 0.75rem',
                  borderRadius: '0.375rem',
                  fontSize: '0.8125rem',
                  fontWeight: 500,
                  color: isActive ? 'var(--text-main)' : 'var(--text-muted)',
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

            {/* Section 4: AI Assistants */}
            <span
              style={{
                fontSize: '0.65rem',
                textTransform: 'uppercase',
                letterSpacing: '0.06em',
                fontWeight: 700,
                color: 'var(--text-faint)',
                padding: '0.85rem 0.75rem 0.4rem',
              }}
            >
              AI Assistants
            </span>
            {aiNavItems.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                style={({ isActive }) => ({
                  display: 'flex',
                  alignItems: 'center',
                  gap: '0.75rem',
                  padding: '0.5rem 0.75rem',
                  borderRadius: '0.375rem',
                  fontSize: '0.8125rem',
                  fontWeight: 500,
                  color: isActive ? 'var(--text-main)' : 'var(--text-muted)',
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
              <span style={{ fontWeight: 600 }}>CONTROL PLANE READY</span>
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
