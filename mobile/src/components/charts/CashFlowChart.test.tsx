import { render, screen } from '@testing-library/react-native';
import { CashFlowChart } from './CashFlowChart';
import { ThemeProvider } from '../../theme';

const points = ['Apr 26', 'May 26', 'Jun 26', 'Jul 26', 'Aug 26', 'Sep 26'].map((label, i) => ({
  label, income: 1000 + i, expense: 500 + i,
}));

describe('CashFlowChart axis', () => {
  // Found on an iPhone 17 Pro simulator at an accessibility text size: the month labels share
  // the chart's fixed width, so uncapped they were cut to "Apr...", "May...". They are hidden
  // from assistive tech (the chart's own label reads every month and amount), so the cap costs
  // a screen-reader user nothing.
  it('caps how far the month labels grow with Dynamic Type, since their spacing is fixed by the chart', () => {
    render(<ThemeProvider><CashFlowChart points={points} width={313} /></ThemeProvider>);
    for (const p of points) {
      const label = screen.getByText(p.label, { includeHiddenElements: true });
      expect(label.props.maxFontSizeMultiplier).toBe(1.3);
      expect(label.props.numberOfLines).toBe(1);
    }
  });

  it('still reads every month and amount to assistive tech', () => {
    render(<ThemeProvider><CashFlowChart points={points} width={313} /></ThemeProvider>);
    expect(screen.getByLabelText(/^Cash flow over 6 months\. Apr 26: income ₹1,000, expense ₹500\./)).toBeTruthy();
  });
});

describe('CashFlowChart partial month', () => {
  const withPartialLast = points.map((p, i) => (i === points.length - 1 ? { ...p, partial: true } : p));

  it('stars the partial month, rings its points, explains the star, and says so to assistive tech', () => {
    render(<ThemeProvider><CashFlowChart points={withPartialLast} width={313} /></ThemeProvider>);
    expect(screen.getByText('Sep 26*', { includeHiddenElements: true })).toBeTruthy();
    expect(screen.getByText('Aug 26', { includeHiddenElements: true })).toBeTruthy();
    expect(screen.getByTestId('cash-flow-partial-income-Sep 26')).toBeTruthy();
    expect(screen.getByTestId('cash-flow-partial-expense-Sep 26')).toBeTruthy();
    expect(screen.queryByTestId('cash-flow-partial-income-Aug 26')).toBeNull();
    expect(screen.getByTestId('cash-flow-partial-note')).toBeTruthy();
    expect(screen.getByLabelText(/Sep 26 \(partial month\): income/)).toBeTruthy();
  });

  it('draws every month as an ordinary point, with no note, when none is partial', () => {
    render(<ThemeProvider><CashFlowChart points={points} width={313} /></ThemeProvider>);
    expect(screen.queryByTestId('cash-flow-partial-note')).toBeNull();
    expect(screen.queryByText(/\*/, { includeHiddenElements: true })).toBeNull();
    expect(screen.queryByLabelText(/partial month/)).toBeNull();
  });
});
