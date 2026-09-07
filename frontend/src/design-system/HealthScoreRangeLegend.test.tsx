import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { HealthScoreRangeLegend } from './HealthScoreRangeLegend';

describe('HealthScoreRangeLegend', () => {
  it('renders all four tiers', () => {
    render(<HealthScoreRangeLegend score={51} />);
    expect(screen.getByText('0-40')).toBeInTheDocument();
    expect(screen.getByText('41-60')).toBeInTheDocument();
    expect(screen.getByText('61-80')).toBeInTheDocument();
    expect(screen.getByText('81-100')).toBeInTheDocument();
  });

  it('marks the tier containing the current score as current', () => {
    render(<HealthScoreRangeLegend score={51} />);
    expect(screen.getByText('41-60').closest('[data-current]')).toHaveAttribute('data-current', 'true');
    expect(screen.getByText('0-40').closest('[data-current]')).toHaveAttribute('data-current', 'false');
  });

  it('marks the top tier as current at the boundary score of 100', () => {
    render(<HealthScoreRangeLegend score={100} />);
    expect(screen.getByText('81-100').closest('[data-current]')).toHaveAttribute('data-current', 'true');
  });
});
