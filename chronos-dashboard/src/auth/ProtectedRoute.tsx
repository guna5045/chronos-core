import React from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from './AuthContext';
import { ShieldAlert } from 'lucide-react';

interface ProtectedRouteProps {
  children: React.ReactNode;
  requiredRole?: 'ADMIN' | 'OPERATOR' | 'VIEWER';
}

export const ProtectedRoute: React.FC<ProtectedRouteProps> = ({ children, requiredRole }) => {
  const { isAuthenticated, isAdmin, isOperator } = useAuth();
  const location = useLocation();

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  if (requiredRole === 'ADMIN' && !isAdmin) {
    return (
      <div style={{ padding: '2rem' }}>
        <div
          style={{
            backgroundColor: 'rgba(244, 63, 94, 0.1)',
            border: '1px solid rgba(244, 63, 94, 0.3)',
            borderRadius: '0.5rem',
            padding: '1.5rem',
            display: 'flex',
            alignItems: 'center',
            gap: '1rem',
          }}
        >
          <ShieldAlert size={28} color="#f43f5e" />
          <div>
            <h3 style={{ fontSize: '1rem', fontWeight: 600, color: '#fda4af' }}>Access Restricted</h3>
            <p style={{ fontSize: '0.85rem', color: '#94a3b8', marginTop: '0.25rem' }}>
              This operational resource requires administrative privileges (<code className="font-mono">ADMIN</code>).
            </p>
          </div>
        </div>
      </div>
    );
  }

  if (requiredRole === 'OPERATOR' && !isOperator) {
    return (
      <div style={{ padding: '2rem' }}>
        <div
          style={{
            backgroundColor: 'rgba(244, 63, 94, 0.1)',
            border: '1px solid rgba(244, 63, 94, 0.3)',
            borderRadius: '0.5rem',
            padding: '1.5rem',
            display: 'flex',
            alignItems: 'center',
            gap: '1rem',
          }}
        >
          <ShieldAlert size={28} color="#f43f5e" />
          <div>
            <h3 style={{ fontSize: '1rem', fontWeight: 600, color: '#fda4af' }}>Access Restricted</h3>
            <p style={{ fontSize: '0.85rem', color: '#94a3b8', marginTop: '0.25rem' }}>
              This action requires operator privileges (<code className="font-mono">OPERATOR</code>).
            </p>
          </div>
        </div>
      </div>
    );
  }

  return <>{children}</>;
};
