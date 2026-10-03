import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { QuickSortCard } from './QuickSortCard';
import { transactionsApi, categoriesApi } from '../api/endpoints';
import type { QuickSortBatch, QuickSortQuestion } from '../types';

vi.mock('../api/endpoints', () => ({
  transactionsApi: {
    quickSort: vi.fn(),
    quickSortAnswer: vi.fn(),
    quickSortMore: vi.fn(),
    quickSortKeepRest: vi.fn(),
  },
  categoriesApi: { list: vi.fn(), options: vi.fn(), create: vi.fn() },
}));

function question(over: Partial<QuickSortQuestion> = {}): QuickSortQuestion {
  return {
    id: 'vpa:sample|EXPENSE',
    anchorTransactionId: 'txn-1',
    kind: 'SHOP',
    payee: 'SAMPLE STORE',
    payments: 3,
    total: 1240,
    latestDate: '2026-09-12',
    largeOneOff: false,
    currentCategory: 'Other',
    answers: ['Groceries', 'Dining', 'Shopping'],
    samples: [{ id: 'txn-1', date: '2026-09-12', description: 'UPI/SAMPLE STORE/REF1', amount: 400, type: 'EXPENSE' }],
    ...over,
  };
}

function batch(questions: QuickSortQuestion[], over: Partial<QuickSortBatch> = {}): QuickSortBatch {
  return {
    questions,
    waitingTotal: 10000,
    rest: { questions: 0, payments: 0, amount: 0, transactionIds: [] },
    ...over,
  };
}

function renderCard() {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <QuickSortCard />
    </QueryClientProvider>
  );
}

describe('QuickSortCard', () => {
  beforeEach(() => {
    vi.mocked(transactionsApi.quickSort).mockReset();
    vi.mocked(transactionsApi.quickSortAnswer).mockReset().mockResolvedValue({ filed: 1 });
    vi.mocked(transactionsApi.quickSortMore).mockReset().mockResolvedValue({} as any);
    vi.mocked(transactionsApi.quickSortKeepRest).mockReset().mockResolvedValue({ cleared: 2 });
    vi.mocked(categoriesApi.list).mockResolvedValue([] as any);
    vi.mocked(categoriesApi.options).mockResolvedValue({ icons: [], colors: [] } as any);
  });

  it('renders nothing when nothing is waiting', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([], { waitingTotal: 0 }));
    const { container } = renderCard();
    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });

  it('shows the first question with its payee, payments and kind copy', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([question()]));
    renderCard();
    expect(await screen.findByText('SAMPLE STORE')).toBeInTheDocument();
    expect(screen.getByText(/What kind of shop is this\?/)).toBeInTheDocument();
    expect(screen.getByText(/3 payments/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Groceries' })).toBeInTheDocument();
  });

  it('a person question asks what the person was paid for', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([question({ kind: 'PERSON_PAID' })]));
    renderCard();
    expect(await screen.findByText(/What was this person paid for\?/)).toBeInTheDocument();
  });

  it('money in asks what the money was', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([question({ kind: 'MONEY_IN' })]));
    renderCard();
    expect(await screen.findByText(/What was this money\?/)).toBeInTheDocument();
  });

  it('marks a large one-off payment', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([question({ largeOneOff: true, payments: 1 })]));
    renderCard();
    expect(await screen.findByText(/Large one-off payment/)).toBeInTheDocument();
  });

  it('answering sends the anchor, category and kind, then shows the next question', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([
      question(),
      question({ id: 'vpa:second|EXPENSE', anchorTransactionId: 'txn-2', payee: 'SECOND SHOP' }),
    ]));
    renderCard();
    await userEvent.click(await screen.findByRole('button', { name: 'Groceries' }));
    expect(transactionsApi.quickSortAnswer).toHaveBeenCalledWith('txn-1', 'Groceries', 'SHOP');
    expect(await screen.findByText('SECOND SHOP')).toBeInTheDocument();
  });

  it('a guess offers Correct, which answers with the current category', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([
      question({ kind: 'GUESS', currentCategory: 'Groceries', answers: ['Groceries', 'Dining'] }),
    ]));
    renderCard();
    expect(await screen.findByText(/Fynora thinks: Groceries/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Correct' }));
    expect(transactionsApi.quickSortAnswer).toHaveBeenCalledWith('txn-1', 'Groceries', 'GUESS');
  });

  it('skip moves on without answering', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([
      question(),
      question({ id: 'vpa:second|EXPENSE', anchorTransactionId: 'txn-2', payee: 'SECOND SHOP' }),
    ]));
    renderCard();
    await userEvent.click(await screen.findByRole('button', { name: 'Skip' }));
    expect(await screen.findByText('SECOND SHOP')).toBeInTheDocument();
    expect(transactionsApi.quickSortAnswer).not.toHaveBeenCalled();
  });

  it('after the batch, offers more and stop asking with the exact wording', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([question()], {
      rest: { questions: 4, payments: 7, amount: 1890, transactionIds: ['r1', 'r2'] },
    }));
    renderCard();
    await userEvent.click(await screen.findByRole('button', { name: 'Groceries' }));
    expect(await screen.findByRole('button', { name: 'Sort 10 more' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Stop asking about these' })).toBeInTheDocument();
    expect(screen.getByText(
      '7 payments (₹1,890) stay as Personal Transfer or Other. You can change any of them later from the Ledger.',
    )).toBeInTheDocument();
    expect(screen.queryByText(/correct|done|keep as they are/i)).not.toBeInTheDocument();
  });

  it('stop asking sends the rest of the batch', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([question()], {
      rest: { questions: 2, payments: 2, amount: 90, transactionIds: ['r1', 'r2'] },
    }));
    renderCard();
    await userEvent.click(await screen.findByRole('button', { name: 'Groceries' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Stop asking about these' }));
    expect(transactionsApi.quickSortKeepRest).toHaveBeenCalledWith(['r1', 'r2']);
    expect(await screen.findByText(/All sorted/)).toBeInTheDocument();
  });

  it('sort 10 more records it and fetches past the skipped payees', async () => {
    vi.mocked(transactionsApi.quickSort)
      .mockResolvedValueOnce(batch([question(), question({ id: 'b', anchorTransactionId: 'txn-2', payee: 'SKIPPED' })], {
        rest: { questions: 1, payments: 1, amount: 50, transactionIds: ['r1'] },
      }))
      .mockResolvedValueOnce(batch([question({ id: 'c', anchorTransactionId: 'txn-3', payee: 'NEXT SHOP' })], {
        waitingTotal: 8000,
      }));
    renderCard();
    await userEvent.click(await screen.findByRole('button', { name: 'Groceries' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Skip' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Sort 10 more' }));
    expect(transactionsApi.quickSortMore).toHaveBeenCalled();
    expect(transactionsApi.quickSort).toHaveBeenLastCalledWith(1);
    expect(await screen.findByText('NEXT SHOP')).toBeInTheDocument();
    // Progress is measured against the first batch's waiting money: 10,000 -> 8,000 is 20%.
    expect(screen.getByText(/You've sorted 20% of your waiting money/)).toBeInTheDocument();
  });

  it('never says all sorted while skipped questions are still waiting', async () => {
    vi.mocked(transactionsApi.quickSort)
      .mockResolvedValueOnce(batch([question()]))
      .mockResolvedValueOnce(batch([question({ payee: 'ASKED AGAIN' })]));
    renderCard();
    await userEvent.click(await screen.findByRole('button', { name: 'Skip' }));
    expect(await screen.findByText("You skipped 1 question. It's still waiting.")).toBeInTheDocument();
    expect(screen.queryByText(/All sorted/)).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Ask the skipped ones again' }));
    expect(transactionsApi.quickSort).toHaveBeenLastCalledWith(0);
    expect(await screen.findByText('ASKED AGAIN')).toBeInTheDocument();
  });

  it('stays visible with the skipped message when Sort 10 more comes back empty', async () => {
    vi.mocked(transactionsApi.quickSort)
      .mockResolvedValueOnce(batch([question()], { rest: { questions: 1, payments: 1, amount: 5, transactionIds: ['r1'] } }))
      .mockResolvedValueOnce(batch([], { waitingTotal: 5 }));
    renderCard();
    await userEvent.click(await screen.findByRole('button', { name: 'Skip' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Sort 10 more' }));
    expect(await screen.findByText("You skipped 1 question. It's still waiting.")).toBeInTheDocument();
  });

  it('a failed answer keeps the question and says so', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([question()]));
    vi.mocked(transactionsApi.quickSortAnswer).mockRejectedValue(new Error('boom'));
    renderCard();
    await userEvent.click(await screen.findByRole('button', { name: 'Groceries' }));
    expect(await screen.findByText("Couldn't save that answer — please try again.")).toBeInTheDocument();
    expect(screen.getByText('SAMPLE STORE')).toBeInTheDocument();
  });
});
