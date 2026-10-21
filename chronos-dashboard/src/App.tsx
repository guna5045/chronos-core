import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from './auth/AuthContext';
import { ProtectedRoute } from './auth/ProtectedRoute';
import { AppShell } from './components/layout/AppShell';

// Pages
import { LoginPage } from './pages/LoginPage';
import { DashboardPage } from './pages/DashboardPage';
import { TasksPage } from './pages/TasksPage';
import { TaskDetailPage } from './pages/TaskDetailPage';
import { WorkersPage } from './pages/WorkersPage';
import { WorkerDetailPage } from './pages/WorkerDetailPage';
import { WorkflowsPage } from './pages/WorkflowsPage';
import { WorkflowDetailPage } from './pages/WorkflowDetailPage';
import { WorkflowExecutionPage } from './pages/WorkflowExecutionPage';
import { DlqPage } from './pages/DlqPage';
import { ClusterPage } from './pages/ClusterPage';
import { TenantPolicyPage } from './pages/TenantPolicyPage';
import { AiWorkflowsPage } from './pages/AiWorkflowsPage';
import { AiOperationsPage } from './pages/AiOperationsPage';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
      staleTime: 4000,
    },
  },
});

export const App: React.FC = () => {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <Routes>
            {/* Public Auth Gateway */}
            <Route path="/login" element={<LoginPage />} />

            {/* Protected Control Plane Routes */}
            <Route
              path="/"
              element={
                <ProtectedRoute>
                  <AppShell />
                </ProtectedRoute>
              }
            >
              <Route index element={<DashboardPage />} />
              <Route path="tasks" element={<TasksPage />} />
              <Route path="tasks/:taskId" element={<TaskDetailPage />} />
              <Route path="workers" element={<WorkersPage />} />
              <Route path="workers/:workerId" element={<WorkerDetailPage />} />
              <Route path="workflows" element={<WorkflowsPage />} />
              <Route path="workflows/:workflowId" element={<WorkflowDetailPage />} />
              <Route
                path="workflow-executions/:executionId"
                element={<WorkflowExecutionPage />}
              />
              <Route path="dlq" element={<DlqPage />} />
              <Route path="cluster" element={<ClusterPage />} />
              <Route path="tenants/:tenantId/policy" element={<TenantPolicyPage />} />
              <Route
                path="tenants"
                element={<Navigate to="/tenants/tenant-alpha/policy" replace />}
              />
              <Route path="ai/workflows" element={<AiWorkflowsPage />} />
              <Route path="ai/operations" element={<AiOperationsPage />} />
            </Route>

            {/* Catch-all Fallback */}
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  );
};

export default App;
