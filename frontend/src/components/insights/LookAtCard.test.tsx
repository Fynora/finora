import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { peopleApi, type PeopleSuggestion, type RecurringItem } from '../../api/endpoints';
import type { LookAtRow } from '../../lib/insightsFeed';
import { LookAtCard, UNDO_MS } from './LookAtCard';

vi.mock('../../api/endpoints', () => ({
  recurringApi: { categorize: vi.fn() },
  categoriesApi: { list: vi.fn().mockResolvedValue([]), options: vi.fn(), create: vi.fn() },
  peopleApi: { addEntry: vi.fn(), settle: vi.fn(), addSplitRule: vi.fn() },
  inflowApi: { kinds: vi.fn().mockResolvedValue([]), setChoice: vi.fn() },
  transactionsApi: {
    updateCategory: vi.fn(),
    search: vi.fn().mockResolvedValue({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }),
  },
  userApi: { getSettings: vi.fn().mockResolvedValue({}) },
}));
vi.mock('../../context/AuthContext', () => ({ useAuth: () => ({ fullName: 'Sample User' }) }));

// Every name and amount here is invented.
const HIGH: LookAtRow = {
  kind: 'running-high', category: 'Food', current: 9400, priorAverage: 6300, month: '2026-09',
  ackKind: 'CATEGORY_HIGH', ackItemKey: 'Food|2026-09',
};
const CARD: LookAtRow = {
  kind: 'card-statement', month: '2026-09', total: 21400, count: 2, cardName: 'Sample Card', cardLast4: '0001',
  ackKind: 'CARD_STATEMENT_MISSING', ackItemKey: '2026-09',
};
const gym: RecurringItem = {
  merchant: 'sample gym', label: 'Monthly', averageAmount: 1500, occurrences: 3, lastDate: '2026-09-05',
  nextEstimate: '2026-10-05', state: 'NEEDS_ANSWER',
};
const NEW: LookAtRow = { kind: 'new-recurring', item: gym };
const CHANGED: LookAtRow = {
  kind: 'amount-changed', merchant: 'Sample TV', category: 'Subscriptions', latestAmount: 649,
  latestDate: '2026-09-12', ackKind: 'AMOUNT_CHANGED', ackItemKey: 'sample tv|2026-09-12',
};
const owes: PeopleSuggestion = {
  kind: 'SETTLE', ackKey: 'vpa:friend.k', txnId: null, relatedTxnId: null, personKey: 'vpa:friend.k',
  label: 'Friend K', amount: 500, date: '2026-09-01', category: null, bills: [], owed: 500,
};
const PEOPLE: LookAtRow = { kind: 'people', item: owes };

function Where() {
  const location = useLocation();
  return <p data-testid="where">{location.pathname + location.search}</p>;
}

function renderCard(props: Partial<Parameters<typeof LookAtCard>[0]> = {}) {
  const onAck = vi.fn();
  const onRetry = vi.fn();
  const onAnswered = vi.fn();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/app/insights']}>
        <Routes>
          <Route path="*" element={(
            <>
              <LookAtCard
                status="ready" rows={[HIGH]} month="2026-09" firstMonth={false}
                onAck={onAck} onRetry={onRetry} onAnswered={onAnswered} {...props}
              />
              <Where />
            </>
          )} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...view, onAck, onRetry, onAnswered };
}

describe('LookAtCard rows', () => {
  it('names the card and the month of a missing card statement, and links to Import', () => {
    renderCard({ rows: [CARD] });
    expect(screen.getByText(
      'You paid ₹21,400 toward your Sample Card ending 0001 in September 2026 with no card statement uploaded for it. Upload it to see what you bought.',
    )).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Upload statement' })).toHaveAttribute('href', '/app/import');
  });

  it('says "a credit card" when the card is not known', () => {
    renderCard({ rows: [{ ...CARD, cardName: null, cardLast4: null }] });
    expect(screen.getByText(/toward a credit card in September 2026/)).toBeInTheDocument();
  });

  it('shows a category running high with the rupee gap, linking to its payments that month', () => {
    renderCard({ rows: [HIGH] });
    const link = screen.getByRole('link', { name: 'Food is ₹3,100 above your usual month, ₹9,400 vs ₹6,300. Shows the payments' });
    expect(link).toHaveAttribute('href', '/app/transactions?category=Food&from=2026-09-01&to=2026-09-30');
    expect(within(link).getByText('Food is ₹3,100 above your usual month')).toBeInTheDocument();
  });

  it('asks about a new repeating payment in place, with no "Got it"', () => {
    renderCard({ rows: [NEW] });
    expect(screen.getByText('New repeating payment: sample gym')).toBeInTheDocument();
    expect(screen.getByText('What is this ₹1,500 monthly payment?')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Got it/ })).not.toBeInTheDocument();
  });

  it('shows an amount change with its question and a "Got it"', () => {
    renderCard({ rows: [CHANGED] });
    expect(screen.getByText('Amount changed: Sample TV')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Got it: Sample TV' })).toBeInTheDocument();
  });

  it('asks the questions about money with people in place', () => {
    renderCard({ rows: [PEOPLE] });
    expect(screen.getByText(/Friend K owes you ₹500/)).toBeInTheDocument();
  });

  it('counts the rows in its heading', () => {
    renderCard({ rows: [CARD, HIGH, NEW] });
    expect(screen.getByRole('heading', { name: 'To look at 3' })).toBeInTheDocument();
  });
});

describe('LookAtCard "Got it" with undo', () => {
  beforeEach(() => { vi.useFakeTimers({ shouldAdvanceTime: true }); });
  afterEach(() => { vi.useRealTimers(); });

  it('hides the row at once, offers Undo, and saves only when the undo time ends', async () => {
    const { onAck } = renderCard({ rows: [HIGH, CARD] });
    await userEvent.click(screen.getByRole('button', { name: 'Got it: Food' }));

    expect(screen.queryByRole('link', { name: /Food is ₹3,100/ })).not.toBeInTheDocument();
    expect(screen.getByText('Hidden: Food above your usual month')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'To look at 1' })).toBeInTheDocument();
    expect(onAck).not.toHaveBeenCalled();

    await act(() => vi.advanceTimersByTimeAsync(UNDO_MS));
    expect(onAck).toHaveBeenCalledTimes(1);
    expect(onAck).toHaveBeenCalledWith(HIGH);
    expect(screen.queryByText('Hidden: Food above your usual month')).not.toBeInTheDocument();
  });

  it('brings the row back on Undo and saves nothing', async () => {
    const { onAck } = renderCard({ rows: [HIGH] });
    await userEvent.click(screen.getByRole('button', { name: 'Got it: Food' }));
    await userEvent.click(screen.getByRole('button', { name: 'Undo: Food above your usual month' }));

    expect(screen.getByRole('link', { name: /Food is ₹3,100/ })).toBeInTheDocument();
    await act(() => vi.advanceTimersByTimeAsync(UNDO_MS * 2));
    expect(onAck).not.toHaveBeenCalled();
  });

  it('saves a pending "Got it" at once when the page closes, so it is never lost', async () => {
    const { onAck, unmount } = renderCard({ rows: [HIGH] });
    await userEvent.click(screen.getByRole('button', { name: 'Got it: Food' }));
    unmount();
    expect(onAck).toHaveBeenCalledWith(HIGH);
    await act(() => vi.advanceTimersByTimeAsync(UNDO_MS * 2));
    expect(onAck).toHaveBeenCalledTimes(1);
  });

  it('gives a people question\'s "not now" answer the same undo', async () => {
    const { onAck } = renderCard({ rows: [PEOPLE] });
    await userEvent.click(screen.getByRole('button', { name: 'Keep waiting: Friend K' }));
    expect(screen.getByText('Hidden: Friend K')).toBeInTheDocument();
    expect(onAck).not.toHaveBeenCalled();
    await act(() => vi.advanceTimersByTimeAsync(UNDO_MS));
    expect(onAck).toHaveBeenCalledWith(PEOPLE);
  });

  it('shows the empty line, not "Hidden", once the last row is saved', async () => {
    renderCard({ rows: [HIGH] });
    await userEvent.click(screen.getByRole('button', { name: 'Got it: Food' }));
    expect(screen.queryByText('Nothing new to look at for September 2026.')).not.toBeInTheDocument();
  });
});

describe('LookAtCard states', () => {
  it('says there is nothing new for the month', () => {
    renderCard({ rows: [] });
    expect(screen.getByText('Nothing new to look at for September 2026.')).toBeInTheDocument();
  });

  it('asks for last month\'s statement when there is only one month to go on', () => {
    renderCard({ rows: [], firstMonth: true });
    expect(screen.getByText(
      'Nothing to compare September 2026 with yet. Add the month before and Fynora can tell you what changed.',
    )).toBeInTheDocument();
    expect(screen.getByRole('link', { name: "Upload last month's statement" })).toHaveAttribute('href', '/app/import');
  });

  it('offers to try again when the list failed to load', async () => {
    const { onRetry } = renderCard({ status: 'error', rows: [] });
    expect(screen.getByText("Couldn't load what needs a look.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('announces that it is loading', () => {
    renderCard({ status: 'loading', rows: [] });
    expect(screen.getByText('Loading what needs a look')).toBeInTheDocument();
  });
});

// Moved from the money-with-people card these rows used to sit in (components/people/PeopleQuestionsCard).
describe('LookAtCard people rows', () => {
  const shareCheck: PeopleSuggestion = {
    kind: 'SHARE_CHECK', ackKey: 'c-1', txnId: 'c-1', relatedTxnId: null, personKey: 'vpa:flatmate.a', label: 'Flatmate A',
    amount: 9000, date: '2026-09-04', category: 'Rent', owed: null,
    bills: [{ billId: 'b-1', category: 'Rent', payeeLabel: 'Sample Owner', date: '2026-09-01', amount: 36000, remaining: 36000 }],
  };
  const lentCheck: PeopleSuggestion = {
    ...shareCheck, kind: 'LENT_CHECK', ackKey: 'd-1', txnId: 'd-1', label: 'Friend K', amount: 2000, date: '2026-08-03',
    category: null, bills: [],
  };

  it('opens the bill picker with its bills', async () => {
    renderCard({ rows: [{ kind: 'people', item: shareCheck }] });
    expect(screen.getByText('₹9,000 from Flatmate A on 4 Sep. You filed it under Rent.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Yes, link it to a bill' }));
    expect(await screen.findByRole('heading', { name: 'Share of which bill?' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Rent, 1 Sep, Sample Owner, ₹36,000' })).toBeChecked();
  });

  it('"Paid for something" opens that day\'s transactions and is saved at once, with no undo', async () => {
    const { onAck } = renderCard({ rows: [{ kind: 'people', item: lentCheck }] });
    await userEvent.click(screen.getByRole('button', { name: 'Paid for something' }));
    expect(screen.getByTestId('where')).toHaveTextContent('/app/transactions?from=2026-08-03&to=2026-08-03');
    expect(onAck).toHaveBeenCalledWith({ kind: 'people', item: lentCheck });
  });

  it('tells the page after an answer is saved, so it re-reads its figures', async () => {
    vi.mocked(peopleApi.addEntry).mockResolvedValue({} as never);
    const { onAnswered } = renderCard({ rows: [{ kind: 'people', item: lentCheck }] });
    await userEvent.click(screen.getByRole('button', { name: 'Lent to Friend K' }));
    await waitFor(() => expect(onAnswered).toHaveBeenCalledTimes(1));
  });
});
