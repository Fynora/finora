import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { QuickSortPanel } from './QuickSortPanel';
import { transactionsApi } from '../api/endpoints';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
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

jest.mock('../lib/invalidateFinancialData', () => ({ invalidateFinancialData: jest.fn() }));
jest.mock('../lib/haptics');

// The full picker is CategoryPickerModal's own concern; here only that More... opens it and a
// pick answers the question.
jest.mock('./CategoryPickerModal', () => {
  const { Pressable, Text } = jest.requireActual('react-native');
  return {
    CategoryPickerModal: ({ visible, onSelect }: { visible: boolean; onSelect: (c: { name: string }) => void }) =>
      visible ? (
        <Pressable accessibilityRole="button" onPress={() => onSelect({ name: 'Rent' })}>
          <Text>Pick Rent</Text>
        </Pressable>
      ) : null,
  };
});

const tx = transactionsApi as jest.Mocked<typeof transactionsApi>;

function question(over: Partial<QuickSortQuestion> = {}): QuickSortQuestion {
  return {
    id: 'vpa:sample|EXPENSE', anchorTransactionId: 'txn-1', kind: 'SHOP', payee: 'SAMPLE STORE', payments: 3,
    total: 1240, latestDate: '2026-09-12', largeOneOff: false, currentCategory: 'Other',
    answers: ['Groceries', 'Dining'], samples: [], ...over,
  };
}

function batch(questions: QuickSortQuestion[], over: Partial<QuickSortBatch> = {}): QuickSortBatch {
  return { questions, waitingTotal: 10000, rest: { questions: 0, payments: 0, amount: 0, transactionIds: [] }, ...over };
}

function renderPanel(onLoaded = jest.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(
    <QueryClientProvider client={queryClient}>
      <QuickSortPanel onLoaded={onLoaded} />
    </QueryClientProvider>
  );
  return onLoaded;
}

describe('QuickSortPanel', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    tx.quickSortAnswer.mockResolvedValue({ filed: 1 });
    tx.quickSortMore.mockResolvedValue({} as never);
    tx.quickSortKeepRest.mockResolvedValue({ cleared: 2 });
  });

  it('renders nothing and reports no questions when nothing is waiting', async () => {
    tx.quickSort.mockResolvedValue(batch([], { waitingTotal: 0 }));
    const onLoaded = renderPanel();
    await waitFor(() => expect(onLoaded).toHaveBeenCalledWith(0));
    expect(screen.queryByText('Quick sort')).toBeNull();
  });

  it('reports no questions when the batch cannot be loaded', async () => {
    tx.quickSort.mockRejectedValue(new Error('down'));
    const onLoaded = renderPanel();
    await waitFor(() => expect(onLoaded).toHaveBeenCalledWith(0));
  });

  it('shows the first question with its kind copy and reports the count', async () => {
    tx.quickSort.mockResolvedValue(batch([question(), question({ id: 'b', anchorTransactionId: 'txn-2' })]));
    const onLoaded = renderPanel();
    expect(await screen.findByText('SAMPLE STORE')).toBeTruthy();
    expect(screen.getByText('What kind of shop is this?')).toBeTruthy();
    expect(onLoaded).toHaveBeenCalledWith(2);
  });

  it('answering sends anchor, category and kind and moves on', async () => {
    tx.quickSort.mockResolvedValue(batch([question(), question({ id: 'b', anchorTransactionId: 'txn-2', payee: 'SECOND SHOP' })]));
    renderPanel();
    fireEvent.press(await screen.findByText('Groceries'));
    await waitFor(() => expect(tx.quickSortAnswer).toHaveBeenCalledWith('txn-1', 'Groceries', 'SHOP'));
    expect(await screen.findByText('SECOND SHOP')).toBeTruthy();
    expect(invalidateFinancialData).toHaveBeenCalled();
  });

  it('More... opens the picker and its pick answers the question', async () => {
    tx.quickSort.mockResolvedValue(batch([question()]));
    renderPanel();
    fireEvent.press(await screen.findByText('More…'));
    fireEvent.press(await screen.findByText('Pick Rent'));
    await waitFor(() => expect(tx.quickSortAnswer).toHaveBeenCalledWith('txn-1', 'Rent', 'SHOP'));
  });

  it('a guess offers Correct', async () => {
    tx.quickSort.mockResolvedValue(batch([question({ kind: 'GUESS', currentCategory: 'Groceries', answers: ['Groceries'] })]));
    renderPanel();
    expect(await screen.findByText('Fynora thinks: Groceries')).toBeTruthy();
    fireEvent.press(screen.getByText('Correct'));
    await waitFor(() => expect(tx.quickSortAnswer).toHaveBeenCalledWith('txn-1', 'Groceries', 'GUESS'));
  });

  it('a person question asks what the person was paid for', async () => {
    tx.quickSort.mockResolvedValue(batch([question({ kind: 'PERSON_PAID' })]));
    renderPanel();
    expect(await screen.findByText('What was this person paid for?')).toBeTruthy();
  });

  it('skip moves on without answering', async () => {
    tx.quickSort.mockResolvedValue(batch([question(), question({ id: 'b', anchorTransactionId: 'txn-2', payee: 'SECOND SHOP' })]));
    renderPanel();
    fireEvent.press(await screen.findByText('Skip'));
    expect(await screen.findByText('SECOND SHOP')).toBeTruthy();
    expect(tx.quickSortAnswer).not.toHaveBeenCalled();
  });

  it('after the batch: Sort 10 more, and Stop asking about these with the exact wording', async () => {
    tx.quickSort.mockResolvedValue(batch([question()], {
      rest: { questions: 4, payments: 7, amount: 1890, transactionIds: ['r1', 'r2'] },
    }));
    renderPanel();
    fireEvent.press(await screen.findByText('Groceries'));
    expect(await screen.findByText('Sort 10 more')).toBeTruthy();
    expect(screen.getByText('Stop asking about these')).toBeTruthy();
    expect(screen.getByText(
      '7 payments (₹1,890) stay as Personal Transfer or Other. You can change any of them later from the Ledger.',
    )).toBeTruthy();
    fireEvent.press(screen.getByText('Stop asking about these'));
    await waitFor(() => expect(tx.quickSortKeepRest).toHaveBeenCalledWith(['r1', 'r2']));
    expect(await screen.findByText(/All sorted/)).toBeTruthy();
  });

  it('Sort 10 more records it and fetches past the skipped payees', async () => {
    tx.quickSort
      .mockResolvedValueOnce(batch([question(), question({ id: 'b', anchorTransactionId: 'txn-2', payee: 'SKIPPED' })], {
        rest: { questions: 1, payments: 1, amount: 50, transactionIds: ['r1'] },
      }))
      .mockResolvedValueOnce(batch([question({ id: 'c', anchorTransactionId: 'txn-3', payee: 'NEXT SHOP' })], { waitingTotal: 8000 }));
    renderPanel();
    fireEvent.press(await screen.findByText('Groceries'));
    fireEvent.press(await screen.findByText('Skip'));
    fireEvent.press(await screen.findByText('Sort 10 more'));
    expect(await screen.findByText('NEXT SHOP')).toBeTruthy();
    expect(tx.quickSortMore).toHaveBeenCalled();
    expect(tx.quickSort).toHaveBeenLastCalledWith(1);
    expect(screen.getByText("You've sorted 20% of your waiting money")).toBeTruthy();
  });

  it('never says all sorted while skipped questions are still waiting', async () => {
    tx.quickSort
      .mockResolvedValueOnce(batch([question()]))
      .mockResolvedValueOnce(batch([question({ payee: 'ASKED AGAIN' })]));
    renderPanel();
    fireEvent.press(await screen.findByText('Skip'));
    expect(await screen.findByText("You skipped 1 question. It's still waiting.")).toBeTruthy();
    expect(screen.queryByText(/All sorted/)).toBeNull();
    fireEvent.press(screen.getByText('Ask the skipped ones again'));
    expect(await screen.findByText('ASKED AGAIN')).toBeTruthy();
    expect(tx.quickSort).toHaveBeenLastCalledWith(0);
  });

  it('stays visible with the skipped message when Sort 10 more comes back empty', async () => {
    tx.quickSort
      .mockResolvedValueOnce(batch([question()], { rest: { questions: 1, payments: 1, amount: 5, transactionIds: ['r1'] } }))
      .mockResolvedValueOnce(batch([], { waitingTotal: 5 }));
    renderPanel();
    fireEvent.press(await screen.findByText('Skip'));
    fireEvent.press(await screen.findByText('Sort 10 more'));
    expect(await screen.findByText("You skipped 1 question. It's still waiting.")).toBeTruthy();
  });

  it('a failed answer keeps the question and says so', async () => {
    tx.quickSort.mockResolvedValue(batch([question()]));
    tx.quickSortAnswer.mockRejectedValue(new Error('boom'));
    renderPanel();
    fireEvent.press(await screen.findByText('Groceries'));
    expect(await screen.findByText("Couldn't save that answer — please try again.")).toBeTruthy();
    expect(screen.getByText('SAMPLE STORE')).toBeTruthy();
  });
});
