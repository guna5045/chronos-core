import React, { useState, useEffect, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Search,
  BookOpen,
  ChevronRight,
  Layers,
  Activity,
  ListTodo,
  Cpu,
  GitBranch,
  Terminal,
  Shield,
  Radio,
  Bot,
  ShieldAlert,
  Hash,
  X,
} from 'lucide-react';
import {
  DOCUMENTATION_CATEGORIES,
  DOCUMENTATION_CONCEPTS,
} from '../docs/documentationData';
import { DocumentationCategoryId, DocumentationConcept } from '../docs/types';
import { VisualizerRenderer } from '../components/docs/visualizers/VisualizerRenderer';

const CATEGORY_ICONS: Record<string, React.ReactNode> = {
  Activity: <Activity size={16} />,
  ListTodo: <ListTodo size={16} />,
  Cpu: <Cpu size={16} />,
  Terminal: <Terminal size={16} />,
  GitBranch: <GitBranch size={16} />,
  Layers: <Layers size={16} />,
  Shield: <Shield size={16} />,
  Radio: <Radio size={16} />,
  Bot: <Bot size={16} />,
  ShieldAlert: <ShieldAlert size={16} />,
};

export const DocumentationPage: React.FC = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  const [searchQuery, setSearchQuery] = useState('');
  const [activeCategoryId, setActiveCategoryId] = useState<DocumentationCategoryId>('getting-started');

  // Deep-linking: get concept from URL params or default to first concept
  const conceptFromUrl = searchParams.get('concept');

  const activeConcept = useMemo<DocumentationConcept>(() => {
    if (conceptFromUrl) {
      const found = DOCUMENTATION_CONCEPTS.find((c) => c.id === conceptFromUrl);
      if (found) return found;
    }
    return DOCUMENTATION_CONCEPTS[0];
  }, [conceptFromUrl]);

  // Keep category accordion aligned with active concept
  useEffect(() => {
    if (activeConcept) {
      setActiveCategoryId(activeConcept.category);
    }
  }, [activeConcept]);

  const handleSelectConcept = (conceptId: string) => {
    setSearchParams({ concept: conceptId });
  };

  // Search filter across titles, summaries, definitions, and aliases
  const filteredConcepts = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    if (!q) return [];
    return DOCUMENTATION_CONCEPTS.filter((c) => {
      return (
        c.title.toLowerCase().includes(q) ||
        c.aliases.some((a) => a.toLowerCase().includes(q)) ||
        c.summary.toLowerCase().includes(q) ||
        c.definition.toLowerCase().includes(q)
      );
    });
  }, [searchQuery]);

  const isSearching = searchQuery.trim().length > 0;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem', minHeight: '100%' }}>
      {/* Header Bar */}
      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          gap: '0.5rem',
          borderBottom: '1px solid var(--border-subtle)',
          paddingBottom: '1rem',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
          <div
            style={{
              width: '32px',
              height: '32px',
              borderRadius: '6px',
              backgroundColor: 'rgba(56, 189, 248, 0.15)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: 'var(--accent-cyan)',
            }}
          >
            <BookOpen size={18} />
          </div>
          <div>
            <h1 style={{ fontSize: '1.45rem', fontWeight: 700, color: 'var(--text-main)', letterSpacing: '0.01em', margin: 0 }}>
              Chronos Core Documentation
            </h1>
            <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', margin: 0 }}>
              A professional, searchable reference for the concepts, components, workflows, and operational mechanisms used throughout the Chronos Core Control Plane.
            </p>
          </div>
        </div>

        {/* Search Bar */}
        <div style={{ position: 'relative', marginTop: '0.5rem', maxWidth: '640px' }}>
          <div
            style={{
              position: 'absolute',
              left: '0.75rem',
              top: '50%',
              transform: 'translateY(-50%)',
              color: 'var(--text-faint)',
              display: 'flex',
              alignItems: 'center',
            }}
          >
            <Search size={16} />
          </div>
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search concepts, aliases, DLQ, fencing, epoch, virtual threads..."
            aria-label="Search documentation concepts"
            style={{
              width: '100%',
              backgroundColor: 'var(--bg-elevated)',
              border: '1px solid var(--border-default)',
              borderRadius: '0.375rem',
              padding: '0.55rem 2.2rem 0.55rem 2.2rem',
              color: 'var(--text-main)',
              fontSize: '0.85rem',
              outline: 'none',
              transition: 'border-color 0.15s ease',
            }}
          />
          {searchQuery && (
            <button
              onClick={() => setSearchQuery('')}
              style={{
                position: 'absolute',
                right: '0.65rem',
                top: '50%',
                transform: 'translateY(-50%)',
                background: 'none',
                border: 'none',
                color: 'var(--text-muted)',
                cursor: 'pointer',
              }}
              title="Clear search"
            >
              <X size={14} />
            </button>
          )}
        </div>
      </div>

      {/* Main Two-Column Layout */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: '290px 1fr',
          gap: '1.5rem',
          alignItems: 'start',
        }}
      >
        {/* Left Navigation Column */}
        <div
          style={{
            backgroundColor: 'var(--bg-surface)',
            border: '1px solid var(--border-subtle)',
            borderRadius: '0.5rem',
            padding: '1rem',
            display: 'flex',
            flexDirection: 'column',
            gap: '1rem',
            maxHeight: 'calc(100vh - 200px)',
            overflowY: 'auto',
          }}
        >
          {isSearching ? (
            <div>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '0.6rem' }}>
                <span style={{ fontSize: '0.7rem', fontWeight: 700, color: 'var(--text-faint)', textTransform: 'uppercase' }}>
                  Search Results ({filteredConcepts.length})
                </span>
                <button
                  onClick={() => setSearchQuery('')}
                  style={{ background: 'none', border: 'none', color: 'var(--accent-cyan)', fontSize: '0.7rem', cursor: 'pointer' }}
                >
                  Clear
                </button>
              </div>

              {filteredConcepts.length === 0 ? (
                <div style={{ padding: '1rem', textAlign: 'center', color: 'var(--text-muted)', fontSize: '0.8rem' }}>
                  No matching technical concepts found.
                </div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.25rem' }}>
                  {filteredConcepts.map((c) => (
                    <button
                      key={c.id}
                      onClick={() => handleSelectConcept(c.id)}
                      style={{
                        padding: '0.45rem 0.65rem',
                        borderRadius: '0.375rem',
                        textAlign: 'left',
                        background: c.id === activeConcept.id ? 'var(--bg-active)' : 'transparent',
                        border: c.id === activeConcept.id ? '1px solid var(--border-default)' : '1px solid transparent',
                        color: c.id === activeConcept.id ? 'var(--text-main)' : 'var(--text-muted)',
                        cursor: 'pointer',
                        display: 'flex',
                        flexDirection: 'column',
                        gap: '0.15rem',
                      }}
                    >
                      <span style={{ fontSize: '0.8125rem', fontWeight: 600 }}>{c.title}</span>
                      <span style={{ fontSize: '0.65rem', color: 'var(--text-faint)', textTransform: 'uppercase' }}>
                        {c.category}
                      </span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
              {DOCUMENTATION_CATEGORIES.map((cat) => {
                const isCatActive = cat.id === activeCategoryId;
                const catConcepts = DOCUMENTATION_CONCEPTS.filter((c) => c.category === cat.id);

                return (
                  <div key={cat.id} style={{ display: 'flex', flexDirection: 'column', gap: '0.25rem' }}>
                    <button
                      onClick={() => setActiveCategoryId(cat.id)}
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        padding: '0.4rem 0.5rem',
                        background: 'none',
                        border: 'none',
                        color: isCatActive ? 'var(--accent-cyan)' : 'var(--text-muted)',
                        cursor: 'pointer',
                        textAlign: 'left',
                      }}
                    >
                      <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                        {CATEGORY_ICONS[cat.iconName] || <Hash size={15} />}
                        <span style={{ fontSize: '0.8125rem', fontWeight: 700 }}>{cat.title}</span>
                      </div>
                      <span
                        className="font-mono"
                        style={{
                          fontSize: '0.65rem',
                          padding: '0.1rem 0.35rem',
                          borderRadius: '3px',
                          backgroundColor: 'rgba(100, 116, 139, 0.15)',
                          color: 'var(--text-faint)',
                        }}
                      >
                        {catConcepts.length}
                      </span>
                    </button>

                    {isCatActive && (
                      <div
                        style={{
                          display: 'flex',
                          flexDirection: 'column',
                          gap: '0.15rem',
                          paddingLeft: '1.25rem',
                          borderLeft: '1px solid var(--border-subtle)',
                          marginLeft: '0.75rem',
                        }}
                      >
                        {catConcepts.map((c) => {
                          const isSelected = c.id === activeConcept.id;
                          return (
                            <button
                              key={c.id}
                              onClick={() => handleSelectConcept(c.id)}
                              style={{
                                padding: '0.35rem 0.5rem',
                                borderRadius: '0.25rem',
                                textAlign: 'left',
                                background: isSelected ? 'var(--bg-active)' : 'transparent',
                                border: isSelected ? '1px solid var(--border-default)' : '1px solid transparent',
                                color: isSelected ? 'var(--text-main)' : 'var(--text-muted)',
                                fontSize: '0.78125rem',
                                fontWeight: isSelected ? 600 : 400,
                                cursor: 'pointer',
                                transition: 'all 0.1s ease',
                              }}
                            >
                              {c.title}
                            </button>
                          );
                        })}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* Right Active Concept Display Column */}
        <div
          style={{
            backgroundColor: 'var(--bg-surface)',
            border: '1px solid var(--border-subtle)',
            borderRadius: '0.5rem',
            padding: '1.75rem',
            display: 'flex',
            flexDirection: 'column',
            gap: '1.5rem',
          }}
        >
          {/* Concept Header */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap' }}>
              <span
                className="font-mono"
                style={{
                  fontSize: '0.7rem',
                  textTransform: 'uppercase',
                  padding: '0.15rem 0.5rem',
                  borderRadius: '3px',
                  backgroundColor: 'rgba(56, 189, 248, 0.15)',
                  color: 'var(--accent-cyan)',
                  fontWeight: 600,
                }}
              >
                {activeConcept.category}
              </span>
              {activeConcept.aliases.map((alias) => (
                <span
                  key={alias}
                  style={{
                    fontSize: '0.7rem',
                    padding: '0.15rem 0.45rem',
                    borderRadius: '3px',
                    backgroundColor: 'rgba(100, 116, 139, 0.15)',
                    color: 'var(--text-faint)',
                  }}
                >
                  alias: {alias}
                </span>
              ))}
            </div>

            <h2 style={{ fontSize: '1.65rem', fontWeight: 700, color: 'var(--text-main)', margin: 0 }}>
              {activeConcept.title}
            </h2>

            <p style={{ fontSize: '0.9rem', color: 'var(--text-muted)', lineHeight: 1.6, margin: 0 }}>
              {activeConcept.summary}
            </p>
          </div>

          {/* Embedded Interactive Educational Visualizer */}
          {activeConcept.visualizerId && (
            <div style={{ margin: '0.25rem 0' }}>
              <VisualizerRenderer visualizerId={activeConcept.visualizerId} />
            </div>
          )}

          {/* Structured Technical Sections */}
          <div
            style={{
              display: 'flex',
              flexDirection: 'column',
              gap: '1.25rem',
              backgroundColor: 'var(--bg-canvas)',
              padding: '1.5rem',
              borderRadius: '0.375rem',
              border: '1px solid var(--border-subtle)',
            }}
          >
            {/* Definition */}
            <div>
              <h3
                style={{
                  fontSize: '0.75rem',
                  fontWeight: 700,
                  textTransform: 'uppercase',
                  letterSpacing: '0.06em',
                  color: 'var(--accent-cyan)',
                  marginBottom: '0.4rem',
                }}
              >
                Definition
              </h3>
              <p style={{ fontSize: '0.875rem', color: 'var(--text-main)', lineHeight: 1.65, margin: 0 }}>
                {activeConcept.definition}
              </p>
            </div>

            {/* Purpose */}
            <div>
              <h3
                style={{
                  fontSize: '0.75rem',
                  fontWeight: 700,
                  textTransform: 'uppercase',
                  letterSpacing: '0.06em',
                  color: 'var(--accent-cyan)',
                  marginBottom: '0.4rem',
                }}
              >
                Purpose &amp; Design Rationale
              </h3>
              <p style={{ fontSize: '0.875rem', color: 'var(--text-muted)', lineHeight: 1.65, margin: 0 }}>
                {activeConcept.purpose}
              </p>
            </div>

            {/* Chronos Core Usage */}
            <div>
              <h3
                style={{
                  fontSize: '0.75rem',
                  fontWeight: 700,
                  textTransform: 'uppercase',
                  letterSpacing: '0.06em',
                  color: 'var(--accent-cyan)',
                  marginBottom: '0.4rem',
                }}
              >
                Chronos Core Implementation
              </h3>
              <p style={{ fontSize: '0.875rem', color: 'var(--text-muted)', lineHeight: 1.65, margin: 0 }}>
                {activeConcept.chronosUsage}
              </p>
            </div>

            {/* Operational Significance */}
            <div>
              <h3
                style={{
                  fontSize: '0.75rem',
                  fontWeight: 700,
                  textTransform: 'uppercase',
                  letterSpacing: '0.06em',
                  color: 'var(--accent-cyan)',
                  marginBottom: '0.4rem',
                }}
              >
                Operational Significance
              </h3>
              <p style={{ fontSize: '0.875rem', color: 'var(--text-muted)', lineHeight: 1.65, margin: 0 }}>
                {activeConcept.operationalSignificance}
              </p>
            </div>
          </div>

          {/* Related Concepts Clickable Links */}
          {activeConcept.relatedConceptIds.length > 0 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
              <span style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-faint)', textTransform: 'uppercase' }}>
                Related Concepts (Click to Navigate):
              </span>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.5rem' }}>
                {activeConcept.relatedConceptIds.map((relId) => {
                  const rel = DOCUMENTATION_CONCEPTS.find((c) => c.id === relId);
                  if (!rel) return null;
                  return (
                    <button
                      key={rel.id}
                      onClick={() => handleSelectConcept(rel.id)}
                      className="btn btn-secondary btn-sm"
                      style={{ fontSize: '0.8rem', display: 'flex', alignItems: 'center', gap: '0.35rem' }}
                    >
                      <span>{rel.title}</span>
                      <ChevronRight size={13} />
                    </button>
                  );
                })}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
