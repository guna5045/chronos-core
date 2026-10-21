import React, { createContext, useContext, useEffect, useState } from 'react';

export interface AuthUser {
  subject: string;
  roles: string[];
  tenantId: string;
  token: string;
}

interface AuthContextType {
  user: AuthUser | null;
  isAuthenticated: boolean;
  isAdmin: boolean;
  isOperator: boolean;
  isViewer: boolean;
  canMutateTasks: boolean;
  canManagePolicies: boolean;
  canManageWorkflowVersions: boolean;
  login: (token: string, subject: string, roles: string[], tenantId: string) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<AuthUser | null>(() => {
    const savedToken = localStorage.getItem('chronos_auth_token');
    const savedUser = localStorage.getItem('chronos_auth_user');
    if (savedToken && savedUser) {
      try {
        const parsed = JSON.parse(savedUser);
        return { ...parsed, token: savedToken };
      } catch {
        localStorage.removeItem('chronos_auth_token');
        localStorage.removeItem('chronos_auth_user');
      }
    }
    return null;
  });

  const login = (token: string, subject: string, roles: string[], tenantId: string) => {
    const authUser: AuthUser = { subject, roles, tenantId, token };
    localStorage.setItem('chronos_auth_token', token);
    localStorage.setItem('chronos_auth_user', JSON.stringify({ subject, roles, tenantId }));
    setUser(authUser);
  };

  const logout = () => {
    localStorage.removeItem('chronos_auth_token');
    localStorage.removeItem('chronos_auth_user');
    setUser(null);
  };

  useEffect(() => {
    const handleUnauthorized = () => {
      logout();
    };
    window.addEventListener('chronos:unauthorized', handleUnauthorized);
    return () => {
      window.removeEventListener('chronos:unauthorized', handleUnauthorized);
    };
  }, []);

  const roles = user?.roles || [];
  const isAdmin = roles.includes('ADMIN') || roles.includes('ROLE_ADMIN');
  const isOperator = roles.includes('OPERATOR') || roles.includes('ROLE_OPERATOR') || isAdmin;
  const isViewer = roles.includes('VIEWER') || roles.includes('ROLE_VIEWER') || isOperator;

  return (
    <AuthContext.Provider
      value={{
        user,
        isAuthenticated: !!user,
        isAdmin,
        isOperator,
        isViewer,
        canMutateTasks: isOperator,
        canManagePolicies: isAdmin,
        canManageWorkflowVersions: isAdmin,
        login,
        logout,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export function useAuth(): AuthContextType {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
