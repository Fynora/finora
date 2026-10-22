import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { inflowApi, insightsApi, peopleApi, transactionsApi, type PeopleSuggestion } from '../../api/endpoints';
import { PeopleQuestionsCard } from './PeopleQuestionsCard';
import { WithPeopleCard } from './WithPeopleCard';

// Every name and amount here is invented.
vi.mock('../../api/endpoints', () => ({
  peopleApi: {
    suggestions: vi.fn(), list: vi.fn(), link: vi.fn(), addEntry: vi.fn(), settle: vi.fn(), addSplitRule: vi.fn(),
    preferences: vi.fn(), setOwnUpiId: vi.fn(),
  },
  insightsApi: { acknowledgements: vi.fn(), acknowledge: vi.fn() },
  inflowApi: { kinds: vi.fn(), setChoice: vi.fn() },
  transactionsApi: { search: vi.fn(), updateCategory: vi.fn() },
}));
// The UPI request names the user; every page sits inside AuthProvider in the app.
vi.mock('../../context/AuthContext', () => ({ useAuth: () => ({ fullName: 'Sample User' }) }));;

const shareCheck: PeopleSuggestion = {
  kind: 'SHARE_CHECK', ackKey: 'c-1', txnId: 'c-1', relatedTxnId: null, personKey: 'vpa:flatmate.a', label: 'Flatmate A',
  amount: 9000, date: '2026-09-04', category: 'Rent', owed: null,
  bills: [{ billId: 'b-1', category: 'Rent', payeeLabel: 'Sample Owner', date: '2026-09-01', amount: 36000, remaining: 36000 }],
};
const lentCheck: PeopleSuggestion = {
  ...shareCheck, kind: 'LENT_CHECK', ackKey: 'd-1', txnId: 'd-1', label: 'Friend K', amount: 2000, date: '2026-08-03', category: null, bills: [],
};

function Where() {
  const location = useLocation();
  return <p data-testid="where">{location.pathname + location.search}</p>;
}

function show(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/app/insights']}>
        <Routes>
          <Route path="*" element={<>{ui}<Where /></>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.mocked(peopleApi.suggestions).mockReset().mockResolvedValue({ items: [], expected: [] });
  vi.mocked(peopleApi.list).mockReset().mockResolvedValue([]);
  vi.mocked(insightsApi.acknowledgements).mockReset().mockResolvedValue([]);
  vi.mocked(insightsApi.acknowledge).mockReset().mockResolvedValue(undefined);
  vi.mocked(inflowApi.kinds).mockReset().mockResolvedValue([{ id: 'k-paid-back', name: 'Paid back', countsAsIncome: false, builtIn: 'PAID_BACK' }] as never);
  vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 } as never);
});

describe('PeopleQuestionsCard', () => {
  it('asks the server\'s questions under "Money with people", and the bill picker opens with its bills', async () => {
    vi.mocked(peopleApi.suggestions).mockResolvedValue({ items: [shareCheck], expected: [] });
    show(<PeopleQuestionsCard onChanged={vi.fn()} />);
    expect(await screen.findByText('₹9,000 from Flatmate A on 4 Sep. You filed it under Rent.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Money with people' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Yes, link it to a bill' }));
    expect(await screen.findByRole('heading', { name: 'Share of which bill?' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Rent, 1 Sep, Sample Owner, ₹36,000' })).toBeChecked();
  });

  it('hides a question marked "Leave as it is" at once and saves it under its own kind', async () => {
    vi.mocked(peopleApi.suggestions).mockResolvedValue({ items: [shareCheck, lentCheck], expected: [] });
    show(<PeopleQuestionsCard onChanged={vi.fn()} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Leave as it is: Flatmate A' }));
    await waitFor(() => expect(screen.queryByText(/from Flatmate A on 4 Sep/)).toBeNull());
    expect(insightsApi.acknowledge).toHaveBeenCalledWith('SHARE_CHECK', 'c-1');
    expect(screen.getByText('₹2,000 to Friend K on 3 Aug. Nothing back yet.')).toBeInTheDocument();
  });

  it('brings the question back when the server refuses "Leave as it is"', async () => {
    vi.mocked(peopleApi.suggestions).mockResolvedValue({ items: [shareCheck], expected: [] });
    vi.mocked(insightsApi.acknowledge).mockRejectedValue(new Error('offline'));
    show(<PeopleQuestionsCard onChanged={vi.fn()} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Leave as it is: Flatmate A' }));
    expect(await screen.findByText('₹9,000 from Flatmate A on 4 Sep. You filed it under Rent.')).toBeInTheDocument();
  });

  it('does not ask what was marked "Got it" on another device', async () => {
    vi.mocked(peopleApi.suggestions).mockResolvedValue({ items: [shareCheck, lentCheck], expected: [] });
    vi.mocked(insightsApi.acknowledgements).mockResolvedValue([{ kind: 'LENT_CHECK', itemKey: 'd-1' }]);
    show(<PeopleQuestionsCard onChanged={vi.fn()} />);
    expect(await screen.findByText(/from Flatmate A on 4 Sep/)).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText(/to Friend K on 3 Aug/)).toBeNull());
  });

  it('"Paid for something" opens that day\'s transactions', async () => {
    vi.mocked(peopleApi.suggestions).mockResolvedValue({ items: [lentCheck], expected: [] });
    show(<PeopleQuestionsCard onChanged={vi.fn()} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Paid for something' }));
    expect(screen.getByTestId('where')).toHaveTextContent('/app/transactions?from=2026-08-03&to=2026-08-03');
  });

  it('tells the page after an answer is saved, so it re-reads its figures', async () => {
    vi.mocked(peopleApi.suggestions).mockResolvedValue({ items: [lentCheck], expected: [] });
    vi.mocked(peopleApi.addEntry).mockResolvedValue({} as never);
    const onChanged = vi.fn();
    show(<PeopleQuestionsCard onChanged={onChanged} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Lent to Friend K' }));
    await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
  });

  it('shows nothing with no questions, or on a server without them', async () => {
    vi.mocked(peopleApi.suggestions).mockRejectedValue(new Error('404'));
    show(<PeopleQuestionsCard onChanged={vi.fn()} />);
    await waitFor(() => expect(peopleApi.suggestions).toHaveBeenCalled());
    expect(screen.queryByRole('heading', { name: 'Money with people' })).toBeNull();
  });
});

describe('WithPeopleCard', () => {
  it('lists who owes whom, never family, each a link to their page', async () => {
    vi.mocked(peopleApi.list).mockResolvedValue([
      { personKey: 'vpa:friend.k', label: 'Friend K', owedToUser: 500, since: '2026-08-03', family: false },
      { personKey: 'vpa:friend.p', label: 'Friend P', owedToUser: -800, since: '2026-08-12', family: false },
      { personKey: 'vpa:sibling.s', label: 'Sibling S', owedToUser: 3000, since: '2026-08-01', family: true },
    ]);
    show(<WithPeopleCard />);
    expect(await screen.findByRole('heading', { name: 'With people' })).toBeInTheDocument();
    expect(screen.getByText('Owed to you')).toBeInTheDocument();
    expect(screen.getByText('You owe')).toBeInTheDocument();
    expect(screen.queryByText('Sibling S')).toBeNull();
    expect(screen.getByRole('link', { name: 'You owe Friend P ₹800' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('link', { name: 'Friend K owes you ₹500' }));
    expect(screen.getByTestId('where')).toHaveTextContent('/app/people/vpa%3Afriend.k');
  });

  it('lists the shares expected in from people with an every-month rule, and which is late', async () => {
    vi.mocked(peopleApi.suggestions).mockResolvedValue({
      items: [], expected: [{ personKey: 'vpa:flatmate.a', label: 'Flatmate A', usualAmount: 9000, usualDay: 4, late: true, ruleId: 'r1' }],
    });
    show(<WithPeopleCard />);
    expect(await screen.findByText("Flatmate A's share")).toBeInTheDocument();
    expect(screen.getByText('late, usually around the 4th')).toBeInTheDocument();
    expect(screen.getByText('+₹9,000')).toBeInTheDocument();
  });

  it('shows nothing with nobody owing and nothing expected, or on a server without it', async () => {
    vi.mocked(peopleApi.list).mockRejectedValue(new Error('404'));
    show(<WithPeopleCard />);
    await waitFor(() => expect(peopleApi.list).toHaveBeenCalled());
    expect(screen.queryByRole('heading', { name: 'With people' })).toBeNull();
  });
});
