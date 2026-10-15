import { StyleSheet } from 'react-native';
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

  const months = (labels: string[]) => labels.map((label, i) => ({ label, income: 100 + i, expense: 50 }));
  const hidden = { includeHiddenElements: true };

  // The approved design names the months under the line; without them the trend has no scale.
  // They are hidden from assistive tech: the full Cash Flow card further down reads the same
  // months out with their amounts, and a second set of bare month names adds nothing spoken.
  it('names the months under the trend, for sighted readers only', () => {
    render(<ThemeProvider><CashFlowMiniCard deltaPct={null} deltaLabel="vs last month" points={months(['May 26', 'Jun 26', 'Jul 26', 'Aug 26', 'Sep 26', 'Oct 26'])} /></ThemeProvider>);
    const axis = screen.getByTestId('cash-flow-trend-axis', hidden);
    expect(axis.props.accessibilityElementsHidden).toBe(true);
    expect(axis.props.importantForAccessibility).toBe('no-hide-descendants');
    for (const month of ['May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct']) {
      expect(screen.getByText(month, hidden)).toBeTruthy();
      expect(screen.queryByText(month)).toBeNull();
    }
  });

  // Measured on an iPhone 17 Pro screenshot: with the year ("Apr 26") the first two labels sat
  // 8 points apart and the rest 23, and by arithmetic they overlap at the capped large text size
  // on a 360 point wide phone. The approved design names the month alone, which leaves 17 points
  // in that worst case. The year is on the full Cash Flow card and the month header.
  it('names the month alone, without the year its label carries', () => {
    render(<ThemeProvider><CashFlowMiniCard deltaPct={null} deltaLabel="vs last month" points={months(['Sep 26', 'Oct 26'])} /></ThemeProvider>);
    expect(screen.getByText('Oct', hidden)).toBeTruthy();
    expect(screen.queryByText('Oct 26', hidden)).toBeNull();
  });

  it('shows a label that has no year as it is', () => {
    render(<ThemeProvider><CashFlowMiniCard deltaPct={null} deltaLabel="vs last month" points={months(['Sep', 'Oct'])} /></ThemeProvider>);
    expect(screen.getByText('Oct', hidden)).toBeTruthy();
  });

  it('puts each month under its own point: first flush left, last flush right, the rest centred on the point', () => {
    render(<ThemeProvider><CashFlowMiniCard deltaPct={null} deltaLabel="vs last month" points={months(['May 26', 'Jun 26', 'Jul 26'])} /></ThemeProvider>);
    const slot = (label: string) => StyleSheet.flatten(screen.getByTestId(`cash-flow-trend-month-${label}`, hidden).props.style);
    expect(slot('May 26')).toMatchObject({ position: 'absolute', left: 0 });
    expect(slot('Jul 26')).toMatchObject({ position: 'absolute', right: 0 });
    // The middle point of three sits at half the chart's width.
    expect(slot('Jun 26')).toMatchObject({ position: 'absolute', left: '50%', alignItems: 'center' });
  });

  it('names every other month once there are more than six, always keeping the latest', () => {
    const year = ['Nov 25', 'Dec 25', 'Jan 26', 'Feb 26', 'Mar 26', 'Apr 26', 'May 26', 'Jun 26', 'Jul 26', 'Aug 26', 'Sep 26', 'Oct 26'];
    render(<ThemeProvider><CashFlowMiniCard deltaPct={null} deltaLabel="vs last month" points={months(year)} /></ThemeProvider>);
    const shown = year.filter((label) => screen.queryByTestId(`cash-flow-trend-month-${label}`, hidden) !== null);
    expect(shown).toEqual(['Dec 25', 'Feb 26', 'Apr 26', 'Jun 26', 'Aug 26', 'Oct 26']);
  });

  it('centres the one label under the dot when there is a single month', () => {
    render(<ThemeProvider><CashFlowMiniCard deltaPct={null} deltaLabel="vs last month" points={months(['Aug 26'])} /></ThemeProvider>);
    expect(StyleSheet.flatten(screen.getByTestId('cash-flow-trend-month-Aug 26', hidden).props.style)).toMatchObject({ left: '50%', alignItems: 'center' });
  });

  it('caps how far the month labels grow with Dynamic Type, since their spacing is fixed by the chart', () => {
    render(<ThemeProvider><CashFlowMiniCard deltaPct={null} deltaLabel="vs last month" points={months(['Sep 26', 'Oct 26'])} /></ThemeProvider>);
    expect(screen.getByText('Oct', hidden).props.maxFontSizeMultiplier).toBe(1.3);
    expect(screen.getByText('Oct', hidden).props.numberOfLines).toBe(1);
  });

  // The line ends on the latest month; the marker says which end is "now".
  it('marks the latest month at the end of the line', () => {
    render(<ThemeProvider><CashFlowMiniCard deltaPct={null} deltaLabel="vs last month" points={months(['Sep 26', 'Oct 26'])} /></ThemeProvider>);
    expect(screen.getByTestId('cash-flow-trend-end', hidden)).toBeTruthy();
  });

  it('stars a partial latest month on the axis, rings its end point, and explains the star', () => {
    const pts = [{ label: 'Aug 26', income: 100, expense: 50 }, { label: 'Sep 26', income: 100, expense: 50, partial: true }];
    render(<ThemeProvider><CashFlowMiniCard deltaPct={null} deltaLabel="vs last month" points={pts} /></ThemeProvider>);
    expect(screen.getByText('Sep*', hidden)).toBeTruthy();
    expect(screen.getByText('Aug', hidden)).toBeTruthy();
    expect(screen.getByTestId('cash-flow-trend-end', hidden).props.strokeWidth).toBe(2);
    expect(screen.getByTestId('cash-flow-trend-partial-note')).toBeTruthy();
  });

  it('leaves a whole latest month as a solid point with no star or footnote', () => {
    render(<ThemeProvider><CashFlowMiniCard deltaPct={null} deltaLabel="vs last month" points={months(['Aug 26', 'Sep 26'])} /></ThemeProvider>);
    expect(screen.queryByText('Sep*', hidden)).toBeNull();
    expect(screen.getByTestId('cash-flow-trend-end', hidden).props.strokeWidth).toBe(0);
    expect(screen.queryByTestId('cash-flow-trend-partial-note')).toBeNull();
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

  const POINTS = [{ label: 'Jun', income: 40000, expense: 20000 }];
  const NOTE = "September is partial so far: HDFC Card's latest data ends Sep 11, so its last days aren't in yet.";

  it('says the month is "so far" beside the net change it labels, and keeps the figures', () => {
    render(<ThemeProvider><CashFlowMiniCard deltaPct={22} deltaLabel="vs last month" points={POINTS} note={NOTE} /></ThemeProvider>);
    expect(screen.getByTestId('cash-flow-so-far-note').props.children).toBe(NOTE);
    expect(screen.getByText('▲ 22.0%')).toBeTruthy();
    expect(screen.getByText('₹20,000')).toBeTruthy();
  });

  it('shows no note when there is none, or when there is no net change for it to label', () => {
    render(<ThemeProvider><CashFlowMiniCard deltaPct={22} deltaLabel="vs last month" points={POINTS} /></ThemeProvider>);
    expect(screen.queryByTestId('cash-flow-so-far-note')).toBeNull();
    screen.unmount();
    render(<ThemeProvider><CashFlowMiniCard deltaPct={null} deltaLabel="vs last month" points={POINTS} note={NOTE} /></ThemeProvider>);
    expect(screen.queryByTestId('cash-flow-so-far-note')).toBeNull();
  });

  it('renders nothing with no points -- the full Cash Flow card explains why', () => {
    const { toJSON } = render(<ThemeProvider><CashFlowMiniCard points={[]} deltaPct={null} deltaLabel="vs last month" /></ThemeProvider>);
    expect(toJSON()).toBeNull();
  });
});
