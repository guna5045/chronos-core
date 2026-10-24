import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { DocumentationPage } from '../pages/DocumentationPage';
import { ContextualDocDrawer } from '../components/docs/ContextualDocDrawer';
import { LearnAboutPageButton } from '../components/docs/LearnAboutPageButton';
import { TaskLifecycleVisualizer } from '../components/docs/visualizers/TaskLifecycleVisualizer';
import { LeaderElectionVisualizer } from '../components/docs/visualizers/LeaderElectionVisualizer';
import { WorkerHeartbeatVisualizer } from '../components/docs/visualizers/WorkerHeartbeatVisualizer';
import { ConsistentHashVisualizer } from '../components/docs/visualizers/ConsistentHashVisualizer';
import { DagExecutionVisualizer } from '../components/docs/visualizers/DagExecutionVisualizer';
import { DlqLifecycleVisualizer } from '../components/docs/visualizers/DlqLifecycleVisualizer';
import { DispatchVisualizer } from '../components/docs/visualizers/DispatchVisualizer';
import { AiWorkflowVisualizer } from '../components/docs/visualizers/AiWorkflowVisualizer';
import { AiOperationsVisualizer } from '../components/docs/visualizers/AiOperationsVisualizer';
import { ObservabilityTraceVisualizer } from '../components/docs/visualizers/ObservabilityTraceVisualizer';
import { DOCUMENTATION_CONCEPTS, DOCUMENTATION_CATEGORIES } from '../docs/documentationData';
import { PAGE_DOCUMENTATION_MAPPINGS } from '../docs/pageMappings';

describe('Documentation & Contextual Learning System', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  describe('Core Data & Category Invariants', () => {
    it('contains all 10 canonical categories', () => {
      expect(DOCUMENTATION_CATEGORIES.length).toBe(10);
      const catIds = DOCUMENTATION_CATEGORIES.map((c) => c.id);
      expect(catIds).toContain('getting-started');
      expect(catIds).toContain('tasks');
      expect(catIds).toContain('workers');
      expect(catIds).toContain('coordination');
      expect(catIds).toContain('workflows');
      expect(catIds).toContain('data-reliability');
      expect(catIds).toContain('tenant-security');
      expect(catIds).toContain('observability');
      expect(catIds).toContain('ai-subsystem');
      expect(catIds).toContain('operations');
    });

    it('contains comprehensive documented concepts with required schema fields', () => {
      expect(DOCUMENTATION_CONCEPTS.length).toBeGreaterThanOrEqual(65);
      for (const concept of DOCUMENTATION_CONCEPTS) {
        expect(concept.id).toBeTruthy();
        expect(concept.title).toBeTruthy();
        expect(concept.category).toBeTruthy();
        expect(concept.summary).toBeTruthy();
        expect(concept.definition).toBeTruthy();
        expect(concept.purpose).toBeTruthy();
        expect(concept.chronosUsage).toBeTruthy();
        expect(concept.operationalSignificance).toBeTruthy();
        expect(Array.isArray(concept.relatedConceptIds)).toBe(true);
        expect(Array.isArray(concept.aliases)).toBe(true);
      }
    });

    it('maps all 10 major control plane pages to valid concepts', () => {
      const pageKeys = Object.keys(PAGE_DOCUMENTATION_MAPPINGS);
      expect(pageKeys).toHaveLength(10);
      expect(pageKeys).toEqual(
        expect.arrayContaining([
          'overview',
          'tasks',
          'workers',
          'workflows',
          'dlq',
          'cluster',
          'tenants',
          'observability',
          'ai-workflows',
          'ai-operations',
        ])
      );

      for (const [pageId, mapping] of Object.entries(PAGE_DOCUMENTATION_MAPPINGS)) {
        expect(mapping.pageTitle).toBeTruthy();
        expect(mapping.primaryConceptIds.length).toBeGreaterThanOrEqual(4);
        for (const conceptId of mapping.primaryConceptIds) {
          const found = DOCUMENTATION_CONCEPTS.find((c) => c.id === conceptId);
          expect(found, `Concept ${conceptId} on page ${pageId} must exist in catalog`).toBeDefined();
        }
      }
    });
  });

  describe('DocumentationPage Full Experience', () => {
    it('renders the Documentation page with title, subtitle, and search input', () => {
      render(
        <MemoryRouter initialEntries={['/documentation']}>
          <Routes>
            <Route path="/documentation" element={<DocumentationPage />} />
          </Routes>
        </MemoryRouter>
      );

      expect(screen.getByText('Chronos Core Documentation')).toBeInTheDocument();
      expect(
        screen.getByText(/professional, searchable reference for the concepts/i)
      ).toBeInTheDocument();
      expect(screen.getByRole('textbox', { name: /search documentation concepts/i })).toBeInTheDocument();
    });

    it('filters concepts accurately when searching by title or alias', async () => {
      render(
        <MemoryRouter initialEntries={['/documentation']}>
          <Routes>
            <Route path="/documentation" element={<DocumentationPage />} />
          </Routes>
        </MemoryRouter>
      );

      const searchInput = screen.getByRole('textbox', { name: /search documentation concepts/i });

      // Search for DLQ alias
      fireEvent.change(searchInput, { target: { value: 'DLQ' } });
      await waitFor(() => {
        expect(screen.getAllByText(/Dead-Letter Queue/i).length).toBeGreaterThan(0);
      });

      // Search for fencing
      fireEvent.change(searchInput, { target: { value: 'fencing' } });
      await waitFor(() => {
        expect(screen.getAllByText(/Fencing Token/i).length).toBeGreaterThan(0);
      });

      // Clear search
      fireEvent.change(searchInput, { target: { value: '' } });
    });

    it('navigates to selected concept and displays detailed technical sections', async () => {
      render(
        <MemoryRouter initialEntries={['/documentation']}>
          <Routes>
            <Route path="/documentation" element={<DocumentationPage />} />
          </Routes>
        </MemoryRouter>
      );

      // Search and select fencing token
      const searchInput = screen.getByRole('textbox', { name: /search documentation concepts/i });
      fireEvent.change(searchInput, { target: { value: 'fencing' } });

      const conceptButtons = await screen.findAllByRole('button', { name: /fencing token/i });
      fireEvent.click(conceptButtons[0]);

      await waitFor(() => {
        expect(screen.getByText('Definition')).toBeInTheDocument();
        expect(screen.getByText('Purpose & Design Rationale')).toBeInTheDocument();
        expect(screen.getByText('Chronos Core Implementation')).toBeInTheDocument();
        expect(screen.getByText('Operational Significance')).toBeInTheDocument();
        expect(screen.getByText(/Related Concepts/i)).toBeInTheDocument();
      });
    });

    it('supports deep-linking via query parameters ?concept=fencing-token', async () => {
      render(
        <MemoryRouter initialEntries={['/documentation?concept=fencing-token']}>
          <Routes>
            <Route path="/documentation" element={<DocumentationPage />} />
          </Routes>
        </MemoryRouter>
      );

      await waitFor(() => {
        const heading = screen.getByRole('heading', { level: 2, name: /fencing token/i });
        expect(heading).toBeInTheDocument();
      });
    });

    it('navigates through cross-linked related concepts on click', async () => {
      render(
        <MemoryRouter initialEntries={['/documentation?concept=fencing-token']}>
          <Routes>
            <Route path="/documentation" element={<DocumentationPage />} />
          </Routes>
        </MemoryRouter>
      );

      await waitFor(() => {
        expect(screen.getByRole('heading', { level: 2, name: /fencing token/i })).toBeInTheDocument();
      });

      const leaderElectionLinks = screen.getAllByRole('button', { name: /leader election/i });
      expect(leaderElectionLinks.length).toBeGreaterThan(0);
      // The button inside Related Concepts is the last rendered button matching the name
      fireEvent.click(leaderElectionLinks[leaderElectionLinks.length - 1]);

      await waitFor(() => {
        expect(screen.getByRole('heading', { level: 2, name: /leader election/i })).toBeInTheDocument();
      });
    });
  });

  describe('ContextualDocDrawer & LearnAboutPageButton', () => {
    it('renders "Learn About This Page" button', () => {
      render(
        <MemoryRouter>
          <LearnAboutPageButton pageId="cluster" />
        </MemoryRouter>
      );

      const btn = screen.getByRole('button', { name: /learn about this page/i });
      expect(btn).toBeInTheDocument();
    });

    it('opens contextual drawer when button is clicked and displays page-specific concepts', async () => {
      render(
        <MemoryRouter>
          <LearnAboutPageButton pageId="cluster" />
        </MemoryRouter>
      );

      const btn = screen.getByRole('button', { name: /learn about this page/i });
      fireEvent.click(btn);

      await waitFor(() => {
        expect(screen.getByText('Cluster Coordination & Topology')).toBeInTheDocument();
        expect(screen.getByText('Contextual Learning System')).toBeInTheDocument();
      });

      expect(screen.getAllByText(/Leader Election/i).length).toBeGreaterThan(0);
      expect(screen.getAllByText(/Fencing Token/i).length).toBeGreaterThan(0);
      expect(screen.getAllByText(/Lease TTL/i).length).toBeGreaterThan(0);
    });

    it('closes contextual drawer when close button or Escape key is pressed', async () => {
      const onClose = vi.fn();
      const { rerender } = render(
        <MemoryRouter>
          <ContextualDocDrawer pageId="tasks" isOpen={true} onClose={onClose} />
        </MemoryRouter>
      );

      expect(screen.getByText('Task Management')).toBeInTheDocument();

      const closeBtn = screen.getByRole('button', { name: /close contextual documentation/i });
      fireEvent.click(closeBtn);
      expect(onClose).toHaveBeenCalledTimes(1);

      fireEvent.keyDown(window, { key: 'Escape' });
      expect(onClose).toHaveBeenCalledTimes(2);

      rerender(
        <MemoryRouter>
          <ContextualDocDrawer pageId="tasks" isOpen={false} onClose={onClose} />
        </MemoryRouter>
      );

      expect(screen.queryByText('Task Management')).not.toBeInTheDocument();
    });

    it('provides "Open in Full Documentation" link inside contextual drawer', async () => {
      render(
        <MemoryRouter>
          <ContextualDocDrawer pageId="dlq" isOpen={true} onClose={vi.fn()} />
        </MemoryRouter>
      );

      const fullDocBtn = screen.getByRole('button', { name: /open in full documentation/i });
      expect(fullDocBtn).toBeInTheDocument();
    });
  });

  describe('Interactive Educational Visualizers', () => {
    it('TaskLifecycleVisualizer supports play, pause, step forward, and reset controls', () => {
      render(<TaskLifecycleVisualizer />);

      expect(screen.getByText(/Interactive Scenario:/i)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /play/i })).toBeInTheDocument();
      expect(screen.getByTitle('Step Forward')).toBeInTheDocument();
      expect(screen.getByTitle('Reset')).toBeInTheDocument();

      // Step forward
      const stepBtn = screen.getByTitle('Step Forward');
      fireEvent.click(stepBtn);
      expect(screen.getAllByText('QUEUED').length).toBeGreaterThan(0);

      // Reset
      const resetBtn = screen.getByTitle('Reset');
      fireEvent.click(resetBtn);
    });

    it('LeaderElectionVisualizer simulates leader failure and fencing token rejection', () => {
      render(<LeaderElectionVisualizer />);

      expect(screen.getByText(/Redis Lease & Fencing Token Mechanics/i)).toBeInTheDocument();
      expect(screen.getAllByText(/LEADER/i).length).toBeGreaterThan(0);

      const simulateBtn = screen.getByRole('button', { name: /simulate leader crash/i });
      expect(simulateBtn).toBeInTheDocument();
      fireEvent.click(simulateBtn);

      expect(screen.getAllByText(/Epoch/i).length).toBeGreaterThan(0);
    });

    it('WorkerHeartbeatVisualizer simulates missed heartbeats and eviction', () => {
      render(<WorkerHeartbeatVisualizer />);

      expect(screen.getByText(/Worker Heartbeat & Health Monitoring/i)).toBeInTheDocument();
      const failBtn = screen.getByRole('button', { name: /simulate worker death/i });
      fireEvent.click(failBtn);

      expect(screen.getByRole('button', { name: /reset/i })).toBeInTheDocument();
    });

    it('ConsistentHashVisualizer simulates hash ring remapping upon node failure', () => {
      render(<ConsistentHashVisualizer />);

      expect(screen.getByText(/Consistent Hashing Ring & Virtual Nodes/i)).toBeInTheDocument();
      const killWorkerBtn = screen.getByRole('button', { name: /kill worker 2/i });
      fireEvent.click(killWorkerBtn);

      expect(screen.getByText(/Restore Worker 2/i)).toBeInTheDocument();
    });

    it('DagExecutionVisualizer steps through dependency ordering and failure propagation', () => {
      render(<DagExecutionVisualizer />);

      expect(screen.getByText(/DAG Topology Scenario:/i)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /play workflow/i })).toBeInTheDocument();
    });

    it('DlqLifecycleVisualizer demonstrates retry exhaustion and replay flow', () => {
      render(<DlqLifecycleVisualizer />);

      expect(screen.getByText(/Dead-Letter Queue Quarantine & Replay/i)).toBeInTheDocument();
      const nextBtn = screen.getByRole('button', { name: /next step/i });
      fireEvent.click(nextBtn);
      expect(screen.getByRole('button', { name: /reset/i })).toBeInTheDocument();
    });

    it('DispatchVisualizer steps through the end-to-end task path', () => {
      render(<DispatchVisualizer />);

      expect(screen.getByText(/Distributed Task Dispatch & Execution Pipeline/i)).toBeInTheDocument();
      const nextBtn = screen.getByRole('button', { name: /next phase/i });
      fireEvent.click(nextBtn);
      expect(screen.getByText(/Durable DB Commit/i)).toBeInTheDocument();
    });

    it('AiWorkflowVisualizer verifies natural language to DAG registration gate', () => {
      render(<AiWorkflowVisualizer />);

      expect(screen.getByText(/AI Workflow Agent: Deterministic Proposal Pipeline/i)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /next stage/i })).toBeInTheDocument();
    });

    it('AiOperationsVisualizer verifies incident diagnosis and human approval boundary', () => {
      render(<AiOperationsVisualizer />);

      expect(screen.getByText(/AI Operations Agent: Incident Triage & Guardrails/i)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /next phase/i })).toBeInTheDocument();
    });

    it('ObservabilityTraceVisualizer steps through correlation and trace propagation', () => {
      render(<ObservabilityTraceVisualizer />);

      expect(screen.getByText(/Distributed Trace & Context Propagation/i)).toBeInTheDocument();
      const nextBtn = screen.getByRole('button', { name: /next boundary/i });
      fireEvent.click(nextBtn);
      expect(screen.getByText(/MDC Logging/i)).toBeInTheDocument();
    });
  });
});
