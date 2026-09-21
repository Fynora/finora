import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { InvestmentActivity } from './InvestmentActivity';
import { categoriesApi, transactionsApi } from '../api/endpoints';
import type { Transaction } from '../types';

vi.mock('../api/endpoints', () => ({
  categoriesApi: { list: vi.fn() },
  transactionsApi: { search: vi.fn() },
}));

const INVESTMENTS = { id: 'cat-inv', name: 'Investments', isSystem: true, icon: 'trending-up', color: 'teal' };

function txn(id: string, amount: number, overrides: Partial<Transaction> = {}): Transaction {
  return {
    id,
    description: `SIP ${id}`,
    date: '2026-08-05',
    amount,
    type: 'EXPENSE',
    reconciliationStatus: 'INVESTMENT_TRANSFER',
    ...overrides,
  } as Transaction;
}

function page(content: Transaction[], totalPages = 1, pageIndex = 0) {
  return { content, page: pageIndex, size: 100, totalElements: content.length, totalPages };
}

function pending<T>(): Promise<T> {
  return new Promise<T>(() => {});
}

describe('InvestmentActivity', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(categoriesApi.list).mockResolvedValue([INVESTMENTS]);
  });

  afterEach(() => { vi.useRealTimers(); });

  it('announces loading immediately, and never shows the empty state while the fetch is in flight', () => {
    vi.mocked(categoriesApi.list).mockReturnValue(pending());

    render(<InvestmentActivity />);

    expect(screen.getByText('Loading your investment activity')).toBeInTheDocument();
    expect(screen.queryByText('No SIPs or broker transfers yet')).not.toBeInTheDocument();
  });

  it('lists each outflow with its description, date and amount, and totals the period', async () => {
    vi.mocked(transactionsApi.search).mockResolvedValue(page([
      txn('a', 3000, { description: 'UPI-GROWW INVEST TECH', date: '2026-08-05' }),
      txn('b', 1234.4, { description: 'NET PAYIN TO NSE MF', date: '2026-07-06' }),
    ]));

    render(<InvestmentActivity />);

    expect(await screen.findByText('UPI-GROWW INVEST TECH')).toBeInTheDocument();
    expect(screen.getByText('NET PAYIN TO NSE MF')).toBeInTheDocument();
    expect(screen.getByText('₹3,000')).toBeInTheDocument();
    expect(screen.getByText('₹1,234')).toBeInTheDocument();
    expect(screen.getByText('5 Aug 2026')).toBeInTheDocument();
    expect(screen.getByTestId('invested-total')).toHaveTextContent('₹4,234');
    expect(screen.getByText('2 payments')).toBeInTheDocument();
  });

  it('asks only for the Investments category, only outflows, newest first, bounded to the period', async () => {
    vi.mocked(transactionsApi.search).mockResolvedValue(page([txn('a', 100)]));

    render(<InvestmentActivity />);
    await screen.findByText('SIP a');

    const filters = vi.mocked(transactionsApi.search).mock.calls[0][0];
    expect(filters).toMatchObject({ categoryId: 'cat-inv', type: 'EXPENSE', sortField: 'date', sortDir: 'desc', page: 0, size: 100 });
    expect(filters.dateFrom).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(filters.dateTo).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(filters.dateFrom! < filters.dateTo!).toBe(true);
  });

  // The engine's own verdict that a row is a second copy, or was replaced by a re-upload, means it
  // is not a second investment -- counting it would inflate "invested".
  it('does not count a row reconciliation marked DUPLICATE or SUPERSEDED', async () => {
    vi.mocked(transactionsApi.search).mockResolvedValue(page([
      txn('real', 3000),
      txn('dup', 3000, { reconciliationStatus: 'DUPLICATE' }),
      txn('old', 3000, { reconciliationStatus: 'SUPERSEDED' }),
    ]));

    render(<InvestmentActivity />);

    expect(await screen.findByText('SIP real')).toBeInTheDocument();
    expect(screen.queryByText('SIP dup')).not.toBeInTheDocument();
    expect(screen.queryByText('SIP old')).not.toBeInTheDocument();
    expect(screen.getByTestId('invested-total')).toHaveTextContent('₹3,000');
    expect(screen.getByText('1 payment')).toBeInTheDocument();
  });

  // A transfer to a holding the user also tracks is still money invested.
  it('does count a row matched as a TRANSFER to a tracked holding', async () => {
    vi.mocked(transactionsApi.search).mockResolvedValue(page([
      txn('t', 5000, { reconciliationStatus: 'TRANSFER' }),
    ]));

    render(<InvestmentActivity />);

    expect(await screen.findByTestId('invested-total')).toHaveTextContent('₹5,000');
  });

  it('totals across every page of a long period, not just the first', async () => {
    vi.mocked(transactionsApi.search)
      .mockResolvedValueOnce(page([txn('p0', 1000)], 2, 0))
      .mockResolvedValueOnce(page([txn('p1', 2000)], 2, 1));

    render(<InvestmentActivity />);

    await waitFor(() => expect(screen.getByTestId('invested-total')).toHaveTextContent('₹3,000'));
    expect(transactionsApi.search).toHaveBeenCalledTimes(2);
    expect(vi.mocked(transactionsApi.search).mock.calls[1][0]).toMatchObject({ page: 1 });
  });

  it('says "at least" and names the cap when the period has more rows than it will fetch', async () => {
    vi.mocked(transactionsApi.search).mockImplementation(async (f) =>
      page([txn(`r${f.page}`, 100)], 50, f.page ?? 0));

    render(<InvestmentActivity />);

    const total = await screen.findByTestId('invested-total');
    expect(total).toHaveTextContent('At least ₹1,000');
    expect(transactionsApi.search).toHaveBeenCalledTimes(10);
    expect(screen.getByText(/counting the most recent 1000 only/)).toBeInTheDocument();
  });

  it('lists only the newest rows but totals every one, and says how many there are', async () => {
    const many = Array.from({ length: 12 }, (_, i) => txn(`n${i}`, 100));
    vi.mocked(transactionsApi.search).mockResolvedValue(page(many));

    render(<InvestmentActivity />);

    expect(await screen.findByText('SIP n0')).toBeInTheDocument();
    expect(screen.getByText('SIP n7')).toBeInTheDocument();
    expect(screen.queryByText('SIP n8')).not.toBeInTheDocument();
    expect(screen.getByTestId('invested-total')).toHaveTextContent('₹1,200');
    expect(screen.getByText('Showing the latest 8 of 12. The full list is in your Ledger.')).toBeInTheDocument();
  });

  it('shows the empty state, not a zero total, when nothing is filed under Investments', async () => {
    vi.mocked(transactionsApi.search).mockResolvedValue(page([], 0));

    render(<InvestmentActivity />);

    expect(await screen.findByText('No SIPs or broker transfers yet')).toBeInTheDocument();
    expect(screen.queryByTestId('invested-total')).not.toBeInTheDocument();
  });

  it('shows the empty state, and never queries transactions, for a user with no Investments category', async () => {
    vi.mocked(categoriesApi.list).mockResolvedValue([{ ...INVESTMENTS, id: 'other', name: 'Groceries' }]);

    render(<InvestmentActivity />);

    expect(await screen.findByText('No SIPs or broker transfers yet')).toBeInTheDocument();
    expect(transactionsApi.search).not.toHaveBeenCalled();
  });

  it('matches the category name case-insensitively', async () => {
    vi.mocked(categoriesApi.list).mockResolvedValue([{ ...INVESTMENTS, name: 'investments' }]);
    vi.mocked(transactionsApi.search).mockResolvedValue(page([txn('a', 100)]));

    render(<InvestmentActivity />);

    expect(await screen.findByText('SIP a')).toBeInTheDocument();
  });

  // "No SIPs yet" would be a false claim to someone whose fetch simply failed.
  it('shows an error, not the empty state, when the transactions fetch fails', async () => {
    vi.mocked(transactionsApi.search).mockRejectedValue(new Error('offline'));

    render(<InvestmentActivity />);

    expect(await screen.findByText(/Couldn't load your investment activity/)).toBeInTheDocument();
    expect(screen.queryByText('No SIPs or broker transfers yet')).not.toBeInTheDocument();
  });

  it('shows an error when the category list fails to load', async () => {
    vi.mocked(categoriesApi.list).mockRejectedValue(new Error('offline'));

    render(<InvestmentActivity />);

    expect(await screen.findByText(/Couldn't load your investment activity/)).toBeInTheDocument();
    expect(transactionsApi.search).not.toHaveBeenCalled();
  });

  it('re-queries with a different start date when the period changes, and labels the total for it', async () => {
    const user = userEvent.setup();
    vi.mocked(transactionsApi.search).mockResolvedValue(page([txn('a', 100)]));

    render(<InvestmentActivity />);
    await screen.findByText('SIP a');
    const twelveMonthStart = vi.mocked(transactionsApi.search).mock.calls[0][0].dateFrom!;
    expect(screen.getByText('Invested in the last 12 months')).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Period'), '3');

    await waitFor(() => expect(transactionsApi.search).toHaveBeenCalledTimes(2));
    const threeMonthStart = vi.mocked(transactionsApi.search).mock.calls[1][0].dateFrom!;
    expect(threeMonthStart > twelveMonthStart).toBe(true);
    expect(await screen.findByText('Invested in the last 3 months')).toBeInTheDocument();
  });

  // A slower response for the period the user just left must not land after, and overwrite, the
  // newer one.
  it('ignores a superseded response when the period is switched mid-flight', async () => {
    const user = userEvent.setup();
    const resolvers: Array<(p: ReturnType<typeof page>) => void> = [];
    vi.mocked(transactionsApi.search).mockImplementation(
      () => new Promise((resolve) => { resolvers.push(resolve as never); }));

    render(<InvestmentActivity />);
    await waitFor(() => expect(resolvers).toHaveLength(1)); // 12-month request in flight

    await user.selectOptions(screen.getByLabelText('Period'), '3');
    await waitFor(() => expect(resolvers).toHaveLength(2)); // 3-month request in flight

    resolvers[1](page([txn('three', 300, { description: 'THREE MONTH ROW' })]));
    expect(await screen.findByText('THREE MONTH ROW')).toBeInTheDocument();

    resolvers[0](page([txn('twelve', 9000, { description: 'TWELVE MONTH ROW' })]));
    await new Promise((r) => setTimeout(r, 0));

    expect(screen.queryByText('TWELVE MONTH ROW')).not.toBeInTheDocument();
    expect(screen.getByTestId('invested-total')).toHaveTextContent('₹300');
  });
});
