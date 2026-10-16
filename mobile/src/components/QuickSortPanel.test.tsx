import { useEffect } from 'react';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StyleSheet } from 'react-native';
import { QuickSortPanel } from './QuickSortPanel';
import { CategoryPickerModal } from './CategoryPickerModal';
import { transactionsApi } from '../api/endpoints';
import { fmtDate } from '../lib/format';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import { ThemeProvider, useThemeSetting } from '../theme';
import { dark } from '../theme/palette';
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

// Same helper as dashboard/primitives.test.tsx: the switch a person taps is the only way to force
// a theme, and setup.ts clears the stored choice before every test.
function ForceDarkTheme({ children }: { children: React.ReactNode }) {
  const { setSetting } = useThemeSetting();
  useEffect(() => { setSetting('dark'); }, [setSetting]);
  return <>{children}</>;
}

function renderPanelDark() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(
    <ThemeProvider>
      <ForceDarkTheme>
        <QueryClientProvider client={queryClient}>
          <QuickSortPanel />
        </QueryClientProvider>
      </ForceDarkTheme>
    </ThemeProvider>
  );
}

// A chip by its label: the Pressable carrying the button role.
const chipOf = (label: string) => screen.getByRole('button', { name: label });

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

  // The pick is the answer, and the answer's success or error is its one haptic.
  it('asks the picker for no tick of its own', async () => {
    tx.quickSort.mockResolvedValue(batch([question()]));
    renderPanel();
    fireEvent.press(await screen.findByText('More…'));
    expect(screen.UNSAFE_getByType(CategoryPickerModal).props.silent).toBe(true);
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
    fireEvent.press(await screen.findByText('Skip for now'));
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
      // The server's figures after the 1240 answer and a skip: what it filed is sorted, not waiting.
      .mockResolvedValueOnce(batch([question({ id: 'c', anchorTransactionId: 'txn-3', payee: 'NEXT SHOP' })],
        { waitingTotal: 8000, sortedTotal: 2000 }));
    renderPanel();
    fireEvent.press(await screen.findByText('Groceries'));
    fireEvent.press(await screen.findByText('Skip for now'));
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
    fireEvent.press(await screen.findByText('Skip for now'));
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
    fireEvent.press(await screen.findByText('Skip for now'));
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

  // Dark primary is near-white (#F4F1EC); the filled chips drew '#fff' on it and could not be read.
  it('draws a filled chip label in the theme on-primary colour, readable in dark mode', async () => {
    tx.quickSort.mockResolvedValue(batch([question({ kind: 'GUESS', currentCategory: 'Groceries', answers: ['Groceries'] })]));
    renderPanelDark();
    const label = await screen.findByText('Correct');
    await waitFor(() => expect(StyleSheet.flatten(label.props.style).color).toBe(dark.onPrimary));
  });

  it('writes the latest payment date the way the rest of the app does', async () => {
    tx.quickSort.mockResolvedValue(batch([question()]));
    renderPanel();
    expect(await screen.findByText(`3 payments · ₹1,240 · latest ${fmtDate('2026-09-12')}`)).toBeTruthy();
  });

  it('shows the place in the batch beside the title, and the money sorted as a bar', async () => {
    tx.quickSort.mockResolvedValue(batch([question(), question({ id: 'b', anchorTransactionId: 'txn-2' })], { waitingTotal: 10000 }));
    renderPanel();
    expect(await screen.findByText('1 of 2')).toBeTruthy();
    const fill = () => StyleSheet.flatten(
      screen.getByTestId('quick-sort-progress-fill', { includeHiddenElements: true }).props.style,
    );
    expect(fill().width).toBe('0%');
    fireEvent.press(screen.getByText('Groceries'));
    expect(await screen.findByText('2 of 2')).toBeTruthy();
    // 1240 of 10000 waiting, floored, as the caption already counts it.
    expect(fill().width).toBe('12%');
    expect(screen.getByText("You've sorted 12% of your waiting money")).toBeTruthy();
  });

  // "Stop asking about these" clears only the payees after the batch. A question skipped in the
  // batch is still waiting, so the card must not call everything sorted or show 100%.
  it('after stopping, still counts a skipped question as waiting', async () => {
    tx.quickSort.mockResolvedValue(batch(
      [question(), question({ id: 'b', anchorTransactionId: 'txn-2', payee: 'SECOND SHOP' })],
      { waitingTotal: 10000, rest: { questions: 3, payments: 4, amount: 7520, transactionIds: ['r1', 'r2'] } },
    ));
    renderPanel();
    fireEvent.press(await screen.findByText('Skip for now'));
    fireEvent.press(await screen.findByText('Groceries'));
    fireEvent.press(await screen.findByText('Stop asking about these'));
    await waitFor(() => expect(tx.quickSortKeepRest).toHaveBeenCalledWith(['r1', 'r2']));
    expect(await screen.findByText("You skipped 1 question. It's still waiting.")).toBeTruthy();
    expect(screen.queryByText(/All sorted/)).toBeNull();
    expect(screen.queryByText('Sort 10 more')).toBeNull();
    // 1240 answered, out of 10000 less the 7520 that stopped waiting.
    expect(screen.getByText("You've sorted 50% of your waiting money")).toBeTruthy();
  });

  it('after stopping with nothing skipped, says all sorted at 100%', async () => {
    tx.quickSort.mockResolvedValue(batch([question()], {
      waitingTotal: 10000, rest: { questions: 3, payments: 4, amount: 8760, transactionIds: ['r1'] },
    }));
    renderPanel();
    fireEvent.press(await screen.findByText('Groceries'));
    fireEvent.press(await screen.findByText('Stop asking about these'));
    expect(await screen.findByText(/All sorted/)).toBeTruthy();
    expect(screen.getByText("You've sorted 100% of your waiting money")).toBeTruthy();
  });

  // Amounts carry paise, and summing them in a different order from the server's total can land a
  // hair under it: 0.7 + 0.1 is 0.7999999999999999 against 0.8. "All sorted" must still say 100%.
  it('says 100% when everything is sorted, whatever the floating point does', async () => {
    tx.quickSort.mockResolvedValue(batch([
      question({ total: 0.7 }),
      question({ id: 'b', anchorTransactionId: 'txn-2', payee: 'SECOND SHOP', total: 0.1 }),
    ], { waitingTotal: 0.8 }));
    renderPanel();
    fireEvent.press(await screen.findByText('Groceries'));
    fireEvent.press(await screen.findByText('Dining'));
    expect(await screen.findByText(/All sorted/)).toBeTruthy();
    expect(screen.getByText("You've sorted 100% of your waiting money")).toBeTruthy();
  });

  // A tester left Quick sort part-way and came back to 0%: progress counted only this visit. It
  // now counts what earlier visits sorted too.
  it('picks up where the last visit ended', async () => {
    tx.quickSort.mockResolvedValue(batch([question(), question({ id: 'b', anchorTransactionId: 'txn-2' })],
      { waitingTotal: 6000, sortedTotal: 4000 }));
    renderPanel();
    expect(await screen.findByText("You've sorted 40% of your waiting money")).toBeTruthy();
    const fill = () => StyleSheet.flatten(
      screen.getByTestId('quick-sort-progress-fill', { includeHiddenElements: true }).props.style,
    );
    expect(fill().width).toBe('40%');
    fireEvent.press(screen.getByText('Groceries'));
    // (4000 + 1240) / 10000, floored.
    expect(await screen.findByText("You've sorted 52% of your waiting money")).toBeTruthy();
    expect(fill().width).toBe('52%');
  });

  // Skip moves on without an answer; sat among the categories it read as one of them.
  it('keeps Skip apart from the answers', async () => {
    tx.quickSort.mockResolvedValue(batch([question()]));
    renderPanel();
    const answers = await screen.findByTestId('quick-sort-answers');
    expect(within(answers).getByText('Groceries')).toBeTruthy();
    expect(within(answers).queryByText('Skip for now')).toBeNull();
    expect(screen.getByText('Skip for now')).toBeTruthy();
  });

  it('gives every answer chip a 44pt touch target', async () => {
    tx.quickSort.mockResolvedValue(batch([question()]));
    renderPanel();
    await screen.findByText('Groceries');
    for (const label of ['Groceries', 'Dining', 'More…']) {
      expect(StyleSheet.flatten(chipOf(label).props.style).minHeight).toBe(44);
    }
  });

  it('dims the answers while one is saving', async () => {
    let finish: (v: { filed: number }) => void = () => {};
    tx.quickSortAnswer.mockReturnValue(new Promise((r) => { finish = r; }));
    tx.quickSort.mockResolvedValue(batch([question(), question({ id: 'b', anchorTransactionId: 'txn-2', payee: 'SECOND SHOP' })]));
    renderPanel();
    fireEvent.press(await screen.findByText('Groceries'));
    await waitFor(() => expect(StyleSheet.flatten(chipOf('Dining').props.style).opacity).toBe(0.5));
    await act(async () => { finish({ filed: 1 }); });
    expect(await screen.findByText('SECOND SHOP')).toBeTruthy();
    expect(StyleSheet.flatten(chipOf('Dining').props.style).opacity).toBeUndefined();
  });

  it('says what was saved once the next question is up, and drops it on a skip', async () => {
    tx.quickSort.mockResolvedValue(batch([
      question(),
      question({ id: 'b', anchorTransactionId: 'txn-2', payee: 'SECOND SHOP' }),
      question({ id: 'c', anchorTransactionId: 'txn-3', payee: 'THIRD SHOP' }),
    ]));
    renderPanel();
    fireEvent.press(await screen.findByText('Dining'));
    expect(await screen.findByText('Saved Dining for SAMPLE STORE')).toBeTruthy();
    fireEvent.press(screen.getByText('Skip for now'));
    expect(await screen.findByText('THIRD SHOP')).toBeTruthy();
    expect(screen.queryByText(/^Saved /)).toBeNull();
  });

  it('does not say anything was saved when the answer fails', async () => {
    tx.quickSort.mockResolvedValue(batch([question()]));
    tx.quickSortAnswer.mockRejectedValue(new Error('boom'));
    renderPanel();
    fireEvent.press(await screen.findByText('Groceries'));
    expect(await screen.findByText("Couldn't save that answer — please try again.")).toBeTruthy();
    expect(screen.queryByText(/^Saved /)).toBeNull();
  });
});
