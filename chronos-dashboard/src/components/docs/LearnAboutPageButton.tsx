import React, { useState } from 'react';
import { BookOpen } from 'lucide-react';
import { ControlPlanePageId } from '../../docs/types';
import { ContextualDocDrawer } from './ContextualDocDrawer';

interface Props {
  pageId: ControlPlanePageId;
  initialConceptId?: string;
  className?: string;
}

export const LearnAboutPageButton: React.FC<Props> = ({
  pageId,
  initialConceptId,
  className = '',
}) => {
  const [isOpen, setIsOpen] = useState(false);

  return (
    <>
      <button
        onClick={() => setIsOpen(true)}
        className={`btn btn-secondary btn-sm ${className}`}
        title="Open in-place technical documentation and concepts for this page"
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: '0.4rem',
          fontSize: '0.8125rem',
        }}
      >
        <BookOpen size={14} color="var(--accent-cyan)" />
        <span>Learn About This Page</span>
      </button>

      {isOpen && (
        <ContextualDocDrawer
          pageId={pageId}
          isOpen={isOpen}
          onClose={() => setIsOpen(false)}
          initialConceptId={initialConceptId}
        />
      )}
    </>
  );
};
