import React, { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { requestDevToken } from '../api/auth';
import { Activity, AlertCircle, Key, ShieldCheck, User } from 'lucide-react';
import { ApiClientError } from '../api/client';

export const LoginPage: React.FC = () => {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: { pathname: string } })?.from?.pathname || '/';

  const [subject, setSubject] = useState('operator-alpha');
  const [selectedRole, setSelectedRole] = useState<'ADMIN' | 'OPERATOR' | 'VIEWER'>('OPERATOR');
  const [tenantId, setTenantId] = useState('tenant-alpha');
  const [customToken, setCustomToken] = useState('');
  const [isManualToken, setIsManualToken] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const handleDevLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsLoading(true);
    setErrorMessage(null);

    try {
      const response = await requestDevToken({
        subject: subject.trim(),
        roles: [selectedRole],
        tenantId: tenantId.trim(),
      });

      login(response.token, subject.trim(), [selectedRole], tenantId.trim());
      navigate(from, { replace: true });
    } catch (err) {
      if (err instanceof ApiClientError) {
        setErrorMessage(`${err.code}: ${err.message}`);
      } else if (err instanceof Error) {
        setErrorMessage(err.message);
      } else {
        setErrorMessage('Failed to connect to authentication gateway.');
      }
    } finally {
      setIsLoading(false);
    }
  };

  const handleManualLogin = (e: React.FormEvent) => {
    e.preventDefault();
    if (!customToken.trim()) return;

    // Use token directly with default profile
    login(customToken.trim(), subject.trim(), [selectedRole], tenantId.trim());
    navigate(from, { replace: true });
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: 'var(--bg-canvas)',
        padding: '1.5rem',
      }}
    >
      <div
        style={{
          width: '100%',
          maxWidth: '460px',
          backgroundColor: 'var(--bg-surface)',
          border: '1px solid var(--border-default)',
          borderRadius: '0.75rem',
          padding: '2rem',
          boxShadow: '0 25px 50px -12px rgba(0, 0, 0, 0.6)',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', marginBottom: '1.5rem' }}>
          <div
            style={{
              width: '38px',
              height: '38px',
              borderRadius: '8px',
              backgroundColor: 'rgba(2, 132, 199, 0.15)',
              border: '1px solid rgba(56, 189, 248, 0.4)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: 'var(--accent-cyan)',
            }}
          >
            <Activity size={22} />
          </div>
          <div>
            <h2 style={{ fontSize: '1.25rem', fontWeight: 700, color: 'var(--text-main)', letterSpacing: '0.02em' }}>
              CHRONOS CORE
            </h2>
            <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
              Engineering Control Plane Gateway
            </p>
          </div>
        </div>

        {errorMessage && (
          <div
            style={{
              backgroundColor: 'rgba(244, 63, 94, 0.1)',
              border: '1px solid rgba(244, 63, 94, 0.3)',
              borderRadius: '0.375rem',
              padding: '0.75rem 1rem',
              display: 'flex',
              alignItems: 'center',
              gap: '0.65rem',
              fontSize: '0.8125rem',
              color: '#fda4af',
              marginBottom: '1.25rem',
            }}
          >
            <AlertCircle size={16} />
            <span>{errorMessage}</span>
          </div>
        )}

        <div style={{ display: 'flex', borderBottom: '1px solid var(--border-subtle)', marginBottom: '1.25rem' }}>
          <button
            type="button"
            onClick={() => setIsManualToken(false)}
            style={{
              flex: 1,
              padding: '0.65rem',
              background: 'none',
              border: 'none',
              borderBottom: !isManualToken ? '2px solid var(--accent-cyan)' : '2px solid transparent',
              color: !isManualToken ? 'var(--text-main)' : 'var(--text-muted)',
              fontWeight: 500,
              fontSize: '0.8125rem',
              cursor: 'pointer',
            }}
          >
            Dev Profile Auth
          </button>
          <button
            type="button"
            onClick={() => setIsManualToken(true)}
            style={{
              flex: 1,
              padding: '0.65rem',
              background: 'none',
              border: 'none',
              borderBottom: isManualToken ? '2px solid var(--accent-cyan)' : '2px solid transparent',
              color: isManualToken ? 'var(--text-main)' : 'var(--text-muted)',
              fontWeight: 500,
              fontSize: '0.8125rem',
              cursor: 'pointer',
            }}
          >
            Custom Bearer Token
          </button>
        </div>

        {!isManualToken ? (
          <form onSubmit={handleDevLogin} style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            <div>
              <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 500, color: 'var(--text-muted)', marginBottom: '0.35rem' }}>
                Principal Subject
              </label>
              <div style={{ position: 'relative' }}>
                <input
                  type="text"
                  required
                  className="input-field"
                  style={{ paddingLeft: '2.25rem' }}
                  value={subject}
                  onChange={(e) => setSubject(e.target.value)}
                />
                <User size={16} color="var(--text-faint)" style={{ position: 'absolute', left: '0.75rem', top: '50%', transform: 'translateY(-50%)' }} />
              </div>
            </div>

            <div>
              <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 500, color: 'var(--text-muted)', marginBottom: '0.35rem' }}>
                Assigned RBAC Role
              </label>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '0.5rem' }}>
                {(['VIEWER', 'OPERATOR', 'ADMIN'] as const).map((r) => (
                  <button
                    key={r}
                    type="button"
                    onClick={() => setSelectedRole(r)}
                    style={{
                      padding: '0.5rem',
                      borderRadius: '0.375rem',
                      border: selectedRole === r ? '1px solid var(--accent-cyan)' : '1px solid var(--border-default)',
                      backgroundColor: selectedRole === r ? 'rgba(56, 189, 248, 0.12)' : 'var(--bg-elevated)',
                      color: selectedRole === r ? 'var(--accent-cyan)' : 'var(--text-muted)',
                      fontSize: '0.75rem',
                      fontWeight: 600,
                      cursor: 'pointer',
                      fontFamily: 'var(--font-mono)',
                    }}
                  >
                    {r}
                  </button>
                ))}
              </div>
            </div>

            <div>
              <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 500, color: 'var(--text-muted)', marginBottom: '0.35rem' }}>
                Tenant Context
              </label>
              <input
                type="text"
                required
                className="input-field font-mono"
                value={tenantId}
                onChange={(e) => setTenantId(e.target.value)}
                placeholder="e.g. tenant-alpha"
              />
            </div>

            <button
              type="submit"
              className="btn btn-primary"
              disabled={isLoading}
              style={{ marginTop: '0.5rem', width: '100%', padding: '0.65rem' }}
            >
              <ShieldCheck size={16} />
              <span>{isLoading ? 'Authenticating...' : 'Authenticate & Enter Console'}</span>
            </button>
          </form>
        ) : (
          <form onSubmit={handleManualLogin} style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            <div>
              <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 500, color: 'var(--text-muted)', marginBottom: '0.35rem' }}>
                Raw Bearer JWT Token
              </label>
              <textarea
                required
                rows={4}
                className="input-field font-mono"
                style={{ resize: 'vertical', fontSize: '0.75rem' }}
                placeholder="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                value={customToken}
                onChange={(e) => setCustomToken(e.target.value)}
              />
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.75rem' }}>
              <div>
                <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 500, color: 'var(--text-muted)', marginBottom: '0.35rem' }}>
                  Tenant Claim Override
                </label>
                <input
                  type="text"
                  className="input-field font-mono"
                  value={tenantId}
                  onChange={(e) => setTenantId(e.target.value)}
                />
              </div>
              <div>
                <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 500, color: 'var(--text-muted)', marginBottom: '0.35rem' }}>
                  Role Override
                </label>
                <select
                  className="select-field"
                  style={{ width: '100%' }}
                  value={selectedRole}
                  onChange={(e) => setSelectedRole(e.target.value as 'ADMIN' | 'OPERATOR' | 'VIEWER')}
                >
                  <option value="ADMIN">ADMIN</option>
                  <option value="OPERATOR">OPERATOR</option>
                  <option value="VIEWER">VIEWER</option>
                </select>
              </div>
            </div>

            <button
              type="submit"
              className="btn btn-primary"
              style={{ marginTop: '0.5rem', width: '100%', padding: '0.65rem' }}
            >
              <Key size={16} />
              <span>Use Bearer Token</span>
            </button>
          </form>
        )}

        <div
          style={{
            marginTop: '1.5rem',
            paddingTop: '1rem',
            borderTop: '1px solid var(--border-subtle)',
            fontSize: '0.75rem',
            color: 'var(--text-faint)',
            textAlign: 'center',
          }}
        >
          Secured by Chronos Core OAuth2 Resource Server &amp; RBAC
        </div>
      </div>
    </div>
  );
};
