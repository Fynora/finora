import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { peopleApi, type PersonDetail } from '../api/endpoints';
import Person from './Person';

// Every name and amount here is invented.
vi.mock('../api/endpoints', () => ({
  peopleApi: { person: vi.fn(), setFamily: vi.fn(), removeSplitRule: vi.fn() },
}));
const refreshed = vi.fn();
vi.mock('../lib/invalidateMoneyFigures', () => ({ useInvalidateMoneyFigures: () => refreshed }));

const person = vi.mocked(peopleApi.person);
const setFamily = vi.mocked(peopleApi.setFamily);
const removeSplitRule = vi.mocked(peopleApi.removeSplitRule);

function detail(overrides: Partial<PersonDetail> = {}): PersonDetail {
  return {
    personKey: 'vpa:friend.k', label: 'Friend K', owedToUser: 500, since: '2026-09-03', family: false,
    timeline: [
      { date: '2026-09-03', kind: 'LENT', amount: 2000, txnId: 't1' },
      { date: '2026-09-20', kind: 'PAID_BACK', amount: 1500, txnId: 't2' },
    ],
    rules: [],
    ...overrides,
  };
}

function show(path = '/app/people/vpa%3Afriend.k') {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>
      <MemoryRouter initialEntries={[path]}>
        <Routes><Route path="/app/people/:personKey" element={<Person />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  person.mockReset().mockResolvedValue(detail());
  setFamily.mockReset().mockResolvedValue(undefined);
  removeSplitRule.mockReset().mockResolvedValue(undefined);
  refreshed.mockReset();
});

describe('Person', () => {
  it('reads the person from the address, and shows what they owe, since when, and the history', async () => {
    show();
    expect(await screen.findByRole('heading', { name: 'Friend K' })).toBeInTheDocument();
    expect(person).toHaveBeenCalledWith('vpa:friend.k');
    expect(screen.getByText('Owes you ₹500')).toBeInTheDocument();
    expect(screen.getByText('Open since 3 Sep')).toBeInTheDocument();
    expect(screen.getByText('You lent ₹2,000')).toBeInTheDocument();
    expect(screen.getByText('Paid back ₹1,500')).toBeInTheDocument();
  });

  it('says when the user owes them', async () => {
    person.mockResolvedValueOnce(detail({ owedToUser: -800, timeline: [{ date: '2026-09-12', kind: 'BORROWED', amount: 800, txnId: 't3' }] }));
    show();
    expect(await screen.findByText('You owe ₹800')).toBeInTheDocument();
    expect(screen.getByText('You borrowed ₹800')).toBeInTheDocument();
  });

  it('says "All settled" with nothing open', async () => {
    person.mockResolvedValueOnce(detail({ owedToUser: 0, since: null }));
    show();
    expect(await screen.findByText('All settled')).toBeInTheDocument();
    expect(screen.queryByText(/Open since/)).toBeNull();
  });

  it('names every kind of entry in the history', async () => {
    person.mockResolvedValueOnce(detail({
      owedToUser: 0,
      timeline: [
        { date: '2026-09-01', kind: 'SHARE', amount: 9000, txnId: 'a' },
        { date: '2026-09-02', kind: 'REPAID', amount: 300, txnId: 'b' },
        { date: '2026-09-03', kind: 'CASH_SETTLED', amount: 200, txnId: null },
        { date: '2026-09-04', kind: 'LET_GO', amount: 100, txnId: null },
      ],
    }));
    show();
    expect(await screen.findByText('Their share of a bill ₹9,000')).toBeInTheDocument();
    expect(screen.getByText('You paid back ₹300')).toBeInTheDocument();
    expect(screen.getByText('Settled in cash ₹200')).toBeInTheDocument();
    expect(screen.getByText('Let go ₹100')).toBeInTheDocument();
  });

  it('removes a rule, and every figure refreshes', async () => {
    person.mockResolvedValue(detail({
      rules: [{ id: 'r1', senderKey: 'vpa:friend.k', billCategoryId: 'c', billCategory: 'Rent', billPayeeKey: 'vpa:owner.b', billMerchant: 'Sample Owner' }],
    }));
    show();
    expect(await screen.findByText('Rent shares, every month')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Remove the Rent rule' }));
    await waitFor(() => expect(removeSplitRule).toHaveBeenCalledWith('r1'));
    await waitFor(() => expect(refreshed).toHaveBeenCalled());
  });

  it('marks them family, which stops loan questions about them', async () => {
    show();
    await userEvent.click(await screen.findByRole('checkbox', { name: 'Family' }));
    await waitFor(() => expect(setFamily).toHaveBeenCalledWith('vpa:friend.k', true));
    await waitFor(() => expect(refreshed).toHaveBeenCalled());
  });

  it('says so when the page cannot be loaded', async () => {
    person.mockRejectedValue(new Error('offline'));
    show();
    expect(await screen.findByText("Couldn't load this person. Try again later.")).toBeInTheDocument();
  });
});
