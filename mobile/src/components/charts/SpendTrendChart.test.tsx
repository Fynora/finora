import { render, screen } from '@testing-library/react-native';
import { SpendTrendChart } from './SpendTrendChart';

describe('SpendTrendChart', () => {
  it('shows an empty state with no points', () => {
    render(<SpendTrendChart points={[]} width={320} />);

    expect(screen.getByText('No trend yet.')).toBeTruthy();
  });

  it('carries the trend as an accessibility label, for assistive tech that cannot read the SVG', () => {
    render(
      <SpendTrendChart
        points={[
          { label: 'Jul 26', value: 12000 },
          { label: 'Aug 26', value: 15000 },
        ]}
        width={320}
      />
    );

    expect(screen.getByLabelText(/Spend trend over 2 months/)).toBeTruthy();
    expect(screen.getByLabelText(/Jul 26: ₹12,000/)).toBeTruthy();
    expect(screen.getByLabelText(/Aug 26: ₹15,000/)).toBeTruthy();
  });
});
