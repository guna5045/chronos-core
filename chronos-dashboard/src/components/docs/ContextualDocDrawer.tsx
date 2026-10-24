import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { X, ExternalLink, BookOpen, ChevronRight } from 'lucide-react';
import { ControlPlanePageId } from '../../docs/types';
import { PAGE_DOCUMENTATION_MAPPINGS } from '../../docs/pageMappings';
import { DOCUMENTATION_CONCEPTS } from '../../docs/documentationData';
import { VisualizerRenderer } from './visualizers/VisualizerRenderer';

interface Props {
  pageId: ControlPlanePageId;
  isOpen: boolean;
  onClose: () => void;
  initialConceptId?: string;
}

export const ContextualDocDrawer: React.FC<Props> = ({
  pageId,
  isOpen,
  onClose,
  initialConceptId,
}) => {
  const navigate = useNavigate();
  const pageMapping = PAGE_DOCUMENTATION_MAPPINGS[pageId];

  // Resolve matching concept objects
  const pageConcepts = (pageMapping?.primaryConceptIds || [])
    .map((id) => DOCUMENTATION_CONCEPTS.find((c) => c.id === id))
    .filter(Boolean) as typeof DOCUMENTATION_CONCEPTS;

  const [selectedConceptId, setSelectedConceptId] = useState<string>(() => {
    return initialConceptId || (pageConcepts[0]?.id ?? '');
  });

  useEffect(() => {
    if (initialConceptId) {
      setSelectedConceptId(initialConceptId);
    } else if (pageConcepts[0]) {
      setSelectedConceptId(pageConcepts[0].id);
    }
  }, [pageId, initialConceptId]);

  // Handle ESC key press to close drawer
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && isOpen) {
        onClose();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  const activeConcept =
    DOCUMENTATION_CONCEPTS.find((c) => c.id === selectedConceptId) ||
    pageConcepts[0];

  const handleOpenFullDocumentation = (conceptId?: string) => {
    onClose();
    const targetId = conceptId || activeConcept?.id;
    navigate(targetId ? `/documentation?concept=${targetId}` : '/documentation');
  };

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 1000,
        display: 'flex',
        justifyContent: 'flex-end',
      }}
      role="dialog"
      aria-modal="true"
      aria-label={`${pageMapping?.pageTitle || 'Page'} Documentation`}
    >
      {/* Backdrop */}
      <div
        onClick={onClose}
        style={{
          position: 'absolute',
          inset: 0,
          backgroundColor: 'rgba(9, 13, 22, 0.7)',
          backdropFilter: 'blur(3px)',
          animation: 'fadeIn 0.15s ease',
        }}
      />

      {/* Drawer Container */}
      <div
        style={{
          position: 'relative',
          width: '680px',
          maxWidth: '100%',
          height: '100%',
          backgroundColor: 'var(--bg-surface)',
          borderLeft: '1px solid var(--border-default)',
          display: 'flex',
          flexDirection: 'column',
          boxShadow: '-8px 0 24px rgba(0, 0, 0, 0.45)',
          animation: 'slideInRight 0.2s cubic-bezier(0.16, 1, 0.3, 1)',
        }}
      >
        {/* Drawer Header */}
        <div
          style={{
            padding: '1.25rem 1.5rem',
            borderBottom: '1px solid var(--border-subtle)',
            display: 'flex',
            alignItems: 'flex-start',
            justifyContent: 'space-between',
            gap: '1rem',
            backgroundColor: 'var(--bg-elevated)',
          }}
        >
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.25rem' }}>
              <BookOpen size={16} color="var(--accent-cyan)" />
              <span
                style={{
                  fontSize: '0.7rem',
                  fontWeight: 700,
                  textTransform: 'uppercase',
                  letterSpacing: '0.06em',
                  color: 'var(--accent-cyan)',
                }}
              >
                Contextual Learning System
              </span>
            </div>
            <h3 style={{ fontSize: '1.15rem', fontWeight: 700, color: 'var(--text-main)', margin: 0 }}>
              {pageMapping?.pageTitle}
            </h3>
            <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)', margin: '0.25rem 0 0' }}>
              {pageMapping?.subtitle}
            </p>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <button
              onClick={() => handleOpenFullDocumentation(activeConcept?.id)}
              className="btn btn-secondary btn-sm"
              title="Open in full documentation page"
              style={{ fontSize: '0.75rem' }}
            >
              <ExternalLink size={13} />
              <span>Full Docs</span>
            </button>
            <button
              onClick={onClose}
              className="btn btn-secondary btn-sm"
              title="Close panel (Esc)"
              aria-label="Close contextual documentation"
              style={{ padding: '0.35rem' }}
            >
              <X size={16} />
            </button>
          </div>
        </div>

        {/* Concept Quick-Tabs Selector */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: '0.4rem',
            overflowX: 'auto',
            padding: '0.65rem 1.25rem',
            borderBottom: '1px solid var(--border-subtle)',
            backgroundColor: 'var(--bg-canvas)',
            whiteSpace: 'nowrap',
          }}
        >
          {pageConcepts.map((concept) => {
            const isSelected = concept.id === activeConcept?.id;
            return (
              <button
                key={concept.id}
                onClick={() => setSelectedConceptId(concept.id)}
                style={{
                  padding: '0.3rem 0.65rem',
                  borderRadius: '0.375rem',
                  fontSize: '0.75rem',
                  fontWeight: isSelected ? 600 : 400,
                  color: isSelected ? 'var(--text-main)' : 'var(--text-muted)',
                  backgroundColor: isSelected ? 'var(--bg-surface)' : 'transparent',
                  border: isSelected ? '1px solid var(--border-accent)' : '1px solid transparent',
                  cursor: 'pointer',
                  transition: 'all 0.15s ease',
                }}
              >
                {concept.title}
              </button>
            );
          })}
        </div>

        {/* Drawer Body */}
        <div style={{ flex: 1, overflowY: 'auto', padding: '1.5rem', display: 'flex', flexDirection: 'column', gap: '1.25rem' }}>
          {activeConcept ? (
            <>
              {/* Concept Title & Summary */}
              <div>
                <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.35rem' }}>
                  <span
                    className="font-mono"
                    style={{
                      fontSize: '0.65rem',
                      textTransform: 'uppercase',
                      padding: '0.1rem 0.4rem',
                      borderRadius: '3px',
                      backgroundColor: 'rgba(56, 189, 248, 0.1)',
                      color: 'var(--accent-cyan)',
                    }}
                  >
                    {activeConcept.category}
                  </span>
                  <h2 style={{ fontSize: '1.3rem', fontWeight: 700, color: 'var(--text-main)', margin: 0 }}>
                    {activeConcept.title}
                  </h2>
                </div>
                <p style={{ fontSize: '0.85rem', color: 'var(--text-muted)', lineHeight: 1.5 }}>
                  {activeConcept.summary}
                </p>
              </div>

              {/* Embedded Visualizer (if available) */}
              {activeConcept.visualizerId && (
                <div>
                  <VisualizerRenderer visualizerId={activeConcept.visualizerId} />
                </div>
              )}

              {/* Detailed Sections */}
              <div
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  gap: '1rem',
                  backgroundColor: 'var(--bg-canvas)',
                  padding: '1.25rem',
                  borderRadius: '0.375rem',
                  border: '1px solid var(--border-subtle)',
                }}
              >
                <div>
                  <h4 style={{ fontSize: '0.75rem', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--accent-cyan)', marginBottom: '0.3rem' }}>
                    Definition
                  </h4>
                  <p style={{ fontSize: '0.8125rem', color: 'var(--text-main)', lineHeight: 1.6 }}>
                    {activeConcept.definition}
                  </p>
                </div>

                <div>
                  <h4 style={{ fontSize: '0.75rem', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--accent-cyan)', marginBottom: '0.3rem' }}>
                    Purpose
                  </h4>
                  <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', lineHeight: 1.6 }}>
                    {activeConcept.purpose}
                  </p>
                </div>

                <div>
                  <h4 style={{ fontSize: '0.75rem', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--accent-cyan)', marginBottom: '0.3rem' }}>
                    Chronos Core Usage
                  </h4>
                  <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', lineHeight: 1.6 }}>
                    {activeConcept.chronosUsage}
                  </p>
                </div>

                <div>
                  <h4 style={{ fontSize: '0.75rem', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--accent-cyan)', marginBottom: '0.3rem' }}>
                    Operational Significance
                  </h4>
                  <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', lineHeight: 1.6 }}>
                    {activeConcept.operationalSignificance}
                  </p>
                </div>
              </div>

              {/* Related Concepts Cross-links */}
              {activeConcept.relatedConceptIds.length > 0 && (
                <div>
                  <span style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-faint)', textTransform: 'uppercase' }}>
                    Related Concepts:
                  </span>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.4rem', marginTop: '0.4rem' }}>
                    {activeConcept.relatedConceptIds.map((relId) => {
                      const rel = DOCUMENTATION_CONCEPTS.find((c) => c.id === relId);
                      if (!rel) return null;
                      return (
                        <button
                          key={rel.id}
                          onClick={() => {
                            if (pageMapping.primaryConceptIds.includes(rel.id)) {
                              setSelectedConceptId(rel.id);
                            } else {
                              handleOpenFullDocumentation(rel.id);
                            }
                          }}
                          className="btn btn-secondary btn-sm"
                          style={{ fontSize: '0.75rem' }}
                        >
                          <span>{rel.title}</span>
                          <ChevronRight size={12} />
                        </button>
                      );
                    })}
                  </div>
                </div>
              )}
            </>
          ) : (
            <div style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>
              Select a concept above to view its detailed technical reference.
            </div>
          )}
        </div>

        {/* Drawer Footer */}
        <div
          style={{
            padding: '1rem 1.5rem',
            borderTop: '1px solid var(--border-subtle)',
            backgroundColor: 'var(--bg-elevated)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
          }}
        >
          <span style={{ fontSize: '0.75rem', color: 'var(--text-faint)' }}>
            Chronos Core Technical Reference • Implementation-Accurate
          </span>
          <button
            onClick={() => handleOpenFullDocumentation(activeConcept?.id)}
            className="btn btn-primary btn-sm"
          >
            <BookOpen size={14} />
            <span>Open in Full Documentation</span>
          </button>
        </div>
      </div>
    </div>
  );
};
