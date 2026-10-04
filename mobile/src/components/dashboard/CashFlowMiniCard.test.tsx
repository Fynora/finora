import { render, screen } from '@testing-library/react-native';
import { CashFlowMiniCard } from './CashFlowMiniCard';
import { ThemeProvider } from '../../theme';

describe('CashFlowMiniCard', () => {
  it('shows the average monthly savings figure and delta', () => {
    render(
      <ThemeProvider>
        <CashFlowMiniCard
          deltaPct={22}
          deltaLabel="vs last month"
          points={[
            { label: 'Jul', income: 50000, expense: 30000 },
            { label: 'Aug', income: 40000, expense: 20000 },
          ]}
        />
      </ThemeProvider>
    );
    expect(screen.getByText('Cash Flow Trend')).toBeTruthy();
    expect(screen.getByText('Average Monthly Savings (2 mos)')).toBeTruthy();
    expect(screen.getByText('₹20,000')).toBeTruthy();
    expect(screen.getByText('▲ 22.0% vs last month')).toBeTruthy();
  });

  // netDeltaPct compares the summary's reporting month with the one before it; for statements
  // imported in arrears that is not "last month", so the caller names the comparison.
  it('labels the delta with the comparison it is given', () => {
    render(
      <ThemeProvider>
        <CashFlowMiniCard
          deltaPct={-5}
          deltaLabel="vs the month before Jun 26"
          points={[{ label: 'Jun', income: 40000, expense: 20000 }]}
        />
      </ThemeProvider>
    );
    expect(screen.getByText('▼ 5.0% vs the month before Jun 26')).toBeTruthy();
    expect(screen.queryByText(/last month/)).toBeNull();
  });

  it('singularizes the month count and states the real window even for a single point', () => {
    render(
      <ThemeProvider>
        <CashFlowMiniCard deltaPct={null} deltaLabel="vs last month" points={[{ label: 'Aug', income: 40000, expense: 20000 }]} />
      </ThemeProvider>
    );
    expect(screen.getByText('Average Monthly Savings (1 mo)')).toBeTruthy();
  });

  it('renders nothing with no points -- the full Cash Flow card explains why', () => {
    const { toJSON } = render(<ThemeProvider><CashFlowMiniCard points={[]} deltaPct={null} deltaLabel="vs last month" /></ThemeProvider>);
    expect(toJSON()).toBeNull();
  });
});
