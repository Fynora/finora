import { render, screen } from '@testing-library/react-native';
import { VerticalBarChart } from './VerticalBarChart';

describe('VerticalBarChart', () => {
  it('shows the empty message when there are no points', () => {
    render(<VerticalBarChart points={[]} width={300} valueLabel={(v) => `₹${v}`} />);

    expect(screen.getByText('No trend yet.')).toBeTruthy();
  });

  it('renders every point with its label and formatted value', () => {
    render(
      <VerticalBarChart
        points={[
          { label: 'Apr', value: 98000 },
          { label: 'May', value: 112000 },
        ]}
        width={300}
        valueLabel={(v) => `₹${(v / 1000).toFixed(0)}K`}
      />
    );

    expect(screen.getByText('Apr')).toBeTruthy();
    expect(screen.getByText('₹98K')).toBeTruthy();
    expect(screen.getByText('May')).toBeTruthy();
    expect(screen.getByText('₹112K')).toBeTruthy();
  });

  it('carries the full series in one accessible label for screen readers', () => {
    render(
      <VerticalBarChart
        points={[
          { label: 'Apr', value: 98000 },
          { label: 'May', value: 112000 },
        ]}
        width={300}
        valueLabel={(v) => `₹${v}`}
      />
    );

    expect(screen.getByLabelText(/Apr: ₹98000\. May: ₹112000/)).toBeTruthy();
  });
});
