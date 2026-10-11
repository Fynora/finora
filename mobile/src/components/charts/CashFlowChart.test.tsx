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
