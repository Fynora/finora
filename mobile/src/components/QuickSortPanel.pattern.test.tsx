import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { QuickSortPanel } from './QuickSortPanel';
import { transactionsApi } from '../api/endpoints';
import { safeStorage } from '../lib/safeStorage';
import type { QuickSortBatch, QuickSortQuestion, QuickSortSample } from '../types';

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
const LINE = '14 payments since Aug · usually ₹150–₹400';

function recentRows(n: number): QuickSortSample[] {
  return Array.from({ length: n }, (_, i) => ({
    id: `r-${i}`, date: `2026-11-${String(12 - i).padStart(2, '0')}`, description: `UPI/RAVI K/REF${i}`,
    amount: 200 + i, type: 'EXPENSE',
  }));
}

function question(over: Partial<QuickSortQuestion> = {}): QuickSortQuestion {
  return {
    id: 'vpa:ravi|EXPENSE', anchorTransactionId: 'txn-1', kind: 'PERSON_PAID', payee: 'RAVI K', payments: 3,
    total: 1240, latestDate: '2026-11-12', largeOneOff: false, currentCategory: 'Personal Transfer',
    answers: ['Personal Transfer', 'Groceries'], samples: [],
    allPayments: 14, firstDate: '2026-08-03', typicalLow: 150, typicalHigh: 400, recent: recentRows(10), ...over,
  };
}

function batch(questions: QuickSortQuestion[]): QuickSortBatch {
  return { questions, waitingTotal: 10000, rest: { questions: 0, payments: 0, amount: 0, transactionIds: [] } };
}

function renderPanel(props: Partial<React.ComponentProps<typeof QuickSortPanel>> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(
    <QueryClientProvider client={queryClient}>
      <QuickSortPanel {...props} />
    </QueryClientProvider>
  );
}

describe('QuickSortPanel pattern line', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    storage.getItem.mockResolvedValue(null);
    tx.quickSortAnswer.mockResolvedValue({ filed: 1 });
  });

  it("opens and closes the payee's payments from the pattern line", async () => {
    tx.quickSort.mockResolvedValue(batch([question()]));
    renderPanel();
    const line = await screen.findByRole('button', { name: LINE });
    expect(line).toBeCollapsed();
    expect(screen.queryByText(/UPI\/RAVI K\/REF0/)).not.toBeOnTheScreen();
    fireEvent.press(line);
    expect(screen.getByRole('button', { name: LINE })).toBeExpanded();
    expect(screen.getAllByText(/UPI\/RAVI K\/REF/)).toHaveLength(10);
    fireEvent.press(screen.getByRole('button', { name: LINE }));
    expect(screen.queryByText(/UPI\/RAVI K\/REF0/)).not.toBeOnTheScreen();
  });

  it('still says how many payments are waiting and how much', async () => {
    tx.quickSort.mockResolvedValue(batch([question()]));
    renderPanel();
    expect(await screen.findByText('3 waiting · ₹1,240')).toBeOnTheScreen();
  });

  it('closes the list when the next question comes up', async () => {
    tx.quickSort.mockResolvedValue(batch([
      question(),
      question({ id: 'vpa:second|EXPENSE', anchorTransactionId: 'txn-2', payee: 'SECOND PERSON' }),
    ]));
    renderPanel();
    fireEvent.press(await screen.findByRole('button', { name: LINE }));
    fireEvent.press(screen.getByRole('button', { name: 'Groceries' }));
    expect(await screen.findByText('SECOND PERSON')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: LINE })).toBeCollapsed();
    expect(screen.queryByText(/UPI\/RAVI K\/REF0/)).not.toBeOnTheScreen();
  });

  it('offers See all when there are more payments and hands the payee over', async () => {
    tx.quickSort.mockResolvedValue(batch([question()]));
    const onSeeAll = jest.fn();
    renderPanel({ onSeeAll });
    fireEvent.press(await screen.findByRole('button', { name: LINE }));
    fireEvent.press(screen.getByRole('button', { name: 'See all 14 in Transactions' }));
    expect(onSeeAll).toHaveBeenCalledWith(expect.objectContaining({ anchorTransactionId: 'txn-1', payee: 'RAVI K' }));
  });

  it('shows no See all without a handler', async () => {
    tx.quickSort.mockResolvedValue(batch([question()]));
    renderPanel();
    fireEvent.press(await screen.findByRole('button', { name: LINE }));
    expect(screen.queryByText(/See all/)).not.toBeOnTheScreen();
  });

  it('shows no See all when every payment is listed', async () => {
    tx.quickSort.mockResolvedValue(batch([question({ allPayments: 3, recent: recentRows(3) })]));
    renderPanel({ onSeeAll: jest.fn() });
    fireEvent.press(await screen.findByRole('button', { name: /3 payments since Aug/ }));
    expect(screen.queryByText(/See all/)).not.toBeOnTheScreen();
  });

  it('answers the same with the list open', async () => {
    tx.quickSort.mockResolvedValue(batch([question()]));
    renderPanel();
    fireEvent.press(await screen.findByRole('button', { name: LINE }));
    fireEvent.press(screen.getByRole('button', { name: 'Groceries' }));
    await waitFor(() => expect(tx.quickSortAnswer).toHaveBeenCalledWith('txn-1', 'Groceries', 'PERSON_PAID'));
  });

  it("falls back to today's line without a pattern", async () => {
    tx.quickSort.mockResolvedValue(batch([question({ allPayments: undefined, firstDate: undefined, recent: undefined })]));
    renderPanel();
    expect(await screen.findByText(/3 payments · /)).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: /payments since/ })).not.toBeOnTheScreen();
  });

  it('works the same on the Dashboard card', async () => {
    tx.quickSort.mockResolvedValue(batch([question()]));
    renderPanel({ variant: 'dashboard', accountEmail: 'a@example.com', onSeeAll: jest.fn() });
    fireEvent.press(await screen.findByRole('button', { name: LINE }));
    expect(screen.getAllByText(/UPI\/RAVI K\/REF/)).toHaveLength(10);
    expect(screen.getByRole('button', { name: 'See all 14 in Transactions' })).toBeOnTheScreen();
  });
});
