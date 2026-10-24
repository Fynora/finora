import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { ReactNode } from 'react';
import { ThemeProvider } from '../../context/ThemeContext';
import { CategoriesPanel } from './CategoriesPanel';
import { CategoryTrend } from './CategoryTrend';
import { ChangeLine } from './ChangeLine';
import { IncomePanel } from './IncomePanel';
import { KpiRow } from './KpiRow';
import { MerchantsPanel } from './MerchantsPanel';
import { UnusualSpends } from './UnusualSpends';
import { fixtureReport } from './testFixtures';

const wrap = (node: ReactNode) => render(<ThemeProvider><MemoryRouter>{node}</MemoryRouter></ThemeProvider>);

describe('ChangeLine', () => {
  it('shows nothing when the comparison was withheld, so a change is never invented', () => {
    const { container } = render(<ChangeLine previousAmount={null} changeAmount={null} changePct={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('says a row is new when it had nothing before, and has no percentage to give', () => {
    render(<ChangeLine previousAmount={0} changeAmount={12000} changePct={null} />);
    expect(screen.getByText('New, nothing in the earlier period')).toBeInTheDocument();
  });

  it('writes more spent in danger and less in success, with the sign and arrow in the text', () => {
    const { rerender } = render(<ChangeLine previousAmount={40000} changeAmount={20000} changePct={50} />);
    expect(screen.getByText('+₹20,000 · ↑ 50%')).toHaveClass('text-danger');
    rerender(<ChangeLine previousAmount={40000} changeAmount={-10000} changePct={-25} />);
    expect(screen.getByText('-₹10,000 · ↓ 25%')).toHaveClass('text-success');
  });

  it('says "No change" in neither colour when it rounds to nothing', () => {
    render(<ChangeLine previousAmount={40000} changeAmount={0.3} changePct={0} />);
    expect(screen.getByText('No change')).not.toHaveClass('text-danger');
    expect(screen.getByText('No change')).not.toHaveClass('text-success');
  });
});

describe('KpiRow', () => {
  it('never colours "No change"', () => {
    const base = fixtureReport();
    wrap(<KpiRow report={fixtureReport({ comparison: { ...base.comparison, incomeDeltaPct: 0.1 } })} />);
    const noChange = within(screen.getByTestId('kpi-income').parentElement!).getByText('No change');
    expect(noChange).toHaveClass('text-ink');
  });

  it('compares net as an amount, never a percentage', () => {
    wrap(<KpiRow report={fixtureReport()} />);
    expect(within(screen.getByTestId('kpi-net').parentElement!).getByText('↑ ₹20,000')).toBeInTheDocument();
  });

  it('draws a negative net in danger and still writes the sign', () => {
    const base = fixtureReport();
    wrap(<KpiRow report={fixtureReport({ totals: { ...base.totals, net: -5000 } })} />);
    expect(screen.getByTestId('kpi-net')).toHaveTextContent('-₹5,000');
    expect(screen.getByTestId('kpi-net')).toHaveClass('text-danger');
  });

  it('shows a dash, not a zero, for an average when no month has transactions', () => {
    const base = fixtureReport();
    wrap(<KpiRow report={fixtureReport({ totals: { ...base.totals, avgMonthlyExpense: null, monthsWithData: 0 } })} />);
    expect(screen.getByTestId('kpi-average')).toHaveTextContent('—');
  });
});

describe('CategoriesPanel', () => {
  const names = () => screen.getAllByTestId(/^category-(?!rows|other)/).map((li) => li.getAttribute('data-testid'));

  it('ranks by amount, then by size of change when asked, so a fast riser is not buried', () => {
    wrap(<CategoriesPanel report={fixtureReport()} />);
    expect(names()).toEqual(['category-Rent', 'category-Food & Dining', 'category-Shopping', 'category-Travel']);

    fireEvent.click(screen.getByRole('button', { name: 'By change' }));

    // Food +20,000, Travel +12,000, Shopping -10,000, Rent 0.
    expect(names()).toEqual(['category-Food & Dining', 'category-Travel', 'category-Shopping', 'category-Rent']);
  });

  it('offers no change sort when there is no comparison', () => {
    const base = fixtureReport();
    wrap(<CategoriesPanel report={fixtureReport({ comparison: { ...base.comparison, reliable: false, gateReason: 'COVERAGE_GAP' } })} />);
    expect(screen.queryByRole('button', { name: 'By change' })).not.toBeInTheDocument();
  });

  it('says how many categories are in "Other", and gives the share of the spending', () => {
    wrap(<CategoriesPanel report={fixtureReport()} />);
    expect(screen.getByTestId('category-other')).toHaveTextContent('Other (3 more)');
    expect(screen.getByTestId('category-other')).toHaveTextContent('₹18,000');
    expect(screen.getByTestId('category-other')).toHaveTextContent('8%');
  });

  // A category can be money sent to people (Personal Transfer), which is not a purchase, so every
  // row counts payments.
  it('counts payments, never purchases, since a category can be money sent to people', () => {
    const base = fixtureReport();
    wrap(<CategoriesPanel report={fixtureReport({ categories: { ...base.categories, rows: [
      { ...base.categories.rows[0], name: 'Personal Transfer', transactionCount: 1 },
      { ...base.categories.rows[1], name: 'Food & Dining', transactionCount: 5 },
    ] } })} />);
    expect(screen.getByTestId('category-Personal Transfer')).toHaveTextContent('1 payment');
    expect(screen.getByTestId('category-Food & Dining')).toHaveTextContent('5 payments');
    expect(screen.getByTestId('category-rows')).not.toHaveTextContent(/purchase/i);
  });

  it('writes a share under one percent as "<1%", never "0%"', () => {
    const base = fixtureReport();
    wrap(<CategoriesPanel report={fixtureReport({ categories: { ...base.categories, rows: [{ ...base.categories.rows[0], sharePct: 0.4 }] } })} />);
    expect(screen.getByTestId('category-Rent')).toHaveTextContent('<1%');
  });

  it('shows a short list with a way to the full one on the overview', () => {
    let opened = 0;
    wrap(<CategoriesPanel report={fixtureReport()} limit={2} onViewAll={() => { opened += 1; }} />);
    expect(names()).toHaveLength(2);
    expect(screen.queryByTestId('category-other')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'View all categories' }));
    expect(opened).toBe(1);
  });

  it('has a real empty state for a period with no spending', () => {
    const base = fixtureReport();
    wrap(<CategoriesPanel report={fixtureReport({ categories: { ...base.categories, rows: [], otherAmount: 0 } })} />);
    expect(screen.getByText('No spending in this period')).toBeInTheDocument();
  });
});

describe('MerchantsPanel', () => {
  it('says how much of the spending the ranking covers, and how much the top five take', () => {
    wrap(<MerchantsPanel report={fixtureReport()} />);
    expect(screen.getByTestId('merchant-coverage')).toHaveTextContent('62% of your spending is tied to a named merchant');
    expect(screen.getByTestId('merchant-coverage')).toHaveTextContent('take 45% of all your spending');
  });

  // A friend can rank here under their own name, and money sent to a person is not a purchase, so
  // every row counts payments.
  it('counts payments, never purchases, since a row can be a person', () => {
    const base = fixtureReport();
    wrap(<MerchantsPanel report={fixtureReport({ merchants: { ...base.merchants, rows: [
      { ...base.merchants.rows[0], merchantId: 'm1', name: 'Sample Person', transactionCount: 1, logoDomain: null },
      { ...base.merchants.rows[1], merchantId: 'm2', name: 'Swiggy', transactionCount: 30 },
    ] } })} />);
    expect(screen.getByTestId('merchant-Sample Person')).toHaveTextContent('1 payment');
    expect(screen.getByTestId('merchant-Swiggy')).toHaveTextContent('30 payments');
    expect(screen.getByTestId('merchant-rows')).not.toHaveTextContent(/purchase/i);
  });

  it('has an empty state when no spending names a merchant', () => {
    const base = fixtureReport();
    wrap(<MerchantsPanel report={fixtureReport({ merchants: { ...base.merchants, rows: [], merchantCount: 0, attributedSharePct: 0, topFiveSharePct: 0 } })} />);
    expect(screen.getByText('No merchant spending yet')).toBeInTheDocument();
    expect(screen.queryByTestId('merchant-coverage')).not.toBeInTheDocument();
  });
});

describe('IncomePanel', () => {
  it('splits income by source, with the earlier amounts, and describes how steady it was', () => {
    wrap(<IncomePanel report={fixtureReport()} />);
    expect(screen.getByTestId('income-sources')).toHaveTextContent('Salary');
    expect(screen.getByTestId('income-sources')).toHaveTextContent('₹5,40,000');
    expect(screen.getByTestId('income-sources')).toHaveTextContent('₹4,80,000 in the previous 6 months');
    expect(screen.getByTestId('income-steadiness')).toHaveTextContent('Income came in during 6 of 6 months.');
    expect(screen.getByTestId('income-steadiness')).toHaveTextContent('the lowest Apr 2026 (₹95,000), 86% of the highest');
  });

  it('names credits that are not counted as income yet, and does not fold them in', () => {
    const base = fixtureReport();
    wrap(<IncomePanel report={fixtureReport({ totals: { ...base.totals, unresolvedInflow: 30000, unresolvedInflowCount: 3 } })} />);
    expect(screen.getByTestId('unresolved-inflow')).toHaveTextContent('₹30,000 in 3 credits are not counted as income yet');
  });

  it('says there is no income, rather than drawing empty bars, when there is none', () => {
    const base = fixtureReport();
    wrap(<IncomePanel report={fixtureReport({ totals: { ...base.totals, income: 0 }, income: { ...base.income, sources: [] } })} />);
    expect(screen.getByText('No income in this period')).toBeInTheDocument();
  });

  it('marks a month that is only part of a month', () => {
    const base = fixtureReport();
    const months = base.months.map((m) => (m.month === '2026-04' ? { ...m, complete: false } : m));
    wrap(<IncomePanel report={fixtureReport({ months })} />);
    expect(screen.getByTestId('income-months')).toHaveTextContent('Apr 2026*');
  });
});

describe('UnusualSpends', () => {
  it('draws nothing when there is nothing larger than usual', () => {
    const { container } = wrap(<UnusualSpends items={[]} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('says "larger than usual", with the rule, and links to that day in the Ledger', () => {
    wrap(<UnusualSpends items={[{ transactionId: 't1', date: '2026-08-17', label: 'IndiGo', category: 'Travel', amount: 38400, typicalAmount: 4000, timesTypical: 9.6, sampleSize: 6 }]} />);
    expect(screen.getByTestId('unusual-spends')).toHaveTextContent('about 9.6 times the usual ₹4,000');
    expect(screen.getByRole('link', { name: 'Open' })).toHaveAttribute('href', '/app/transactions?from=2026-08-17&to=2026-08-17&category=Travel');
    expect(screen.getByText(/not a sign that anything is wrong/)).toBeInTheDocument();
  });
});

describe('CategoryTrend', () => {
  it('is not drawn for one month, which is not a trend', () => {
    const base = fixtureReport();
    const { container } = wrap(<CategoryTrend months={base.months.slice(0, 1)} categories={base.categories.rows} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('draws the top four by default, and the same figures as a table', () => {
    const base = fixtureReport();
    wrap(<CategoryTrend months={base.months} categories={base.categories.rows} />);
    expect(screen.getByTestId('category-trend-chart')).toBeInTheDocument();
    expect(screen.getAllByTestId(/^trend-line-/)).toHaveLength(4);

    fireEvent.click(screen.getByRole('button', { name: /View as table/ }));

    const table = screen.getByTestId('category-trend-table');
    expect(within(table).getByRole('row', { name: /Jul 2026/ })).toHaveTextContent('₹12,000');
  });

  it('lets the reader draw fewer lines', () => {
    const base = fixtureReport();
    wrap(<CategoryTrend months={base.months} categories={base.categories.rows} />);
    fireEvent.change(screen.getByRole('combobox'), { target: { value: '3' } });
    expect(screen.getAllByTestId(/^trend-line-/)).toHaveLength(3);
  });
});
