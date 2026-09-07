import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { HealthScoreSparkline } from './HealthScoreSparkline';

describe('HealthScoreSparkline', () => {
  it('renders one polyline segment per contiguous run of months', () => {
    render(
      <HealthScoreSparkline
        points={[
          { yearMonth: '2026-04', score: 40 },
          { yearMonth: '2026-05', score: 45 },
          // gap at 2026-06 -- nobody opened the dashboard that month
          { yearMonth: '2026-07', score: 55 },
          { yearMonth: '2026-08', score: 51 },
        ]}
      />
    );
    // Two contiguous runs (Apr-May, Jul-Aug) around the June gap -> two polylines, not one
    // continuous line that would visually paper over the missing month.
    const svg = screen.getByTestId('health-score-sparkline');
    expect(svg.querySelectorAll('polyline')).toHaveLength(2);
  });

  it('renders nothing but an empty svg for fewer than 2 points', () => {
    render(<HealthScoreSparkline points={[{ yearMonth: '2026-08', score: 51 }]} />);
    expect(screen.getByTestId('health-score-sparkline').querySelectorAll('polyline')).toHaveLength(0);
  });
});
