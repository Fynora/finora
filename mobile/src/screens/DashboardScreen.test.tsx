import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { Dimensions, RefreshControl } from 'react-native';
import { QueryClient, QueryClientProvider, onlineManager } from '@tanstack/react-query';
import { useNavigation } from '@react-navigation/native';
import { DashboardScreen } from './DashboardScreen';
import { ToastProvider } from '../context/ToastContext';
import {
  accountsApi, budgetsApi, dashboardApi, goalsApi, insightsApi, recurringApi, reportsApi,
  transactionsApi, userApi,
} from '../api/endpoints';
import { light } from '../theme/palette';
import type { DashboardSummary } from '../types';

// useWindowDimensions (used for chart width and, per the "large Dynamic Type" describe block
// below, font scale) reads its value from Dimensions.get('window') on mount -- spying there,
// rather than re-mocking the whole 'react-native' module, avoids re-running the module's own
// native TurboModule getters (which blow up under jest-expo when the module object is spread
// rather than used as-is).
const dimensionsGetSpy = jest.spyOn(Dimensions, 'get');

/**
 * The Expenses KPI renders through AnimatedNumber now -- a non-editable TextInput, so its
 * settled value lives in `defaultValue` (see AnimatedNumber's own doc comment) rather than in
 * text content getByText can see. These cross-checks against DonutChart's plain-Text centre
 * label predate that change; kept accurate by reading each source the way it actually renders.
 */
function expensesKpiValue(): string {
  return screen.getByTestId('kpi-Expenses').props.defaultValue as string;
}

/**
 * Unlike expensesKpiValue above, the Financial Health Score's initial render is never a static
 * snapshot of AnimatedHealthScoreNumber's real props tree the way AnimatedNumber's genuinely is:
 * this component intentionally starts at 0 and its mount-effect immediately schedules a
 * withTiming count-up to the real score, so even the "first" settled value is, mechanically, a
 * post-mount native-thread-only prop update -- the same class of update AnimatedNumber.test.tsx's
 * own "settles on the new formatted value when the prop changes" test documents in detail.
 * `.props.defaultValue` can't see it; only Reanimated's toHaveAnimatedProps matcher (registered by
 * setUpTests()) can, and only once fake timers have actually advanced past the animation.
 */
function expectHealthScoreValue(value: string) {
  expect(screen.getByTestId('health-score-value')).toHaveAnimatedProps({ text: value, defaultValue: value });
}

/**
 * The distinction this file exists to protect: a dashboard that FAILED TO LOAD must never be
 * indistinguishable from a dashboard that legitimately has no money in it.
 *
 * Both render mostly-zero data, so the difference lives entirely in one guard --
 * `if (!summary)` in DashboardScreen. Delete that guard and every test here still compiles, the
 * screen still renders, and a user whose request 500s is told their balance is Rs 0. In a finance
 * app that is not a cosmetic bug: it is the app asserting something false about someone's money.
 *
 * The behaviour was correct before these tests were written (the guard has been there since the
 * screen was built). What was missing was anything stopping a refactor from removing it, which is
 * what these tests supply -- they pin the DIFFERENCE, not merely the failure path.
 */

jest.mock('../api/endpoints', () => ({
  dashboardApi: { summary: jest.fn() },
  accountsApi: { list: jest.fn() },
  transactionsApi: {
    search: jest.fn(), needsReview: jest.fn(), needsReviewGroups: jest.fn(), confirmNotDuplicate: jest.fn(),
  },
  goalsApi: { list: jest.fn() },
  insightsApi: { get: jest.fn() },
  userApi: { get: jest.fn() },
  reportsApi: { availableMonths: jest.fn(), forMonth: jest.fn() },
  budgetsApi: { list: jest.fn() },
  recurringApi: { list: jest.fn(), dismiss: jest.fn() },
  // ChecklistWidget (mounted on DashboardScreen, D-onboarding) fetches this on every render --
  // default to "already 6/6" so it renders nothing and every existing test below, none of which
  // cares about onboarding, keeps seeing exactly the Dashboard content it did before this widget
  // existed.
  onboardingApi: { getChecklist: jest.fn().mockResolvedValue({ items: [], completedCount: 6, totalCount: 6 }) },
}));

jest.mock('../context/AuthContext', () => ({
  useAuth: () => ({ fullName: 'Test User' }),
}));

const dashboard = dashboardApi as jest.Mocked<typeof dashboardApi>;
const accounts = accountsApi as jest.Mocked<typeof accountsApi>;
const transactions = transactionsApi as jest.Mocked<typeof transactionsApi>;
const goals = goalsApi as jest.Mocked<typeof goalsApi>;
const insights = insightsApi as jest.Mocked<typeof insightsApi>;
const user = userApi as jest.Mocked<typeof userApi>;
const reports = reportsApi as jest.Mocked<typeof reportsApi>;
const budgets = budgetsApi as jest.Mocked<typeof budgetsApi>;
const recurring = recurringApi as jest.Mocked<typeof recurringApi>;

/**
 * A real summary for an account that has been imported but holds nothing -- every figure zero,
 * every delta null, no categories. This is the LEGITIMATE empty case, and it is deliberately the
 * closest possible neighbour to the failure case: if the screen ever conflates the two, this is
 * the fixture that catches it.
 */
function emptySummary(over: Partial<DashboardSummary> = {}): DashboardSummary {
  return {
    currentBalance: 0,
    totalAssets: 0,
    totalLiabilities: 0,
    netWorth: 0,
    monthlyIncome: 0,
    monthlyExpense: 0,
    netCashFlow: 0,
    savingsRatePct: 0,
    incomeDeltaPct: null,
    expenseDeltaPct: null,
    netDeltaPct: null,
    healthScore: 0,
    healthLabel: 'No data',
    healthBreakdown: {},
    healthBreakdownDetail: {},
    healthScoreAvailable: false,
    healthScoreTransactionCount: 0,
    healthScoreMinTransactions: 10,
    healthScoreDeltaVsLastMonth: null,
    healthSparkline: [],
    healthTopOpportunityFactor: null,
    healthTopOpportunityPotentialGain: null,
    spendByCategory: {},
    notifications: [],
    reportingMonth: null,
    reportingMonthIsCurrent: true,
    // Phase 4 (Medium-Tier Parity): real defaults, not just whatever `as DashboardSummary` would
    // paper over -- same reasoning as the Track C/C1 fields' own comment below.
    limitedHistory: false,
    historyMonthCount: 0,
    limitedHistoryMonthFloor: 3,
    statementCount: 0,
    accountCount: 0,
    // Track C/C1 fields: real defaults, not just whatever `as DashboardSummary` would paper over --
    // a genuinely undefined categorizationConfidenceScore (as opposed to backend's real `null`)
    // would otherwise slip past this cast and render as a broken "undefined out of 100" card in
    // any test whose recentTxnsQ fixture happens to make isEmpty false.
    categorizationConfidenceScore: null,
    categorizationConfidenceTransactionCount: 0,
    categorizationConfidenceMinTransactions: 5,
    duplicateTransactionCount: 0,
    detectedDuplicates: [],
    ...over,
  } as DashboardSummary;
}

function renderScreen() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <DashboardScreen />
      </ToastProvider>
    </QueryClientProvider>
  );
  return { ...utils, queryClient };
}

beforeEach(() => {
  jest.clearAllMocks();
  // Reset to the default (non-scaled) window on every test -- a leftover large fontScale from one
  // test must never leak into the next.
  dimensionsGetSpy.mockReturnValue({ width: 390, height: 844, scale: 2, fontScale: 1 });
  // Everything except the summary succeeds, so each test isolates one variable: the summary call.
  accounts.list.mockResolvedValue([]);
  transactions.search.mockResolvedValue({
    content: [], page: 0, size: 5, totalElements: 0, totalPages: 0,
  } as never);
  goals.list.mockResolvedValue([]);
  insights.get.mockResolvedValue({ sentences: [], movers: [], coverageCaveat: null } as never);
  user.get.mockResolvedValue({ timezone: 'Asia/Kolkata' } as never);
  reports.availableMonths.mockResolvedValue([]);
  reports.forMonth.mockResolvedValue({} as never);
  // usePrefetchAdjacentScreens prefetches budgets unconditionally on every mount (see that hook's
  // own file) -- without a default here, every test below it triggers React Query's own "Query
  // data cannot be undefined" console.error for the ['budgets'] key, since the un-mocked jest.fn()
  // resolves to undefined.
  budgets.list.mockResolvedValue([]);
  // Same reasoning as budgets.list above -- this screen's own recurringQ fires unconditionally.
  recurring.list.mockResolvedValue([]);
  // Default: an empty review backlog, so the nudge stays absent unless a test asks for it.
  transactions.needsReview.mockResolvedValue([]);
  transactions.needsReviewGroups.mockResolvedValue([]);
});

describe('when /dashboard/summary fails', () => {
  it('says the dashboard could not be loaded, instead of rendering it', async () => {
    dashboard.summary.mockRejectedValue(new Error('Network Error'));

    renderScreen();

    expect(await screen.findByText(/Couldn't load your dashboard/i)).toBeTruthy();
    // The load failed, so the screen must not also claim anything about the user's money.
    expect(screen.queryByText('Total Balance')).toBeNull();
    expect(screen.queryByText('Net Savings')).toBeNull();
  });

  it('offers a retry that refetches rather than leaving the user stuck', async () => {
    dashboard.summary.mockRejectedValueOnce(new Error('Network Error'));
    await screen.findByText; // no-op guard for lint symmetry

    renderScreen();
    await screen.findByText(/Couldn't load your dashboard/i);
    expect(dashboard.summary).toHaveBeenCalledTimes(1);

    dashboard.summary.mockResolvedValue(emptySummary({ currentBalance: 4200 }));
    fireEvent.press(screen.getByText(/Try again/i));

    // The retry must actually re-request; a button that only re-renders the error is worse than none.
    await waitFor(() => expect(dashboard.summary).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('Total Balance')).toBeTruthy();
  });

  it('does not render a zero balance while the request is failing', async () => {
    // The specific misreading this guards: Rs 0 shown as fact when the number is simply unknown.
    dashboard.summary.mockRejectedValue(new Error('500'));

    renderScreen();
    await screen.findByText(/Couldn't load your dashboard/i);

    expect(screen.queryByText('₹0')).toBeNull();
    expect(screen.queryByText('₹0.00')).toBeNull();
  });
});

describe('M0-A: the spending donut must not understate the period total', () => {
  /**
   * A known corpus, not a plausible-looking one. Eight categories, because the donut has six
   * colours and the interesting case is the seventh:
   *
   *   Rent 20,000 + Food 5,000 + Transport 3,000 + Bills 2,500 + Shopping 2,000
   *     + Health 1,500 + Education 800 + Misc 700  =  35,500
   *   top six only                                 =  34,000
   *
   * NOTE -- this suite's original premise no longer holds, and the fixture below is what keeps it
   * true here. It used to read: "the backend builds spendByCategory and monthlyExpense from the
   * same filtered transaction list, so their totals agree by construction". PR #596 (2026-08-30)
   * ended that: DashboardService now feeds monthlyExpense from
   * `RefundNetting.excludingInvestmentTransfers(active)` while spendByCategory still streams the
   * unfiltered list, so in any real month containing a SIP or a broker debit the category sum is
   * LARGER than monthlyExpense. This fixture sets the two equal by hand, so these tests still pass
   * -- they simply no longer describe production.
   *
   * What they DO still protect is the bug they were written for: the centre must show the whole
   * period total, not just the six slices that fit. That is unaffected. The open question they no
   * longer answer is which figure the centre should claim when the two genuinely disagree -- the
   * screen currently shows "TOTAL ₹35,500" in the donut next to "Expenses ₹32,500" in the KPI, with
   * nothing distinguishing the two definitions. That is a product call, deliberately not made here.
   */
  const CATEGORIES = {
    Rent: 20000, Food: 5000, Transport: 3000, Bills: 2500,
    Shopping: 2000, Health: 1500, Education: 800, Misc: 700,
  };
  const TRUE_TOTAL = 35500;

  it('shows the whole period total in the centre, not just the slices that fit', async () => {
    dashboard.summary.mockResolvedValue(
      emptySummary({ spendByCategory: CATEGORIES, monthlyExpense: TRUE_TOTAL })
    );

    renderScreen();
    await screen.findByText('Total Balance');

    // ₹34,000 is the sum of the six largest categories. Rendering it as the centre of a chart
    // titled "Spending by Category" tells the user they spent 1,500 less than they did.
    expect(screen.queryByText('₹34,000')).toBeNull();
    // The centre label is still a plain Text; the Expenses KPI now renders through AnimatedNumber
    // (see expensesKpiValue's own comment) -- both must agree on the true total.
    expect(screen.getByText('₹35,500')).toBeTruthy();
    expect(expensesKpiValue()).toBe('₹35,500');
  });

  it('agrees with the Expenses KPI, which reads the same backend field', async () => {
    // Two figures for one quantity on one screen is the failure mode worth pinning: whichever is
    // wrong, a user cannot tell which to believe.
    dashboard.summary.mockResolvedValue(
      emptySummary({ spendByCategory: CATEGORIES, monthlyExpense: TRUE_TOTAL })
    );

    renderScreen();
    await screen.findByText('Total Balance');

    expect(screen.getByText('₹35,500')).toBeTruthy();
    expect(expensesKpiValue()).toBe('₹35,500');
  });

  it('does not show two rows both labelled Other', async () => {
    /**
     * Found on a real Android device, in the state an actual import produces: a CSV whose merchants
     * match no rule lands in a REAL backend category called "Other", and the remainder bucket is
     * called "Other" too. The legend rendered "Other 3,000" and "Other 5,500" as separate rows.
     * The total was right; two identically labelled rows with different amounts still is not
     * something a reader can resolve.
     *
     * Nine categories, with a real "Other" large enough to survive into the named five:
     *   Rent 20,000 · Food 5,000 · Transport 3,000 · Other 3,000 · Bills 2,500
     *   + Health 2,000 · Shopping 2,000 · Education 800 · Misc 700  =  39,000
     */
    dashboard.summary.mockResolvedValue(
      emptySummary({
        spendByCategory: {
          Rent: 20000, Food: 5000, Transport: 3000, Other: 3000, Bills: 2500,
          Health: 2000, Shopping: 2000, Education: 800, Misc: 700,
        },
        monthlyExpense: 39000,
      })
    );

    renderScreen();
    await screen.findByText('Total Balance');

    expect(screen.getAllByText('Other')).toHaveLength(1);
    // 3,000 real + 5,500 remainder, in one row rather than two.
    expect(screen.getByText('₹8,500')).toBeTruthy();
    expect(screen.queryByText('₹5,500')).toBeNull();
    // And the invariant that started all of this still holds.
    expect(screen.getByText('₹39,000')).toBeTruthy();
    expect(expensesKpiValue()).toBe('₹39,000');
  });

  it('is unaffected when every category already fits', async () => {
    // Guards the fix from over-reaching: with six or fewer categories nothing was ever wrong, and
    // the displayed total must stay exactly what it was.
    dashboard.summary.mockResolvedValue(
      emptySummary({ spendByCategory: { Rent: 20000, Food: 5000 }, monthlyExpense: 25000 })
    );

    renderScreen();
    await screen.findByText('Total Balance');

    expect(screen.getByText('₹25,000')).toBeTruthy();
    expect(expensesKpiValue()).toBe('₹25,000');
  });
});

describe('when the dashboard is legitimately empty', () => {
  it('renders the real dashboard, not the failure state', async () => {
    // A brand-new account with nothing imported: the request SUCCEEDED and the answer is zero.
    dashboard.summary.mockResolvedValue(emptySummary());

    renderScreen();

    expect(await screen.findByText('Total Balance')).toBeTruthy();
    expect(screen.getByText('Income')).toBeTruthy();
    expect(screen.getByText('Expenses')).toBeTruthy();
    expect(screen.getByText('Net Savings')).toBeTruthy();
    // The whole point: zero data is not an error, and must never be reported as one.
    expect(screen.queryByText(/Couldn't load your dashboard/i)).toBeNull();
    expect(screen.queryByText(/Try again/i)).toBeNull();
  });

  it('is reached through a different branch than the failure state', async () => {
    // Renders both cases in one test so the assertion is the DIFFERENCE itself. A refactor that
    // collapses "no summary" and "empty summary" into one path fails here even if each case looks
    // individually reasonable.
    dashboard.summary.mockResolvedValue(emptySummary());
    const ok = renderScreen();
    await screen.findByText('Total Balance');
    const emptyShowsError = screen.queryByText(/Couldn't load your dashboard/i) !== null;
    ok.unmount();

    dashboard.summary.mockRejectedValue(new Error('Network Error'));
    renderScreen();
    await screen.findByText(/Couldn't load your dashboard/i);
    const failureShowsKpis = screen.queryByText('Total Balance') !== null;

    expect(emptyShowsError).toBe(false);
    expect(failureShowsKpis).toBe(false);
  });
});

describe('large Dynamic Type support (mobile design review, iOS VoiceOver/Dynamic Type pass)', () => {
  // A financial description long enough to actually truncate at either line count -- short enough
  // fixtures would pass numberOfLines={1} by accident and prove nothing.
  const LONG_DESCRIPTION = 'Payment to Greenfield Grocers and Home Essentials Superstore Ltd';
  const LONG_GOAL_NAME = 'Emergency Fund for Home Repairs and Unexpected Medical Expenses';

  beforeEach(() => {
    transactions.search.mockResolvedValue({
      content: [{
        id: 't1', accountId: 'a1', categoryId: 'c1', categoryName: 'Shopping', date: '2026-08-01',
        description: LONG_DESCRIPTION, merchant: 'Greenfield Grocers', paymentMethod: 'CARD',
        amount: 1200, type: 'EXPENSE', tags: [], notes: null, reconciliationStatus: 'OK',
        recurring: false, needsCategoryReview: false, categoryManuallySet: false,
      }],
      page: 0, size: 5, totalElements: 1, totalPages: 1,
    } as never);
    goals.list.mockResolvedValue([
      { id: 'g1', name: LONG_GOAL_NAME, targetAmount: 100000, currentAmount: 25000 },
    ] as never);
  });

  it('truncates the transaction description and goal name to one line at the default text size', async () => {
    dashboard.summary.mockResolvedValue(emptySummary());
    renderScreen();

    const desc = await screen.findByText(LONG_DESCRIPTION);
    expect(desc.props.numberOfLines).toBe(1);

    const goalName = await screen.findByText(LONG_GOAL_NAME);
    expect(goalName.props.numberOfLines).toBe(1);
  });

  it('allows two lines instead of truncating once Dynamic Type is scaled up', async () => {
    dimensionsGetSpy.mockReturnValue({ width: 390, height: 844, scale: 2, fontScale: 1.3 });
    dashboard.summary.mockResolvedValue(emptySummary());
    renderScreen();

    const desc = await screen.findByText(LONG_DESCRIPTION);
    expect(desc.props.numberOfLines).toBe(2);

    const goalName = await screen.findByText(LONG_GOAL_NAME);
    expect(goalName.props.numberOfLines).toBe(2);
  });

  it('still allows two lines at full accessibility text sizes, not just the first large step', async () => {
    dimensionsGetSpy.mockReturnValue({ width: 390, height: 844, scale: 2, fontScale: 2.0 });
    dashboard.summary.mockResolvedValue(emptySummary());
    renderScreen();

    expect((await screen.findByText(LONG_DESCRIPTION)).props.numberOfLines).toBe(2);
    expect((await screen.findByText(LONG_GOAL_NAME)).props.numberOfLines).toBe(2);
  });
});

describe('pull-to-refresh indicator', () => {
  it('does not wait on accounts, since accounts data is never rendered on this screen', async () => {
    dashboard.summary.mockResolvedValue(emptySummary());
    const { queryClient } = renderScreen();
    await screen.findByText('Total Balance');

    // Summary resolves fast; accounts is held open on purpose -- the indicator must NOT wait on
    // it, since nothing on screen reads accountsQ.data. (This used to be inverted: the indicator
    // tracked accountsQ.isFetching, which both kept the spinner up after every visible section had
    // settled, AND could flip the spinner on with no user gesture at all if accounts merely
    // happened to resolve slower than summary/recent-transactions on first mount.)
    let resolveAccounts: (value: unknown) => void = () => {};
    accounts.list.mockReturnValue(new Promise((resolve) => { resolveAccounts = resolve as typeof resolveAccounts; }));

    await act(async () => {
      void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
      void queryClient.invalidateQueries({ queryKey: ['accounts'] });
    });

    // The QueryClient's own state flips to fetching synchronously inside invalidateQueries, but
    // the component's re-render (via the query observer's subscriber) can land a tick later than
    // act()'s own flush -- waitFor absorbs that gap instead of asserting on a stale render.
    await waitFor(() => {
      expect(screen.UNSAFE_getByType(RefreshControl).props.refreshing).toBe(false);
    });

    await act(async () => resolveAccounts([]));
  });

  it('stays visible until Goals, Insights, and the Cash Flow report queries have finished too', async () => {
    dashboard.summary.mockResolvedValue(emptySummary());
    const { queryClient } = renderScreen();
    await screen.findByText('Total Balance');

    // Summary/accounts/recent-transactions all resolve fast; insights is held open on purpose --
    // refresh() invalidates it and its section is genuinely rendered, so the spinner must track it.
    let resolveInsights: (value: unknown) => void = () => {};
    insights.get.mockReturnValue(new Promise((resolve) => { resolveInsights = resolve as typeof resolveInsights; }));

    await act(async () => {
      void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
      void queryClient.invalidateQueries({ queryKey: ['insights'] });
    });

    await waitFor(() => {
      expect(screen.UNSAFE_getByType(RefreshControl).props.refreshing).toBe(true);
    });

    await act(async () => resolveInsights({ sentences: [], movers: [] }));

    await waitFor(() => {
      expect(screen.UNSAFE_getByType(RefreshControl).props.refreshing).toBe(false);
    });
  });
});

describe('the shell mounts before the network settles (dashboard shell capstone)', () => {
  it('shows the greeting and section skeletons immediately, then swaps in real content once summary and recent transactions arrive', async () => {
    let resolveSummary: (value: unknown) => void = () => {};
    dashboard.summary.mockReturnValue(new Promise((resolve) => { resolveSummary = resolve as typeof resolveSummary; }));
    let resolveTxns: (value: unknown) => void = () => {};
    transactions.search.mockReturnValue(new Promise((resolve) => { resolveTxns = resolve as typeof resolveTxns; }));

    renderScreen();

    // The shell -- greeting and section headings -- is already on screen, not hidden behind a
    // full-screen spinner.
    expect(screen.getByText(/Good (morning|afternoon|evening|night)/)).toBeTruthy();
    expect(screen.getByText('Cash Flow')).toBeTruthy();
    expect(screen.getByText('Recent Transactions')).toBeTruthy();
    expect(screen.getAllByTestId('shimmer-block', { hidden: true }).length).toBeGreaterThan(0);
    expect(screen.queryByText('Total Balance')).toBeNull();

    await act(async () => {
      resolveSummary(emptySummary({ currentBalance: 4200 }));
      resolveTxns({ content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 });
    });

    expect(await screen.findByText('Total Balance')).toBeTruthy();
    expect(screen.queryByTestId('shimmer-block', { hidden: true })).toBeNull();
  });

  it('skeletons Recent Transactions independently of the summary section', async () => {
    dashboard.summary.mockResolvedValue(emptySummary());
    let resolveTxns: (value: unknown) => void = () => {};
    transactions.search.mockReturnValue(new Promise((resolve) => { resolveTxns = resolve as typeof resolveTxns; }));

    renderScreen();

    expect(await screen.findByText('Total Balance')).toBeTruthy();
    // Summary has already settled, but the transactions section is still on its own skeleton.
    expect(screen.getAllByTestId('skeleton-transaction-row', { hidden: true }).length).toBeGreaterThan(0);

    await act(async () => resolveTxns({ content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 }));

    expect(await screen.findByText(/No transactions yet/i)).toBeTruthy();
  });
});

// Phase 5 (Low-Priority Polish). "No transactions yet. Import a statement to get started." used
// to say that with nothing to tap -- the reader had to find the Import tab on their own.
describe('empty-transactions CTA (Phase 5)', () => {
  it('opens Import from the empty state\'s own action', async () => {
    dashboard.summary.mockResolvedValue(emptySummary());
    transactions.search.mockResolvedValue({ content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 } as never);
    const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
    navigate.mockClear();

    renderScreen();
    fireEvent.press(await screen.findByText('Import a statement'));

    expect(navigate).toHaveBeenCalledWith('Import');
  });
});

// Phase 5 (Low-Priority Polish). Ledger's own search box already works and was already reachable
// via the Transactions tab -- this pins the shorter path FROM Dashboard, not a second search.
describe('search entry point (Phase 5)', () => {
  it('opens Transactions from the header search button', async () => {
    dashboard.summary.mockResolvedValue(emptySummary());
    const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
    navigate.mockClear();

    renderScreen();
    fireEvent.press(await screen.findByLabelText('Search transactions'));

    expect(navigate).toHaveBeenCalledWith('Transactions');
  });
});

describe('Recent Transactions error state', () => {
  it('says the transactions could not be loaded, instead of claiming there are none', async () => {
    dashboard.summary.mockResolvedValue(emptySummary());
    transactions.search.mockRejectedValue(new Error('boom'));
    renderScreen();

    expect(await screen.findByText(/Couldn't load your transactions/)).toBeTruthy();
    expect(screen.queryByText(/No transactions yet/i)).toBeNull();
  });
});

/**
 * Cash Flow is fed by its own two-step chain -- report-months, then one report query per month --
 * which the card used to render nothing about: its only gate was `summary`, an unrelated query.
 * That made every failure and every intermediate state indistinguishable from "you have no data".
 */
describe('Cash Flow loading and failure states', () => {
  afterEach(() => onlineManager.setOnline(true));

  const summaryOnly = () => {
    dashboard.summary.mockResolvedValue(emptySummary());
  };

  it('does not claim there is no monthly data while the months are still loading', async () => {
    // `summary` resolves; the months list never does. This is the ordinary cold-start ordering,
    // not an error case -- the two requests are sequential, so this window happens on every launch.
    dashboard.summary.mockResolvedValue(emptySummary({ currentBalance: 4200 }));
    reports.availableMonths.mockReturnValue(new Promise(() => {}) as never);

    renderScreen();

    // Anchored on the KPI section, which only renders once summary has landed -- not on the static
    // "Cash Flow" heading, which is present during the skeleton state too and would let this
    // assertion run before summary arrived, passing without ever entering the window it tests.
    // (The balance itself goes through AnimatedNumber, so it is not a queryable Text node.)
    await screen.findByText('Total Balance');

    expect(screen.queryByText(/No monthly data yet/i)).toBeNull();
  });

  it('says it could not load the cash flow rather than showing an empty chart', async () => {
    summaryOnly();
    reports.availableMonths.mockResolvedValue(['2026-07', '2026-08']);
    reports.forMonth.mockRejectedValue(new Error('boom'));

    renderScreen();

    expect(await screen.findByText(/Couldn’t load your cash flow/)).toBeTruthy();
    expect(screen.queryByText(/No monthly data yet/i)).toBeNull();
  });

  it('admits when only some months are missing instead of drawing the gap as continuous', async () => {
    summaryOnly();
    reports.availableMonths.mockResolvedValue(['2026-06', '2026-07', '2026-08']);
    reports.forMonth.mockImplementation((month: string) =>
      month === '2026-07'
        ? Promise.reject(new Error('boom'))
        : Promise.resolve({ month, income: 100, expense: 50, categories: [] })
    );

    renderScreen();

    // The chart still renders what it has -- but says what it doesn't have, because the x-axis is
    // index-based and would otherwise join June straight to August as one even segment.
    expect(await screen.findByText(/One month couldn’t be loaded/)).toBeTruthy();
  });

  it('does not spin a skeleton forever when offline with no cached months', async () => {
    // The realistic offline shape, not a blanket one: 'dashboard-summary' IS in the persistence
    // allowlist, so it warm-starts from disk, while a device that has never loaded this month's
    // report list has nothing for 'report-months'. That query then PAUSES rather than failing --
    // pending, and staying pending until the network returns. Gating the skeleton on isPending
    // alone would trade the old false empty state for a spinner that implies data is coming.
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0 } },
    });
    queryClient.setQueryData(['dashboard-summary'], emptySummary({ currentBalance: 4200 }));
    onlineManager.setOnline(false);

    render(
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <DashboardScreen />
        </ToastProvider>
      </QueryClientProvider>
    );

    expect(await screen.findByText(/Couldn’t load your cash flow/)).toBeTruthy();
    expect(screen.queryByText(/No monthly data yet/i)).toBeNull();
  });

  it('still shows the genuine empty state when there really are no months', async () => {
    // The other side of the guard: a user with no statements at all must keep getting the real
    // answer rather than an error.
    summaryOnly();
    reports.availableMonths.mockResolvedValue([]);

    renderScreen();

    expect(await screen.findByText(/No monthly data yet/i)).toBeTruthy();
    expect(screen.queryByText(/Couldn’t load your cash flow/)).toBeNull();
  });
});

describe('adjacent-screen prefetching', () => {
  it('prefetches the Ledger, Budgets and latest Reports caches once summary loads', async () => {
    dashboard.summary.mockResolvedValue(emptySummary());
    reports.availableMonths.mockResolvedValue(['2026-08']);
    reports.forMonth.mockResolvedValue({ month: '2026-08', income: 0, expense: 0, categories: [] });

    renderScreen();

    // Asserted via the mock call, not queryClient.getQueryData(['budgets']): this screen's test
    // QueryClient uses gcTime: 0 (see renderScreen's own comment), and a prefetched query has no
    // mounted useQuery observer, so it goes "inactive" -- and eligible for garbage collection --
    // the instant it resolves. The prefetch demonstrably still ran and populated the cache
    // correctly (confirmed manually during development), but the cache entry doesn't survive long
    // enough for a read-back assertion here to reliably observe it.
    await waitFor(() => expect(budgets.list).toHaveBeenCalled());
    expect(transactions.search).toHaveBeenCalledWith({ page: 0, size: 20, sortField: 'date', sortDir: 'desc' });
    await waitFor(() => expect(reports.forMonth).toHaveBeenCalledWith('2026-08'));
  });
});

/**
 * The review nudge.
 *
 * The categorization design spec (§3) is explicit that "needs review" is a queue state and never a
 * chart slice -- a wedge of unclassified spend sitting in the donut alongside Food and Travel
 * reads as information about someone's money when it is actually an admission of not knowing. So
 * the backlog surfaces here as a count of work with somewhere to go, above the figures it would
 * otherwise quietly distort.
 */
describe('categorization review nudge', () => {
  beforeEach(() => {
    dashboard.summary.mockResolvedValue(emptySummary());
  });

  it('stays absent when there is nothing to review', async () => {
    renderScreen();
    await screen.findByTestId('kpi-Expenses');
    expect(screen.queryByText(/needs? a quick look/i)).toBeNull();
  });

  it('counts the one-off queue and every transaction inside every merchant group', async () => {
    // The two queries are disjoint server-side, so the honest total is the sum -- showing only
    // one of them would understate the user's actual backlog.
    transactions.needsReview.mockResolvedValue([{ id: 't-1' }, { id: 't-2' }] as never);
    transactions.needsReviewGroups.mockResolvedValue([
      { merchantId: 'm-1', merchantName: 'Swiggy', transactionIds: ['t-3', 't-4', 't-5'] },
    ] as never);

    renderScreen();

    expect(await screen.findByText('5 transactions need a quick look')).toBeTruthy();
  });

  it('uses the singular for a backlog of one', async () => {
    transactions.needsReview.mockResolvedValue([{ id: 't-1' }] as never);

    renderScreen();

    expect(await screen.findByText('1 transaction needs a quick look')).toBeTruthy();
  });

  it('shows no count at all when only one half of the backlog loads', async () => {
    // The two queries are disjoint halves of one number. If one fails and the other returns rows,
    // `data ?? []` still produces a specific, confident, WRONG total -- and it is the
    // accessibilityLabel too. Suppressing the nudge is the only honest option; the review screen
    // is where the partial outage gets disclosed.
    transactions.needsReview.mockResolvedValue([{ id: 't-1' }, { id: 't-2' }] as never);
    transactions.needsReviewGroups.mockRejectedValue(new Error('down'));

    renderScreen();

    await screen.findByTestId('kpi-Expenses');
    await waitFor(() => expect(transactions.needsReviewGroups).toHaveBeenCalled());
    expect(screen.queryByText(/needs? a quick look/i)).toBeNull();
  });

  it('renders the rest of the dashboard when the backlog lookup fails', async () => {
    // A nudge is the one thing on this screen that should fail silently: no count means no nudge,
    // which is exactly what a user with an empty queue already sees.
    transactions.needsReview.mockRejectedValue(new Error('down'));
    transactions.needsReviewGroups.mockRejectedValue(new Error('down'));

    renderScreen();

    await screen.findByTestId('kpi-Expenses');
    expect(screen.queryByText(/needs? a quick look/i)).toBeNull();
  });
});

/**
 * Track C/C1: porting frontend/src/pages/Dashboard.tsx's Financial Health Score, Categorization
 * Confidence and Detected Issues cards. DashboardService has always computed and returned all
 * three; this is the first mobile UI that renders any of them.
 */
describe('Financial Health Score, Categorization Confidence, Detected Issues (Track C/C1)', () => {
  // isEmpty is driven by recentTxnsQ, not by summary -- one non-empty transaction is enough to
  // clear it for every test in this block that needs Health Score / Categorization Confidence
  // visible, without having to fight the file-wide default (totalElements: 0) per test.
  function markNotEmpty() {
    transactions.search.mockResolvedValue({
      content: [{
        id: 't1', accountId: 'a1', categoryId: 'c1', categoryName: 'Shopping', date: '2026-08-01',
        description: 'Coffee', merchant: 'Cafe', paymentMethod: 'CARD', amount: 150, type: 'EXPENSE',
        tags: [], notes: null, reconciliationStatus: 'OK', recurring: false, needsCategoryReview: false,
        categoryManuallySet: false,
      }],
      page: 0, size: 5, totalElements: 1, totalPages: 1,
    } as never);
  }

  it('hides Financial Health Score entirely while the account is empty, even with a score computed', async () => {
    // isEmpty stays true (the file-wide default: totalElements 0) -- a score computed from zero
    // real transactions has nothing behind it, same reasoning as the web card's own comment.
    dashboard.summary.mockResolvedValue(emptySummary({ healthScoreAvailable: true, healthScore: 72, healthLabel: 'Good' }));

    renderScreen();

    await screen.findByTestId('kpi-Expenses');
    expect(screen.queryByText('Financial Health Score')).toBeNull();
  });

  it('shows onboarding progress, not a score, below the transaction-count floor', async () => {
    markNotEmpty();
    dashboard.summary.mockResolvedValue(emptySummary({
      healthScoreAvailable: false, healthScoreTransactionCount: 4, healthScoreMinTransactions: 10,
    }));

    renderScreen();

    expect(await screen.findByText('Getting Started')).toBeTruthy();
    expect(screen.getByText('4 / 10 transactions')).toBeTruthy();
    expect(screen.getByText('40%')).toBeTruthy();
  });

  it('shows the score and breakdown once available, with each row\'s detail hidden until asked for', async () => {
    markNotEmpty();
    dashboard.summary.mockResolvedValue(emptySummary({
      healthScoreAvailable: true,
      healthScore: 82,
      healthLabel: 'Excellent',
      healthBreakdown: { 'Debt Score': 100, 'Savings Rate': 65 },
      healthBreakdownDetail: { 'Debt Score': 'No credit card balance carried over.' },
    }));

    // Same fake-timer + doNotFake:['queueMicrotask'] combination InsightsScreen.test.tsx's own
    // dwell-timer tests use: lets the mocked summary promise still resolve normally while giving
    // control over the withTiming count-up AnimatedHealthScoreNumber schedules on mount.
    jest.useFakeTimers({ doNotFake: ['queueMicrotask'] });
    renderScreen();
    await act(async () => { await jest.advanceTimersByTimeAsync(0); });
    await act(async () => { await jest.advanceTimersByTimeAsync(500); });
    expectHealthScoreValue('82');
    jest.useRealTimers();

    // "Excellent" now legitimately appears twice -- the Hero's own overall label, AND the
    // Debt Score factor card's tone pill (HealthFactorsRow: scoreLabel(100) === 'Excellent' too,
    // premium-redesign addition) -- so this asserts the label renders at all, not that it's unique.
    expect(screen.getAllByText('Excellent').length).toBeGreaterThan(0);
    expect(screen.getByText('Debt Score')).toBeTruthy();
    expect(screen.getByText('100%')).toBeTruthy();
    // Savings Rate has no detail entry -- no "Why?" control to offer for it.
    const whys = screen.getAllByText('Why?');
    expect(whys).toHaveLength(1);
    expect(screen.queryByText('No credit card balance carried over.')).toBeNull();

    fireEvent.press(whys[0]);

    expect(await screen.findByText('No credit card balance carried over.')).toBeTruthy();
    expect(screen.getByText('Hide')).toBeTruthy();
  });

  it('hides Categorization Confidence below the engine-decided-transaction floor (score null)', async () => {
    markNotEmpty();
    dashboard.summary.mockResolvedValue(emptySummary({ categorizationConfidenceScore: null }));

    renderScreen();

    await screen.findByTestId('kpi-Expenses');
    expect(screen.queryByText('Categorization Confidence')).toBeNull();
  });

  it('shows the categorization confidence score, label and transaction count once available', async () => {
    markNotEmpty();
    dashboard.summary.mockResolvedValue(emptySummary({
      categorizationConfidenceScore: 91, categorizationConfidenceTransactionCount: 23,
    }));

    renderScreen();

    expect(await screen.findByText('Categorization Confidence')).toBeTruthy();
    expect(screen.getByText('91')).toBeTruthy();
    expect(screen.getByText('Excellent')).toBeTruthy();
    expect(screen.getByText(/Based on 23 automatically categorized transactions/)).toBeTruthy();
  });

  it('hides Detected Issues when nothing was flagged', async () => {
    dashboard.summary.mockResolvedValue(emptySummary({ duplicateTransactionCount: 0 }));

    renderScreen();

    await screen.findByTestId('kpi-Expenses');
    expect(screen.queryByText('Detected Issues')).toBeNull();
  });

  // Deliberately NOT gated on isEmpty, unlike the two cards above -- markNotEmpty() is not called
  // here, pinning that a flagged duplicate on an otherwise-empty account still surfaces.
  it('lists detected duplicates and says how many more exist beyond the capped list', async () => {
    dashboard.summary.mockResolvedValue(emptySummary({
      duplicateTransactionCount: 3,
      detectedDuplicates: [
        { transactionId: 'tx-1', date: '2026-07-10', merchant: 'Swiggy', amount: 450 },
      ],
    }));

    renderScreen();

    expect(await screen.findByText('Detected Issues')).toBeTruthy();
    expect(screen.getByText(/We found 3 transactions/)).toBeTruthy();
    expect(screen.getByText('Swiggy')).toBeTruthy();
    expect(screen.getByText('and 2 more')).toBeTruthy();
  });

  it('confirms a detected duplicate and refreshes the figures it just changed', async () => {
    dashboard.summary.mockResolvedValue(emptySummary({
      duplicateTransactionCount: 1,
      detectedDuplicates: [{ transactionId: 'tx-1', date: '2026-07-10', merchant: 'Swiggy', amount: 450 }],
    }));
    transactions.confirmNotDuplicate.mockResolvedValue({} as never);

    const { queryClient } = renderScreen();
    const invalidateSpy = jest.spyOn(queryClient, 'invalidateQueries');
    await screen.findByText('Swiggy');

    fireEvent.press(screen.getByLabelText('Not a duplicate: Swiggy'));

    await waitFor(() => expect(transactions.confirmNotDuplicate).toHaveBeenCalledWith('tx-1'));
    // dashboard-summary (the card itself) and recent-transactions/transactions (the row's status
    // just changed) all have to refresh -- BH-027's whole point is that this transaction counts
    // again everywhere it didn't before.
    await waitFor(() => expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['dashboard-summary'] }));
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['transactions'] });
  });

  it('shows an inline error rather than silently dropping a failed confirmation', async () => {
    dashboard.summary.mockResolvedValue(emptySummary({
      duplicateTransactionCount: 1,
      detectedDuplicates: [{ transactionId: 'tx-1', date: '2026-07-10', merchant: 'Swiggy', amount: 450 }],
    }));
    transactions.confirmNotDuplicate.mockRejectedValue(new Error('network'));

    renderScreen();
    await screen.findByText('Swiggy');

    fireEvent.press(screen.getByLabelText('Not a duplicate: Swiggy'));

    expect(await screen.findByText("Couldn't update this transaction. Please try again.")).toBeTruthy();
  });
});

/**
 * Mobile audit Phase 2: summary.notifications (DashboardService.buildNotifications) has always
 * been computed and sent; this is the first mobile UI that renders it, mirroring
 * frontend/src/pages/Dashboard.tsx's identical "Next Actions" card.
 */
describe('Next Actions (mobile audit Phase 2)', () => {
  function markNotEmpty() {
    transactions.search.mockResolvedValue({
      content: [{
        id: 't1', accountId: 'a1', categoryId: 'c1', categoryName: 'Shopping', date: '2026-08-01',
        description: 'Coffee', merchant: 'Cafe', paymentMethod: 'CARD', amount: 150, type: 'EXPENSE',
        tags: [], notes: null, reconciliationStatus: 'OK', recurring: false, needsCategoryReview: false,
        categoryManuallySet: false,
      }],
      page: 0, size: 5, totalElements: 1, totalPages: 1,
    } as never);
  }

  it('hides the whole card while the account is empty, even with notifications computed', async () => {
    dashboard.summary.mockResolvedValue(emptySummary({ notifications: ['Your Visa payment is due tomorrow.'] }));

    renderScreen();

    await screen.findByTestId('kpi-Expenses');
    expect(screen.queryByText('Next Actions')).toBeNull();
  });

  it('shows a positive empty state once available with nothing to act on', async () => {
    markNotEmpty();
    dashboard.summary.mockResolvedValue(emptySummary({ notifications: [] }));

    renderScreen();

    expect(await screen.findByText('Next Actions')).toBeTruthy();
    expect(screen.getByText('Nothing needs your attention right now.')).toBeTruthy();
  });

  it('lists every notification the backend computed', async () => {
    markNotEmpty();
    dashboard.summary.mockResolvedValue(emptySummary({
      notifications: ['Your Visa payment is due tomorrow.', 'Your balance is below ₹1,000.'],
    }));

    renderScreen();

    expect(await screen.findByText('Next Actions')).toBeTruthy();
    expect(screen.getByText('Your Visa payment is due tomorrow.')).toBeTruthy();
    expect(screen.getByText('Your balance is below ₹1,000.')).toBeTruthy();
  });
});

/**
 * Track C/C2: promoting the statement coverage-gap warning from a buried Insights sentence to a
 * proactive Dashboard banner with a CTA into Import. InsightsService has always aggregated this
 * across every live account and returned it on /insights; nothing on mobile rendered the
 * structured field until now (only the flattened sentence, folded in among several others).
 */
describe('statement coverage-gap banner (Track C/C2)', () => {
  // Every test in this file isolates the summary call (see the file's own top comment) -- each
  // test below sets it explicitly rather than relying on whatever a PRECEDING test happened to
  // leave behind, since beforeEach only clears call history (jest.clearAllMocks), not mocked
  // implementations.
  beforeEach(() => {
    dashboard.summary.mockResolvedValue(emptySummary());
  });

  it('stays absent when nothing is missing', async () => {
    insights.get.mockResolvedValue({ sentences: [], movers: [], coverageCaveat: null } as never);

    renderScreen();

    await screen.findByTestId('kpi-Expenses');
    expect(screen.queryByText(/Possible gap/)).toBeNull();
  });

  it('names the month and offers a way into Import when a gap touches the current month', async () => {
    insights.get.mockResolvedValue({
      sentences: ['Some transactions for August 2026 may be missing — import that statement to complete your history.'],
      movers: [],
      coverageCaveat: { month: '2026-08', gaps: [{ gapStart: '2026-08-05', gapEnd: '2026-08-19' }] },
    } as never);

    renderScreen();

    expect(await screen.findByText('Possible gap in August 2026')).toBeTruthy();
    expect(screen.getByText(/Import that statement/)).toBeTruthy();
    // Said once, as the banner -- not also as an Insights bullet. The banner's own body copy is
    // deliberately worded differently from the backend's sentence, so this can only match the
    // filtered-out original.
    expect(screen.queryByText(/may be missing — import that statement to complete your history/)).toBeNull();
  });

  it('leaves unrelated Insights sentences in place -- the filter targets one sentence, not the whole card', async () => {
    insights.get.mockResolvedValue({
      sentences: [
        'Some transactions for August 2026 may be missing — import that statement to complete your history.',
        'Groceries was your biggest category at ₹6,000.',
      ],
      movers: [],
      coverageCaveat: { month: '2026-08', gaps: [{ gapStart: '2026-08-05', gapEnd: '2026-08-19' }] },
    } as never);

    renderScreen();

    expect(await screen.findByText(/Groceries was your biggest category/)).toBeTruthy();
  });

  it('opens Import when the banner is tapped', async () => {
    insights.get.mockResolvedValue({
      sentences: [],
      movers: [],
      coverageCaveat: { month: '2026-08', gaps: [{ gapStart: '2026-08-05', gapEnd: '2026-08-19' }] },
    } as never);
    const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
    navigate.mockClear();

    renderScreen();
    fireEvent.press(await screen.findByText('Possible gap in August 2026'));

    expect(navigate).toHaveBeenCalledWith('Import');
  });
});

/** Phase 4 (Medium-Tier Parity). Ported from frontend/src/pages/Dashboard.tsx's identical KPI
 *  and banner -- both already computed server-side and sent on every dashboard load, unrendered
 *  on mobile until now. */
describe('Savings Rate KPI (Phase 4)', () => {
  it('renders as a percent, not a currency amount', async () => {
    dashboard.summary.mockResolvedValue(emptySummary({ savingsRatePct: 32.7 }));

    renderScreen();

    // Rounded, not truncated or fixed to one decimal -- matches web's Math.round-equivalent
    // toFixed(0). Distinct testID from AnimatedNumber's kpi-<label> convention isn't needed: this
    // card uses the same convention, just a plain Text instead of the currency-only animated one.
    const value = await screen.findByTestId('kpi-Savings Rate');
    expect(value.props.children).toBe('33%');
  });

  it('is not read out as a rupee amount by assistive tech', async () => {
    dashboard.summary.mockResolvedValue(emptySummary({ savingsRatePct: 40 }));

    renderScreen();
    await screen.findByTestId('kpi-Savings Rate');

    expect(screen.getByLabelText('Savings Rate: 40%')).toBeTruthy();
  });
});

describe('Limited financial history banner (Phase 4)', () => {
  beforeEach(() => {
    markNotEmpty();
  });

  it('stays absent once history clears the floor', async () => {
    dashboard.summary.mockResolvedValue(emptySummary({ limitedHistory: false }));

    renderScreen();

    await screen.findByTestId('kpi-Expenses');
    expect(screen.queryByText('Limited financial history')).toBeNull();
  });

  it('names how thin the history is and what it may be distorting', async () => {
    dashboard.summary.mockResolvedValue(emptySummary({
      limitedHistory: true, statementCount: 2, accountCount: 1, historyMonthCount: 1,
      limitedHistoryMonthFloor: 3,
    }));

    renderScreen();

    expect(await screen.findByText('Limited financial history')).toBeTruthy();
    expect(screen.getByText(/Based on 2 statements across 1 account and 1 month of activity/)).toBeTruthy();
    expect(screen.getByText(/until at least 3 months of history are imported/)).toBeTruthy();
  });

  it('pluralizes singular counts correctly', async () => {
    dashboard.summary.mockResolvedValue(emptySummary({
      limitedHistory: true, statementCount: 1, accountCount: 1, historyMonthCount: 1,
    }));

    renderScreen();

    expect(await screen.findByText(/Based on 1 statement across 1 account and 1 month of activity/)).toBeTruthy();
  });

  // Same rule as the coverage-gap banner just above it in this file: a zero-transaction account
  // has its own dedicated empty state and must not also show a data-reliability warning about
  // numbers that were never rendered in the first place.
  it('stays hidden while the dashboard is legitimately empty, even if the server marks it limited', async () => {
    transactions.search.mockResolvedValue({
      content: [], page: 0, size: 5, totalElements: 0, totalPages: 0,
    } as never);
    dashboard.summary.mockResolvedValue(emptySummary({ limitedHistory: true }));

    renderScreen();

    await screen.findByTestId('kpi-Expenses');
    expect(screen.queryByText('Limited financial history')).toBeNull();
  });

  function markNotEmpty() {
    transactions.search.mockResolvedValue({
      content: [{
        id: 't1', accountId: 'a1', categoryId: 'c1', categoryName: 'Shopping', date: '2026-08-01',
        description: 'Coffee', merchant: 'Cafe', paymentMethod: 'CARD', amount: 150, type: 'EXPENSE',
        tags: [], notes: null, reconciliationStatus: 'OK', recurring: false, needsCategoryReview: false,
        categoryManuallySet: false,
      }],
      page: 0, size: 5, totalElements: 1, totalPages: 1,
    } as never);
  }
});

/**
 * Ported from frontend/src/pages/Dashboard.tsx:1023-1079. Always rendered (unlike Goals below it,
 * whose empty state IS hiding the card) -- a fresh account with no budgets is itself worth telling
 * someone about, the same reasoning Recent Transactions and Cash Flow already apply on this screen.
 */
describe('Budget Progress widget (Phase 4)', () => {
  beforeEach(() => {
    dashboard.summary.mockResolvedValue(emptySummary());
  });

  it('stays visible and shows its own empty state when there are no budgets', async () => {
    budgets.list.mockResolvedValue([]);

    renderScreen();

    expect(await screen.findByText('Budget Progress')).toBeTruthy();
    // findByText, not getByText: the SectionHeading above renders immediately regardless of the
    // budgets query's own state, so waiting on it alone doesn't prove the query has settled yet.
    expect(await screen.findByText('No budgets set. Create one to track your spending.')).toBeTruthy();
  });

  it('shows progress toward each budget, capped to the top 3', async () => {
    budgets.list.mockResolvedValue([
      { id: 'b1', categoryId: 'c1', categoryName: 'Groceries', monthlyLimit: 10000, spentThisMonth: 6000 },
      { id: 'b2', categoryId: 'c2', categoryName: 'Dining', monthlyLimit: 5000, spentThisMonth: 5500 },
      { id: 'b3', categoryId: 'c3', categoryName: 'Transport', monthlyLimit: 2000, spentThisMonth: 500 },
      { id: 'b4', categoryId: 'c4', categoryName: 'Shopping', monthlyLimit: 3000, spentThisMonth: 100 },
    ]);

    renderScreen();
    await screen.findByText('Groceries');

    expect(screen.getByText('60%')).toBeTruthy();
    expect(screen.getByText('₹6,000 of ₹10,000')).toBeTruthy();
    // Fourth budget is beyond the top-3 cap.
    expect(screen.queryByText('Shopping')).toBeNull();
  });

  // Over budget is the one state a bar/percentage colour actually has to carry meaning for --
  // green-vs-red is the whole point of a budget progress indicator. Web caps the percentage
  // itself at 100 (Math.min(100, ...)), same as the bar width -- this mirrors that exactly rather
  // than showing a truer-but-inconsistent "150%" the bar itself could never visually represent.
  it('marks an over-budget category in the danger colour, and caps its own percentage at 100%', async () => {
    budgets.list.mockResolvedValue([
      { id: 'b1', categoryId: 'c1', categoryName: 'Dining', monthlyLimit: 5000, spentThisMonth: 7500 },
    ]);

    renderScreen();

    const pct = await screen.findByText('100%');
    expect(pct).toHaveStyle({ color: light.danger });
  });

  it('says so rather than showing an empty state when budgets fail to load', async () => {
    budgets.list.mockRejectedValue(new Error('boom'));

    renderScreen();

    expect(await screen.findByText("Couldn't load your budgets.")).toBeTruthy();
    expect(screen.queryByText('No budgets set. Create one to track your spending.')).toBeNull();
  });

  it('opens the Budgets screen from "Manage Budgets"', async () => {
    budgets.list.mockResolvedValue([
      { id: 'b1', categoryId: 'c1', categoryName: 'Groceries', monthlyLimit: 10000, spentThisMonth: 6000 },
    ]);
    const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
    navigate.mockClear();

    renderScreen();
    fireEvent.press(await screen.findByLabelText('Manage Budgets'));

    expect(navigate).toHaveBeenCalledWith('More', { screen: 'Budgets' });
  });
});

/**
 * Ported from frontend/src/pages/Dashboard.tsx:1238-1266. RecurringService.detectForUser has
 * computed this since before this session; the Ledger/Reports "recurring" badge was the only
 * place it ever reached a screen.
 */
describe('Subscriptions & Recurring Payments widget (Phase 4)', () => {
  beforeEach(() => {
    dashboard.summary.mockResolvedValue(emptySummary());
  });

  // Device-local, matching recurringExpectedLabel's own fromLocalDateString parsing -- a raw
  // ISO/UTC string here would make this test itself flaky near midnight IST, exactly the bug the
  // production code's local-date handling exists to avoid.
  function inLocalDays(days: number): string {
    const d = new Date();
    d.setDate(d.getDate() + days);
    const month = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${d.getFullYear()}-${month}-${day}`;
  }

  function recurringItem(over: Partial<{
    merchant: string; label: string; averageAmount: number; occurrences: number;
    lastDate: string; nextEstimate: string;
  }> = {}) {
    return {
      merchant: 'Netflix', label: 'Subscription', averageAmount: 499, occurrences: 6,
      lastDate: inLocalDays(-30), nextEstimate: inLocalDays(5),
      ...over,
    };
  }

  it('stays absent when nothing recurring was detected', async () => {
    recurring.list.mockResolvedValue([]);

    renderScreen();

    await screen.findByTestId('kpi-Expenses');
    expect(screen.queryByText('Subscriptions & Recurring Payments')).toBeNull();
  });

  it('shows the merchant, label, and average amount', async () => {
    recurring.list.mockResolvedValue([recurringItem({ merchant: 'Netflix', label: 'Subscription', averageAmount: 499 })]);

    renderScreen();

    expect(await screen.findByText('Netflix')).toBeTruthy();
    expect(screen.getByText('Subscription')).toBeTruthy();
    expect(screen.getByText('₹499')).toBeTruthy();
  });

  it('words the projected date as today, tomorrow, or in N days', async () => {
    recurring.list.mockResolvedValue([
      recurringItem({ merchant: 'Today Co', nextEstimate: inLocalDays(0) }),
      recurringItem({ merchant: 'Tomorrow Co', nextEstimate: inLocalDays(1) }),
      recurringItem({ merchant: 'Five Day Co', nextEstimate: inLocalDays(5) }),
    ]);

    renderScreen();

    expect(await screen.findByText('expected today')).toBeTruthy();
    expect(screen.getByText('expected tomorrow')).toBeTruthy();
    expect(screen.getByText(/expected in 5 days \(/)).toBeTruthy();
  });

  it('caps the list to the first 5, trusting the server\'s own nextEstimate ordering', async () => {
    recurring.list.mockResolvedValue(
      Array.from({ length: 7 }, (_, i) => recurringItem({ merchant: `Merchant ${i}`, nextEstimate: inLocalDays(i) }))
    );

    renderScreen();

    await screen.findByText('Merchant 0');
    expect(screen.getByText('Merchant 4')).toBeTruthy();
    expect(screen.queryByText('Merchant 5')).toBeNull();
    expect(screen.queryByText('Merchant 6')).toBeNull();
  });

  it('dismisses a wrongly-detected group and removes it from the list', async () => {
    recurring.list.mockResolvedValue([recurringItem({ merchant: 'Netflix' })]);
    recurring.dismiss.mockResolvedValue(undefined);

    renderScreen();
    await screen.findByText('Netflix');

    fireEvent.press(screen.getByLabelText('Not recurring: dismiss Netflix'));

    await waitFor(() => expect(recurring.dismiss).toHaveBeenCalledWith('Netflix'));
    await waitFor(() => expect(screen.queryByText('Netflix')).toBeNull());
  });
});

/**
 * Ported from frontend/src/pages/Dashboard.tsx:1216-1235. A shortcut grid to destinations already
 * scattered across this screen's own empty states and CTAs.
 */
describe('Quick Actions grid (Phase 4)', () => {
  beforeEach(() => {
    dashboard.summary.mockResolvedValue(emptySummary());
  });

  it.each([
    ['Import Statement', 'Import', undefined],
    ['Create Budget', 'More', { screen: 'Budgets' }],
    ['View Reports', 'More', { screen: 'Reports' }],
    ['Manage Goals', 'Goals', undefined],
    ['Investments', 'More', { screen: 'Investments' }],
  ])('opens %s', async (label, route, params) => {
    const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
    navigate.mockClear();

    renderScreen();
    fireEvent.press(await screen.findByLabelText(label));

    if (params === undefined) {
      expect(navigate).toHaveBeenCalledWith(route);
    } else {
      expect(navigate).toHaveBeenCalledWith(route, params);
    }
  });

  // Doesn't fully drive AddTransactionSheet's own form -- that flow already has its own dedicated
  // test file (AddTransactionSheet.test.tsx). This only proves the integration point: tapping the
  // tile actually opens it, the same controlled-sheet pattern LedgerScreen already uses.
  it('opens the Add Transaction sheet', async () => {
    renderScreen();

    fireEvent.press(await screen.findByLabelText('Add Transaction'));

    // Not 'Add Transaction' itself -- the tile that opened the sheet renders the identical label
    // and stays mounted underneath it, so that text now matches twice. This screen's default
    // accounts.list() is [] (see beforeEach), so the sheet's own "no account to file this under"
    // copy is what proves it actually opened, rather than the tap silently doing nothing.
    expect(
      await screen.findByText('Import a statement or add an account first — a transaction always has to belong to one.')
    ).toBeTruthy();
  });

  // Web drops this entry only because it lacks another empty-state card to live in; mobile's own
  // Gmail connect is already one tap from Settings, so including it here would be a second,
  // redundant entry point rather than filling a real gap.
  it('does not include a Gmail shortcut', async () => {
    renderScreen();
    await screen.findByText('Quick Actions');

    expect(screen.queryByLabelText('Connect Gmail')).toBeNull();
  });
});

describe('Spending by Category donut drill-through (Track C/C4)', () => {
  it('navigates to Transactions with the tapped category and the reporting month it belongs to', async () => {
    dashboard.summary.mockResolvedValue(emptySummary({
      spendByCategory: { Dining: 4000, Groceries: 6000 },
      monthlyExpense: 10000,
      reportingMonth: '2026-08',
      reportingMonthIsCurrent: false,
    }));
    const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
    navigate.mockClear();

    renderScreen();

    fireEvent.press(await screen.findByRole('button', { name: 'Dining: ₹4,000' }));

    expect(navigate).toHaveBeenCalledWith('Transactions', {
      filters: expect.objectContaining({
        categoryName: 'Dining', dateFrom: '2026-08-01', dateTo: '2026-08-31', label: 'Dining · Aug 26',
      }),
    });
  });
});

describe('"As of" staleness caption on Total Balance (Track C/C5)', () => {
  it('reads "As of today" when the reporting month is the current one', async () => {
    dashboard.summary.mockResolvedValue(emptySummary({ reportingMonthIsCurrent: true }));

    renderScreen();

    expect(await screen.findByText('As of today')).toBeTruthy();
    expect(screen.getByLabelText(/Total Balance: .*, As of today/)).toBeTruthy();
    // Total Balance's own new slot, not a change to the other three -- with every delta null
    // (emptySummary's default), Income/Expenses/Net Savings all take the SAME "no delta" branch
    // Total Balance used to, and none of them grows a caption of its own.
    expect(screen.getAllByText(/^As of/)).toHaveLength(1);
  });

  it('names the stale month instead, when the reporting month is not the current one', async () => {
    dashboard.summary.mockResolvedValue(emptySummary({ reportingMonth: '2026-06', reportingMonthIsCurrent: false }));

    renderScreen();

    expect(await screen.findByText('As of Jun 26')).toBeTruthy();
    expect(screen.queryByText('As of today')).toBeNull();
  });
});
