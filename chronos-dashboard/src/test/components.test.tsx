import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { StatusBadge } from '../components/common/StatusBadge';
import { CopyableId } from '../components/common/CopyableId';
import { ConfirmModal } from '../components/common/ConfirmModal';
import { ErrorAlert } from '../components/common/ErrorAlert';
import { ApiClientError } from '../api/client';
import { formatDate, formatDuration, formatTimeRelative, truncateId } from '../utils/formatters';

describe('Common UI Components & Formatters', () => {
  describe('StatusBadge', () => {
    it('renders with appropriate text and uppercase styling', () => {
      const { rerender } = render(<StatusBadge status="RUNNING" />);
      expect(screen.getByText('RUNNING')).toBeInTheDocument();

      rerender(<StatusBadge status="succeeded" />);
      expect(screen.getByText('SUCCEEDED')).toBeInTheDocument();

      rerender(<StatusBadge status="DLQ" />);
      expect(screen.getByText('DLQ')).toBeInTheDocument();

      rerender(<StatusBadge status="SUSPECTED" />);
      expect(screen.getByText('SUSPECTED')).toBeInTheDocument();
    });
  });

  describe('CopyableId', () => {
    it('truncates UUID for readability and copies to clipboard on click', async () => {
      const writeText = vi.fn();
      Object.assign(navigator, {
        clipboard: { writeText },
      });

      const fullId = '4dfd827f-3a21-4d92-94b2-a4290130db92';
      render(<CopyableId id={fullId} />);

      expect(screen.getByText('4dfd827f...db92')).toBeInTheDocument();

      fireEvent.click(screen.getByText('4dfd827f...db92'));
      expect(writeText).toHaveBeenCalledWith(fullId);
    });
  });

  describe('ConfirmModal', () => {
    it('calls onConfirm with provided reason when confirmed', () => {
      const handleConfirm = vi.fn();
      const handleClose = vi.fn();

      render(
        <ConfirmModal
          isOpen={true}
          title="Cancel Chronos Task"
          description="Are you sure you want to cancel this task?"
          confirmLabel="Yes, Cancel"
          requireReason={true}
          onConfirm={handleConfirm}
          onClose={handleClose}
        />
      );

      expect(screen.getByText('Cancel Chronos Task')).toBeInTheDocument();
      expect(screen.getByText('Are you sure you want to cancel this task?')).toBeInTheDocument();

      const input = screen.getByPlaceholderText('e.g. Manual triage, worker deadlock');
      fireEvent.change(input, { target: { value: 'Operator requested abort' } });

      fireEvent.click(screen.getByText('Yes, Cancel'));
      expect(handleConfirm).toHaveBeenCalledWith('Operator requested abort');
    });

    it('does not render when isOpen is false', () => {
      render(
        <ConfirmModal
          isOpen={false}
          title="Hidden"
          description="Hidden"
          confirmLabel="Ok"
          onConfirm={vi.fn()}
          onClose={vi.fn()}
        />
      );
      expect(screen.queryByText('Hidden')).toBeNull();
    });
  });

  describe('ErrorAlert', () => {
    it('displays ApiClientError status and code', () => {
      const error = new ApiClientError({
        timestamp: '2026-10-21T18:00:00Z',
        status: 403,
        code: 'FORBIDDEN',
        message: 'Access denied for tenant resource',
        path: '/tasks/1',
      });

      render(<ErrorAlert error={error} />);

      expect(screen.getByText('FORBIDDEN (403)')).toBeInTheDocument();
      expect(screen.getByText('Access denied for tenant resource')).toBeInTheDocument();
    });
  });

  describe('Formatters', () => {
    it('formats ISO timestamps cleanly', () => {
      expect(formatDate('2026-10-21T18:14:02.123Z')).toBe('2026-10-21 18:14:02');
      expect(formatDate(null)).toBe('—');
    });

    it('formats durations from ISO-8601 strings', () => {
      expect(formatDuration('PT30M')).toBe('30m');
      expect(formatDuration('PT5S')).toBe('5s');
      expect(formatDuration(null)).toBe('—');
    });

    it('truncates IDs safely', () => {
      expect(truncateId('1234567890abcdef', 4, 4)).toBe('1234...cdef');
      expect(truncateId(null)).toBe('—');
    });
  });
});
