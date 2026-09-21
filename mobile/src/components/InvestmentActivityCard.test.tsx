import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { InvestmentActivityCard } from './InvestmentActivityCard';
import { categoriesApi, transactionsApi } from '../api/endpoints';
import type { Transaction } from '../types';

jest.mock('../api/endpoints', () => ({
  categoriesApi: { list: jest.fn() },
  transactionsApi: { search: jest.fn() },
}));

const categories = categoriesApi as jest.Mocked<typeof categoriesApi>;
const transactions = transactionsApi as jest.Mocked<typeof transactionsApi>;

const INVESTMENTS = { id: 'cat-inv', name: 'Investments', isSystem: true, icon: 'trending-up', color: 'teal' };

function txn(id: string, amount: number, over: Partial<Transaction> = {}): Transaction {
  return {
    id, description: `SIP ${id}`, date: '2026-08-05', amount, type: 'EXPENSE',
    reconciliationStatus: 'INVESTMENT_TRANSFER', ...over,
  } as Transaction;
}

function page(content: Transaction[], totalPages = 1, pageIndex = 0) {
  return { content, page: pageIndex, size: 100, totalElements: content.length, totalPages };
}

function renderCard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <InvestmentActivityCard />
    </QueryClientProvider>
  );
}

describe('InvestmentActivityCard', () => {
  beforeEach(() => {
    categories.list.mockReset().mockResolvedValue([INVESTMENTS]);
    transactions.search.mockReset().mockResolvedValue(page([]));
  });

  it('lists each outflow with its description, date and amount, and totals the period', async () => {
    transactions.search.mockResolvedValue(page([
      txn('a', 3000, { description: 'UPI-GROWW INVEST TECH', date: '2026-08-05' }),
      txn('b', 1234.4, { description: 'NET PAYIN TO NSE MF', date: '2026-07-06' }),
    ]));
    renderCard();

    expect(await screen.findByText('UPI-GROWW INVEST TECH')).toBeTruthy();
    expect(screen.getByText('NET PAYIN TO NSE MF')).toBeTruthy();
    expect(screen.getByText('₹3,000')).toBeTruthy();
    expect(screen.getByText('₹1,234')).toBeTruthy();
    expect(screen.getByText('5 Aug 2026')).toBeTruthy();
    expect(screen.getByTestId('invested-total')).toHaveTextContent('₹4,234');
    expect(screen.getByText('2 payments')).toBeTruthy();
  });

  it('asks only for the Investments category, only outflows, newest first, bounded to the period', async () => {
    transactions.search.mockResolvedValue(page([txn('a', 100)]));
    renderCard();
    await screen.findByText('SIP a');

    const filters = transactions.search.mock.calls[0][0];
    expect(filters).toMatchObject({ categoryId: 'cat-inv', type: 'EXPENSE', sortField: 'date', sortDir: 'desc', page: 0, size: 100 });
    expect(filters.dateFrom).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(filters.dateTo).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(filters.dateFrom! < filters.dateTo!).toBe(true);
  });

  it('does not count a row reconciliation marked DUPLICATE or SUPERSEDED, but does count a TRANSFER', async () => {
    transactions.search.mockResolvedValue(page([
      txn('real', 3000),
      txn('dup', 3000, { reconciliationStatus: 'DUPLICATE' }),
      txn('old', 3000, { reconciliationStatus: 'SUPERSEDED' }),
      txn('tracked', 500, { reconciliationStatus: 'TRANSFER' }),
    ]));
    renderCard();

    expect(await screen.findByText('SIP real')).toBeTruthy();
    expect(screen.queryByText('SIP dup')).toBeNull();
    expect(screen.queryByText('SIP old')).toBeNull();
    expect(screen.getByText('SIP tracked')).toBeTruthy();
    expect(screen.getByTestId('invested-total')).toHaveTextContent('₹3,500');
    expect(screen.getByText('2 payments')).toBeTruthy();
  });

  it('totals across every page of a long period, not just the first', async () => {
    transactions.search
      .mockResolvedValueOnce(page([txn('p0', 1000)], 2, 0))
      .mockResolvedValueOnce(page([txn('p1', 2000)], 2, 1));
    renderCard();

    await waitFor(() => expect(screen.getByTestId('invested-total')).toHaveTextContent('₹3,000'));
    expect(transactions.search).toHaveBeenCalledTimes(2);
    expect(transactions.search.mock.calls[1][0]).toMatchObject({ page: 1 });
  });

  it('says "at least" and names the cap when the period has more rows than it will fetch', async () => {
    transactions.search.mockImplementation(async (f) => page([txn(`r${f.page}`, 100)], 50, f.page ?? 0));
    renderCard();

    await waitFor(() => expect(screen.getByTestId('invested-total')).toHaveTextContent('At least ₹1,000'));
    expect(transactions.search).toHaveBeenCalledTimes(10);
    expect(screen.getByText(/counting the most recent 1000 only/)).toBeTruthy();
  });

  it('lists only the newest rows but totals every one, and says how many there are', async () => {
    transactions.search.mockResolvedValue(page(Array.from({ length: 12 }, (_, i) => txn(`n${i}`, 100))));
    renderCard();

    expect(await screen.findByText('SIP n0')).toBeTruthy();
    expect(screen.getByText('SIP n7')).toBeTruthy();
    expect(screen.queryByText('SIP n8')).toBeNull();
    expect(screen.getByTestId('invested-total')).toHaveTextContent('₹1,200');
    expect(screen.getByText('Showing the latest 8 of 12. The full list is in your Ledger.')).toBeTruthy();
  });

  it('shows the empty state, not a zero total, when nothing is filed under Investments', async () => {
    renderCard();

    expect(await screen.findByText(/No SIPs or broker transfers yet/)).toBeTruthy();
    expect(screen.queryByTestId('invested-total')).toBeNull();
  });

  it('shows the empty state, and never queries transactions, for a user with no Investments category', async () => {
    categories.list.mockResolvedValue([{ ...INVESTMENTS, id: 'other', name: 'Groceries' }]);
    renderCard();

    expect(await screen.findByText(/No SIPs or broker transfers yet/)).toBeTruthy();
    expect(transactions.search).not.toHaveBeenCalled();
  });

  // "No SIPs yet" would be a false claim to someone whose fetch simply failed.
  it('shows an error, not the empty state, when the fetch fails', async () => {
    transactions.search.mockRejectedValue(new Error('offline'));
    renderCard();

    expect(await screen.findByText('Could not load your investment activity.')).toBeTruthy();
    expect(screen.queryByText(/No SIPs or broker transfers yet/)).toBeNull();
  });

  it('re-queries with a later start date when the period is narrowed, and labels the total for it', async () => {
    transactions.search.mockResolvedValue(page([txn('a', 100)]));
    renderCard();
    await screen.findByText('SIP a');
    const twelveMonthStart = transactions.search.mock.calls[0][0].dateFrom!;
    expect(screen.getByText('Invested in the last 12 months')).toBeTruthy();

    await act(async () => { fireEvent.press(screen.getByLabelText('Show the last 3 months')); });

    await waitFor(() => expect(transactions.search).toHaveBeenCalledTimes(2));
    expect(transactions.search.mock.calls[1][0].dateFrom! > twelveMonthStart).toBe(true);
    expect(await screen.findByText('Invested in the last 3 months')).toBeTruthy();
  });
});
