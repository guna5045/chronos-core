import React from 'react';

interface MetricCardProps {
  title: string;
  value: string | number;
  subtitle?: string;
  icon?: React.ReactNode;
  accentColor?: string;
}

export const MetricCard: React.FC<MetricCardProps> = ({
  title,
  value,
  subtitle,
  icon,
  accentColor,
}) => {
  return (
    <div className="metric-card">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <span className="metric-title">{title}</span>
        {icon && <span style={{ color: accentColor || 'var(--text-faint)' }}>{icon}</span>}
      </div>
      <div className="metric-value" style={{ color: accentColor || 'var(--text-main)' }}>
        {value}
      </div>
      {subtitle && <div className="metric-sub">{subtitle}</div>}
    </div>
  );
};
