import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, within, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Dashboard from './Dashboard';
import { AuthProvider } from '../context/AuthContext';
import {
  dashboardApi, accountsApi, transactionsApi, categoriesApi, goalsApi, insightsApi, userApi, budgetsApi, reportsApi, recurringApi,
} from '../api/endpoints';
import type { DashboardRangeSummary, DashboardSummary } from '../types';
import { mockMatchMedia } from '../test/mockMatchMedia';

// jsdom implements no canvas, so HTMLCanvasElement.getContext() returns null and Chart.js's
// constructor bails early -- but only AFTER assigning `this.canvas = null` and registering the
// half-built instance. react-chartjs-2 keeps that instance in its ref, so the very next render
// that changes `data.labels`/`data.datasets`/`options` runs its update effect and calls
// chart.update() on it: _checkEventBindings -> bindEvents -> bindResponsiveEvents sees
// isAttached(null) === false, calls detached() -> _resize(0, 0) -> getMaximumSize(null) ->
// `null.ownerDocument`. That TypeError is thrown from a passive effect with no error boundary
// above it, so React tears down the entire root -- the DOM goes to a bare <div /> mid-test and
// whichever findBy* is pending then polls an empty body until asyncUtilTimeout (5s) and fails.
//
// It presents as an intermittent failure in an unrelated-looking test because the Line chart only
// mounts once the independent reportsApi chain resolves: whether the crashing update() lands
// inside a still-asserting test is a matter of load-dependent timing. It is not cross-file
// pollution -- each test file runs in its own child process, so nothing can leak between them.
// (The "Not implemented: navigation" noise that shows up next to these failures comes from a
// different worker entirely and is printed unattributed; see src/test/setup.ts.)
//
// Mocking the chart components is what Investments.test.tsx already does, for the same reason:
// the charts are not under test here, the page's loading and empty-state behaviour is.
vi.mock('react-chartjs-2', () => ({
  Line: () => <div data-testid="cash-flow-chart" />,
  Doughnut: () => <div data-testid="spending-breakdown-chart" />,
}));

// Dashboard had no prior test file -- this covers only what each change added (the Financial
// Health Score card, D-19 Step 1; the Subscriptions & Recurring Payments card, C6.5; the D-21
// empty-state welcome screen), not the whole page. Every card renders data
// DashboardService/RecurringService already computed; nothing rendered any of it before these
// changes.
vi.mock('../api/endpoints', () => ({
  dashboardApi: { summary: vi.fn(), rangeSummary: vi.fn(), journey: vi.fn() },
  accountsApi: { list: vi.fn() },
  transactionsApi: { search: vi.fn(), create: vi.fn(), confirmNotDuplicate: vi.fn() },
  categoriesApi: { list: vi.fn() },
  goalsApi: { list: vi.fn() },
  insightsApi: { get: vi.fn() },
  userApi: { get: vi.fn() },
  budgetsApi: { list: vi.fn() },
  reportsApi: { availableMonths: vi.fn(), forMonth: vi.fn() },
  recurringApi: { list: vi.fn(), dismiss: vi.fn() },
  // ChecklistWidget (mounted on Dashboard, D-onboarding) fetches this on every render -- default
  // to "already 6/6" so it renders nothing and every existing test below, none of which cares
  // about onboarding, keeps seeing exactly the Dashboard content it did before this widget
  // existed.
  onboardingApi: { getChecklist: vi.fn().mockResolvedValue({ items: [], completedCount: 6, totalCount: 6 }) },
}));

// The Financial Health Score card's number counts up on mount (useCountUp in Dashboard.tsx) --
// real, intended behavior, not something under test in most of the describe blocks below. Global
// prefers-reduced-motion here makes every test see the settled final value immediately, the same
// way a real user with that OS preference already would, rather than every assertion on the score
// number racing a ~900ms animation. HealthScoreGauge's own test file covers the animation
// mechanics (including this exact reduced-motion skip-to-target path) directly.
let restoreMatchMedia: () => void;
beforeEach(() => {
  restoreMatchMedia = mockMatchMedia({ '(prefers-reduced-motion: reduce)': true });
});
afterEach(() => {
  restoreMatchMedia();
});

// The 5 KPI cards (Balance/Income/Expenses/Net Savings/Savings Rate) read from this range-based
// endpoint now, a separate query from dashboardApi.summary above -- see DashboardRangeService's
// own doc comment for why the two period models aren't unified into one call. Defaulted globally
// (not per-describe, unlike dashboardApi.summary) since none of the existing describe blocks below
// care about range-specific behavior; only the dedicated describe block further down overrides it.
beforeEach(() => {
  vi.mocked(dashboardApi.rangeSummary).mockReset().mockResolvedValue(rangeSummary());
});

function summary(overrides: Partial<DashboardSummary> = {}): DashboardSummary {
  return {
    currentBalance: 50000,
    totalAssets: 60000,
    totalLiabilities: 10000,
    netWorth: 50000,
    monthlyIncome: 80000,
    monthlyExpense: 45000,
    netCashFlow: 35000,
    savingsRatePct: 43.75,
    incomeDeltaPct: null,
    expenseDeltaPct: null,
    netDeltaPct: null,
    healthScore: 82,
    healthLabel: 'Excellent',
    healthBreakdown: {
      'Savings Rate': 83,
      'Debt Score': 100,
      'Emergency Fund': 70,
      'Spend Consistency': 50,
      'Cash Flow Stability': 80,
    },
    // Defaults to no "Why?" toggles rendering (existing tests, none of which cares about this)
    // so they keep rendering exactly as they did before this field existed.
    healthBreakdownDetail: {},
    healthScoreAvailable: true,
    healthScoreTransactionCount: 12,
    healthScoreMinTransactions: 10,
    // Defaults to "no history yet" so existing tests, none of which cares about these fields,
    // keep rendering exactly as they did before these fields existed.
    healthScoreDeltaVsLastMonth: null,
    healthSparkline: [],
    healthTopOpportunityFactor: null,
    healthTopOpportunityPotentialGain: null,
    spendByCategory: {},
    notifications: [],
    reportingMonth: '2026-08',
    reportingMonthIsCurrent: true,
    // Defaults to a mature account (not limited) so every existing test below, none of which
    // cares about this banner, keeps rendering exactly as it did before this field existed.
    limitedHistory: false,
    historyMonthCount: 6,
    limitedHistoryMonthFloor: 3,
    statementCount: 8,
    accountCount: 2,
    categoryReviewWarning: false,
    categoryReviewSpendPct: 0,
    categoryReviewSpendAmount: 0,
    categoryReviewTransactionCount: 0,
    categoryReviewSpendWarningThresholdPct: 20,
    // Defaults to no gate firing (the deltas above are null because this fixture doesn't set them,
    // not because anything was withheld) so existing tests keep seeing a plain muted "—" with no
    // "Why?" toggle, matching how they rendered before this field existed.
    comparisonGateReason: null,
    comparisonGateMinTransactions: 3,
    // Defaults to no movers (the delta above is null in this fixture, so there's nothing to
    // explain) so existing tests keep seeing a plain "Why?"-free delta line.
    expenseCategoryMovers: [],
    // Defaults to nothing detected so existing tests, none of which cares about this card, keep
    // rendering exactly as they did before this field existed.
    duplicateTransactionCount: 0,
    detectedDuplicates: [],
    // Defaults to null (below the floor) so existing tests, none of which cares about this card,
    // keep rendering exactly as they did before this field existed.
    categorizationConfidenceScore: null,
    categorizationConfidenceTransactionCount: 0,
    categorizationConfidenceMinTransactions: 5,
    ...overrides,
  };
}

// Same headline figures the old single-month `summary()` fixture used (currentBalance 50000,
// income 80000, expense 45000, net 35000) -- deliberately, so tests written against those numbers
// before the KPI cards moved to this endpoint keep passing unchanged.
function rangeSummary(overrides: Partial<DashboardRangeSummary> = {}): DashboardRangeSummary {
  return {
    rangeType: 'LAST_6_MONTHS',
    startDate: '2026-03-01',
    endDate: '2026-08-31',
    previousStartDate: '2025-09-01',
    previousEndDate: '2026-02-28',
    incomeTotal: 80000,
    expenseTotal: 45000,
    netSavingsTotal: 35000,
    savingsRatePct: 43.75,
    incomeDeltaPct: null,
    expenseDeltaPct: null,
    netDeltaPct: null,
    comparisonGateReason: null,
    comparisonGateMinTransactions: 3,
    currentBalance: 50000,
    currentBalanceAsOf: '2026-08-31',
    previousBalance: null,
    previousBalanceAsOf: null,
    balanceDeltaPct: null,
    balanceGateReason: null,
    ...overrides,
  };
}

function renderDashboard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter>
          <Dashboard />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>
  );
}

describe('Dashboard — Financial Health Score', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue(summary());
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      // totalElements > 0: this account has real history, matching every scenario these two
      // describe blocks actually test (a populated Health Score, real recurring items) -- 0 would
      // trip D-21's empty-state welcome screen instead of rendering the dashboard under test.
      // content stays [] since neither describe block asserts on the Recent Transactions list.
      content: [], page: 0, size: 4, totalElements: 12, totalPages: 3,
    });
    vi.mocked(goalsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(insightsApi.get).mockReset().mockResolvedValue({ sentences: [], movers: [] });
    vi.mocked(userApi.get).mockReset().mockResolvedValue({
      email: 'amy@example.test', fullName: 'Amy Santiago', lowBalanceThreshold: 2000,
      theme: 'system', timezone: 'Asia/Kolkata', phoneNumber: '+919876500000',
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
      onboardingCompleted: true,
    });
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.availableMonths).mockReset().mockResolvedValue(['2026-08']);
    vi.mocked(reportsApi.forMonth).mockReset().mockResolvedValue({
      month: '2026-08', income: 80000, expense: 45000, categories: [],
    });
    vi.mocked(recurringApi.list).mockReset().mockResolvedValue([]);
  });

  it('shows the score and label the backend already computed', async () => {
    renderDashboard();

    expect(await screen.findByText('Financial Health Score')).toBeInTheDocument();
    // Scoped to the gauge/label block itself -- "Excellent" also appears on any factor card
    // whose own score clears 80 (the default fixture has three), so an unscoped query is
    // ambiguous now that factor cards carry their own status badge.
    const summaryBlock = within(screen.getByTestId('health-score-summary'));
    expect(summaryBlock.getByText('82')).toBeInTheDocument();
    expect(summaryBlock.getByText('Excellent')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: /financial health score 82 out of 100/i })).toBeInTheDocument();
  });

  it('shows every factor with its own NN/100 score, status badge, and explanation', async () => {
    renderDashboard();

    await screen.findByText('Financial Health Score');
    expect(screen.getByTestId('health-factor-Savings Rate')).toBeInTheDocument();
    expect(screen.getByTestId('health-factor-Debt Score')).toBeInTheDocument();
    expect(screen.getByTestId('health-factor-Emergency Fund')).toBeInTheDocument();
    expect(screen.getByTestId('health-factor-Spend Consistency')).toBeInTheDocument();
    expect(screen.getByTestId('health-factor-Cash Flow Stability')).toBeInTheDocument();

    const debtCard = within(screen.getByTestId('health-factor-Debt Score'));
    expect(debtCard.getByText('100 / 100')).toBeInTheDocument();
    expect(debtCard.getByText('Excellent')).toBeInTheDocument(); // status badge, score 100
    expect(debtCard.getByText("You're managing debt well.")).toBeInTheDocument();

    const spendCard = within(screen.getByTestId('health-factor-Spend Consistency'));
    expect(spendCard.getByText('50 / 100')).toBeInTheDocument();
    expect(spendCard.getByText('Fair')).toBeInTheDocument(); // status badge, score 50
    expect(spendCard.getByText('Try to keep monthly spending within about 20% of your average.')).toBeInTheDocument();

    // Savings Rate (83) and Cash Flow Stability (80) are this default fixture's other two "good"
    // branches -- asserted here so all 10 (factor x good/bad) suggestion strings are covered
    // across this test and the flipped-branch test below, not just 5 of them.
    expect(within(screen.getByTestId('health-factor-Savings Rate'))
      .getByText("You're saving well — keep it up.")).toBeInTheDocument();
    expect(within(screen.getByTestId('health-factor-Cash Flow Stability'))
      .getByText('Your cash flow has been stable.')).toBeInTheDocument();
  });

  it('shows the correct improvement suggestion for every factor, both above and below 80', async () => {
    // The default fixture (Savings Rate 83, Debt Score 100, Emergency Fund 70, Spend Consistency
    // 50, Cash Flow Stability 80) only ever exercises the "good" branch for 3 factors and the
    // "not good" branch for 2 -- 5 of the 10 possible (factor x good/bad) suggestion strings.
    // This flips every factor to its OTHER branch to cover the remaining 5, so all 10 are real,
    // asserted behavior rather than 5 covered by luck and 5 never rendered by any test.
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      healthBreakdown: { 'Savings Rate': 20, 'Debt Score': 30, 'Emergency Fund': 90, 'Spend Consistency': 85, 'Cash Flow Stability': 40 },
    }));
    renderDashboard();

    await screen.findByText('Financial Health Score');
    expect(within(screen.getByTestId('health-factor-Savings Rate'))
      .getByText('Aim to save at least 24% of your income each month.')).toBeInTheDocument();
    expect(within(screen.getByTestId('health-factor-Debt Score'))
      .getByText('Pay down credit card balances to bring utilization under 20%.')).toBeInTheDocument();
    expect(within(screen.getByTestId('health-factor-Emergency Fund'))
      .getByText('You have a solid safety net.')).toBeInTheDocument();
    expect(within(screen.getByTestId('health-factor-Spend Consistency'))
      .getByText('Your spending has been consistent.')).toBeInTheDocument();
    expect(within(screen.getByTestId('health-factor-Cash Flow Stability'))
      .getByText('Work toward income meeting or exceeding expenses most months.')).toBeInTheDocument();
  });

  it("badges each factor by its OWN score, not the overall label", async () => {
    // A perfect Debt Score (100 -- no credit card debt) must render as "Excellent" even when the
    // overall health score is poor and every other factor is struggling. Before this fix (when
    // this was a colored bar), every bar inherited the overall label's color, so a 100 rendered
    // as full-width red -- reading as "maxed out" regardless of what its own number said.
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      healthScore: 28, healthLabel: 'Needs Attention',
      healthBreakdown: { 'Savings Rate': 0, 'Debt Score': 100, 'Emergency Fund': 9, 'Spend Consistency': 8, 'Cash Flow Stability': 50 },
    }));
    renderDashboard();

    await screen.findByText('Financial Health Score');
    expect(within(screen.getByTestId('health-factor-Debt Score')).getByText('Excellent')).toBeInTheDocument();
    expect(within(screen.getByTestId('health-factor-Savings Rate')).getByText('Needs Attention')).toBeInTheDocument();
  });

  it('reflects a low score honestly rather than always looking healthy', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      healthScore: 28, healthLabel: 'Needs Attention',
      healthBreakdown: { 'Savings Rate': 10, 'Debt Score': 20, 'Emergency Fund': 15, 'Spend Consistency': 40, 'Cash Flow Stability': 35 },
    }));
    renderDashboard();

    await screen.findByText('Financial Health Score');
    const summaryBlock = within(screen.getByTestId('health-score-summary'));
    expect(summaryBlock.getByText('28')).toBeInTheDocument();
    expect(summaryBlock.getByText('Needs Attention')).toBeInTheDocument();
  });

  it('shows the monthly change indicator when a delta is present, hides it when null', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({ healthScoreDeltaVsLastMonth: 8 }));
    renderDashboard();
    expect(await screen.findByText(/↑ 8 vs your last recorded score/)).toBeInTheDocument();
  });

  it('shows a down arrow for a negative delta', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({ healthScoreDeltaVsLastMonth: -5 }));
    renderDashboard();
    expect(await screen.findByText(/↓ 5 vs your last recorded score/)).toBeInTheDocument();
  });

  it('shows a neutral "no change" message for a delta of exactly zero, not "↑ 0"', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({ healthScoreDeltaVsLastMonth: 0 }));
    renderDashboard();
    expect(await screen.findByText('No change vs your last recorded score')).toBeInTheDocument();
    const summaryBlock = within(screen.getByTestId('health-score-summary'));
    expect(summaryBlock.queryByText(/↑/)).not.toBeInTheDocument();
  });

  it('hides the monthly change indicator when there is no prior snapshot', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({ healthScoreDeltaVsLastMonth: null }));
    renderDashboard();
    await screen.findByText('Financial Health Score');
    // Scoped: KPI cards elsewhere on the page also render their own "vs last month" delta text,
    // so an unscoped query is ambiguous.
    const summaryBlock = within(screen.getByTestId('health-score-summary'));
    expect(summaryBlock.queryByText(/vs your last recorded score/)).not.toBeInTheDocument();
  });

  it('renders the AI Insight card with a Create Goal link when a real opportunity exists', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      healthTopOpportunityFactor: 'Emergency Fund', healthTopOpportunityPotentialGain: 18,
    }));
    renderDashboard();

    expect(await screen.findByText(/emergency fund is the biggest opportunity/i)).toBeInTheDocument();
    expect(screen.getByText('+18 points')).toBeInTheDocument();
    // Scoped: the Goals section elsewhere on this page has its own, unrelated "+ Create Goal"
    // empty-state CTA that also links to /app/goals -- an unscoped query is ambiguous.
    const insightCard = within(screen.getByTestId('health-score-insight'));
    expect(insightCard.getByRole('link', { name: /create goal/i })).toHaveAttribute('href', '/app/goals');
  });

  it('hides the AI Insight card when there is no real opportunity', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      healthTopOpportunityFactor: null, healthTopOpportunityPotentialGain: null,
    }));
    renderDashboard();
    await screen.findByText('Financial Health Score');
    expect(screen.queryByText(/biggest opportunity/i)).not.toBeInTheDocument();
    expect(screen.queryByTestId('health-score-insight')).not.toBeInTheDocument();
  });

  it("shows the point-opportunity badge only on the top opportunity factor's own card", async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      healthTopOpportunityFactor: 'Emergency Fund', healthTopOpportunityPotentialGain: 18,
    }));
    renderDashboard();

    expect(await screen.findByText('↑ +18 point opportunity')).toBeInTheDocument();
    // Exactly one badge -- not repeated on every card.
    expect(screen.getAllByText(/point opportunity/)).toHaveLength(1);
    expect(within(screen.getByTestId('health-factor-Emergency Fund')).getByText('↑ +18 point opportunity')).toBeInTheDocument();
  });

  it('renders the sparkline only with at least 2 points, splitting across a gap month', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      healthSparkline: [
        { yearMonth: '2026-06', score: 45 },
        { yearMonth: '2026-08', score: 51 }, // gap at 2026-07
      ],
    }));
    renderDashboard();
    await screen.findByText('Financial Health Score');
    const sparkline = screen.getByTestId('health-score-sparkline');
    expect(sparkline.querySelectorAll('polyline')).toHaveLength(0); // two 1-point runs, neither drawable
  });

  it("D-25 PR3-A: shows a 'Getting Started' progress state instead of a score below the transaction floor", async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      healthScore: null, healthLabel: null, healthBreakdown: {},
      healthScoreAvailable: false, healthScoreTransactionCount: 7, healthScoreMinTransactions: 10,
    }));
    renderDashboard();

    const heading = await screen.findByText('Financial Health Score');
    const card = within(heading.closest('div.bg-card') as HTMLElement);
    expect(card.getByText('Getting Started')).toBeInTheDocument();
    expect(card.getByText('7 / 10 transactions')).toBeInTheDocument();
    expect(card.getByText('70%')).toBeInTheDocument();
    // Not a real score or breakdown -- rendering either here would be the exact harsh-first-
    // impression bug this state exists to avoid. Scoped to the card itself: "Savings Rate" is
    // also a KPI tile label elsewhere on the page, same reason the breakdown test above scopes.
    expect(card.queryByText('out of 100')).not.toBeInTheDocument();
    expect(card.queryByText('Savings Rate')).not.toBeInTheDocument();
  });

  it('shows each factor\'s explanation directly on its card, no toggle needed', async () => {
    // The old bar layout hid this behind a "Why?" toggle; the factor-card redesign shows it
    // inline on every card immediately -- there's nothing left to toggle.
    vi.mocked(reportsApi.availableMonths).mockResolvedValue([]);
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      healthBreakdownDetail: {
        'Savings Rate': 'Your savings rate was 18.5%.',
        'Debt Score': 'You have no credit cards on file.',
      },
    }));
    renderDashboard();

    await screen.findByText('Financial Health Score');
    expect(within(screen.getByTestId('health-factor-Savings Rate')).getByText('Your savings rate was 18.5%.')).toBeInTheDocument();
    expect(within(screen.getByTestId('health-factor-Debt Score')).getByText('You have no credit cards on file.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Why?' })).not.toBeInTheDocument();
  });

  it('renders KPI cards with the elevated visual treatment', async () => {
    renderDashboard();

    await screen.findByText('Financial Health Score');
    const balanceValue = screen.getByText('₹50,000');
    expect(balanceValue).toHaveClass('font-display');
  });

  it('wraps the greeting in a hero card with a decorative, hidden illustration', async () => {
    renderDashboard();

    const heading = await screen.findByRole('heading', { level: 1 });
    expect(heading.textContent).toMatch(/👋/);

    const illustration = document.querySelector('[data-testid="dashboard-hero-illustration"]');
    expect(illustration).toBeTruthy();
    expect(illustration).toHaveAttribute('aria-hidden', 'true');
  });

  it('keeps the hero free of quick actions and of the Financial Health/Savings Rate numbers already shown just below', async () => {
    renderDashboard();

    const heading = await screen.findByRole('heading', { level: 1 });
    const hero = within(heading.closest('div.bg-card') as HTMLElement);

    expect(hero.queryByText(/Financial Health:/)).not.toBeInTheDocument();
    expect(hero.queryByText(/Savings rate/)).not.toBeInTheDocument();
    expect(hero.queryByRole('link', { name: /import statement/i })).not.toBeInTheDocument();
    expect(hero.queryByRole('button', { name: /add transaction/i })).not.toBeInTheDocument();
  });
});

describe('Dashboard — Spending Breakdown category review warning', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue(summary());
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    // totalElements: 0 (isEmpty) -- these tests don't care about Financial Health Score. This
    // originally also avoided mounting two live Chart.js instances at once; react-chartjs-2 is
    // mocked at the top of this file now, so that hazard is gone and this is purely about scoping
    // these tests to the Spending Breakdown card.
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [], page: 0, size: 4, totalElements: 0, totalPages: 0,
    });
    vi.mocked(transactionsApi.create).mockReset();
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([
      { id: 'cat-1', name: 'Groceries' } as any,
      { id: 'cat-2', name: 'Salary' } as any,
    ]);
    vi.mocked(goalsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(insightsApi.get).mockReset().mockResolvedValue({ sentences: [], movers: [] });
    vi.mocked(userApi.get).mockReset().mockResolvedValue({
      email: 'amy@example.test', fullName: 'Amy Santiago', lowBalanceThreshold: 2000,
      theme: 'system', timezone: 'Asia/Kolkata', phoneNumber: '+919876500000',
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
      onboardingCompleted: true,
    });
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);
    // Empty (not a real month) -- CashFlowChart isn't gated by the page-level isEmpty at all;
    // it's driven independently by these two APIs. This used to be load-bearing: real data here
    // mounted a live Line chart whose first update() threw uncaught and unmounted the whole tree.
    // react-chartjs-2 is mocked at the top of this file now, so it only keeps the Cash Flow card
    // out of tests that are about Spending Breakdown.
    vi.mocked(reportsApi.availableMonths).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.forMonth).mockReset();
    vi.mocked(recurringApi.list).mockReset().mockResolvedValue([]);
  });

  it('shows the callout with the real amount/count/pct when the warning is active', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      spendByCategory: { Other: 73306, Shopping: 16627, Groceries: 193 },
      categoryReviewWarning: true, categoryReviewSpendPct: 81, categoryReviewSpendAmount: 73306,
      categoryReviewTransactionCount: 24,
    }));
    renderDashboard();

    expect(await screen.findByText('Spending needs category review')).toBeInTheDocument();
    expect(screen.getByText(/₹73,306 \(81%\) across 24 transactions/)).toBeInTheDocument();
    expect(screen.getByText('Review transactions →').closest('a')).toHaveAttribute('href', '/app/transactions');
  });

  it('uses singular wording for exactly one flagged transaction', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      spendByCategory: { Other: 500 },
      categoryReviewWarning: true, categoryReviewSpendPct: 100, categoryReviewSpendAmount: 500,
      categoryReviewTransactionCount: 1,
    }));
    renderDashboard();

    expect(await screen.findByText(/across 1 transaction this/)).toBeInTheDocument();
  });

  it('stays hidden when the warning is not active, even with real spending data', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      spendByCategory: { Groceries: 5000, Dining: 2000 },
      categoryReviewWarning: false, categoryReviewSpendPct: 5,
    }));
    renderDashboard();

    await screen.findByText('Spending Breakdown');
    expect(screen.queryByText('Spending needs category review')).not.toBeInTheDocument();
  });
});

// Task 14: Recent Transactions used to look up its icon/color from a 4-entry hardcoded map keyed
// by category NAME (Dining/Shopping/Transport/Salary), falling back to a generic ShoppingBag/gray
// for every other category -- including all 21 other default categories and any custom one a user
// creates. This confirms the row now renders the real icon/color TOKEN the backend assigned via
// categoryId, for a category that was never in that old 4-entry map.
describe('Dashboard — Recent Transactions icon/color', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue(summary());
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([
      { id: 'cat-pets', name: 'Pets', isSystem: true, icon: 'paw-print', color: 'teal' } as any,
    ]);
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [
        {
          id: 'txn-1', accountId: 'acct-1', categoryId: 'cat-pets', categoryName: 'Pets',
          date: '2026-08-20', description: 'Vet visit', merchant: 'Local Vet Clinic',
          paymentMethod: 'UPI', amount: 1200, type: 'EXPENSE', tags: [], notes: null,
          reconciliationStatus: 'OK', recurring: false, needsCategoryReview: false,
          categoryManuallySet: false, counterpartyType: 'UNKNOWN',
        },
      ],
      page: 0, size: 4, totalElements: 1, totalPages: 1,
    });
    vi.mocked(goalsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(insightsApi.get).mockReset().mockResolvedValue({ sentences: [], movers: [] });
    vi.mocked(userApi.get).mockReset().mockResolvedValue({
      email: 'amy@example.test', fullName: 'Amy Santiago', lowBalanceThreshold: 2000,
      theme: 'system', timezone: 'Asia/Kolkata', phoneNumber: '+919876500000',
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
      onboardingCompleted: true,
    });
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.availableMonths).mockReset().mockResolvedValue(['2026-08']);
    vi.mocked(reportsApi.forMonth).mockReset().mockResolvedValue({
      month: '2026-08', income: 80000, expense: 45000, categories: [],
    });
    vi.mocked(recurringApi.list).mockReset().mockResolvedValue([]);
  });

  it("renders the category's real backend icon/color token instead of the old hardcoded fallback", async () => {
    renderDashboard();

    const row = (await screen.findByText('Vet visit')).closest('.flex.items-center.gap-3') as HTMLElement;
    // The teal token's real hex (CategoryPalette.COLORS / COLOR_HEX) rendered as `color + '20'`
    // (12.5% alpha) -- jsdom normalizes the inline style's hex+alpha shorthand to rgba -- not the
    // old generic '#262A33' fallback that every non-mapped category used to get.
    const iconWrap = row.querySelector('div[style*="background"]') as HTMLElement;
    expect(iconWrap.style.background).toBe('rgba(13, 148, 136, 0.125)');
  });
});

describe('Dashboard — Limited History Banner', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue(summary());
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [], page: 0, size: 4, totalElements: 12, totalPages: 3,
    });
    vi.mocked(goalsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(insightsApi.get).mockReset().mockResolvedValue({ sentences: [], movers: [] });
    vi.mocked(userApi.get).mockReset().mockResolvedValue({
      email: 'amy@example.test', fullName: 'Amy Santiago', lowBalanceThreshold: 2000,
      theme: 'system', timezone: 'Asia/Kolkata', phoneNumber: '+919876500000',
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
      onboardingCompleted: true,
    });
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.availableMonths).mockReset().mockResolvedValue(['2026-08']);
    vi.mocked(reportsApi.forMonth).mockReset().mockResolvedValue({
      month: '2026-08', income: 80000, expense: 45000, categories: [],
    });
    vi.mocked(recurringApi.list).mockReset().mockResolvedValue([]);
  });

  it('shows the banner with the real counts when history is limited', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      limitedHistory: true, historyMonthCount: 1, limitedHistoryMonthFloor: 3,
      statementCount: 2, accountCount: 2,
    }));
    renderDashboard();

    expect(await screen.findByText('Limited financial history')).toBeInTheDocument();
    expect(screen.getByText(
      'Based on 2 statements across 2 accounts and 1 month of activity. Trends and the Financial Health Score below may be unreliable until at least 3 months of history are imported.'
    )).toBeInTheDocument();
  });

  it('collapses the detail text on toggle without hiding the banner itself', async () => {
    const user = userEvent.setup();
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      limitedHistory: true, historyMonthCount: 1, limitedHistoryMonthFloor: 3,
      statementCount: 2, accountCount: 2,
    }));
    renderDashboard();

    const detail = 'Based on 2 statements across 2 accounts and 1 month of activity. Trends and the Financial Health Score below may be unreliable until at least 3 months of history are imported.';
    expect(await screen.findByText(detail)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /collapse details/i }));
    expect(screen.getByText('Limited financial history')).toBeInTheDocument();
    expect(screen.queryByText(detail)).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /expand details/i }));
    expect(await screen.findByText(detail)).toBeInTheDocument();
  });

  it('does not show the banner once history clears the floor', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({ limitedHistory: false }));
    renderDashboard();

    await screen.findByText('Financial Health Score'); // wait for the dashboard to finish loading
    expect(screen.queryByText('Limited financial history')).not.toBeInTheDocument();
  });

  it('uses singular wording for exactly one statement/account/month', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      limitedHistory: true, historyMonthCount: 1, limitedHistoryMonthFloor: 3,
      statementCount: 1, accountCount: 1,
    }));
    renderDashboard();

    expect(await screen.findByText(
      'Based on 1 statement across 1 account and 1 month of activity. Trends and the Financial Health Score below may be unreliable until at least 3 months of history are imported.'
    )).toBeInTheDocument();
  });

  it('stays hidden for a zero-transaction account -- the empty state covers that case on its own', async () => {
    vi.mocked(transactionsApi.search).mockResolvedValue({
      content: [], page: 0, size: 4, totalElements: 0, totalPages: 0,
    });
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      limitedHistory: true, historyMonthCount: 0, statementCount: 0, accountCount: 0,
    }));
    renderDashboard();

    await screen.findByText('No transactions yet'); // Recent Transactions' own per-section empty state
    expect(screen.queryByText('Limited financial history')).not.toBeInTheDocument();
  });
});

describe('Dashboard — Next Actions', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue(summary());
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [], page: 0, size: 4, totalElements: 12, totalPages: 3,
    });
    vi.mocked(goalsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(insightsApi.get).mockReset().mockResolvedValue({ sentences: [], movers: [] });
    vi.mocked(userApi.get).mockReset().mockResolvedValue({
      email: 'amy@example.test', fullName: 'Amy Santiago', lowBalanceThreshold: 2000,
      theme: 'system', timezone: 'Asia/Kolkata', phoneNumber: '+919876500000',
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
      onboardingCompleted: true,
    });
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.availableMonths).mockReset().mockResolvedValue(['2026-08']);
    vi.mocked(reportsApi.forMonth).mockReset().mockResolvedValue({
      month: '2026-08', income: 80000, expense: 45000, categories: [],
    });
    vi.mocked(recurringApi.list).mockReset().mockResolvedValue([]);
  });

  it('lists every notification the backend already computed', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      notifications: [
        'HDFC Credit Card payment of 5000.00 is due in 3 day(s).',
        'Groceries spending of 6000.00 has reached your monthly budget of 5000.00.',
      ],
    }));
    renderDashboard();

    expect(await screen.findByText('Next Actions')).toBeInTheDocument();
    expect(screen.getByText('HDFC Credit Card payment of 5000.00 is due in 3 day(s).')).toBeInTheDocument();
    expect(screen.getByText('Groceries spending of 6000.00 has reached your monthly budget of 5000.00.')).toBeInTheDocument();
  });

  it('shows a positive empty state rather than an empty card when nothing needs attention', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({ notifications: [] }));
    renderDashboard();

    expect(await screen.findByText('Next Actions')).toBeInTheDocument();
    expect(screen.getByText('Nothing needs your attention right now.')).toBeInTheDocument();
  });

  it('stays hidden for a zero-transaction account -- nothing has been computed here yet', async () => {
    vi.mocked(transactionsApi.search).mockResolvedValue({
      content: [], page: 0, size: 4, totalElements: 0, totalPages: 0,
    });
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      notifications: ['This should never render while the account is empty.'],
    }));
    renderDashboard();

    await screen.findByText('No transactions yet'); // Recent Transactions' own per-section empty state
    expect(screen.queryByText('Next Actions')).not.toBeInTheDocument();
  });
});

describe('Dashboard — Detected Issues', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue(summary());
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [], page: 0, size: 4, totalElements: 12, totalPages: 3,
    });
    vi.mocked(transactionsApi.confirmNotDuplicate).mockReset();
    vi.mocked(goalsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(insightsApi.get).mockReset().mockResolvedValue({ sentences: [], movers: [] });
    vi.mocked(userApi.get).mockReset().mockResolvedValue({
      email: 'amy@example.test', fullName: 'Amy Santiago', lowBalanceThreshold: 2000,
      theme: 'system', timezone: 'Asia/Kolkata', phoneNumber: '+919876500000',
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
      onboardingCompleted: true,
    });
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.availableMonths).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.forMonth).mockReset();
    vi.mocked(recurringApi.list).mockReset().mockResolvedValue([]);
  });

  it('stays hidden when nothing was detected', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({ duplicateTransactionCount: 0 }));
    renderDashboard();

    await screen.findByText('Financial Health Score');
    expect(screen.queryByText('Detected Issues')).not.toBeInTheDocument();
  });

  it('lists a detected duplicate with a "Not a duplicate" action', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      duplicateTransactionCount: 1,
      detectedDuplicates: [
        { transactionId: 'txn-1', date: '2026-07-10', merchant: 'Swiggy', amount: 500 },
      ],
    }));
    renderDashboard();

    expect(await screen.findByText('Detected Issues')).toBeInTheDocument();
    expect(screen.getByText(
      'We found 1 transaction that looks like a duplicate and excluded it from your totals.'
    )).toBeInTheDocument();
    expect(screen.getByText('Swiggy')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Not a duplicate' })).toBeInTheDocument();
  });

  it('uses plural wording for more than one detected duplicate', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      duplicateTransactionCount: 2,
      detectedDuplicates: [
        { transactionId: 'txn-1', date: '2026-07-10', merchant: 'Swiggy', amount: 500 },
        { transactionId: 'txn-2', date: '2026-07-09', merchant: 'Zomato', amount: 300 },
      ],
    }));
    renderDashboard();

    expect(await screen.findByText(
      'We found 2 transactions that look like duplicates and excluded them from your totals.'
    )).toBeInTheDocument();
  });

  it('notes how many more exist beyond the capped display list', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      duplicateTransactionCount: 8,
      detectedDuplicates: [
        { transactionId: 'txn-1', date: '2026-07-10', merchant: 'Swiggy', amount: 500 },
      ],
    }));
    renderDashboard();

    expect(await screen.findByText('and 7 more')).toBeInTheDocument();
  });

  it('calls confirmNotDuplicate and refreshes the summary when clicked', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      duplicateTransactionCount: 1,
      detectedDuplicates: [
        { transactionId: 'txn-1', date: '2026-07-10', merchant: 'Swiggy', amount: 500 },
      ],
    }));
    vi.mocked(transactionsApi.confirmNotDuplicate).mockResolvedValue({} as any);
    renderDashboard();

    await userEvent.click(await screen.findByRole('button', { name: 'Not a duplicate' }));

    expect(transactionsApi.confirmNotDuplicate).toHaveBeenCalledWith('txn-1');
    await waitFor(() => expect(dashboardApi.summary).toHaveBeenCalledTimes(2));
  });

  it('shows an inline error and re-enables the button when the confirm call fails', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      duplicateTransactionCount: 1,
      detectedDuplicates: [
        { transactionId: 'txn-1', date: '2026-07-10', merchant: 'Swiggy', amount: 500 },
      ],
    }));
    vi.mocked(transactionsApi.confirmNotDuplicate).mockRejectedValue(new Error('network error'));
    renderDashboard();

    const button = await screen.findByRole('button', { name: 'Not a duplicate' });
    await userEvent.click(button);

    expect(await screen.findByText("Couldn't update this transaction. Please try again.")).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Not a duplicate' })).not.toBeDisabled();
  });
});

describe('Dashboard — Categorization Confidence', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue(summary());
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [], page: 0, size: 4, totalElements: 12, totalPages: 3,
    });
    vi.mocked(goalsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(insightsApi.get).mockReset().mockResolvedValue({ sentences: [], movers: [] });
    vi.mocked(userApi.get).mockReset().mockResolvedValue({
      email: 'amy@example.test', fullName: 'Amy Santiago', lowBalanceThreshold: 2000,
      theme: 'system', timezone: 'Asia/Kolkata', phoneNumber: '+919876500000',
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
      onboardingCompleted: true,
    });
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.availableMonths).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.forMonth).mockReset();
    vi.mocked(recurringApi.list).mockReset().mockResolvedValue([]);
  });

  it('stays hidden below the minimum-transactions floor', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      categorizationConfidenceScore: null, categorizationConfidenceTransactionCount: 2,
    }));
    renderDashboard();

    await screen.findByText('Financial Health Score');
    expect(screen.queryByText('Categorization Confidence')).not.toBeInTheDocument();
  });

  it('shows the score, label and transaction count once past the floor', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      categorizationConfidenceScore: 84, categorizationConfidenceTransactionCount: 12,
      reportingMonthIsCurrent: true,
    }));
    renderDashboard();

    const heading = await screen.findByText('Categorization Confidence');
    const card = within(heading.closest('div.bg-card') as HTMLElement);
    expect(card.getByText('84')).toBeInTheDocument();
    expect(card.getByText('Excellent')).toBeInTheDocument();
    expect(card.getByText('Based on 12 automatically categorized transactions this month.')).toBeInTheDocument();
  });

  it('uses singular wording for exactly one categorized transaction', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      categorizationConfidenceScore: 70, categorizationConfidenceTransactionCount: 1,
      categorizationConfidenceMinTransactions: 1,
    }));
    renderDashboard();

    expect(await screen.findByText('Based on 1 automatically categorized transaction this month.')).toBeInTheDocument();
  });

  it('colors a low score as "Needs Attention", not "Excellent"', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      categorizationConfidenceScore: 35, categorizationConfidenceTransactionCount: 10,
    }));
    renderDashboard();

    const heading = await screen.findByText('Categorization Confidence');
    // Scoped to this card: the Financial Health Score card's own range legend always renders all
    // four tier labels (including "Needs Attention") as static text, so an unscoped query is
    // ambiguous whenever that card is also on the page.
    const card = within(heading.closest('div.bg-card') as HTMLElement);
    expect(card.getByText('Needs Attention')).toBeInTheDocument();
  });

  it('stays hidden for a zero-transaction account, same as Financial Health Score', async () => {
    vi.mocked(transactionsApi.search).mockResolvedValue({
      content: [], page: 0, size: 4, totalElements: 0, totalPages: 0,
    });
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      categorizationConfidenceScore: 90, categorizationConfidenceTransactionCount: 10,
    }));
    renderDashboard();

    await screen.findByText('No transactions yet'); // Recent Transactions' own per-section empty state
    expect(screen.queryByText('Categorization Confidence')).not.toBeInTheDocument();
  });
});

describe('Dashboard — comparison gate "Why?" disclosure', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset();
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [], page: 0, size: 4, totalElements: 12, totalPages: 3,
    });
    vi.mocked(goalsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(insightsApi.get).mockReset().mockResolvedValue({ sentences: [], movers: [] });
    vi.mocked(userApi.get).mockReset().mockResolvedValue({
      email: 'amy@example.test', fullName: 'Amy Santiago', lowBalanceThreshold: 2000,
      theme: 'system', timezone: 'Asia/Kolkata', phoneNumber: '+919876500000',
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
      onboardingCompleted: true,
    });
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.availableMonths).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.forMonth).mockReset();
    vi.mocked(recurringApi.list).mockReset().mockResolvedValue([]);
  });

  it('shows a "Why?" toggle on Income/Expenses/Net Savings, but not Balance/Savings Rate, when the previous period reaches before account history', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary());
    vi.mocked(dashboardApi.rangeSummary).mockResolvedValue(rangeSummary({
      incomeDeltaPct: null, expenseDeltaPct: null, netDeltaPct: null,
      comparisonGateReason: 'PRIOR_PERIOD_BEFORE_HISTORY',
    }));
    renderDashboard();

    await screen.findByText('Financial Health Score'); // wait for the dashboard to finish loading
    expect(screen.getAllByRole('button', { name: 'Why?' })).toHaveLength(3);
  });

  it('explains a too-few-transactions gate with the real threshold, not a hardcoded number', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary());
    vi.mocked(dashboardApi.rangeSummary).mockResolvedValue(rangeSummary({
      incomeDeltaPct: null, expenseDeltaPct: null, netDeltaPct: null,
      comparisonGateReason: 'TOO_FEW_PRIOR_TRANSACTIONS', comparisonGateMinTransactions: 5,
    }));
    renderDashboard();

    const [whyButton] = await screen.findAllByRole('button', { name: 'Why?' });
    await userEvent.click(whyButton);

    expect(screen.getAllByText(
      'The previous period has fewer than 5 transactions, too few to compare reliably.'
    ).length).toBeGreaterThan(0);
  });

  it('renders no "Why?" toggle at all when the deltas are real numbers, even with an unrelated stale gate reason', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary());
    vi.mocked(dashboardApi.rangeSummary).mockResolvedValue(rangeSummary({
      incomeDeltaPct: 12.3, expenseDeltaPct: -4.1, netDeltaPct: 8.0,
      comparisonGateReason: null,
    }));
    renderDashboard();

    await screen.findByText('Financial Health Score');
    expect(screen.queryByRole('button', { name: 'Why?' })).not.toBeInTheDocument();
  });

  it('renders no "Why?" toggle for a null delta that is simply a genuinely-zero prior amount, not a gate', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary());
    vi.mocked(dashboardApi.rangeSummary).mockResolvedValue(rangeSummary({
      incomeDeltaPct: null, expenseDeltaPct: null, netDeltaPct: null,
      comparisonGateReason: null,
    }));
    renderDashboard();

    await screen.findByText('Financial Health Score');
    expect(screen.queryByRole('button', { name: 'Why?' })).not.toBeInTheDocument();
  });
});

describe('Dashboard — Expenses card no longer shows category movers', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset();
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [], page: 0, size: 4, totalElements: 12, totalPages: 3,
    });
    vi.mocked(goalsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(insightsApi.get).mockReset().mockResolvedValue({ sentences: [], movers: [] });
    vi.mocked(userApi.get).mockReset().mockResolvedValue({
      email: 'amy@example.test', fullName: 'Amy Santiago', lowBalanceThreshold: 2000,
      theme: 'system', timezone: 'Asia/Kolkata', phoneNumber: '+919876500000',
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
      onboardingCompleted: true,
    });
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.availableMonths).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.forMonth).mockReset();
    vi.mocked(recurringApi.list).mockReset().mockResolvedValue([]);
  });

  // The Expenses card became range-based (dashboardApi.rangeSummary), which doesn't compute a
  // category breakdown -- see DashboardRangeService's own doc comment for why that stayed tied to
  // DashboardService's single reporting month. summary.expenseCategoryMovers is real data the
  // backend still returns, but this card no longer renders it: showing movers explaining a
  // DIFFERENT month's change than the range delta actually displayed would be worse than showing
  // none. This is a regression guard for that deliberate removal, not new behavior under test.
  it('renders no category-mover "Why?" disclosure, even when summary.expenseCategoryMovers has real data', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      expenseCategoryMovers: [
        { category: 'Dining', currentAmount: 8000, priorAmount: 5000, pctChange: 60 },
      ],
    }));
    vi.mocked(dashboardApi.rangeSummary).mockResolvedValue(rangeSummary({ comparisonGateReason: null }));
    renderDashboard();

    await screen.findByText('Financial Health Score');
    expect(screen.queryByText(/Dining/)).not.toBeInTheDocument();
  });
});

describe('Dashboard — Subscriptions & Recurring Payments', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue(summary());
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      // totalElements > 0: this account has real history, matching every scenario these two
      // describe blocks actually test (a populated Health Score, real recurring items) -- 0 would
      // trip D-21's empty-state welcome screen instead of rendering the dashboard under test.
      // content stays [] since neither describe block asserts on the Recent Transactions list.
      content: [], page: 0, size: 4, totalElements: 12, totalPages: 3,
    });
    vi.mocked(goalsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(insightsApi.get).mockReset().mockResolvedValue({ sentences: [], movers: [] });
    vi.mocked(userApi.get).mockReset().mockResolvedValue({
      email: 'amy@example.test', fullName: 'Amy Santiago', lowBalanceThreshold: 2000,
      theme: 'system', timezone: 'Asia/Kolkata', phoneNumber: '+919876500000',
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
      onboardingCompleted: true,
    });
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.availableMonths).mockReset().mockResolvedValue(['2026-08']);
    vi.mocked(reportsApi.forMonth).mockReset().mockResolvedValue({
      month: '2026-08', income: 80000, expense: 45000, categories: [],
    });
    // Only `Date` is faked (not timers) -- RTL's findByText/waitFor poll via real setTimeout,
    // and faking those too would hang every `await screen.findByText(...)` below. Freezing "now"
    // makes these day-count assertions deterministic instead of drifting with whatever moment
    // `npm test` happens to run at.
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 7, 17, 12, 0, 0));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  // Built from local date components, not `.toISOString()` -- Dashboard's own expectedLabel()
  // parses `nextEstimate` as a local date (`new Date(dateStr + 'T00:00:00')`, no 'Z') and compares
  // it against local midnight. A UTC-sliced string here would silently disagree with that by a day
  // whenever the machine's timezone offset straddles midnight differently than UTC does -- which
  // is exactly what made these two tests fail on a real IST machine while passing under UTC CI.
  function daysFromNow(n: number): string {
    const d = new Date();
    d.setDate(d.getDate() + n);
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
  }

  it('renders each recurring item RecurringService already detected, with its own cadence and amount', async () => {
    vi.mocked(recurringApi.list).mockResolvedValue([
      { merchant: 'Netflix', label: 'Monthly', averageAmount: 649, occurrences: 4, lastDate: '2026-07-24', nextEstimate: daysFromNow(5) },
    ]);
    renderDashboard();

    expect(await screen.findByText('Subscriptions & Recurring Payments')).toBeInTheDocument();
    expect(screen.getByText('Netflix')).toBeInTheDocument();
    expect(screen.getByText('Monthly')).toBeInTheDocument();
    expect(screen.getByText('₹649')).toBeInTheDocument();
    expect(screen.getByText(/expected in 5 days/)).toBeInTheDocument();
  });

  it('shows no card at all when nothing is recurring, rather than an empty section', async () => {
    vi.mocked(recurringApi.list).mockResolvedValue([]);
    renderDashboard();

    await screen.findByText('Financial Health Score'); // page has finished loading
    expect(screen.queryByText('Subscriptions & Recurring Payments')).not.toBeInTheDocument();
  });

  it("says 'expected today' rather than a day count for a projection landing on the current date", async () => {
    vi.mocked(recurringApi.list).mockResolvedValue([
      { merchant: 'Spotify', label: 'Monthly', averageAmount: 119, occurrences: 3, lastDate: '2026-07-01', nextEstimate: daysFromNow(0) },
    ]);
    renderDashboard();

    expect(await screen.findByText('expected today')).toBeInTheDocument();
  });

  it('says "expected around" rather than a negative day count for a prediction already in the past', async () => {
    vi.mocked(recurringApi.list).mockResolvedValue([
      { merchant: 'Old Gym Membership', label: 'Monthly', averageAmount: 999, occurrences: 5, lastDate: '2026-05-01', nextEstimate: daysFromNow(-10) },
    ]);
    renderDashboard();

    expect(await screen.findByText(/expected around/)).toBeInTheDocument();
  });

  it('dismisses a wrongly-detected group and removes it from the list', async () => {
    vi.mocked(recurringApi.list).mockResolvedValue([
      { merchant: 'Netflix', label: 'Monthly', averageAmount: 649, occurrences: 4, lastDate: '2026-07-24', nextEstimate: daysFromNow(5) },
    ]);
    vi.mocked(recurringApi.dismiss).mockResolvedValue(undefined);
    renderDashboard();
    await screen.findByText('Netflix');

    await userEvent.click(screen.getByRole('button', { name: 'Not recurring: dismiss Netflix' }));

    expect(recurringApi.dismiss).toHaveBeenCalledWith('Netflix');
    await waitFor(() => expect(screen.queryByText('Netflix')).not.toBeInTheDocument());
  });
});

// D-21: "First Run Experience." A zero-transaction account (brand-new signup, or an existing
// account that connected Gmail/created an account but never got any data in) sees the full
// dashboard shell with a friendly empty state PER SECTION, rather than the original single-gate
// welcome screen this redesign replaced (which hid the whole page behind one "pick a path" screen
// before showing anything else) or the earlier bare "₹0 everywhere" fallback before that.
describe('Dashboard — per-section empty states', () => {
  // A real bank shape, not `as any` -- BankLogo reads bank.id/officialName/websiteUrl directly,
  // and the Accounts Overview card renders it for whatever REAL accounts exist regardless of
  // whether transactions are empty (see the next describe block below), so an incomplete fixture
  // here would crash exactly the scenario this file needs to prove works.
  const BANK = {
    id: 'hdfc', officialName: 'HDFC Bank', shortName: 'HDFC', colorHex: '#004c8f', initials: 'HD',
    logoPath: '/banks/hdfc.svg', category: 'PRIVATE' as const, websiteUrl: 'https://hdfcbank.com',
    ifscPrefix: 'HDFC', supportedAccountTypes: ['SAVINGS'],
  };
  const ACCOUNT = { id: 'acct-1', name: 'HDFC Savings', accountType: 'SAVINGS' as const, balance: 0, bank: BANK } as any;

  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue(summary());
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [], page: 0, size: 4, totalElements: 0, totalPages: 0,
    });
    vi.mocked(transactionsApi.create).mockReset();
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([
      { id: 'cat-1', name: 'Groceries' } as any,
      { id: 'cat-2', name: 'Salary' } as any,
    ]);
    vi.mocked(goalsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(insightsApi.get).mockReset().mockResolvedValue({ sentences: [], movers: [] });
    vi.mocked(userApi.get).mockReset().mockResolvedValue({
      email: 'amy@example.test', fullName: 'Amy Santiago', lowBalanceThreshold: 2000,
      theme: 'system', timezone: 'Asia/Kolkata', phoneNumber: '+919876500000',
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
      onboardingCompleted: true,
    });
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.availableMonths).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.forMonth).mockReset();
    vi.mocked(recurringApi.list).mockReset().mockResolvedValue([]);
  });

  it('shows a friendly empty state per section, and hides Financial Health Score, when there are zero transactions', async () => {
    renderDashboard();

    // The shell itself is still here -- the greeting, the KPI row -- unlike the single-gate
    // welcome screen this replaced, which hid all of it behind one page.
    const heading = await screen.findByRole('heading', { level: 1 });
    expect(heading.textContent).toMatch(/there/);
    expect(heading.textContent).toMatch(/👋/);
    expect(screen.getByText('Balance')).toBeInTheDocument();

    expect(screen.getByText('No data yet')).toBeInTheDocument(); // Cash Flow
    expect(screen.getByText('No spending data yet')).toBeInTheDocument(); // Spending Breakdown
    expect(screen.getByText('No accounts yet')).toBeInTheDocument();
    expect(screen.getByText('No transactions yet')).toBeInTheDocument();
    expect(screen.getByText('No budgets set')).toBeInTheDocument();
    expect(screen.getByText('No goals yet')).toBeInTheDocument();
    // A score computed from zero transactions has nothing real behind it.
    expect(screen.queryByText('Financial Health Score')).not.toBeInTheDocument();
  });

  /**
   * Bug 44. The empty-state gate above only catches categoryEntries.length === 0 -- a completely
   * empty spendByCategory. It doesn't catch categories that exist but all sum to zero, which skips
   * the empty state, leaves totalSpend at 0, and divides val / totalSpend as 0/0 -- rendering the
   * literal string "NaN%" per category instead of a sane 0%.
   */
  it('shows 0%, not NaN%, when every category in the breakdown has a zero amount', async () => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue(
      summary({ spendByCategory: { Groceries: 0, Rent: 0 } })
    );

    renderDashboard();

    await screen.findByText('Groceries');
    expect(screen.queryByText(/NaN/)).not.toBeInTheDocument();
    expect(screen.getAllByText('0%')).toHaveLength(2);
  });

  it('still shows AI Insights and Quick Actions -- generic tips, not hidden entirely', async () => {
    renderDashboard();
    await screen.findByText('No transactions yet');

    expect(screen.getByText('AI Insights')).toBeInTheDocument();
    expect(screen.getByText(/upload or import more transactions/i)).toBeInTheDocument();
    expect(screen.getByText('Quick Actions')).toBeInTheDocument();
  });

  it("shows a real account in Accounts Overview even when transactions are empty -- it's not gated on the same isEmpty flag", async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([ACCOUNT]);
    renderDashboard();

    expect(await screen.findByText('HDFC Savings')).toBeInTheDocument();
    expect(screen.queryByText('No accounts yet')).not.toBeInTheDocument();
    // Recent Transactions is a separate section with its own, still-empty data.
    expect(screen.getByText('No transactions yet')).toBeInTheDocument();
  });

  it('links the Cash Flow empty state\'s Import Statement CTA to the existing Import page', async () => {
    renderDashboard();
    // Scoped to the Cash Flow card -- "Import Statement" is also Quick Actions' own link name,
    // both visible on screen at once.
    const cashFlowCard = within((await screen.findByText('No data yet')).closest('.bg-card') as HTMLElement);

    expect(cashFlowCard.getByRole('link', { name: /import statement/i })).toHaveAttribute('href', '/app/import');
  });

  it('opens the Add Transaction modal from Recent Transactions\' empty-state CTA', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([ACCOUNT]);
    const user = userEvent.setup();
    renderDashboard();
    await user.click(await screen.findByRole('button', { name: /\+ add transaction/i }));

    expect(await screen.findByRole('heading', { name: /add transaction/i })).toBeInTheDocument();
  });

  it('directs to Setup instead of a broken form when there are no accounts to attach a transaction to', async () => {
    const user = userEvent.setup();
    renderDashboard();
    await user.click(await screen.findByRole('button', { name: /\+ add transaction/i }));

    expect(await screen.findByText(/you'll need an account/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /add an account/i })).toHaveAttribute('href', '/app/setup');
    expect(screen.queryByLabelText(/description/i)).not.toBeInTheDocument();
  });

  it('creates a transaction with the form values and closes the modal on success', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([ACCOUNT]);
    vi.mocked(transactionsApi.create).mockResolvedValue({} as any);
    const user = userEvent.setup();
    renderDashboard();
    await user.click(await screen.findByRole('button', { name: /\+ add transaction/i }));
    // Scoped to the modal (not the whole screen) from here on -- "Add transaction" is also the
    // still-visible Quick Actions button's own name once the modal is open on top of it.
    const modal = within((await screen.findByRole('heading', { name: /add transaction/i })).closest('.bg-card') as HTMLElement);

    await user.type(modal.getByLabelText(/description/i), 'Coffee with a friend');
    await user.type(modal.getByLabelText(/amount/i), '250');
    await user.selectOptions(modal.getByLabelText(/category/i), 'Groceries');
    await user.click(modal.getByRole('button', { name: /^add transaction$/i }));

    await waitFor(() => expect(transactionsApi.create).toHaveBeenCalledWith(
      expect.objectContaining({
        accountId: 'acct-1', description: 'Coffee with a friend', amount: 250, type: 'EXPENSE', categoryName: 'Groceries',
        // Explicit [], not omitted -- Transaction.tags is typed non-nullable everywhere it's read.
        tags: [],
      })
    ));
    await waitFor(() => expect(screen.queryByRole('heading', { name: /add transaction/i })).not.toBeInTheDocument());
  });

  it('keeps the required fields disabled from submitting an empty form', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([ACCOUNT]);
    const user = userEvent.setup();
    renderDashboard();
    await user.click(await screen.findByRole('button', { name: /\+ add transaction/i }));
    const modal = within((await screen.findByRole('heading', { name: /add transaction/i })).closest('.bg-card') as HTMLElement);

    expect(modal.getByRole('button', { name: /^add transaction$/i })).toBeDisabled();
  });

  it('shows the backend error inline and keeps the modal open on failure', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([ACCOUNT]);
    vi.mocked(transactionsApi.create).mockRejectedValue({
      response: { data: { message: 'That amount is not valid.' } },
    });
    const user = userEvent.setup();
    renderDashboard();
    await user.click(await screen.findByRole('button', { name: /\+ add transaction/i }));
    const modal = within((await screen.findByRole('heading', { name: /add transaction/i })).closest('.bg-card') as HTMLElement);

    await user.type(modal.getByLabelText(/description/i), 'Coffee');
    await user.type(modal.getByLabelText(/amount/i), '250');
    await user.click(modal.getByRole('button', { name: /^add transaction$/i }));

    expect(await screen.findByText('That amount is not valid.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /add transaction/i })).toBeInTheDocument();
  });

  it('closes without creating anything on Cancel', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([ACCOUNT]);
    const user = userEvent.setup();
    renderDashboard();
    await user.click(await screen.findByRole('button', { name: /\+ add transaction/i }));
    await screen.findByRole('heading', { name: /add transaction/i });

    await user.click(screen.getByRole('button', { name: /^cancel$/i }));

    expect(screen.queryByRole('heading', { name: /add transaction/i })).not.toBeInTheDocument();
    expect(transactionsApi.create).not.toHaveBeenCalled();
  });
});

// Animation-polish roadmap Phase 2 (§3 priority 1): the old page-level gate ANDed four queries
// together. These tests cover the two things that fix had to get right: `isEmpty` (which several
// isEmpty-gated sections depend on, not just Recent Transactions' own card) must never be computed
// from an unresolved recentTxnsQ, and accounts/goals/budgets -- genuinely independent of anything
// outside their own cards -- must be able to render their own section while still loading, without
// blocking the rest of the page.
describe('Dashboard — Phase 2 section-scoped loading', () => {
  function pending<T>() {
    let resolve!: (value: T) => void;
    const promise = new Promise<T>((r) => {
      resolve = r;
    });
    return { promise, resolve };
  }

  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue(summary());
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(insightsApi.get).mockReset().mockResolvedValue({ sentences: [], movers: [] });
    vi.mocked(userApi.get).mockReset().mockResolvedValue({
      email: 'amy@example.test', fullName: 'Amy Santiago', lowBalanceThreshold: 2000,
      theme: 'system', timezone: 'Asia/Kolkata', phoneNumber: '+919876500000',
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
      onboardingCompleted: true,
    });
    vi.mocked(reportsApi.availableMonths).mockReset().mockResolvedValue(['2026-08']);
    vi.mocked(reportsApi.forMonth).mockReset().mockResolvedValue({
      month: '2026-08', income: 80000, expense: 45000, categories: [],
    });
    vi.mocked(recurringApi.list).mockReset().mockResolvedValue([]);
  });

  it('never computes isEmpty (which several sections key off) from an unresolved recentTxnsQ', async () => {
    // summary resolves immediately; recentTxnsQ deliberately never resolves during this test. If
    // isEmpty were computed from recentTxnsQ.data defaulting to undefined, the page would render
    // past the (would-be) gate and wrongly hide Financial Health Score for an account that
    // actually has plenty of history -- recentTxnsQ staying blocking is what prevents that.
    const recentTxns = pending<any>();
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(transactionsApi.search).mockReset().mockReturnValue(recentTxns.promise);
    vi.mocked(goalsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);

    renderDashboard();

    // Give summary's own resolution a tick to land -- Financial Health Score must still not
    // appear, because the page itself hasn't rendered past the blocking gate yet.
    await new Promise((r) => setTimeout(r, 0));
    expect(screen.queryByText('Financial Health Score')).not.toBeInTheDocument();
    // The assertion above alone doesn't discriminate this fix from the exact bug it guards
    // against: a broken implementation that decoupled recentTxnsQ would ALSO render past the gate
    // with isEmpty wrongly defaulting to true, which hides Financial Health Score for a different
    // (wrong) reason -- that assertion would pass either way. What actually proves the page
    // hasn't rendered at all is a page-shell element that ISN'T gated by isEmpty, like Accounts
    // Overview's header -- present the moment the page passes the blocking gate, regardless of
    // isEmpty. If summaryQ.isLoading alone (not summaryQ || recentTxnsQ) were the real gate, this
    // header would already be here even though recentTxnsQ hasn't resolved.
    expect(screen.queryByText('Accounts Overview')).not.toBeInTheDocument();

    recentTxns.resolve({ content: [], page: 0, size: 4, totalElements: 12, totalPages: 3 });
    expect(await screen.findByText('Financial Health Score')).toBeInTheDocument();
    expect(screen.getByText('Accounts Overview')).toBeInTheDocument();
  });

  it('shows Accounts Overview once loaded without waiting on Budgets/Goals, and vice versa', async () => {
    // Everything else resolves immediately except accountsQ, which resolves after the rest of the
    // page is already up -- proving accounts/goals/budgets no longer share one blocking gate.
    const accounts = pending<any>();
    vi.mocked(accountsApi.list).mockReset().mockReturnValue(accounts.promise);
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [], page: 0, size: 4, totalElements: 12, totalPages: 3,
    });
    vi.mocked(goalsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);

    renderDashboard();

    // The page itself (gated only on summary+recentTxns, both resolved) is already up, including
    // the Accounts Overview section header -- it just hasn't gotten its data yet.
    expect(await screen.findByText('Accounts Overview')).toBeInTheDocument();
    expect(screen.queryByText('No accounts yet')).not.toBeInTheDocument();

    accounts.resolve([]);
    expect(await screen.findByText('No accounts yet')).toBeInTheDocument();
  });

  it('shows real budget rows once budgetsQ resolves, without having claimed "No budgets set" first', async () => {
    const budgets = pending<any>();
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [], page: 0, size: 4, totalElements: 12, totalPages: 3,
    });
    vi.mocked(goalsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(budgetsApi.list).mockReset().mockReturnValue(budgets.promise);

    renderDashboard();

    expect(await screen.findByText('Budget Progress')).toBeInTheDocument();
    expect(screen.queryByText('No budgets set')).not.toBeInTheDocument();

    budgets.resolve([{ id: 'b1', categoryName: 'Groceries', monthlyLimit: 8000, spentThisMonth: 2000 }]);
    expect(await screen.findByText('Groceries')).toBeInTheDocument();
    expect(screen.queryByText('No budgets set')).not.toBeInTheDocument();
  });

  it('shows a real goal once goalsQ resolves, without having claimed "No goals yet" first', async () => {
    // Same pattern as the accounts/budgets tests above, for the third independently-loading
    // section -- flagged by the Phase 2 adversarial review as a real coverage gap (accounts and
    // budgets each got a dedicated race test, goals didn't, even though all three follow the
    // identical isLoading-gated pattern).
    const goals = pending<any>();
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [], page: 0, size: 4, totalElements: 12, totalPages: 3,
    });
    vi.mocked(goalsApi.list).mockReset().mockReturnValue(goals.promise);
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);

    renderDashboard();

    expect(await screen.findByText('Goals')).toBeInTheDocument();
    expect(screen.queryByText('No goals yet')).not.toBeInTheDocument();

    goals.resolve([{ id: 'g1', name: 'Emergency Fund', targetAmount: 100000, currentAmount: 25000 }]);
    expect(await screen.findByText('Emergency Fund')).toBeInTheDocument();
    expect(screen.queryByText('No goals yet')).not.toBeInTheDocument();
  });
});

describe('Dashboard — unified date-range picker', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue(summary());
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [], page: 0, size: 4, totalElements: 12, totalPages: 3,
    });
    vi.mocked(goalsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(insightsApi.get).mockReset().mockResolvedValue({ sentences: [], movers: [] });
    vi.mocked(userApi.get).mockReset().mockResolvedValue({
      email: 'amy@example.test', fullName: 'Amy Santiago', lowBalanceThreshold: 2000,
      theme: 'system', timezone: 'Asia/Kolkata', phoneNumber: '+919876500000',
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
      onboardingCompleted: true,
    });
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.availableMonths).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.forMonth).mockReset();
    vi.mocked(recurringApi.list).mockReset().mockResolvedValue([]);
  });

  it('renders the 5 KPI cards from dashboardApi.rangeSummary, labelled with the default Last 6 Months range', async () => {
    renderDashboard();

    expect(await screen.findByText('Balance')).toBeInTheDocument();
    expect(screen.getByText('₹50,000')).toBeInTheDocument();
    expect(screen.getByText('Income (Last 6 Months)')).toBeInTheDocument();
    expect(screen.getByText('₹80,000')).toBeInTheDocument();
    expect(screen.getByText('Expenses (Last 6 Months)')).toBeInTheDocument();
    expect(screen.getByText('₹45,000')).toBeInTheDocument();
    expect(screen.getByText('Net Savings (Last 6 Months)')).toBeInTheDocument();
    expect(screen.getByText('₹35,000')).toBeInTheDocument();
    expect(screen.getByText('Savings Rate (Last 6 Months)')).toBeInTheDocument();
    expect(screen.getByText('44%')).toBeInTheDocument();
  });

  it('Balance compares "vs previous period" while Income/Expenses/Net Savings compare "vs previous N months"', async () => {
    vi.mocked(dashboardApi.rangeSummary).mockResolvedValue(rangeSummary({
      incomeDeltaPct: 20, expenseDeltaPct: 10, netDeltaPct: 30, balanceDeltaPct: 5,
      previousBalance: 47500, previousBalanceAsOf: '2026-02-28',
    }));
    renderDashboard();

    await screen.findByText('Balance');
    expect(screen.getByText('vs previous period')).toBeInTheDocument();
    // Income, Expenses, and Net Savings each have a real delta here, so MetricCard renders
    // deltaLabel in its own isolated span (elevated variant, hasDelta branch) -- an exact text
    // match. Savings Rate shares the same deltaLabel but has no real delta, so MetricCard folds
    // it into a combined "— vs previous 6 months" string instead (the !hasDelta branch), which
    // does not match this exact query -- hence 3, not 4.
    expect(screen.getAllByText('vs previous 6 months').length).toBe(3);
  });

  it('shows the balance-specific gate reason only on the Balance card, independent of the income/expense/net gate', async () => {
    vi.mocked(dashboardApi.rangeSummary).mockResolvedValue(rangeSummary({
      comparisonGateReason: null, // income/expense/net compare fine
      previousBalance: null, balanceGateReason: 'NO_SNAPSHOT_AT_PRIOR_DATE', // balance can't
    }));
    renderDashboard();

    const whyButton = await screen.findByRole('button', { name: 'Why?' });
    await userEvent.click(whyButton);
    expect(screen.getByText('No balance snapshot exists far enough back to compare against.')).toBeInTheDocument();
  });

  it('re-fetches rangeSummary with the newly selected preset when the range picker changes', async () => {
    // Echoes back whichever rangeType was actually requested, so the re-fetch after switching
    // the picker is visible both in the call args AND in the label the response then drives.
    vi.mocked(dashboardApi.rangeSummary).mockImplementation(async (rangeType) => rangeSummary({ rangeType }));
    const user = userEvent.setup();
    renderDashboard();
    await screen.findByText('Balance');

    vi.mocked(dashboardApi.rangeSummary).mockClear();
    await user.selectOptions(screen.getByDisplayValue('Last 6 Months'), 'LAST_12_MONTHS');

    await waitFor(() => {
      expect(dashboardApi.rangeSummary).toHaveBeenCalledWith('LAST_12_MONTHS', undefined, undefined);
    });
    expect(await screen.findByText('Income (Last 12 Months)')).toBeInTheDocument();
  });

  it('selecting Custom reveals two date inputs and, once both are filled, fetches rangeSummary with them', async () => {
    const user = userEvent.setup();
    renderDashboard();
    await screen.findByText('Balance');

    await user.selectOptions(screen.getByDisplayValue('Last 6 Months'), 'CUSTOM');
    const startInput = screen.getByLabelText('Custom range start date');
    const endInput = screen.getByLabelText('Custom range end date');

    vi.mocked(dashboardApi.rangeSummary).mockClear();
    // Native date inputs don't take character-by-character typing reliably in jsdom -- fireEvent
    // with the full value is the established pattern this codebase already uses (see Ledger.tsx's
    // own date-range filter tests).
    fireEvent.change(startInput, { target: { value: '2026-03-15' } });
    fireEvent.change(endInput, { target: { value: '2026-08-20' } });

    await waitFor(() => {
      expect(dashboardApi.rangeSummary).toHaveBeenCalledWith('CUSTOM', '2026-03-15', '2026-08-20');
    });
  });
});
