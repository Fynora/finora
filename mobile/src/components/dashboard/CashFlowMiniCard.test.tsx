import { render, screen } from '@testing-library/react-native';
import { CashFlowMiniCard } from './CashFlowMiniCard';
import { ThemeProvider } from '../../theme';
import { light } from '../../theme/palette';

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
    expect(screen.getByText('▲ 22.0%')).toBeTruthy();
    expect(screen.getByText('vs last month')).toBeTruthy();
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
    expect(screen.getByText('▼ 5.0%')).toBeTruthy();
    expect(screen.getByText('vs the month before Jun 26')).toBeTruthy();
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

  // One month is a point, not a line: without a marker the chart area would be an empty box.
  it('marks a single month with a dot, and draws no dot once there is a line', () => {
    render(<ThemeProvider><CashFlowMiniCard deltaPct={null} deltaLabel="vs last month" points={[{ label: 'Aug', income: 40000, expense: 20000 }]} /></ThemeProvider>);
    expect(screen.getByTestId('cash-flow-trend-dot')).toBeTruthy();
    render(<ThemeProvider><CashFlowMiniCard deltaPct={null} deltaLabel="vs last month" points={[{ label: 'Jul', income: 1, expense: 0 }, { label: 'Aug', income: 2, expense: 0 }]} /></ThemeProvider>);
    expect(screen.queryByTestId('cash-flow-trend-dot')).toBeNull();
  });

  it('draws the trend even when every month nets to the same amount', () => {
    render(<ThemeProvider><CashFlowMiniCard points={[{ label: 'May', income: 100, expense: 50 }, { label: 'Jun', income: 100, expense: 50 }]} deltaPct={null} deltaLabel="vs last month" /></ThemeProvider>);
    expect(screen.getByTestId('cash-flow-trend-chart')).toBeTruthy();
    // No delta, so the comparison it would have named is not shown either.
    expect(screen.queryByText('vs last month')).toBeNull();
  });

  it('colours a fall in net cash flow as bad', () => {
    render(<ThemeProvider><CashFlowMiniCard deltaPct={-5} deltaLabel="vs last month" points={[{ label: 'Jun', income: 40000, expense: 20000 }]} /></ThemeProvider>);
    expect(screen.getByText('▼ 5.0%')).toHaveStyle({ color: light.dangerInk });
  });

  it('renders nothing with no points -- the full Cash Flow card explains why', () => {
    const { toJSON } = render(<ThemeProvider><CashFlowMiniCard points={[]} deltaPct={null} deltaLabel="vs last month" /></ThemeProvider>);
    expect(toJSON()).toBeNull();
  });
});
