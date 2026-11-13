import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { QuickSortPanel } from './QuickSortPanel';
import { transactionsApi } from '../api/endpoints';
import { safeStorage } from '../lib/safeStorage';
import { DASHBOARD_QUICK_SORT_KEY, notNowValue } from '../lib/dashboardQuickSort';
import type { QuickSortBatch, QuickSortQuestion } from '../types';

jest.mock('../api/endpoints', () => ({
  transactionsApi: {
    quickSort: jest.fn(),
    quickSortAnswer: jest.fn(),
    quickSortMore: jest.fn(),
    quickSortKeepRest: jest.fn(),
  },
  categoriesApi: { list: jest.fn().mockResolvedValue([]) },
}));
jest.mock('../lib/safeStorage', () => ({
  safeStorage: { getItem: jest.fn(), setItem: jest.fn(), removeItem: jest.fn() },
}));
jest.mock('../lib/invalidateFinancialData', () => ({ invalidateFinancialData: jest.fn() }));
jest.mock('../lib/haptics');
jest.mock('./CategoryPickerModal', () => ({ CategoryPickerModal: () => null }));

const tx = transactionsApi as jest.Mocked<typeof transactionsApi>;
const storage = safeStorage as jest.Mocked<typeof safeStorage>;
const EMAIL = 'a@example.com';

function question(over: Partial<QuickSortQuestion> = {}): QuickSortQuestion {
  return {
    id: 'vpa:sample|EXPENSE', anchorTransactionId: 'txn-1', kind: 'SHOP', payee: 'SAMPLE STORE', payments: 3,
    total: 1240, latestDate: '2026-09-12', largeOneOff: false, currentCategory: 'Other',
    answers: ['Groceries', 'Dining'], samples: [], ...over,
  };
}

function shops(n: number): QuickSortQuestion[] {
  return Array.from({ length: n }, (_, i) =>
    question({ id: `vpa:shop${i + 1}|EXPENSE`, anchorTransactionId: `txn-${i + 1}`, payee: `SHOP ${i + 1}` }));
}

function batch(questions: QuickSortQuestion[], over: Partial<QuickSortBatch> = {}): QuickSortBatch {
  return { questions, waitingTotal: 10000, rest: { questions: 0, payments: 0, amount: 0, transactionIds: [] }, ...over };
}

function renderDashboardPanel() {
  const onLoaded = jest.fn();
  const onOpenFull = jest.fn();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(
    <QueryClientProvider client={queryClient}>
      <QuickSortPanel variant="dashboard" accountEmail={EMAIL} onLoaded={onLoaded} onOpenFull={onOpenFull} />
    </QueryClientProvider>
  );
  return { onLoaded, onOpenFull };
}

describe('QuickSortPanel on the Dashboard', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    storage.getItem.mockResolvedValue(null);
    storage.setItem.mockResolvedValue(undefined);
    tx.quickSortAnswer.mockResolvedValue({ filed: 1 });
  });

  it('asks at most three questions, then says how many payees are still waiting', async () => {
    tx.quickSort.mockResolvedValue(batch(shops(5), { rest: { questions: 4, payments: 9, amount: 900, transactionIds: [] } }));
    const { onOpenFull } = renderDashboardPanel();
    expect(await screen.findByText('Sort a few payments')).toBeOnTheScreen();
    for (const payee of ['SHOP 1', 'SHOP 2', 'SHOP 3']) {
      expect(await screen.findByText(payee)).toBeOnTheScreen();
      fireEvent.press(screen.getByRole('button', { name: 'Groceries' }));
    }
    expect(await screen.findByText('6 payees still waiting')).toBeOnTheScreen();
    expect(screen.queryByText('SHOP 4')).not.toBeOnTheScreen();
    expect(tx.quickSortAnswer).toHaveBeenCalledTimes(3);
    fireEvent.press(screen.getByRole('button', { name: 'Open Quick sort' }));
    expect(onOpenFull).toHaveBeenCalledTimes(1);
  });

  it('sends the Dashboard source with the batch and the answer', async () => {
    tx.quickSort.mockResolvedValue(batch(shops(2)));
    renderDashboardPanel();
    fireEvent.press(await screen.findByRole('button', { name: 'Groceries' }));
    await waitFor(() => expect(tx.quickSortAnswer).toHaveBeenCalledWith('txn-1', 'Groceries', 'SHOP', 'DASHBOARD'));
    expect(tx.quickSort).toHaveBeenCalledWith(0, false, 'DASHBOARD');
  });

  it('never offers Stop asking about these or Sort 10 more', async () => {
    tx.quickSort.mockResolvedValue(batch(shops(1), { rest: { questions: 4, payments: 9, amount: 900, transactionIds: ['t9'] } }));
    renderDashboardPanel();
    fireEvent.press(await screen.findByRole('button', { name: 'Groceries' }));
    expect(await screen.findByText('4 payees still waiting')).toBeOnTheScreen();
    expect(screen.queryByText('Stop asking about these')).not.toBeOnTheScreen();
    expect(screen.queryByText('Sort 10 more')).not.toBeOnTheScreen();
  });

  it('skip moves to the next question and counts toward the three', async () => {
    tx.quickSort.mockResolvedValue(batch(shops(4)));
    renderDashboardPanel();
    fireEvent.press(await screen.findByRole('button', { name: 'Skip' }));
    expect(await screen.findByText('SHOP 2')).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Skip' }));
    fireEvent.press(await screen.findByRole('button', { name: 'Skip' }));
    expect(await screen.findByText('4 payees still waiting')).toBeOnTheScreen();
    expect(tx.quickSortAnswer).not.toHaveBeenCalled();
  });

  it('Not now hides the card, remembers today and lets the nudge return', async () => {
    tx.quickSort.mockResolvedValue(batch(shops(2)));
    const { onLoaded } = renderDashboardPanel();
    fireEvent.press(await screen.findByRole('button', { name: 'Not now' }));
    await waitFor(() => expect(screen.queryByText('Sort a few payments')).not.toBeOnTheScreen());
    expect(storage.setItem).toHaveBeenCalledWith(DASHBOARD_QUICK_SORT_KEY, notNowValue(EMAIL, new Date()));
    expect(onLoaded).toHaveBeenLastCalledWith(0);
  });

  it('renders nothing and fetches nothing when hidden earlier today', async () => {
    storage.getItem.mockResolvedValue(notNowValue(EMAIL, new Date()));
    tx.quickSort.mockResolvedValue(batch(shops(2)));
    const { onLoaded } = renderDashboardPanel();
    await waitFor(() => expect(onLoaded).toHaveBeenCalledWith(0));
    expect(tx.quickSort).not.toHaveBeenCalled();
    expect(screen.queryByText('Sort a few payments')).not.toBeOnTheScreen();
  });

  it('still shows the card when storage cannot be read', async () => {
    storage.getItem.mockRejectedValue(new Error('blocked'));
    tx.quickSort.mockResolvedValue(batch(shops(1)));
    renderDashboardPanel();
    expect(await screen.findByText('SHOP 1')).toBeOnTheScreen();
  });

  it('a one-question batch ends without a zero count and lets the nudge return', async () => {
    tx.quickSort.mockResolvedValue(batch(shops(1)));
    const { onLoaded } = renderDashboardPanel();
    fireEvent.press(await screen.findByRole('button', { name: 'Groceries' }));
    await waitFor(() => expect(screen.queryByText('Sort a few payments')).not.toBeOnTheScreen());
    expect(screen.queryByText(/0 payees/)).not.toBeOnTheScreen();
    expect(onLoaded).toHaveBeenLastCalledWith(0);
  });

  it('reports nothing to show for an empty batch', async () => {
    tx.quickSort.mockResolvedValue(batch([], { waitingTotal: 0 }));
    const { onLoaded } = renderDashboardPanel();
    await waitFor(() => expect(onLoaded).toHaveBeenCalledWith(0));
    expect(screen.queryByText('Sort a few payments')).not.toBeOnTheScreen();
  });

  it('reports nothing to show, and no error, when the batch fails to load', async () => {
    tx.quickSort.mockRejectedValue(new Error('down'));
    const { onLoaded } = renderDashboardPanel();
    await waitFor(() => expect(onLoaded).toHaveBeenCalledWith(0));
    expect(screen.queryByText(/Couldn't/)).not.toBeOnTheScreen();
  });
});
