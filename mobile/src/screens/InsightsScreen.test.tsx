import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { ScrollView } from 'react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useNavigation, useScrollToTop } from '@react-navigation/native';
import { usePreventScreenCapture } from '../lib/screenCapture';
import { trackNavigation } from '../lib/trackNavigation';
import { InsightsScreen } from './InsightsScreen';
import { expectHaptics, resetHapticCalls } from '../test/haptics';
import {
  accountsApi, aiInsightsApi, categoriesApi, inflowApi, insightsApi, onboardingApi, peopleApi, recurringApi, reportsApi,
  usageApi, type AiStory, type InsightsData, type LookAtServerRow, type PeopleSuggestion,
} from '../api/endpoints';

// Every name and amount here is invented.

jest.mock('../api/endpoints', () => ({
  insightsApi: { get: jest.fn(), acknowledgements: jest.fn(), acknowledge: jest.fn() },
  aiInsightsApi: { story: jest.fn(), generateStory: jest.fn(), lookAt: jest.fn(), why: jest.fn(), feedback: jest.fn() },
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
const ai = aiInsightsApi as jest.Mocked<typeof aiInsightsApi>;

const NO_STORY: AiStory = { status: 'none', id: null, text: null, writtenByAi: false, basis: null, canGenerate: false };
const STORY: AiStory = {
  status: 'ready', id: 'story-1', text: 'September cost ₹48,000, ₹8,000 more than August, mostly Food.',
  writtenByAi: true, basis: 'Compared with your usual month (Jun–Aug)', canGenerate: false,
};
const MONTH_LINE = 'You spent ₹48,000 in September 2026, 20% more than August.';

/** A server "To look at" row with every field the kind does not use left null. */
function serverRow(fields: Partial<LookAtServerRow> & Pick<LookAtServerRow, 'kind' | 'key'>): LookAtServerRow {
  return {
    ackKind: null, ackItemKey: null, month: null, total: null, count: null, cardName: null, cardLast4: null,
    people: null, recurring: null, merchant: null, category: null, latestAmount: null, latestDate: null,
    current: null, priorAverage: null, whyAvailable: null, ...fields,
  };
}
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
  // By default the server has no story to show and no "To look at" of its own (an older server), so
  // the screen builds its rows on the phone as before.
  ai.story.mockReset().mockResolvedValue(NO_STORY);
  ai.generateStory.mockReset().mockResolvedValue(NO_STORY);
  ai.lookAt.mockReset().mockRejectedValue(Object.assign(new Error('Not Found'), { response: { status: 404 } }));
  ai.why.mockReset();
  ai.feedback.mockReset().mockResolvedValue(undefined);
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

// A test that has seen what it needs still has queries in flight; let them land inside act, before
// the screen is unmounted, so their updates are not reported as stray.
// React Query hands its results to React on a timer tick, so wait a few real ticks, not one.
afterEach(async () => {
  jest.useRealTimers();
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)); });
});

describe('the month story', () => {
  it('shows the month line while the story is not ready, and asks for none it may not write', async () => {
    renderScreen();
    expect(await screen.findByText(MONTH_LINE)).toBeTruthy();
    await waitFor(() => expect(ai.story).toHaveBeenCalledWith(undefined));
    expect(ai.generateStory).not.toHaveBeenCalled();
    expect(screen.queryByText('Written by AI')).toBeNull();
  });

  it('writes the story when it may, once, and shows it with its label in place of the month line', async () => {
    ai.story.mockResolvedValue({ ...NO_STORY, canGenerate: true });
    ai.generateStory.mockResolvedValue(STORY);
    renderScreen();
    expect(await screen.findByText(STORY.text as string)).toBeTruthy();
    expect(screen.getByText('Written by AI')).toBeTruthy();
    expect(screen.getByText('Compared with your usual month (Jun–Aug)')).toBeTruthy();
    expect(screen.queryByText(MONTH_LINE)).toBeNull();
    expect(ai.generateStory).toHaveBeenCalledTimes(1);
    expect(ai.generateStory).toHaveBeenCalledWith(undefined);
    // The footnote no longer says nothing here is AI.
    expect(screen.queryByText(/not an AI-generated assistant/)).toBeNull();
    expect(screen.getByText(/Text marked "Written by AI" is written by AI from your own figures/)).toBeTruthy();
  });

  it('shows a story the server already has without asking for a new one', async () => {
    ai.story.mockResolvedValue(STORY);
    renderScreen();
    expect(await screen.findByText(STORY.text as string)).toBeTruthy();
    expect(ai.generateStory).not.toHaveBeenCalled();
  });

  it('asks again twice, 2 seconds apart, while the story is being written, then stops', async () => {
    jest.useFakeTimers({ doNotFake: ['queueMicrotask'] });
    ai.story.mockResolvedValue({ ...NO_STORY, status: 'generating' });
    renderScreen();
    await act(async () => { await jest.advanceTimersByTimeAsync(0); });
    expect(ai.story).toHaveBeenCalledTimes(1);
    await act(async () => { await jest.advanceTimersByTimeAsync(1900); });
    expect(ai.story).toHaveBeenCalledTimes(1);
    // Each poll is 2 s after the answer it follows, which React's effect sees a moment after it lands.
    await act(async () => { await jest.advanceTimersByTimeAsync(200); });
    expect(ai.story).toHaveBeenCalledTimes(2);
    await act(async () => { await jest.advanceTimersByTimeAsync(2100); });
    expect(ai.story).toHaveBeenCalledTimes(3);
    await act(async () => { await jest.advanceTimersByTimeAsync(10000); });
    expect(ai.story).toHaveBeenCalledTimes(3);
    expect(screen.getByText(MONTH_LINE)).toBeTruthy();
    jest.useRealTimers();
  });

  it('shows the story once a poll finds it ready', async () => {
    jest.useFakeTimers({ doNotFake: ['queueMicrotask'] });
    ai.story.mockResolvedValueOnce({ ...NO_STORY, status: 'generating' }).mockResolvedValue(STORY);
    renderScreen();
    await act(async () => { await jest.advanceTimersByTimeAsync(0); });
    await act(async () => { await jest.advanceTimersByTimeAsync(2100); });
    expect(screen.getByText(STORY.text as string)).toBeTruthy();
    jest.useRealTimers();
  });

  it('keeps the month line, with no message, when the story cannot be written or read', async () => {
    ai.story.mockResolvedValue({ ...NO_STORY, canGenerate: true });
    ai.generateStory.mockRejectedValue(new Error('down'));
    renderScreen();
    await waitFor(() => expect(ai.generateStory).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(MONTH_LINE)).toBeTruthy();
    expect(screen.queryByText(/Couldn't/)).toBeNull();
    expect(screen.getAllByText(/not an AI-generated assistant/)).toHaveLength(1);
  });

  it('keeps the month line when the story cannot be read at all', async () => {
    ai.story.mockRejectedValue(new Error('Not Found'));
    renderScreen();
    expect(await screen.findByText(MONTH_LINE)).toBeTruthy();
    expect(screen.queryByText(/Couldn't/)).toBeNull();
  });

  it('does not carry a thumb over to a newer story', async () => {
    let finish: (s: AiStory) => void = () => {};
    ai.story.mockResolvedValue({ ...STORY, canGenerate: true });
    ai.generateStory.mockImplementation(() => new Promise<AiStory>((r) => { finish = r; }));
    renderScreen();
    await screen.findByText(STORY.text as string);
    fireEvent.press(screen.getByRole('button', { name: 'Helpful' }));
    await waitFor(() => expect(ai.feedback).toHaveBeenCalledWith('story-1', 'HELPFUL', null));
    await act(async () => { finish({ ...STORY, id: 'story-2', text: 'September cost ₹49,000, mostly Food.' }); });
    expect(await screen.findByText('September cost ₹49,000, mostly Food.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Helpful' }).props.accessibilityState).toEqual(
      expect.objectContaining({ selected: false }),
    );
  });

  it('polls again for a story written later, after an earlier one was polled out', async () => {
    jest.useFakeTimers({ doNotFake: ['queueMicrotask'] });
    const generating = { ...NO_STORY, status: 'generating' as const };
    ai.story
      .mockResolvedValueOnce(generating).mockResolvedValueOnce(generating).mockResolvedValueOnce(generating)
      .mockResolvedValueOnce(STORY)
      .mockResolvedValueOnce(generating)
      .mockResolvedValue(STORY);
    const { UNSAFE_getByType } = renderScreen();
    await act(async () => { await jest.advanceTimersByTimeAsync(0); });
    await act(async () => { await jest.advanceTimersByTimeAsync(2100); });
    await act(async () => { await jest.advanceTimersByTimeAsync(2100); });
    await act(async () => { await jest.advanceTimersByTimeAsync(10000); });
    expect(ai.story).toHaveBeenCalledTimes(3);
    const pull = () => UNSAFE_getByType(ScrollView).props.refreshControl.props.onRefresh();
    // A pull finds it ready; a later pull finds a rewrite under way, which is polled like the first.
    await act(async () => { pull(); await jest.advanceTimersByTimeAsync(0); });
    expect(ai.story).toHaveBeenCalledTimes(4);
    await act(async () => { pull(); await jest.advanceTimersByTimeAsync(0); });
    expect(ai.story).toHaveBeenCalledTimes(5);
    await act(async () => { await jest.advanceTimersByTimeAsync(2100); });
    expect(ai.story).toHaveBeenCalledTimes(6);
    expect(screen.getByText(STORY.text as string)).toBeTruthy();
    jest.useRealTimers();
  });

  it('asks for the picked month\'s story', async () => {
    renderScreen();
    fireEvent.press(await screen.findByRole('button', { name: 'Change month, currently September 2026' }));
    fireEvent.press(await screen.findByText('August 2026'));
    await waitFor(() => expect(ai.story).toHaveBeenCalledWith('2026-08'));
  });
});

describe('"To look at" from the server', () => {
  const travel = serverRow({
    kind: 'running-high', key: 'CATEGORY_HIGH|Travel|2026-09', ackKind: 'CATEGORY_HIGH', ackItemKey: 'Travel|2026-09',
    month: '2026-09', category: 'Travel', current: 12000, priorAverage: 7000, whyAvailable: true,
  });
  const cardRow = serverRow({
    kind: 'card-statement', key: 'CARD_STATEMENT_MISSING|2026-09', ackKind: 'CARD_STATEMENT_MISSING',
    ackItemKey: '2026-09', month: '2026-09', total: 21400, count: 2, cardName: 'Sample Card', cardLast4: '0001',
  });

  it('shows the server\'s rows in the server\'s order', async () => {
    ai.lookAt.mockResolvedValue({ rows: [travel, cardRow], orderedByAi: true });
    renderScreen();
    expect(await screen.findByText('Travel is ₹5,000 above your usual month')).toBeTruthy();
    // The phone's own rules would have put the card statement first and found Food, not Travel.
    expect(screen.queryByText('Food is ₹3,100 above your usual month')).toBeNull();
    const shown = screen.getAllByText(/above your usual month$|no card statement uploaded/).map((n) => n.props.children);
    expect(shown[0]).toBe('Travel is ₹5,000 above your usual month');
    expect(shown[1]).toMatch(/no card statement uploaded/);
    expect(ai.lookAt).toHaveBeenCalledWith(undefined);
  });

  it('builds the rows on the phone when the server cannot give them', async () => {
    renderScreen();
    expect(await screen.findByText('Food is ₹3,100 above your usual month')).toBeTruthy();
    await waitFor(() => expect(ai.lookAt).toHaveBeenCalled());
    expect(screen.getByText(/toward your Sample Card ending 0001/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /^Why is / })).toBeNull();
  });

  it('hides a server row marked "Got it" at once', async () => {
    ai.lookAt.mockResolvedValue({ rows: [travel, cardRow], orderedByAi: false });
    renderScreen();
    await screen.findByText('Travel is ₹5,000 above your usual month');
    fireEvent.press(screen.getByRole('button', { name: 'Got it: Travel' }));
    await waitFor(() => expect(screen.queryByText('Travel is ₹5,000 above your usual month')).not.toBeOnTheScreen());
    expect(insights.acknowledge).toHaveBeenCalledWith('CATEGORY_HIGH', 'Travel|2026-09');
  });

  it('answers "Why?" under the row and changes the footnote for an AI answer', async () => {
    ai.lookAt.mockResolvedValue({ rows: [travel], orderedByAi: false });
    ai.why.mockResolvedValue({
      status: 'ready', id: 'why-1', text: 'Travel was ₹5,000 above your usual month, mostly one fare.',
      writtenByAi: true, basis: 'Compared with your usual month (Jun–Aug)', freeLeftThisMonth: 4,
    });
    renderScreen();
    fireEvent.press(await screen.findByRole('button', { name: 'Why is Travel above your usual month?' }));
    expect(await screen.findByText('Travel was ₹5,000 above your usual month, mostly one fare.')).toBeTruthy();
    expect(ai.why).toHaveBeenCalledWith('CATEGORY_HIGH', 'Travel|2026-09', undefined);
    expect(screen.getByText('4 of 5 left this month')).toBeTruthy();
    expect(screen.queryByText(/not an AI-generated assistant/)).toBeNull();
  });

  it('opens the plans from "See Plus" once the free explanations are used', async () => {
    ai.lookAt.mockResolvedValue({ rows: [travel], orderedByAi: false });
    ai.why.mockResolvedValue({
      status: 'ready', id: null, text: 'Travel was ₹12,000 this month against ₹7,000 in a usual month.',
      writtenByAi: false, basis: null, freeLeftThisMonth: 0,
    });
    renderScreen();
    fireEvent.press(await screen.findByRole('button', { name: 'Why is Travel above your usual month?' }));
    fireEvent.press(await screen.findByRole('button', { name: 'See Plus' }));
    expect(trackNavigation).toHaveBeenCalledWith('subscription', 'contextual');
    expect(navigate).toHaveBeenCalledWith('More', { screen: 'Subscription' });
    expect(screen.getAllByText(/not an AI-generated assistant/)).toHaveLength(1);
  });

  it('shows the server\'s rows when the older insights call fails', async () => {
    insights.get.mockRejectedValue(new Error('down'));
    ai.lookAt.mockResolvedValue({ rows: [travel], orderedByAi: false });
    renderScreen();
    expect(await screen.findByText('Travel is ₹5,000 above your usual month')).toBeTruthy();
    expect(screen.queryByText("Couldn't load what needs a look. Pull down to try again.")).toBeNull();
  });

  it('goes back to the plain footnote for another month', async () => {
    ai.lookAt.mockResolvedValue({ rows: [travel], orderedByAi: false });
    ai.why.mockResolvedValue({
      status: 'ready', id: 'why-1', text: 'Travel was ₹5,000 above your usual month, mostly one fare.',
      writtenByAi: true, basis: null, freeLeftThisMonth: null,
    });
    renderScreen();
    fireEvent.press(await screen.findByRole('button', { name: 'Why is Travel above your usual month?' }));
    await screen.findByText('Travel was ₹5,000 above your usual month, mostly one fare.');
    expect(screen.queryByText(/not an AI-generated assistant/)).toBeNull();
    fireEvent.press(screen.getByRole('button', { name: 'Change month, currently September 2026' }));
    fireEvent.press(await screen.findByText('August 2026'));
    expect(await screen.findByText(/not an AI-generated assistant/)).toBeTruthy();
  });

  it('asks for the picked month\'s rows', async () => {
    renderScreen();
    fireEvent.press(await screen.findByRole('button', { name: 'Change month, currently September 2026' }));
    fireEvent.press(await screen.findByText('August 2026'));
    await waitFor(() => expect(ai.lookAt).toHaveBeenCalledWith('2026-08'));
  });

  it('asks for "To look at" again once a new story arrives, for its order', async () => {
    ai.lookAt.mockResolvedValue({ rows: [], orderedByAi: false });
    ai.story.mockResolvedValue({ ...NO_STORY, canGenerate: true });
    ai.generateStory.mockResolvedValue(STORY);
    renderScreen();
    await screen.findByText(STORY.text as string);
    await waitFor(() => expect(ai.lookAt).toHaveBeenCalledTimes(2));
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
