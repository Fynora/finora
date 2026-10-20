import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { peopleApi, type PersonDetail } from '../api/endpoints';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import { PersonScreen } from './PersonScreen';

// Hoisted above the imports by babel-jest. Every name and amount here is invented.
jest.mock('../api/endpoints', () => ({
  peopleApi: { person: jest.fn(), setFamily: jest.fn(), removeSplitRule: jest.fn() },
}));
jest.mock('../lib/invalidateFinancialData', () => ({
  ...jest.requireActual('../lib/invalidateFinancialData'),
  invalidateFinancialData: jest.fn(),
}));
jest.mock('../lib/useReduceTransparency', () => ({ useReduceTransparency: jest.fn(() => null) }));

const person = peopleApi.person as jest.Mock;
const setFamily = peopleApi.setFamily as jest.Mock;
const removeSplitRule = peopleApi.removeSplitRule as jest.Mock;

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

function show(personKey = 'vpa:friend.k') {
  const route = { key: 'Person-1', name: 'Person' as const, params: { personKey } };
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>
      <PersonScreen route={route} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  person.mockReset().mockResolvedValue(detail());
  setFamily.mockReset().mockResolvedValue(undefined);
  removeSplitRule.mockReset().mockResolvedValue(undefined);
  (invalidateFinancialData as jest.Mock).mockReset();
});

describe('PersonScreen', () => {
  it('shows what they owe, since when, and the history oldest first', async () => {
    show();
    expect(await screen.findByText('Friend K')).toBeTruthy();
    expect(person).toHaveBeenCalledWith('vpa:friend.k');
    expect(screen.getByText('Owes you ₹500')).toBeTruthy();
    expect(screen.getByText('Open since 3 Sep')).toBeTruthy();
    expect(screen.getByText('You lent ₹2,000')).toBeTruthy();
    expect(screen.getByText('Paid back ₹1,500')).toBeTruthy();
  });

  it('says when the user owes them, and when nothing is open', async () => {
    person.mockResolvedValueOnce(detail({ owedToUser: -800, timeline: [{ date: '2026-09-12', kind: 'BORROWED', amount: 800, txnId: 't3' }] }));
    show();
    expect(await screen.findByText('You owe ₹800')).toBeTruthy();
    expect(screen.getByText('You borrowed ₹800')).toBeTruthy();
  });

  it('says "All settled" with nothing open', async () => {
    person.mockResolvedValueOnce(detail({ owedToUser: 0, since: null }));
    show();
    expect(await screen.findByText('All settled')).toBeTruthy();
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
    expect(await screen.findByText('Their share of a bill ₹9,000')).toBeTruthy();
    expect(screen.getByText('You paid back ₹300')).toBeTruthy();
    expect(screen.getByText('Settled in cash ₹200')).toBeTruthy();
    expect(screen.getByText('Let go ₹100')).toBeTruthy();
  });

  it('removes a rule, and every figure refreshes', async () => {
    person.mockResolvedValue(detail({
      rules: [{ id: 'r1', senderKey: 'vpa:friend.k', billCategoryId: 'c', billCategory: 'Rent', billPayeeKey: 'vpa:owner.b', billMerchant: 'Sample Owner' }],
    }));
    show();
    expect(await screen.findByText('Rent shares, every month')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Remove the Rent rule' }));
    await waitFor(() => expect(removeSplitRule).toHaveBeenCalledWith('r1'));
    await waitFor(() => expect(invalidateFinancialData).toHaveBeenCalled());
  });

  it('marks them family, which stops loan questions about them', async () => {
    show();
    fireEvent(await screen.findByLabelText('Family'), 'valueChange', true);
    await waitFor(() => expect(setFamily).toHaveBeenCalledWith('vpa:friend.k', true));
    await waitFor(() => expect(invalidateFinancialData).toHaveBeenCalled());
  });

  it('says so when the page cannot be loaded', async () => {
    person.mockRejectedValue(new Error('offline'));
    show();
    expect(await screen.findByText("Couldn't load this. Pull down to try again.")).toBeTruthy();
  });
});
