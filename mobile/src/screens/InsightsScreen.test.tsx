import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useNavigation, useScrollToTop } from '@react-navigation/native';
import { usePreventScreenCapture } from '../lib/screenCapture';
import { trackNavigation } from '../lib/trackNavigation';
import { InsightsScreen } from './InsightsScreen';
import { expectHaptics, resetHapticCalls } from '../test/haptics';
import {
  accountsApi, categoriesApi, inflowApi, insightsApi, onboardingApi, peopleApi, recurringApi, reportsApi, usageApi,
  type InsightsData, type PeopleSuggestion,
} from '../api/endpoints';

// Every name and amount here is invented.

jest.mock('../api/endpoints', () => ({
  insightsApi: { get: jest.fn(), acknowledgements: jest.fn(), acknowledge: jest.fn() },
  recurringApi: { list: jest.fn(), dismiss: jest.fn(), categorize: jest.fn(), changedAmounts: jest.fn() },
  reportsApi: { availableMonths: jest.fn(), trend: jest.fn() },
  accountsApi: { list: jest.fn() },
  categoriesApi: { list: jest.fn() },
  usageApi: { recordView: jest.fn().mockResolvedValue(undefined) },
  peopleApi: { suggestions: jest.fn(), list: jest.fn(), link: jest.fn(), addEntry: jest.fn(), settle: jest.fn(), preferences: jest.fn() },
  inflowApi: { kinds: jest.fn(), setChoice: jest.fn() },
  transactionsApi: { search: jest.fn(), updateCategory: jest.fn() },
  onboardingApi: {
    getChecklist: jest.fn().mockResolvedValue({ items: [], completedCount: 0, totalCount: 6 }),
    completeChecklistItem: jest.fn().mockResolvedValue(undefined),
  },
}));
jest.mock('../lib/trackNavigation', () => ({ trackNavigation: jest.fn() }));
// The UPI request names the user; the screen sits inside AuthProvider in the app.
jest.mock('../context/AuthContext', () => ({ useAuth: () => ({ fullName: 'Sample User' }) }));

const insights = insightsApi as jest.Mocked<typeof insightsApi>;
const recurring = recurringApi as jest.Mocked<typeof recurringApi>;
const reports = reportsApi as jest.Mocked<typeof reportsApi>;
const people = peopleApi as jest.Mocked<typeof peopleApi>;
// The shared navigation stub (test/setup.ts), read inside a callback as the other screen tests do.
let navigate: jest.Mock;

function insightsData(overrides: Partial<InsightsData> = {}): InsightsData {
  return {
    sentences: [],
    movers: [{ category: 'Food', current: 9400, priorAverage: 6300, pctChange: 49 }],
    coverageCaveat: null,
    biggestCategory: null,
    topMerchant: null,
    reportingMonth: '2026-09',
    reportingMonthIsCurrent: false,
    unlinkedCardBills: { month: '2026-09', total: 21400, count: 2, cardName: 'Sample Card', cardLast4: '0001' },
    ...overrides,
  };
}

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <InsightsScreen />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  navigate = (useNavigation() as unknown as { navigate: jest.Mock }).navigate;
  navigate.mockClear();
  (trackNavigation as jest.Mock).mockClear();
  insights.get.mockReset().mockResolvedValue(insightsData());
  insights.acknowledgements.mockReset().mockResolvedValue([]);
  insights.acknowledge.mockReset().mockResolvedValue(undefined);
  recurring.list.mockReset().mockResolvedValue([
    // Dates far from any real "today", so past and ahead never depend on the clock.
    { merchant: 'sample gym', label: 'Monthly', averageAmount: 1500, occurrences: 3, lastDate: '2019-12-05', nextEstimate: '2020-01-05', state: 'NEEDS_ANSWER' },
    { merchant: 'sample tv', label: 'Monthly', averageAmount: 649, occurrences: 8, lastDate: '2098-12-20', nextEstimate: '2099-01-20', state: 'ANSWERED', answer: 'Subscriptions' },
  ]);
  recurring.changedAmounts.mockReset().mockResolvedValue([]);
  recurring.dismiss.mockReset().mockResolvedValue(undefined);
  reports.availableMonths.mockReset().mockResolvedValue(['2026-08', '2026-09']);
  reports.trend.mockReset().mockResolvedValue([
    { month: '2026-08', income: 0, expense: 40000, transactionCount: 9, complete: true },
    { month: '2026-09', income: 0, expense: 48000, transactionCount: 11, complete: true },
  ]);
  (accountsApi.list as jest.Mock).mockReset().mockResolvedValue([{ id: 'a1', lastStatementPeriodEnd: '2026-09-30' }]);
  (categoriesApi.list as jest.Mock).mockReset().mockResolvedValue([]);
  people.suggestions.mockReset().mockResolvedValue({ items: [], expected: [] });
  people.list.mockReset().mockResolvedValue([]);
  (inflowApi.kinds as jest.Mock).mockReset().mockResolvedValue([{ id: 'k-paid-back', name: 'Paid back', countsAsIncome: false, builtIn: 'PAID_BACK' }]);
});

describe('InsightsScreen', () => {
  it('is one screen of what is worth knowing, with no tabs', async () => {
    renderScreen();
    expect(await screen.findByText('Food is ₹3,100 above your usual month')).toBeTruthy();
    expect(screen.getByText('To look at')).toBeTruthy();
    expect(screen.getByText('Coming up')).toBeTruthy();
    expect(screen.getByText('Paid regularly')).toBeTruthy();
    expect(screen.getByText(/toward your Sample Card ending 0001 in September 2026/)).toBeTruthy();
    expect(screen.getByText('New repeating payment: sample gym')).toBeTruthy();
    for (const tab of ['Overview', 'Spending', 'Income', 'Recurring', 'Trends']) {
      expect(screen.queryByRole('button', { name: tab })).toBeNull();
    }
  });

  it('opens with one sentence about the month, from the same figures Reports shows', async () => {
    renderScreen();
    expect(await screen.findByText('You spent ₹48,000 in September 2026, 20% more than August.')).toBeTruthy();
    expect(reports.trend).toHaveBeenCalledWith('2026-09', 6);
  });

  it('says "so far" with no comparison for a month not fully in', async () => {
    reports.trend.mockResolvedValue([
      { month: '2026-08', income: 0, expense: 40000, transactionCount: 9, complete: true },
      { month: '2026-09', income: 0, expense: 48000, transactionCount: 11, complete: false },
    ]);
    renderScreen();
    expect(await screen.findByText("You've spent ₹48,000 in September 2026 so far.")).toBeTruthy();
  });

  it('leaves out the comparison when the month before has nothing', async () => {
    reports.trend.mockResolvedValue([{ month: '2026-09', income: 0, expense: 48000, transactionCount: 11, complete: true }]);
    renderScreen();
    expect(await screen.findByText('You spent ₹48,000 in September 2026.')).toBeTruthy();
  });

  it('invites a person with no statements yet to upload one, instead of empty sections', async () => {
    insights.get.mockResolvedValue(insightsData({
      sentences: ['Upload or add transactions to see spending insights.'], movers: [], reportingMonth: null,
      reportingMonthIsCurrent: true, unlinkedCardBills: null,
    }));
    recurring.list.mockResolvedValue([]);
    reports.availableMonths.mockResolvedValue([]);
    renderScreen();
    expect(await screen.findByText('Upload a statement and Insights will show what needs a look, what is coming up and what you pay regularly.')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Import a statement' }));
    expect(navigate).toHaveBeenCalledWith('Import');
    expect(screen.queryByText('To look at')).toBeNull();
    expect(screen.queryByText(/Nothing new to look at for/)).toBeNull();
  });

  it('says so when insights cannot load, and still shows the repeating payments', async () => {
    insights.get.mockRejectedValue(new Error('down'));
    renderScreen();
    expect(await screen.findByText("Couldn't load what needs a look. Pull down to try again.")).toBeTruthy();
    expect(screen.getByText('Paid regularly')).toBeTruthy();
    expect(screen.queryByText(/Nothing new to look at for/)).toBeNull();
  });

  it('works against a server without "Got it" or unlinked card bills', async () => {
    insights.acknowledgements.mockRejectedValue(Object.assign(new Error('Not Found'), { response: { status: 404 } }));
    insights.get.mockResolvedValue(insightsData({ unlinkedCardBills: undefined }));
    renderScreen();
    expect(await screen.findByText('Food is ₹3,100 above your usual month')).toBeTruthy();
    expect(screen.queryByText(/no card statement uploaded/)).toBeNull();
    expect(screen.queryByText(/Couldn't/)).toBeNull();
  });

  it('hides a row marked "Got it" and remembers it on the server', async () => {
    renderScreen();
    await screen.findByText('Food is ₹3,100 above your usual month');
    resetHapticCalls();

    fireEvent.press(screen.getByRole('button', { name: 'Got it: Food' }));

    await waitFor(() => expect(screen.queryByText('Food is ₹3,100 above your usual month')).not.toBeOnTheScreen());
    expect(insights.acknowledge).toHaveBeenCalledTimes(1);
    expect(insights.acknowledge).toHaveBeenCalledWith('CATEGORY_HIGH', 'Food|2026-09');
    await waitFor(() => expectHaptics({ success: 1 }));
  });

  it('brings the row back when the server refuses "Got it"', async () => {
    insights.acknowledge.mockRejectedValueOnce(new Error('down'));
    renderScreen();
    await screen.findByText('Food is ₹3,100 above your usual month');
    resetHapticCalls();

    fireEvent.press(screen.getByRole('button', { name: 'Got it: Food' }));

    await waitFor(() => expectHaptics({ error: 1 }));
    expect(await screen.findByText('Food is ₹3,100 above your usual month')).toBeTruthy();
  });

  it('does not show rows already marked "Got it" on another device', async () => {
    insights.acknowledgements.mockResolvedValue([{ kind: 'CARD_STATEMENT_MISSING', itemKey: '2026-09' }]);
    renderScreen();
    await screen.findByText('Food is ₹3,100 above your usual month');
    expect(screen.queryByText(/no card statement uploaded/)).toBeNull();
  });

  it('opens Import from the card statement row', async () => {
    renderScreen();
    fireEvent.press(await screen.findByRole('button', { name: 'Upload statement' }));
    expect(trackNavigation).toHaveBeenCalledWith('import-statement', 'contextual');
    expect(navigate).toHaveBeenCalledWith('Import');
  });

  it('opens the month\'s payments in that category from a running-high row', async () => {
    renderScreen();
    fireEvent.press(await screen.findByRole('button', { name: /^Food is ₹3,100 above your usual month/ }));
    expect(trackNavigation).toHaveBeenCalledWith('transactions', 'contextual');
    expect(navigate).toHaveBeenCalledWith('Transactions', {
      filters: expect.objectContaining({ categoryName: 'Food', dateFrom: '2026-09-01', dateTo: '2026-09-30' }),
    });
  });

  it('opens Reports on the same month from "Where did my money go?"', async () => {
    renderScreen();
    fireEvent.press(await screen.findByRole('button', { name: 'Where did my money go? Opens Reports' }));
    expect(trackNavigation).toHaveBeenCalledWith('reports', 'contextual');
    expect(navigate).toHaveBeenCalledWith('More', { screen: 'Reports', params: { month: '2026-09' } });
  });

  it('jumps down to a section from its question', async () => {
    renderScreen();
    await screen.findByText('Coming up');
    fireEvent(screen.getByTestId('insights-section-comingUp'), 'layout', { nativeEvent: { layout: { x: 0, y: 640, width: 360, height: 200 } } });
    const calls = (useScrollToTop as jest.Mock).mock.calls;
    const list = calls[calls.length - 1][0].current;
    const scrollTo = jest.spyOn(list, 'scrollTo');

    fireEvent.press(screen.getByRole('button', { name: "What's coming up?" }));

    expect(scrollTo).toHaveBeenCalledWith(expect.objectContaining({ y: 640 }));
  });

  it('shows the coverage warning and opens Import from it', async () => {
    insights.get.mockResolvedValue(insightsData({ coverageCaveat: { month: '2026-09', gaps: [{ gapStart: '2026-09-05', gapEnd: '2026-09-19' }] } }));
    renderScreen();
    fireEvent.press(await screen.findByRole('button', { name: /^Possible gap in your September 2026 history/ }));
    expect(navigate).toHaveBeenCalledWith('Import');
  });

  it('says once that these are rules, not an AI assistant', async () => {
    renderScreen();
    await screen.findByText('To look at');
    expect(screen.getAllByText(/not an AI-generated assistant/)).toHaveLength(1);
  });

  it('dismisses a payment found by mistake, and brings it back if the server refuses', async () => {
    recurring.dismiss.mockRejectedValueOnce(new Error('down'));
    renderScreen();
    await screen.findByText('Paid regularly');
    resetHapticCalls();
    fireEvent.press(screen.getByRole('button', { name: 'Not recurring: dismiss sample tv' }));
    await waitFor(() => expectHaptics({ error: 1 }));
    expect(recurring.dismiss).toHaveBeenCalledWith('sample tv');
    expect(await screen.findByRole('button', { name: 'Not recurring: dismiss sample tv' })).toBeTruthy();
  });

  it('picking an older month asks for that month and says the repeating lists cover every statement', async () => {
    renderScreen();
    fireEvent.press(await screen.findByRole('button', { name: 'Change month, currently September 2026' }));
    fireEvent.press(await screen.findByText('August 2026'));
    await waitFor(() => expect(insights.get).toHaveBeenCalledWith('2026-08'));
    expect(await screen.findByText('Based on all your statements')).toBeTruthy();
  });

  it('opens Settings from the header gear', async () => {
    renderScreen();
    fireEvent.press(await screen.findByRole('button', { name: 'Settings' }));
    expect(navigate).toHaveBeenCalledWith('More', { screen: 'Settings' });
  });
});

describe('money with people', () => {
  const shareCheck: PeopleSuggestion = {
    kind: 'SHARE_CHECK', ackKey: 'c-1', txnId: 'c-1', relatedTxnId: null, personKey: 'vpa:flatmate.a', label: 'Flatmate A',
    amount: 9000, date: '2026-09-04', category: 'Rent', owed: null,
    bills: [{ billId: 'b-1', category: 'Rent', payeeLabel: 'Sample Owner', date: '2026-09-01', amount: 36000, remaining: 36000 }],
  };

  it('asks about a share in To look at, and "Yes, link it to a bill" opens the picker with the server\'s bills', async () => {
    people.suggestions.mockResolvedValue({ items: [shareCheck], expected: [] });
    renderScreen();
    expect(await screen.findByText('₹9,000 from Flatmate A on 4 Sep. You filed it under Rent.')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Yes, link it to a bill' }));
    expect(await screen.findByText('Share of which bill?')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Rent, 1 Sep, Sample Owner, ₹36,000' })).toBeTruthy();
  });

  it('"Leave as it is" on a share question hides it and saves it under its own kind', async () => {
    people.suggestions.mockResolvedValue({ items: [shareCheck], expected: [] });
    renderScreen();
    fireEvent.press(await screen.findByRole('button', { name: 'Leave as it is: Flatmate A' }));
    await waitFor(() => expect(screen.queryByText(/from Flatmate A on 4 Sep/)).not.toBeOnTheScreen());
    expect(insights.acknowledge).toHaveBeenCalledWith('SHARE_CHECK', 'c-1');
  });

  it('"Paid for something" opens that day\'s payments, to find this one by its date', async () => {
    people.suggestions.mockResolvedValue({
      items: [{ ...shareCheck, kind: 'LENT_CHECK', ackKey: 'd-1', txnId: 'd-1', label: 'Friend K', amount: 2000, date: '2026-08-03', category: null, bills: [] }],
      expected: [],
    });
    renderScreen();
    fireEvent.press(await screen.findByRole('button', { name: 'Paid for something' }));
    expect(navigate).toHaveBeenCalledWith('Transactions', {
      filters: expect.objectContaining({ dateFrom: '2026-08-03', dateTo: '2026-08-03', label: 'Friend K · 3 Aug' }),
    });
    // The person's name is the server's label, not words the payment's text is known to contain.
    expect(navigate.mock.calls[0][1].filters.keyword).toBeUndefined();
  });

  it('lists who owes whom under With people, reached by "Who owes me?", and opens a person', async () => {
    people.list.mockResolvedValue([
      { personKey: 'vpa:friend.k', label: 'Friend K', owedToUser: 500, since: '2026-08-03', family: false },
      { personKey: 'vpa:friend.p', label: 'Friend P', owedToUser: -800, since: '2026-08-12', family: false },
      { personKey: 'vpa:sibling.s', label: 'Sibling S', owedToUser: 3000, since: '2026-08-01', family: true },
    ]);
    renderScreen();
    expect(await screen.findByText('With people')).toBeTruthy();
    expect(screen.queryByText('Sibling S')).toBeNull();
    fireEvent(screen.getByTestId('insights-section-withPeople'), 'layout', { nativeEvent: { layout: { x: 0, y: 900, width: 360, height: 200 } } });
    const calls = (useScrollToTop as jest.Mock).mock.calls;
    const scrollTo = jest.spyOn(calls[calls.length - 1][0].current, 'scrollTo');
    fireEvent.press(screen.getByRole('button', { name: 'Who owes me? 2 people' }));
    expect(scrollTo).toHaveBeenCalledWith(expect.objectContaining({ y: 900 }));
    fireEvent.press(screen.getByRole('button', { name: 'Friend K owes you ₹500' }));
    expect(navigate).toHaveBeenCalledWith('More', { screen: 'Person', params: { personKey: 'vpa:friend.k' } });
  });

  it('shows the shares expected in under Coming up', async () => {
    people.suggestions.mockResolvedValue({
      items: [], expected: [{ personKey: 'vpa:flatmate.a', label: 'Flatmate A', usualAmount: 9000, usualDay: 4, late: true, ruleId: 'r1' }],
    });
    renderScreen();
    expect(await screen.findByText("Flatmate A's share")).toBeTruthy();
    expect(screen.getByText('late, usually around the 4th')).toBeTruthy();
  });

  it('works against a server without the people endpoints', async () => {
    people.suggestions.mockRejectedValue(new Error('404'));
    people.list.mockRejectedValue(new Error('404'));
    renderScreen();
    expect(await screen.findByText('Food is ₹3,100 above your usual month')).toBeTruthy();
    expect(screen.queryByText('With people')).toBeNull();
  });
});

describe('kept from the old screen', () => {
  it('blocks screen capture', () => {
    renderScreen();
    expect(usePreventScreenCapture).toHaveBeenCalled();
  });

  it('marks VIEW_INSIGHTS complete after a 1.5s dwell', async () => {
    jest.useFakeTimers({ doNotFake: ['queueMicrotask'] });
    (onboardingApi.getChecklist as jest.Mock).mockResolvedValue({
      items: [{ key: 'VIEW_INSIGHTS', completed: false }], completedCount: 0, totalCount: 6,
    });
    renderScreen();
    await act(async () => { await jest.advanceTimersByTimeAsync(0); });
    await act(async () => { await jest.advanceTimersByTimeAsync(1500); });
    expect(onboardingApi.completeChecklistItem).toHaveBeenCalledWith('VIEW_INSIGHTS');
    jest.useRealTimers();
  });

  it('records one insights view after a 1.5s dwell', async () => {
    (usageApi.recordView as jest.Mock).mockClear();
    jest.useFakeTimers({ doNotFake: ['queueMicrotask'] });
    renderScreen();
    await act(async () => { await jest.advanceTimersByTimeAsync(1400); });
    expect(usageApi.recordView).not.toHaveBeenCalled();
    await act(async () => { await jest.advanceTimersByTimeAsync(200); });
    expect(usageApi.recordView).toHaveBeenCalledWith('insights');
    jest.useRealTimers();
  });

  it('hands its list to useScrollToTop, so a second tab tap scrolls to the top', async () => {
    (useScrollToTop as jest.Mock).mockClear();
    renderScreen();
    await waitFor(() => {
      const calls = (useScrollToTop as jest.Mock).mock.calls;
      expect(typeof calls[calls.length - 1][0].current?.scrollTo).toBe('function');
    });
  });
});
