import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AiSortCard, AI_QUESTION, POLL_MS } from './AiSortCard';
import { aiSortApi, type AiSortOffer, type AiSortRun } from '../api/endpoints';
import { trackNavigation } from '../lib/trackNavigation';

vi.mock('../api/endpoints', () => ({
  aiSortApi: { offer: vi.fn(), start: vi.fn(), run: vi.fn() },
}));
vi.mock('../lib/trackNavigation', () => ({ trackNavigation: vi.fn() }));

const offer = vi.mocked(aiSortApi.offer);
const start = vi.mocked(aiSortApi.start);
const run = vi.mocked(aiSortApi.run);

function sortOffer(over: Partial<AiSortOffer> = {}): AiSortOffer {
  return { show: true, mode: 'SORT', payments: 12, amount: 8400.4, payees: 5, ...over };
}

function aRun(over: Partial<AiSortRun> = {}): AiSortRun {
  return { id: 'run-1', status: 'RUNNING', paymentsTotal: 12, paymentsDone: 4, paymentsSorted: 3, paymentsLeft: 0, ...over };
}

function renderCard() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <AiSortCard statementImportIds={['imp-1', 'imp-2']} />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('AiSortCard', () => {
  beforeEach(() => {
    offer.mockReset().mockResolvedValue(sortOffer());
    start.mockReset();
    run.mockReset();
  });
  afterEach(() => vi.useRealTimers());

  it('offers a customer who said yes to sort the shop payments', async () => {
    renderCard();

    expect(await screen.findByText('AI can sort 12 shop payments (₹8,400)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sort with AI' })).toBeInTheDocument();
    expect(offer).toHaveBeenCalledWith(['imp-1', 'imp-2']);
  });

  it('says payment for one', async () => {
    offer.mockResolvedValue(sortOffer({ payments: 1, amount: 99 }));
    renderCard();

    expect(await screen.findByText('AI can sort 1 shop payment (₹99)')).toBeInTheDocument();
  });

  it('asks a customer who said no, one more time', async () => {
    offer.mockResolvedValue(sortOffer({ mode: 'ASK' }));
    renderCard();

    expect(await screen.findByText(AI_QUESTION)).toBeInTheDocument();
    expect(AI_QUESTION).toBe('Fynora can use AI to suggest categories for these payments. It sends the payment text, '
      + 'with names and account numbers removed, to Anthropic. You can turn this off any time in Settings.');
    expect(screen.getByRole('button', { name: 'Turn on AI and sort' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'No thanks' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sort with AI' })).not.toBeInTheDocument();
  });

  it('No thanks records the answer and the card goes', async () => {
    offer.mockResolvedValue(sortOffer({ mode: 'ASK' }));
    start.mockResolvedValue(null);
    renderCard();

    await userEvent.click(await screen.findByRole('button', { name: 'No thanks' }));

    expect(start).toHaveBeenCalledWith(['imp-1', 'imp-2'], 'NO_THANKS');
    await waitFor(() => expect(screen.queryByText(/AI can sort/)).not.toBeInTheDocument());
  });

  it('Turn on AI and sort starts the run', async () => {
    offer.mockResolvedValue(sortOffer({ mode: 'ASK' }));
    start.mockResolvedValue(aRun({ status: 'DONE', paymentsDone: 12, paymentsSorted: 12 }));
    renderCard();

    await userEvent.click(await screen.findByRole('button', { name: 'Turn on AI and sort' }));

    expect(start).toHaveBeenCalledWith(['imp-1', 'imp-2'], 'TURN_ON_AND_SORT');
    expect(await screen.findByTestId('ai-sort-result')).toHaveTextContent('Sorted 12 — check them in Quick sort');
  });

  it('shows progress while sorting, then what it sorted and what is left', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    start.mockResolvedValue(aRun());
    run.mockResolvedValue(aRun({ status: 'DONE', paymentsDone: 12, paymentsSorted: 9, paymentsLeft: 3 }));
    renderCard();

    await userEvent.click(await screen.findByRole('button', { name: 'Sort with AI' }));
    expect(await screen.findByText('Sorting… 4 of 12')).toBeInTheDocument();
    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_MS); });

    expect(run).toHaveBeenCalledWith('run-1');
    const result = await screen.findByTestId('ai-sort-result');
    expect(result).toHaveTextContent('Sorted 9 — check them in Quick sort');
    expect(screen.getByText('3 left for you')).toBeInTheDocument();
  });

  it('links to Quick sort, tracked', async () => {
    start.mockResolvedValue(aRun({ status: 'DONE', paymentsDone: 12, paymentsSorted: 12 }));
    renderCard();

    await userEvent.click(await screen.findByRole('button', { name: 'Sort with AI' }));
    const link = await screen.findByRole('link', { name: 'check them in Quick sort' });
    expect(link).toHaveAttribute('href', '/app/transactions');
    await userEvent.click(link);
    expect(trackNavigation).toHaveBeenCalledWith('transactions', 'contextual');
  });

  it('says when the daily AI limit stopped it', async () => {
    start.mockResolvedValue(aRun({ status: 'STOPPED_LIMIT', paymentsSorted: 9, paymentsLeft: 3 }));
    renderCard();

    await userEvent.click(await screen.findByRole('button', { name: 'Sort with AI' }));

    expect(await screen.findByTestId('ai-sort-result'))
      .toHaveTextContent("Sorted 9; AI has reached today's limit — the rest stay in Quick sort");
  });

  it('says when AI was turned off', async () => {
    start.mockResolvedValue(aRun({ status: 'STOPPED_AI_OFF', paymentsSorted: 2 }));
    renderCard();

    await userEvent.click(await screen.findByRole('button', { name: 'Sort with AI' }));

    expect(await screen.findByTestId('ai-sort-result')).toHaveTextContent('AI was turned off — the rest stay in Quick sort');
  });

  it('says when a run was cut off', async () => {
    start.mockResolvedValue(aRun({ status: 'STOPPED', paymentsSorted: 5 }));
    renderCard();

    await userEvent.click(await screen.findByRole('button', { name: 'Sort with AI' }));

    expect(await screen.findByTestId('ai-sort-result')).toHaveTextContent('Sorted 5 — the rest stay in Quick sort');
  });

  it('says so when the AI could place none', async () => {
    start.mockResolvedValue(aRun({ status: 'DONE', paymentsDone: 12, paymentsSorted: 0, paymentsLeft: 12 }));
    renderCard();

    await userEvent.click(await screen.findByRole('button', { name: 'Sort with AI' }));

    expect(await screen.findByTestId('ai-sort-result')).toHaveTextContent("AI couldn't sort these — they stay in Quick sort");
  });

  it('keeps the button and says so when starting fails', async () => {
    start.mockRejectedValue(new Error('down'));
    renderCard();

    await userEvent.click(await screen.findByRole('button', { name: 'Sort with AI' }));

    expect(await screen.findByText("Couldn't start sorting. Try again.")).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sort with AI' })).toBeEnabled();
  });

  it('shows nothing when not offered, or when the offer cannot be read', async () => {
    offer.mockResolvedValue(sortOffer({ show: false, mode: null }));
    const { container, unmount } = renderCard();
    await waitFor(() => expect(offer).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
    unmount();

    offer.mockRejectedValue(new Error('down'));
    const second = renderCard();
    await waitFor(() => expect(offer).toHaveBeenCalledTimes(2));
    expect(second.container).toBeEmptyDOMElement();
  });

  it('asks nothing for an import with no statement id', async () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter><AiSortCard statementImportIds={[]} /></MemoryRouter>
      </QueryClientProvider>
    );
    await new Promise((r) => setTimeout(r, 0));
    expect(offer).not.toHaveBeenCalled();
  });
});
