import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AiSortCard, AI_QUESTION, POLL_MS } from './AiSortCard';
import { aiSortApi, type AiSortOffer, type AiSortRun } from '../api/endpoints';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';

jest.mock('../api/endpoints', () => ({
  aiSortApi: { offer: jest.fn(), start: jest.fn(), run: jest.fn() },
}));
jest.mock('../lib/invalidateFinancialData', () => ({ invalidateFinancialData: jest.fn() }));
jest.mock('../lib/haptics');

const api = aiSortApi as jest.Mocked<typeof aiSortApi>;

function sortOffer(over: Partial<AiSortOffer> = {}): AiSortOffer {
  return { show: true, mode: 'SORT', payments: 12, amount: 8400.4, payees: 5, ...over };
}

function aRun(over: Partial<AiSortRun> = {}): AiSortRun {
  return { id: 'run-1', status: 'RUNNING', paymentsTotal: 12, paymentsDone: 4, paymentsSorted: 3, paymentsLeft: 0, ...over };
}

function renderCard(onOpenQuickSort = jest.fn(), ids = ['imp-1', 'imp-2'],
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })) {
  render(
    <QueryClientProvider client={queryClient}>
      <AiSortCard statementImportIds={ids} onOpenQuickSort={onOpenQuickSort} />
    </QueryClientProvider>
  );
  return onOpenQuickSort;
}

describe('AiSortCard', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    api.offer.mockResolvedValue(sortOffer());
  });
  afterEach(() => jest.useRealTimers());

  it('offers a customer who said yes to sort the shop payments', async () => {
    renderCard();

    expect(await screen.findByText('AI can sort 12 shop payments (₹8,400)')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Sort with AI' })).toBeOnTheScreen();
    expect(api.offer).toHaveBeenCalledWith(['imp-1', 'imp-2']);
  });

  it('says payment for one', async () => {
    api.offer.mockResolvedValue(sortOffer({ payments: 1, amount: 99 }));
    renderCard();

    expect(await screen.findByText('AI can sort 1 shop payment (₹99)')).toBeOnTheScreen();
  });

  it('asks a customer who said no, one more time', async () => {
    api.offer.mockResolvedValue(sortOffer({ mode: 'ASK' }));
    renderCard();

    expect(await screen.findByText(AI_QUESTION)).toBeOnTheScreen();
    expect(AI_QUESTION).toBe('Let AI sort these for you? Fynora sends the payment text from your statement to Anthropic\'s AI. '
      + 'We hide the names and account numbers we can spot. You can turn AI off any time in Settings.');
    expect(screen.getByRole('button', { name: 'Turn on AI and sort' })).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'No thanks' })).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Sort with AI' })).not.toBeOnTheScreen();
  });

  it('No thanks records the answer and the card goes', async () => {
    api.offer.mockResolvedValue(sortOffer({ mode: 'ASK' }));
    api.start.mockResolvedValue(null);
    renderCard();

    fireEvent.press(await screen.findByRole('button', { name: 'No thanks' }));

    await waitFor(() => expect(screen.queryByText(/AI can sort/)).not.toBeOnTheScreen());
    expect(api.start).toHaveBeenCalledWith(['imp-1', 'imp-2'], 'NO_THANKS');
  });

  it('Turn on AI and sort starts the run', async () => {
    api.offer.mockResolvedValue(sortOffer({ mode: 'ASK' }));
    api.start.mockResolvedValue(aRun({ status: 'DONE', paymentsDone: 12, paymentsSorted: 12 }));
    renderCard();

    fireEvent.press(await screen.findByRole('button', { name: 'Turn on AI and sort' }));

    expect(await screen.findByText('Sorted 12 — check them in Quick sort')).toBeOnTheScreen();
    expect(api.start).toHaveBeenCalledWith(['imp-1', 'imp-2'], 'TURN_ON_AND_SORT');
  });

  it('shows progress while sorting, then what it sorted and what is left', async () => {
    jest.useFakeTimers();
    api.start.mockResolvedValue(aRun());
    api.run.mockResolvedValue(aRun({ status: 'DONE', paymentsDone: 12, paymentsSorted: 9, paymentsLeft: 3 }));
    renderCard();

    fireEvent.press(await screen.findByRole('button', { name: 'Sort with AI' }));
    expect(await screen.findByText('Sorting… 4 of 12')).toBeOnTheScreen();
    await act(async () => { jest.advanceTimersByTime(POLL_MS); });

    expect(await screen.findByText('Sorted 9 — check them in Quick sort')).toBeOnTheScreen();
    expect(screen.getByText('3 left for you')).toBeOnTheScreen();
    expect(api.run).toHaveBeenCalledWith('run-1');
    expect(invalidateFinancialData).toHaveBeenCalled();
  });

  it('opens Quick sort from the result', async () => {
    api.start.mockResolvedValue(aRun({ status: 'DONE', paymentsDone: 12, paymentsSorted: 12 }));
    const onOpen = renderCard();

    fireEvent.press(await screen.findByRole('button', { name: 'Sort with AI' }));
    fireEvent.press(await screen.findByRole('link', { name: 'Check them in Quick sort' }));

    expect(onOpen).toHaveBeenCalled();
  });

  it.each([
    [{ status: 'STOPPED_LIMIT', paymentsSorted: 9 }, "Sorted 9; AI has reached today's limit — the rest stay in Quick sort"],
    [{ status: 'STOPPED_AI_OFF', paymentsSorted: 2 }, 'AI was turned off — the rest stay in Quick sort'],
    [{ status: 'STOPPED', paymentsSorted: 5 }, 'Sorted 5, then sorting stopped — the rest stay in Quick sort'],
    [{ status: 'STOPPED', paymentsSorted: 0 }, 'Sorting stopped before AI could sort these — they stay in Quick sort'],
    [{ status: 'DONE', paymentsSorted: 0, paymentsLeft: 12 }, "AI couldn't sort these — they stay in Quick sort"],
  ] as const)('says how a run ended (%o)', async (over, text) => {
    api.start.mockResolvedValue(aRun(over as Partial<AiSortRun>));
    renderCard();

    fireEvent.press(await screen.findByRole('button', { name: 'Sort with AI' }));

    expect(await screen.findByText(text)).toBeOnTheScreen();
  });

  it('offers to try a stopped run again, and only a stopped one', async () => {
    api.start.mockResolvedValueOnce(aRun({ status: 'STOPPED', paymentsSorted: 0, paymentsLeft: 12 }))
      .mockResolvedValueOnce(aRun({ status: 'DONE', paymentsDone: 12, paymentsSorted: 12 }));
    renderCard();

    fireEvent.press(await screen.findByRole('button', { name: 'Sort with AI' }));
    fireEvent.press(await screen.findByRole('button', { name: 'Try again' }));

    expect(await screen.findByText('Sorted 12 — check them in Quick sort')).toBeOnTheScreen();
    expect(api.start).toHaveBeenLastCalledWith(['imp-1', 'imp-2'], 'SORT');
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeOnTheScreen();
  });

  it('offers no retry when the daily limit stopped it', async () => {
    api.start.mockResolvedValue(aRun({ status: 'STOPPED_LIMIT', paymentsSorted: 9 }));
    renderCard();

    fireEvent.press(await screen.findByRole('button', { name: 'Sort with AI' }));

    expect(await screen.findByText("Sorted 9; AI has reached today's limit — the rest stay in Quick sort")).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeOnTheScreen();
  });

  it('turning AI on refreshes the Settings AI switch', async () => {
    api.offer.mockResolvedValue(sortOffer({ mode: 'ASK' }));
    api.start.mockResolvedValue(aRun({ status: 'DONE', paymentsDone: 12, paymentsSorted: 12 }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(['user-settings'], { aiProcessingConsent: false });
    renderCard(jest.fn(), ['imp-1'], client);

    fireEvent.press(await screen.findByRole('button', { name: 'Turn on AI and sort' }));

    await waitFor(() => expect(client.getQueryState(['user-settings'])?.isInvalidated).toBe(true));
  });

  it('No thanks leaves the Settings AI switch as it was', async () => {
    api.offer.mockResolvedValue(sortOffer({ mode: 'ASK' }));
    api.start.mockResolvedValue(null as never);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(['user-settings'], { aiProcessingConsent: false });
    renderCard(jest.fn(), ['imp-1'], client);

    fireEvent.press(await screen.findByRole('button', { name: 'No thanks' }));

    await waitFor(() => expect(screen.queryByTestId('ai-sort-card')).not.toBeOnTheScreen());
    expect(client.getQueryState(['user-settings'])?.isInvalidated).toBe(false);
  });

  it('keeps the button and says so when starting fails', async () => {
    api.start.mockRejectedValue(new Error('down'));
    renderCard();

    fireEvent.press(await screen.findByRole('button', { name: 'Sort with AI' }));

    expect(await screen.findByText("Couldn't start sorting. Try again.")).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Sort with AI' })).toBeEnabled();
  });

  it('shows nothing when not offered, or when the offer cannot be read', async () => {
    api.offer.mockResolvedValue(sortOffer({ show: false, mode: null }));
    renderCard();
    await waitFor(() => expect(api.offer).toHaveBeenCalled());
    expect(screen.queryByTestId('ai-sort-card')).not.toBeOnTheScreen();
  });

  it('shows nothing when the offer cannot be read', async () => {
    api.offer.mockRejectedValue(new Error('down'));
    renderCard();
    await waitFor(() => expect(api.offer).toHaveBeenCalled());
    expect(screen.queryByTestId('ai-sort-card')).not.toBeOnTheScreen();
  });

  it('asks nothing for an import with no statement id', async () => {
    renderCard(jest.fn(), []);
    await act(async () => {});
    expect(api.offer).not.toHaveBeenCalled();
  });
});
