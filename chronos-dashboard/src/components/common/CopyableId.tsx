import React, { useState } from 'react';
import { Check, Copy } from 'lucide-react';
import { truncateId } from '../../utils/formatters';

interface CopyableIdProps {
  id: string;
  truncate?: boolean;
  prefixLength?: number;
  suffixLength?: number;
}

export const CopyableId: React.FC<CopyableIdProps> = ({
  id,
  truncate = true,
  prefixLength = 8,
  suffixLength = 4,
}) => {
  const [copied, setCopied] = useState(false);

  const handleCopy = (e: React.MouseEvent) => {
    e.stopPropagation();
    navigator.clipboard.writeText(id);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const displayText = truncate ? truncateId(id, prefixLength, suffixLength) : id;

  return (
    <span
      onClick={handleCopy}
      title={`Click to copy full ID: ${id}`}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: '0.35rem',
        cursor: 'pointer',
        fontFamily: 'var(--font-mono)',
        fontSize: '0.8125rem',
        color: copied ? 'var(--accent-emerald)' : 'var(--text-main)',
        padding: '0.1rem 0.35rem',
        borderRadius: '0.25rem',
        backgroundColor: 'rgba(255, 255, 255, 0.04)',
        border: '1px solid rgba(255, 255, 255, 0.08)',
        transition: 'all 0.15s ease',
      }}
    >
      <span>{displayText}</span>
      {copied ? <Check size={12} color="var(--accent-emerald)" /> : <Copy size={12} color="var(--text-faint)" />}
    </span>
  );
};
