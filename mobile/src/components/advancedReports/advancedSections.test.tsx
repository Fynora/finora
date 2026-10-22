import { fireEvent, render, screen } from '@testing-library/react-native';
import { StyleSheet } from 'react-native';
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fixtureReport } from '../../lib/advancedReports/testFixtures';
import { ThemeProvider } from '../../theme';
import { light } from '../../theme/palette';
import { AdvancedKpis } from './AdvancedKpis';
import { CategoryList } from './CategoryList';
import { CategoryTrendChart } from './CategoryTrendChart';
import { IncomeSection } from './IncomeSection';
import { MerchantList } from './MerchantList';
import { ChangeText, shareText, spokenChange } from './parts';
import { UnusualList } from './UnusualList';

const wrap = (node: ReactNode) => render(
  <QueryClientProvider client={new QueryClient()}><ThemeProvider>{node}</ThemeProvider></QueryClientProvider>,
);
const open = jest.fn();
const colorOf = (node: { props: { style?: unknown } }) => (StyleSheet.flatten(node.props.style as never) as { color?: string }).color;
beforeEach(() => open.mockClear());

describe('shareText', () => {
  it('never reads real money as 0%', () => {
    expect(shareText(0.4)).toBe('<1%');
    expect(shareText(1)).toBe('1%');
    expect(shareText(59.6)).toBe('60%');
  });
});

describe('spokenChange', () => {
  it('says nothing when the comparison was withheld, and words a change the way a screen reader should', () => {
    expect(spokenChange(null, null, null)).toBeNull();
    expect(spokenChange(20000, 50, 40000)).toBe('up ₹20,000, 50 percent');
    expect(spokenChange(-10000, -25, 40000)).toBe('down ₹10,000, 25 percent');
    expect(spokenChange(0.2, 0, 40000)).toBe('no change');
    expect(spokenChange(12000, null, 0)).toBe('new, nothing in the earlier period');
    expect(spokenChange(0, null, 0)).toBeNull();
  });
});

describe('ChangeText', () => {
  it('shows nothing when withheld, "New" when it had nothing before, and the sign and arrow otherwise', () => {
    const { toJSON, rerender } = wrap(<ChangeText previousAmount={null} changeAmount={null} changePct={null} />);
    expect(toJSON()).toBeNull();
    rerender(<QueryClientProvider client={new QueryClient()}><ThemeProvider><ChangeText previousAmount={0} changeAmount={12000} changePct={null} /></ThemeProvider></QueryClientProvider>);
    expect(screen.getByText('New, nothing earlier')).toBeTruthy();
    rerender(<QueryClientProvider client={new QueryClient()}><ThemeProvider><ChangeText previousAmount={40000} changeAmount={20000} changePct={50} /></ThemeProvider></QueryClientProvider>);
    // More spent is in the danger ink, less in the success ink, and the sign says it too.
    expect(colorOf(screen.getByText('+₹20,000 · ↑ 50%'))).toBe(light.dangerInk);
    rerender(<QueryClientProvider client={new QueryClient()}><ThemeProvider><ChangeText previousAmount={40000} changeAmount={-10000} changePct={-25} /></ThemeProvider></QueryClientProvider>);
    expect(colorOf(screen.getByText('-₹10,000 · ↓ 25%'))).toBe(light.successInk);
    rerender(<QueryClientProvider client={new QueryClient()}><ThemeProvider><ChangeText previousAmount={40000} changeAmount={0.3} changePct={0} /></ThemeProvider></QueryClientProvider>);
    expect(colorOf(screen.getByText('No change'))).toBe(light.mutedInk);
  });
});

describe('AdvancedKpis', () => {
  it('never colours "No change"', () => {
    const base = fixtureReport();
    wrap(<AdvancedKpis report={fixtureReport({ comparison: { ...base.comparison, incomeDeltaPct: 0.1 } })} />);
    // Not green, though the change is not negative: no change is neither good nor bad.
    expect(colorOf(screen.getByText('No change'))).toBe(light.mutedInk);
  });

  it('shows a dash, not a zero, for an average when no month has transactions', () => {
    const base = fixtureReport();
    wrap(<AdvancedKpis report={fixtureReport({ totals: { ...base.totals, avgMonthlyExpense: null, monthsWithData: 0 } })} />);
    expect(screen.getByTestId('kpi-average')).toHaveTextContent(/—/);
  });

  it('writes a negative net with its sign', () => {
    const base = fixtureReport();
    wrap(<AdvancedKpis report={fixtureReport({ totals: { ...base.totals, net: -5000 } })} />);
    expect(screen.getByTestId('kpi-net')).toHaveTextContent(/-₹5,000/);
  });
});

describe('CategoryList', () => {
  const order = () => screen.getAllByTestId(/^category-(?!list|other)/).map((n) => n.props.testID);

  it('ranks by amount, then by size of change when asked, so a fast riser is not buried', () => {
    wrap(<CategoryList report={fixtureReport()} onOpen={open} />);
    expect(order()).toEqual(['category-Rent', 'category-Food & Dining', 'category-Shopping', 'category-Travel']);

    fireEvent.press(screen.getByLabelText('Sort by change'));

    expect(order()).toEqual(['category-Food & Dining', 'category-Travel', 'category-Shopping', 'category-Rent']);
  });

  it('offers no change sort when there is no comparison', () => {
    const base = fixtureReport();
    wrap(<CategoryList report={fixtureReport({ comparison: { ...base.comparison, reliable: false, gateReason: 'COVERAGE_GAP' } })} onOpen={open} />);
    expect(screen.queryByLabelText('Sort by change')).toBeNull();
  });

  it('writes a share under one percent as "<1%"', () => {
    const base = fixtureReport();
    wrap(<CategoryList report={fixtureReport({ categories: { ...base.categories, rows: [{ ...base.categories.rows[0], sharePct: 0.4 }] } })} onOpen={open} />);
    expect(screen.getByLabelText(/Rent: ₹1,20,000, <1% of spending/)).toBeTruthy();
  });

  it('has an empty state for a period with no spending', () => {
    const base = fixtureReport();
    wrap(<CategoryList report={fixtureReport({ categories: { ...base.categories, rows: [], otherAmount: 0 } })} onOpen={open} />);
    expect(screen.getByText('No spending in this period.')).toBeTruthy();
  });
});

describe('MerchantList', () => {
  it('has an empty state, and no coverage line, when no spending names a merchant', () => {
    const base = fixtureReport();
    wrap(<MerchantList report={fixtureReport({ merchants: { ...base.merchants, rows: [], merchantCount: 0 } })} onOpen={open} />);
    expect(screen.getByText(/No merchant spending yet/)).toBeTruthy();
    expect(screen.queryByTestId('merchant-coverage')).toBeNull();
  });
});

describe('IncomeSection', () => {
  it('names credits that are not counted as income yet, and does not fold them in', () => {
    const base = fixtureReport();
    wrap(<IncomeSection report={fixtureReport({ totals: { ...base.totals, unresolvedInflow: 30000, unresolvedInflowCount: 3 } })} />);
    expect(screen.getByTestId('unresolved-inflow')).toHaveTextContent(/₹30,000 in 3 credits are not counted as income yet/);
  });

  it('says there is no income, rather than drawing empty bars, when there is none', () => {
    const base = fixtureReport();
    wrap(<IncomeSection report={fixtureReport({ totals: { ...base.totals, income: 0 }, income: { ...base.income, sources: [] } })} />);
    expect(screen.getByTestId('income-empty')).toBeTruthy();
  });

  it('marks a month that is only part of a month', () => {
    const base = fixtureReport();
    wrap(<IncomeSection report={fixtureReport({ months: base.months.map((m) => (m.month === '2026-04' ? { ...m, complete: false } : m)) })} />);
    expect(screen.getByLabelText(/Apr 2026, only part of the month/)).toBeTruthy();
  });
});

describe('UnusualList', () => {
  it('draws nothing when there is nothing larger than usual', () => {
    const { toJSON } = wrap(<UnusualList items={[]} onOpen={open} />);
    expect(toJSON()).toBeNull();
  });

  it('says "larger than usual", with the rule, and opens that day', () => {
    wrap(<UnusualList items={[{ transactionId: 't1', date: '2026-08-17', label: 'IndiGo', category: 'Travel', amount: 38400, typicalAmount: 4000, timesTypical: 9.6, sampleSize: 6 }]} onOpen={open} />);
    expect(screen.getByText(/about 9.6 times the usual ₹4,000/)).toBeTruthy();
    expect(screen.getByText(/not a sign that anything is wrong/)).toBeTruthy();

    fireEvent.press(screen.getByLabelText(/IndiGo, ₹38,400/));

    expect(open).toHaveBeenCalledWith({ dateFrom: '2026-08-17', dateTo: '2026-08-17', categoryName: 'Travel', label: 'IndiGo' });
  });
});

describe('CategoryTrendChart', () => {
  it('is not drawn for one month, which is not a trend', () => {
    const base = fixtureReport();
    const { toJSON } = wrap(<CategoryTrendChart months={base.months.slice(0, 1)} categories={base.categories.rows} />);
    expect(toJSON()).toBeNull();
  });

  it('draws three lines by default, can draw four, and gives the same figures as a list', () => {
    const base = fixtureReport();
    wrap(<CategoryTrendChart months={base.months} categories={base.categories.rows} />);
    expect(screen.getByLabelText(/^Rent: Apr ₹20,000/)).toBeTruthy();
    expect(screen.queryByLabelText(/Travel: Apr ₹/)).toBeNull();

    fireEvent.press(screen.getByLabelText('Top 4 categories'));
    expect(screen.getByLabelText(/Travel: Apr ₹/)).toBeTruthy();

    fireEvent.press(screen.getByLabelText('Show as a list'));
    expect(screen.getByLabelText(/Jul 2026: Rent ₹20,000, Food & Dining ₹11,000, Shopping ₹4,000, Travel ₹12,000/)).toBeTruthy();
  });
});
