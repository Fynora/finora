import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { HealthScoreGauge } from './HealthScoreGauge';

describe('HealthScoreGauge', () => {
  it('renders an accessible label stating the score out of 100', () => {
    render(<HealthScoreGauge score={51} />);
    expect(screen.getByRole('img', { name: /51 out of 100/i })).toBeInTheDocument();
  });

  it('uses the red zone color at a score in 0-30', () => {
    render(<HealthScoreGauge score={20} />);
    const arc = screen.getByTestId('health-score-gauge-fill');
    expect(arc).toHaveAttribute('data-zone', 'red');
  });

  it('uses the amber zone color at a score in 31-60', () => {
    render(<HealthScoreGauge score={45} />);
    expect(screen.getByTestId('health-score-gauge-fill')).toHaveAttribute('data-zone', 'amber');
  });

  it('uses the green zone color at a score in 61-100', () => {
    render(<HealthScoreGauge score={85} />);
    expect(screen.getByTestId('health-score-gauge-fill')).toHaveAttribute('data-zone', 'green');
  });

  it('treats the 30/31 and 60/61 boundaries correctly', () => {
    const { rerender } = render(<HealthScoreGauge score={30} />);
    expect(screen.getByTestId('health-score-gauge-fill')).toHaveAttribute('data-zone', 'red');
    rerender(<HealthScoreGauge score={31} />);
    expect(screen.getByTestId('health-score-gauge-fill')).toHaveAttribute('data-zone', 'amber');
    rerender(<HealthScoreGauge score={60} />);
    expect(screen.getByTestId('health-score-gauge-fill')).toHaveAttribute('data-zone', 'amber');
    rerender(<HealthScoreGauge score={61} />);
    expect(screen.getByTestId('health-score-gauge-fill')).toHaveAttribute('data-zone', 'green');
  });
});
