import { render, screen } from '@testing-library/react-native';
import { CashFlowMiniCard } from './CashFlowMiniCard';
import { ThemeProvider } from '../../theme';

describe('CashFlowMiniCard', () => {
  it('shows the average monthly savings figure and delta', () => {
    render(
      <ThemeProvider>
        <CashFlowMiniCard
          deltaPct={22}
          points={[
            { label: 'Jul', income: 50000, expense: 30000 },
            { label: 'Aug', income: 40000, expense: 20000 },
          ]}
        />
      </ThemeProvider>
    );
    expect(screen.getByText('Cash Flow Trend')).toBeTruthy();
    expect(screen.getByText('₹20,000')).toBeTruthy();
    expect(screen.getByText('▲ 22.0%')).toBeTruthy();
  });

  it('renders nothing with no points -- the full Cash Flow card explains why', () => {
    const { toJSON } = render(<ThemeProvider><CashFlowMiniCard points={[]} deltaPct={null} /></ThemeProvider>);
    expect(toJSON()).toBeNull();
  });
});
