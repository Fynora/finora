import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { QuickSortCard } from './QuickSortCard';
import { transactionsApi, categoriesApi } from '../api/endpoints';
import type { QuickSortBatch, QuickSortQuestion } from '../types';
import { formatDate } from '../utils/date';
import { MemoryRouter } from 'react-router-dom';
import { DASHBOARD_QUICK_SORT_KEY, notNowValue } from '../lib/dashboardQuickSort';
import { trackNavigation } from '../lib/trackNavigation';

vi.mock('../lib/trackNavigation', () => ({ trackNavigation: vi.fn() }));

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
      <MemoryRouter>
        <QuickSortCard />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function recentRows(n: number): QuickSortQuestion['samples'] {
  return Array.from({ length: n }, (_, i) => ({
    id: `r-${i}`, date: `2026-11-${String(12 - i).padStart(2, '0')}`, description: `UPI/RAVI K/REF${i}`,
    amount: 200 + i, type: 'EXPENSE',
  }));
}

function withPattern(over: Partial<QuickSortQuestion> = {}): QuickSortQuestion {
  return question({
    kind: 'PERSON_PAID', payee: 'RAVI K', answers: ['Personal Transfer', 'Groceries'],
    allPayments: 14, firstDate: '2026-08-03', typicalLow: 150, typicalHigh: 400, recent: recentRows(10), ...over,
  });
}

describe('QuickSortCard pattern line', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.mocked(transactionsApi.quickSort).mockReset();
    vi.mocked(transactionsApi.quickSortAnswer).mockReset().mockResolvedValue({ filed: 1 });
    vi.mocked(trackNavigation).mockReset();
  });

  it('opens and closes the payee\'s payments from the pattern line', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([withPattern()]));
    renderCard();
    const line = await screen.findByRole('button', { name: '14 payments since Aug · usually ₹150–₹400' });
    expect(line).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText(/UPI\/RAVI K\/REF0/)).not.toBeInTheDocument();
    await userEvent.click(line);
    expect(line).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText(`${formatDate('2026-11-12')} · ₹200 · UPI/RAVI K/REF0`)).toBeInTheDocument();
    expect(screen.getAllByText(/UPI\/RAVI K\/REF/)).toHaveLength(10);
    await userEvent.click(line);
    expect(screen.queryByText(/UPI\/RAVI K\/REF0/)).not.toBeInTheDocument();
  });

  it('still says how many payments are waiting and how much', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([withPattern()]));
    renderCard();
    expect(await screen.findByText('3 waiting · ₹1,240')).toBeInTheDocument();
  });

  it('closes the list when the next question comes up', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([
      withPattern(),
      withPattern({ id: 'vpa:second|EXPENSE', anchorTransactionId: 'txn-2', payee: 'SECOND PERSON' }),
    ]));
    renderCard();
    await userEvent.click(await screen.findByRole('button', { name: /14 payments since Aug/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Groceries' }));
    expect(await screen.findByText('SECOND PERSON')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /14 payments since Aug/ })).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText(/UPI\/RAVI K\/REF0/)).not.toBeInTheDocument();
  });

  it('links to all payments in Transactions when there are more than shown', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([withPattern()]));
    renderCard();
    await userEvent.click(await screen.findByRole('button', { name: /14 payments since Aug/ }));
    const link = screen.getByRole('link', { name: 'See all 14 in Transactions' });
    expect(link).toHaveAttribute('href', '/app/transactions?q=RAVI%20K');
    await userEvent.click(link);
    expect(trackNavigation).toHaveBeenCalledWith('transactions', 'contextual');
  });

  it('offers no See all link when every payment is listed', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([withPattern({ allPayments: 3, recent: recentRows(3) })]));
    renderCard();
    await userEvent.click(await screen.findByRole('button', { name: /3 payments since Aug/ }));
    expect(screen.queryByRole('link', { name: /See all/ })).not.toBeInTheDocument();
  });

  it('answers the same with the list open', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([withPattern()]));
    renderCard();
    await userEvent.click(await screen.findByRole('button', { name: /14 payments since Aug/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Groceries' }));
    expect(transactionsApi.quickSortAnswer).toHaveBeenCalledWith('txn-1', 'Groceries', 'PERSON_PAID');
  });

  it('falls back to today\'s line without a pattern', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([question()]));
    renderCard();
    expect(await screen.findByText(/3 payments/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /payments since/ })).not.toBeInTheDocument();
    expect(screen.getByText('Show payments')).toBeInTheDocument();
  });

  it('works the same on the Dashboard card', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([withPattern()]));
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter>
          <QuickSortCard variant="dashboard" accountEmail="a@example.com" />
        </MemoryRouter>
      </QueryClientProvider>
    );
    const line = await screen.findByRole('button', { name: '14 payments since Aug · usually ₹150–₹400' });
    await userEvent.click(line);
    expect(screen.getAllByText(/UPI\/RAVI K\/REF/)).toHaveLength(10);
    expect(screen.getByRole('link', { name: 'See all 14 in Transactions' })).toBeInTheDocument();
  });
});

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

  it('writes the latest payment date the way the rest of the app does', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([question()]));
    renderCard();
    expect(await screen.findByText(`3 payments · ₹1,240 · latest ${formatDate('2026-09-12')}`)).toBeInTheDocument();
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
      // The server's figures after the 1240 answer and a skip: what it filed is sorted, not waiting.
      .mockResolvedValueOnce(batch([question({ id: 'c', anchorTransactionId: 'txn-3', payee: 'NEXT SHOP' })], {
        waitingTotal: 8000,
        sortedTotal: 2000,
      }));
    renderCard();
    await userEvent.click(await screen.findByRole('button', { name: 'Groceries' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Skip' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Sort 10 more' }));
    expect(transactionsApi.quickSortMore).toHaveBeenCalled();
    expect(transactionsApi.quickSort).toHaveBeenLastCalledWith(1);
    expect(await screen.findByText('NEXT SHOP')).toBeInTheDocument();
    // Sorted out of everything that waited: 2,000 of 10,000 is 20%.
    expect(screen.getByText(/You've sorted 20% of your waiting money/)).toBeInTheDocument();
  });

  // "Stop asking about these" clears only the payees after the batch. A question skipped in the
  // batch is still waiting, so the card must not call everything sorted or show 100%.
  it('after stopping, still counts a skipped question as waiting', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch(
      [question(), question({ id: 'vpa:second|EXPENSE', anchorTransactionId: 'txn-2', payee: 'SECOND SHOP' })],
      { waitingTotal: 10000, rest: { questions: 3, payments: 4, amount: 7520, transactionIds: ['r1', 'r2'] } },
    ));
    renderCard();
    await userEvent.click(await screen.findByRole('button', { name: 'Skip' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Groceries' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Stop asking about these' }));
    expect(transactionsApi.quickSortKeepRest).toHaveBeenCalledWith(['r1', 'r2']);
    expect(await screen.findByText("You skipped 1 question. It's still waiting.")).toBeInTheDocument();
    expect(screen.queryByText(/All sorted/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sort 10 more' })).not.toBeInTheDocument();
    // 1,240 answered, out of 10,000 less the 7,520 that stopped waiting.
    expect(screen.getByText(/You've sorted 50% of your waiting money/)).toBeInTheDocument();
  });

  it('after stopping with nothing skipped, says all sorted at 100%', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([question()], {
      waitingTotal: 10000, rest: { questions: 3, payments: 4, amount: 8760, transactionIds: ['r1'] },
    }));
    renderCard();
    await userEvent.click(await screen.findByRole('button', { name: 'Groceries' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Stop asking about these' }));
    expect(await screen.findByText(/All sorted/)).toBeInTheDocument();
    expect(screen.getByText(/You've sorted 100% of your waiting money/)).toBeInTheDocument();
  });

  // Amounts carry paise, and summing them in a different order from the server's total can land a
  // hair under it: 0.7 + 0.1 is 0.7999999999999999 against 0.8. "All sorted" must still say 100%.
  it('says 100% when everything is sorted, whatever the floating point does', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([
      question({ total: 0.7 }),
      question({ id: 'vpa:second|EXPENSE', anchorTransactionId: 'txn-2', payee: 'SECOND SHOP', total: 0.1 }),
    ], { waitingTotal: 0.8 }));
    renderCard();
    await userEvent.click(await screen.findByRole('button', { name: 'Groceries' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Dining' }));
    expect(await screen.findByText(/All sorted/)).toBeInTheDocument();
    expect(screen.getByText(/You've sorted 100% of your waiting money/)).toBeInTheDocument();
  });

  // A tester left Quick sort part-way and came back to 0%: progress counted only this visit. It
  // now counts what earlier visits sorted too.
  it('picks up where the last visit ended', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([
      question(),
      question({ id: 'vpa:second|EXPENSE', anchorTransactionId: 'txn-2', payee: 'SECOND SHOP' }),
    ], { waitingTotal: 6000, sortedTotal: 4000 }));
    renderCard();
    expect(await screen.findByText(/You've sorted 40% of your waiting money/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Groceries' }));
    // (4,000 + 1,240) / 10,000, floored.
    expect(await screen.findByText(/You've sorted 52% of your waiting money/)).toBeInTheDocument();
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

describe('QuickSortCard on the Dashboard', () => {
  const EMAIL = 'a@example.com';

  function shops(n: number): QuickSortQuestion[] {
    return Array.from({ length: n }, (_, i) =>
      question({ id: `vpa:shop${i + 1}|EXPENSE`, anchorTransactionId: `txn-${i + 1}`, payee: `SHOP ${i + 1}` }));
  }

  function renderDashboardCard() {
    const queryClient = new QueryClient();
    return render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <QuickSortCard variant="dashboard" accountEmail={EMAIL} />
        </MemoryRouter>
      </QueryClientProvider>
    );
  }

  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
    vi.mocked(transactionsApi.quickSort).mockReset();
    vi.mocked(transactionsApi.quickSortAnswer).mockReset().mockResolvedValue({ filed: 1 });
    vi.mocked(transactionsApi.quickSortMore).mockReset().mockResolvedValue({} as any);
    vi.mocked(transactionsApi.quickSortKeepRest).mockReset().mockResolvedValue({ cleared: 2 });
    vi.mocked(categoriesApi.list).mockResolvedValue([] as any);
    vi.mocked(categoriesApi.options).mockResolvedValue({ icons: [], colors: [] } as any);
  });

  it('asks at most three questions, then says how many payees are still waiting', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch(shops(5), {
      rest: { questions: 4, payments: 9, amount: 900, transactionIds: [] },
    }));
    renderDashboardCard();
    expect(await screen.findByText('Sort a few payments')).toBeInTheDocument();
    for (const payee of ['SHOP 1', 'SHOP 2', 'SHOP 3']) {
      expect(await screen.findByText(payee)).toBeInTheDocument();
      await userEvent.click(screen.getByRole('button', { name: 'Groceries' }));
    }
    expect(await screen.findByText('6 payees still waiting')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open Quick sort' })).toHaveAttribute('href', '/app/transactions');
    expect(screen.queryByText('SHOP 4')).not.toBeInTheDocument();
    expect(transactionsApi.quickSortAnswer).toHaveBeenCalledTimes(3);
  });

  it('records the way to the full Quick sort as a contextual navigation', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch(shops(1), {
      rest: { questions: 2, payments: 4, amount: 400, transactionIds: [] },
    }));
    renderDashboardCard();
    await userEvent.click(await screen.findByRole('button', { name: 'Groceries' }));
    await userEvent.click(await screen.findByRole('link', { name: 'Open Quick sort' }));
    expect(trackNavigation).toHaveBeenCalledWith('transactions', 'contextual');
  });

  it('sends the Dashboard source with the batch and the answer', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch(shops(2)));
    renderDashboardCard();
    await userEvent.click(await screen.findByRole('button', { name: 'Groceries' }));
    expect(transactionsApi.quickSort).toHaveBeenCalledWith(0, false, 'DASHBOARD');
    expect(transactionsApi.quickSortAnswer).toHaveBeenCalledWith('txn-1', 'Groceries', 'SHOP', 'DASHBOARD');
  });

  it('never offers Stop asking about these or Sort 10 more', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch(shops(1), {
      rest: { questions: 4, payments: 9, amount: 900, transactionIds: ['t9'] },
    }));
    renderDashboardCard();
    await userEvent.click(await screen.findByRole('button', { name: 'Groceries' }));
    expect(await screen.findByText('4 payees still waiting')).toBeInTheDocument();
    expect(screen.queryByText('Stop asking about these')).not.toBeInTheDocument();
    expect(screen.queryByText(/Sort 10 more/)).not.toBeInTheDocument();
  });

  it('skip moves to the next question and counts toward the three', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch(shops(4)));
    renderDashboardCard();
    await userEvent.click(await screen.findByRole('button', { name: 'Skip' }));
    expect(await screen.findByText('SHOP 2')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Skip' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Skip' }));
    expect(await screen.findByText('4 payees still waiting')).toBeInTheDocument();
    expect(transactionsApi.quickSortAnswer).not.toHaveBeenCalled();
  });

  it('Not now hides the card and remembers today for this account', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch(shops(2)));
    const { container } = renderDashboardCard();
    await userEvent.click(await screen.findByRole('button', { name: 'Not now' }));
    expect(container).toBeEmptyDOMElement();
    expect(localStorage.getItem(DASHBOARD_QUICK_SORT_KEY)).toBe(notNowValue(EMAIL, new Date()));
  });

  it('renders nothing and fetches nothing when hidden earlier today', async () => {
    localStorage.setItem(DASHBOARD_QUICK_SORT_KEY, notNowValue(EMAIL, new Date()));
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch(shops(2)));
    const { container } = renderDashboardCard();
    await new Promise((r) => setTimeout(r, 0));
    expect(container).toBeEmptyDOMElement();
    expect(transactionsApi.quickSort).not.toHaveBeenCalled();
  });

  it('still shows the card when storage cannot be read', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch(shops(1)));
    renderDashboardCard();
    expect(await screen.findByText('SHOP 1')).toBeInTheDocument();
  });

  it('a one-question batch ends without a zero count', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch(shops(1)));
    const { container } = renderDashboardCard();
    await userEvent.click(await screen.findByRole('button', { name: 'Groceries' }));
    await waitFor(() => expect(container).toBeEmptyDOMElement());
    expect(screen.queryByText(/0 payees/)).not.toBeInTheDocument();
  });

  it('renders nothing for an empty batch', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue(batch([], { waitingTotal: 0 }));
    const { container } = renderDashboardCard();
    await waitFor(() => expect(transactionsApi.quickSort).toHaveBeenCalled());
    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });

  it('renders nothing, and no error, when the batch fails to load', async () => {
    vi.mocked(transactionsApi.quickSort).mockRejectedValue(new Error('down'));
    const { container } = renderDashboardCard();
    await waitFor(() => expect(transactionsApi.quickSort).toHaveBeenCalled());
    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });
});
