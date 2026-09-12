import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { motion, useReducedMotion } from 'framer-motion';
import { Line, Doughnut } from 'react-chartjs-2';
import {
  Chart as ChartJS, ArcElement, LineElement, PointElement, LinearScale, CategoryScale, Tooltip, Legend, Filler,
} from 'chart.js';
import type { Plugin } from 'chart.js';
import {
  Wallet, ArrowDownCircle, ArrowUpCircle, PieChart,
  ShoppingBag, Sparkles, Plus, PiggyBank, TrendingUp, TrendingDown, Target, ShieldCheck, Repeat,
  UploadCloud, Receipt, LineChart as LineChartIcon, Mail, AlertTriangle, ListChecks, Copy, BadgeCheck,
  ChevronDown, X,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { BankLogo } from '../components/BankLogo';
import { MerchantLogo } from '../components/MerchantLogo';
import { AddTransactionModal } from '../components/AddTransactionModal';
import { FinoraCard, MetricCard, EmptyState, SectionHeader, QuickActionCard, ChartContainer, Badge, baseChartOptions, Button, Skeleton, HealthScoreGauge, HealthScoreRangeLegend, HealthScoreSparkline } from '../design-system';
import { useDelayedLoading } from '../hooks/useDelayedLoading';
import { ChecklistWidget } from '../onboarding/ChecklistWidget';
import { JourneyWidget } from '../components/JourneyWidget';
import { ICON_COMPONENTS, COLOR_HEX } from '../lib/categoryIcons';
import {
  dashboardApi, accountsApi, transactionsApi, categoriesApi, goalsApi, insightsApi, userApi, budgetsApi, reportsApi, recurringApi,
  type CategoryOption, type RecurringItem,
} from '../api/endpoints';
import type { DashboardRangeType } from '../types';

ChartJS.register(ArcElement, LineElement, PointElement, LinearScale, CategoryScale, Tooltip, Legend, Filler);

// react-router's Link wrapped for framer-motion gesture props -- same technique as
// QuickActionCard.tsx, used here for the floating action button (the other named hoverScale
// adopter in the animation-polish roadmap alongside Quick Action tiles).
const MotionLink = motion.create(Link);

function fmt(n: number) {
  // Negative amounts (e.g. a month where spend exceeded income) must render as "-₹500",
  // not "₹-500" -- string concatenation put the currency symbol before the sign.
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN', { maximumFractionDigits: 2 });
}

// Reads the current hour in the user's chosen timezone (see Settings) rather than the
// browser's local clock — the two only differ when someone's system clock is set to a
// different zone than the one they actually keep finance-app hours in, but when they do
// differ this used to just be wrong (always whatever the OS thought "now" was), including a
// "Good night" band that a plain morning/afternoon/evening split never had at all.
function greeting(timezone: string | undefined) {
  let hourStr: string;
  try {
    hourStr = new Intl.DateTimeFormat('en-US', { hour: 'numeric', hour12: false, timeZone: timezone || undefined }).format(new Date());
  } catch {
    hourStr = String(new Date().getHours());
  }
  const h = parseInt(hourStr, 10) % 24;
  if (h < 5) return 'Good night';
  if (h < 12) return 'Good morning';
  if (h < 17) return 'Good afternoon';
  if (h < 21) return 'Good evening';
  return 'Good night';
}

// Same three-tier thresholds DashboardService.computeHealthScore already labels server-side
// (Excellent/Good/Fair/Needs Attention at 80/60/40) -- this just maps the label to a color rather
// than re-deriving the cutoffs from the raw score, so the two can't drift apart.
function healthColor(label: string): string {
  switch (label) {
    case 'Excellent': return 'text-success';
    case 'Good': return 'text-primary';
    case 'Fair': return 'text-warning';
    default: return 'text-danger';
  }
}
// Same 80/60/40 cutoffs and label vocabulary as the health score above (Excellent/Good/Fair/Needs
// Attention), reused rather than invented fresh -- Categorization Confidence is on the same 0-100
// scale, and a second vocabulary for the same range would just be one more thing to learn.
function scoreLabel(score: number): string {
  if (score >= 80) return 'Excellent';
  if (score >= 60) return 'Good';
  if (score >= 40) return 'Fair';
  return 'Needs Attention';
}

// Deterministic, per-factor -- never AI-generated prose. Each threshold is the exact 80-point
// cutoff computeTopOpportunity (backend) and scoreLabel (above) both already use, so a card never
// tells a user to do something their own score already shows they've done.
function healthImprovementSuggestion(factor: string, score: number): string {
  const good = score >= 80;
  switch (factor) {
    case 'Savings Rate':
      return good ? "You're saving well — keep it up." : 'Aim to save at least 24% of your income each month.';
    case 'Debt Score':
      // "in good shape" rather than naming utilization specifically -- this also covers the
      // common case of a debtScore=100 from having no credit cards at all (see
      // DashboardService's own "You have no credit cards on file." detail text for that case),
      // where a message about utilization would read oddly paired with it.
      return good ? "You're managing debt well." : 'Pay down credit card balances to bring utilization under 20%.';
    case 'Emergency Fund':
      return good ? 'You have a solid safety net.' : 'Build your emergency fund toward 4-5 months of expenses.';
    case 'Spend Consistency':
      return good ? 'Your spending has been consistent.' : 'Try to keep monthly spending within about 20% of your average.';
    case 'Cash Flow Stability':
      return good ? 'Your cash flow has been stable.' : 'Work toward income meeting or exceeding expenses most months.';
    default:
      return '';
  }
}

// Reuses the same 80/60/40 cutoffs as healthColor/scoreLabel -- the Badge design-system component
// already covers exactly this vocabulary (see Budgets' status pills).
function badgeToneForScore(score: number): 'success' | 'primary' | 'warning' | 'danger' {
  if (score >= 80) return 'success';
  if (score >= 60) return 'primary';
  if (score >= 40) return 'warning';
  return 'danger';
}

// Counts from whatever it was last showing (0 on first mount) up to `target` over `durationMs`,
// easing out rather than a linear ramp -- same easeOutCubic curve and rAF-loop shape as the
// landing page's HealthScoreRing, but standalone here since that component's counting logic is
// coupled to its own staged-reveal sequencing (step/totalSteps props) this page has no use for.
// Skips straight to `target` under prefers-reduced-motion, matching every other animation on
// this page.
function useCountUp(target: number, durationMs = 900): number {
  const [display, setDisplay] = useState(0);
  const fromRef = useRef(0);
  useEffect(() => {
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    if (reduced) {
      setDisplay(target);
      fromRef.current = target;
      return;
    }
    const from = fromRef.current;
    const start = performance.now();
    let frame = 0;
    const tick = (now: number) => {
      // Clamped on BOTH ends, not just the upper bound: rAF's callback timestamp is supposed to
      // be performance.now()-comparable, but jsdom's rAF polyfill doesn't reliably honor that --
      // observed `now` values earlier than the `start` captured via a direct performance.now()
      // call, which without a lower clamp sends `t` negative and the easing formula wildly out of
      // [0,1] (a real bug this surfaced, not a test-only quirk: any clock irregularity, not just
      // jsdom's, should degrade to "stay at the start/end value" rather than overshoot past it).
      const t = Math.max(0, Math.min(1, (now - start) / durationMs));
      const eased = 1 - Math.pow(1 - t, 3);
      setDisplay(Math.round(from + (target - from) * eased));
      if (t < 1) {
        frame = requestAnimationFrame(tick);
      } else {
        fromRef.current = target;
      }
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [target, durationMs]);
  return display;
}

// A dedicated leaf component, not just `{useCountUp(score)}` inlined into Dashboard's own JSX --
// useCountUp's rAF loop calls setState up to ~60 times/sec while the count-up runs, and if that
// state lived in the Dashboard component itself, every one of those updates would re-render the
// entire page (every KPI card, chart, and list) for the ~900ms the animation is active, not just
// this one number.
function AnimatedHealthScoreNumber({ score, className }: { score: number; className: string }) {
  const animated = useCountUp(score);
  return <p className={className}>{animated}</p>;
}

// Unified range control -- drives BOTH the top KPI cards (Total Balance/Income/Expenses/Net
// Savings/Savings Rate, via dashboardApi.rangeSummary) and the Cash Flow chart below. Presets are
// calendar-month aligned; CUSTOM is an arbitrary [start, end] the user picks with native date
// inputs (see Ledger.tsx/StatementHistory.tsx for the same input type already used elsewhere in
// this app -- no date-picker library needed).
const RANGE_MONTHS: Record<Exclude<DashboardRangeType, 'CUSTOM'>, number> = {
  LAST_3_MONTHS: 3, LAST_6_MONTHS: 6, LAST_12_MONTHS: 12, LAST_24_MONTHS: 24,
};
const RANGE_TYPE_LABEL: Record<DashboardRangeType, string> = {
  LAST_3_MONTHS: 'Last 3 Months', LAST_6_MONTHS: 'Last 6 Months', LAST_12_MONTHS: 'Last 12 Months',
  LAST_24_MONTHS: 'Last 24 Months', CUSTOM: 'Custom',
};

function monthLabel(monthStr: string) {
  const [y, m] = monthStr.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'short', year: '2-digit' });
}

// "Mar 1 - Aug 31" -- dateStr is a plain ISO date (YYYY-MM-DD) from the backend, parsed with an
// explicit local midnight (see expectedLabel above for why: bare `new Date(dateStr)` parses an
// ISO date as UTC, which can roll it back a calendar day in a timezone behind UTC).
function dayLabel(dateStr: string) {
  return new Date(dateStr + 'T00:00:00').toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}

// RecurringDto.nextEstimate is a projection from the merchant's own historical gap
// (lastDate + averageGap), never a confirmed bill date -- "expected", not "due", stays honest
// about that. A past-due estimate (the pattern predicted a charge that hasn't shown up yet, e.g.
// a cancelled subscription with no new import since) reads as "expected around <date>" rather
// than a nonsensical negative day count.
function expectedLabel(dateStr: string): string {
  const days = Math.round((new Date(dateStr + 'T00:00:00').getTime() - new Date().setHours(0, 0, 0, 0)) / 86_400_000);
  const date = new Date(dateStr + 'T00:00:00').toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
  if (days < 0) return `expected around ${date}`;
  if (days === 0) return 'expected today';
  if (days === 1) return 'expected tomorrow';
  return `expected in ${days} days (${date})`;
}

export default function Dashboard() {
  const { fullName } = useAuth();
  const queryClient = useQueryClient();
  const [dashboardRange, setDashboardRange] = useState<DashboardRangeType>('LAST_6_MONTHS');
  // Only meaningful (and only sent to the server) when dashboardRange === 'CUSTOM' -- see the
  // range-picker UI below, which shows these two native date inputs only in that case.
  const [customStart, setCustomStart] = useState('');
  const [customEnd, setCustomEnd] = useState('');
  const [showAddModal, setShowAddModal] = useState(false);
  // Spending Breakdown's donut: which category (by index into categoryEntries) is currently
  // hovered, or null when the pointer isn't over any slice -- drives the center label directly
  // (see the donut's own comment) instead of Chart.js's floating tooltip, which had nowhere to
  // render on a donut this small without overlapping that same center label.
  const [hoveredCategoryIndex, setHoveredCategoryIndex] = useState<number | null>(null);
  // Collapse-only, not persisted: the banner already stops appearing entirely once
  // summary.limitedHistory flips false server-side (3+ months of history), so there's nothing to
  // remember across visits -- collapsing just quiets the detail text within the current session.
  const [historyBannerCollapsed, setHistoryBannerCollapsed] = useState(false);
  // Recent Transactions' icon/color used to key off categoryName against a 4-entry hardcoded map
  // (predates custom categories, and covered only 4 of the 25 default categories even before user-
  // created ones existed). Looked up by categoryId instead so every category -- default or custom
  // -- renders its own real, backend-assigned icon/color token.
  //
  // On the shared ['categories'] key rather than its own useState+useEffect: this page also
  // renders AskOnceCard and MerchantGroupReviewCard, each of which mounts CategoryComboboxes
  // reading the same key, so one fetch serves all of them. It also picks up react-query's error
  // handling, replacing a bare .then() with no .catch() at all -- a rejected promise there was an
  // unhandled rejection, and the icons simply fell back to the default forever with no signal.
  const categoriesQ = useQuery({ queryKey: ['categories'], queryFn: () => categoriesApi.list(), retry: false });
  const categoriesById: Record<string, CategoryOption> = useMemo(
    () => Object.fromEntries((categoriesQ.data ?? []).map((c) => [c.id, c])),
    [categoriesQ.data],
  );

  const [confirmingDuplicateId, setConfirmingDuplicateId] = useState<string | null>(null);
  const [duplicateConfirmError, setDuplicateConfirmError] = useState<string | null>(null);

  // BH-027's own service-layer doc comment: "the user asked for this row to count, so it counts
  // now." transactionsApi.confirmNotDuplicate already existed and already worked -- this is the
  // first UI anywhere in the product that calls it. dashboard-summary is invalidated so the card
  // (and every KPI the reinstated transaction now counts toward) reflects the change immediately;
  // recent-transactions/transactions too, since the row itself just changed status.
  async function handleConfirmNotDuplicate(transactionId: string) {
    setConfirmingDuplicateId(transactionId);
    setDuplicateConfirmError(null);
    try {
      await transactionsApi.confirmNotDuplicate(transactionId);
      void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
      void queryClient.invalidateQueries({ queryKey: ['recent-transactions'] });
      void queryClient.invalidateQueries({ queryKey: ['transactions'] });
    } catch {
      setDuplicateConfirmError("Couldn't update this transaction. Please try again.");
    } finally {
      setConfirmingDuplicateId(null);
    }
  }

  function onTransactionAdded() {
    setShowAddModal(false);
    void queryClient.invalidateQueries({ queryKey: ['recent-transactions'] });
    void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
    void queryClient.invalidateQueries({ queryKey: ['accounts'] });
    void queryClient.invalidateQueries({ queryKey: ['transactions'] });
  }

  // useQueries runs all these independently (each gets its own cache entry, own retry/error
  // handling, own loading state) rather than one big Promise.all where a single failure
  // blanks the whole dashboard — the insights query already tolerated failure via .catch(),
  // this generalizes that to every query on the page.
  const [summaryQ, accountsQ, recentTxnsQ, goalsQ, insightsQ, settingsQ, budgetsQ, recurringQ] = useQueries({
    queries: [
      { queryKey: ['dashboard-summary'], queryFn: () => dashboardApi.summary() },
      { queryKey: ['accounts'], queryFn: () => accountsApi.list() },
      { queryKey: ['recent-transactions'], queryFn: () => transactionsApi.search({ page: 0, size: 4, sortField: 'date', sortDir: 'desc' }) },
      { queryKey: ['goals'], queryFn: () => goalsApi.list() },
      { queryKey: ['insights'], queryFn: () => insightsApi.get(), retry: false },
      { queryKey: ['user-settings'], queryFn: () => userApi.get() },
      { queryKey: ['budgets'], queryFn: () => budgetsApi.list() },
      { queryKey: ['recurring'], queryFn: () => recurringApi.list(), retry: false },
    ],
  });

  // A wrongly-detected group (a one-off large purchase RecurringService mistook for a
  // subscription, e.g.) had no way to be dismissed until now -- see recurringApi.dismiss's own
  // comment on why `merchant`, not an id, is the identity. Optimistic removal: this list is
  // purely informational, so there is no real cost to a rare rollback flashing the row back in on
  // a failed request, versus the felt latency of waiting for a refetch on every dismiss.
  const dismissRecurring = useMutation({
    mutationFn: (merchant: string) => recurringApi.dismiss(merchant),
    onMutate: async (merchant) => {
      await queryClient.cancelQueries({ queryKey: ['recurring'] });
      const previous = queryClient.getQueryData<RecurringItem[]>(['recurring']);
      queryClient.setQueryData<RecurringItem[]>(['recurring'], (items) =>
        (items ?? []).filter((item) => item.merchant !== merchant));
      return { previous };
    },
    onError: (_err, _merchant, context) => {
      if (context?.previous) queryClient.setQueryData(['recurring'], context.previous);
    },
  });

  // The unified range picker's OWN summary (Total Balance/Income/Expenses/Net Savings/Savings
  // Rate) -- a separate period model from `summary` above, which stays on DashboardService's
  // single reporting month (Financial Health Score, spend-by-category, notifications, category
  // review, categorization confidence all keep reading from `summary`; none of those are part of
  // this range picker). See DashboardRangeService's own doc comment for why the two aren't unified
  // into one backend call. Disabled for CUSTOM until both dates are actually filled in -- an
  // incomplete custom range has nothing valid to ask the server for yet.
  const isCustomRangeReady = dashboardRange !== 'CUSTOM' || (!!customStart && !!customEnd);
  const rangeSummaryQ = useQuery({
    queryKey: ['dashboard-range-summary', dashboardRange, customStart, customEnd],
    queryFn: () => dashboardApi.rangeSummary(dashboardRange, customStart || undefined, customEnd || undefined),
    enabled: isCustomRangeReady,
  });
  const rangeSummary = rangeSummaryQ.data;

  // Cash Flow Overview's time-range selector — backed by real per-month totals (Reports'
  // /reports?month= endpoint), not the flat single-month line this used to render. Available
  // months come from the server already sorted ascending. Presets take however many the selected
  // range asks for from the tail (most recent); CUSTOM instead keeps only the available months
  // that actually fall within [customStart, customEnd] -- the same "only fetch months known to
  // have data" principle, just filtered by date range instead of by count.
  const { data: availableMonths = [] } = useQuery({
    queryKey: ['report-months'],
    queryFn: () => reportsApi.availableMonths(),
  });
  const monthsInRange = useMemo(() => {
    if (dashboardRange === 'CUSTOM') {
      if (!customStart || !customEnd) return [];
      const startMonth = customStart.slice(0, 7);
      const endMonth = customEnd.slice(0, 7);
      return availableMonths.filter((m) => m >= startMonth && m <= endMonth);
    }
    return availableMonths.slice(-RANGE_MONTHS[dashboardRange]);
  }, [availableMonths, dashboardRange, customStart, customEnd]);
  const monthlyReportsQ = useQueries({
    queries: monthsInRange.map((month) => ({
      queryKey: ['report', month],
      queryFn: () => reportsApi.forMonth(month),
      staleTime: 5 * 60_000, // past months' totals don't change once the month is over
    })),
  });
  const cashFlowLoading = monthlyReportsQ.some((q) => q.isLoading);
  const cashFlowSeries = monthlyReportsQ
    .map((q) => q.data)
    .filter((d): d is NonNullable<typeof d> => !!d);

  // Animation-polish roadmap §3 priority 1 / §1's "section-scoped loading" rule: split the old
  // blanket 4-query gate so whichever section's data arrives first renders first, instead of
  // everything waiting on the slowest one. Not a full split, though -- `isEmpty` below reads
  // recentTxnsQ.data directly and gates several OTHER sections (Financial Health Score,
  // Categorization Confidence, Next Actions, the Limited History banner), so recentTxnsQ is
  // structurally load-bearing for the page shell, not just its own card. Decoupling it the way
  // accounts/goals/budgets are decoupled below would let `isEmpty` default to `true` off an
  // unresolved query, hiding those sections for a user who actually has data -- trading the
  // original flash-of-wrong-content bug for a new one in the opposite direction. summaryQ and
  // recentTxnsQ stay a blocking pair; accountsQ/goalsQ/budgetsQ (below, each only used within its
  // own card) become independently-loading sections.
  const blockingLoading = summaryQ.isLoading || recentTxnsQ.isLoading;
  // Bug fix: TanStack Query's isLoading flips to false once a query SETTLES, including settling
  // with an error -- so a failed summary/accounts/transactions/goals fetch used to fall straight
  // through to `if (!summary) return null`, rendering a blank page on the app's own landing route
  // with zero indication anything went wrong. isError only ever reflects the queries `loading`
  // itself is already built from, so this can't introduce a new spinner-that-never-resolves case.
  const hasError = summaryQ.isError || accountsQ.isError || recentTxnsQ.isError || goalsQ.isError;
  const showPageSkeleton = useDelayedLoading(blockingLoading);
  const showAccountsSkeleton = useDelayedLoading(accountsQ.isLoading);
  const showGoalsSkeleton = useDelayedLoading(goalsQ.isLoading);
  const showBudgetsSkeleton = useDelayedLoading(budgetsQ.isLoading);
  const showRangeSummarySkeleton = useDelayedLoading(rangeSummaryQ.isLoading);
  const prefersReducedMotion = useReducedMotion();
  const summary = summaryQ.data;
  const accounts = (accountsQ.data ?? []).filter((acc) => acc.accountType !== 'INVESTMENT').slice(0, 4);
  const recentTxns = recentTxnsQ.data?.content ?? [];
  const goals = (goalsQ.data ?? []).slice(0, 2);
  const budgets = (budgetsQ.data ?? []).slice(0, 3);
  const sentences = insightsQ.data?.sentences ?? [];
  const movers = (insightsQ.data?.movers ?? []).filter((m) => m.pctChange !== null).slice(0, 2);
  // RecurringDto already arrives sorted by nextEstimate (RecurringService's own doc comment) --
  // taking the first few is "soonest due", not an arbitrary truncation.
  const upcomingRecurring = (recurringQ.data ?? []).slice(0, 5);

  if (blockingLoading) return showPageSkeleton ? <DashboardSkeleton /> : null;
  if (hasError || !summary) {
    return <p className="text-muted">Couldn’t load your dashboard — please try again later.</p>;
  }

  const firstName = fullName?.split(' ')[0] ?? 'there';

  // D-21: totalElements is the real total this account has, not recentTxnsQ's own 4-row page
  // size -- a brand-new account (or one that connected Gmail/created an account but never
  // actually got any transactions in) needs the same "here's what to do next" treatment a
  // completely fresh signup does. Redesigned from D-21 Step 1's original single-gate welcome
  // screen (which replaced the whole page) to keeping the full dashboard shell visible with a
  // per-section empty state instead -- the shell itself is what shows a new user the shape of the
  // product, not a page that hides it behind one more screen before they've seen anything.
  const isEmpty = (recentTxnsQ.data?.totalElements ?? 0) === 0;

  // Bug 05: these KPIs are the newest month the account has DATA for, which for a product built
  // around importing statements in arrears is routinely not the current calendar month. This page
  // used to assert "this month" and "vs last month" over whichever month that happened to be, so a
  // user who hadn't yet imported August read July's figures as August's. The backend now says which
  // month it is reporting on; this stops guessing and renders it.
  const periodLabel = summary.reportingMonthIsCurrent || !summary.reportingMonth
    ? 'this month'
    : monthLabel(summary.reportingMonth);
  const categoryEntries = Object.entries(summary.spendByCategory).sort((a, b) => b[1] - a[1]);
  const totalSpend = categoryEntries.reduce((s, [, v]) => s + v, 0);
  const donutColors = ['#3b82f6', '#16a34a', '#f59e0b', '#8b5cf6', '#ef4444', '#94a3b8'];

  // Range period phrasing for the 5 KPI cards + chart -- a separate set of labels from
  // periodLabel above, which describes `summary`'s single reporting month (Health Score, category
  // review, categorization confidence -- none of those moved to the range picker).
  const rangePeriodPhrase = rangeSummary
    ? rangeSummary.rangeType === 'CUSTOM'
      ? `from ${dayLabel(rangeSummary.startDate)} to ${dayLabel(rangeSummary.endDate)}`
      : `in the ${RANGE_TYPE_LABEL[rangeSummary.rangeType].toLowerCase()}`
    : '';
  // Custom's previous period is the same DAY COUNT immediately before `start`, not a calendar
  // shift (see DashboardRangeService) -- the label names days there and months for a preset,
  // rather than claiming "months" for a comparison that was actually built out of raw days.
  const rangeComparisonLabel = rangeSummary
    ? rangeSummary.rangeType === 'CUSTOM'
      ? `vs previous ${Math.round((new Date(rangeSummary.endDate).getTime() - new Date(rangeSummary.startDate).getTime()) / 86_400_000) + 1} days`
      : `vs previous ${RANGE_MONTHS[rangeSummary.rangeType]} months`
    : '';
  const rangeGateReasonText = !rangeSummary ? null
    : rangeSummary.comparisonGateReason === 'NO_TRANSACTION_HISTORY'
      ? "There's no transaction history yet to compare against."
      : rangeSummary.comparisonGateReason === 'PRIOR_PERIOD_BEFORE_HISTORY'
        ? "The comparison period reaches back before your account's own history began, so it wouldn't be a fair like-for-like."
        : rangeSummary.comparisonGateReason === 'TOO_FEW_PRIOR_TRANSACTIONS'
          ? `The previous period has fewer than ${rangeSummary.comparisonGateMinTransactions} transactions, too few to compare reliably.`
          : null;
  // Bug fix: currentBalanceGateReason takes priority -- it explains why the VALUE itself is
  // missing (no snapshot as of this range's end at all), which is more fundamental than
  // balanceGateReason (which only explains why the COMPARISON is missing, with a real current
  // value still shown). Showing the prior-period text while masking that the current figure
  // itself is absent would be worse than showing nothing.
  const balanceGateReasonText = rangeSummary?.currentBalanceGateReason === 'NO_SNAPSHOT_AT_OR_BEFORE_DATE'
    ? 'No balance snapshot exists as of this date range.'
    : rangeSummary?.balanceGateReason === 'NO_SNAPSHOT_AT_PRIOR_DATE'
      ? 'No balance snapshot exists far enough back to compare against.'
      : null;

  // For CUSTOM, "(Custom)" alone tells the user nothing about which dates -- unlike a preset,
  // where "(Last 6 Months)" IS the full period description. Mirrors rangePeriodPhrase's own
  // custom-vs-preset branch above.
  const rangeCardSuffix = rangeSummary
    ? rangeSummary.rangeType === 'CUSTOM'
      ? `${dayLabel(rangeSummary.startDate)} – ${dayLabel(rangeSummary.endDate)}`
      : RANGE_TYPE_LABEL[rangeSummary.rangeType]
    : '';

  const kpis = rangeSummary ? [
    // Bug fix: fmt() coerces null to 0 (Math.abs(null) === 0 in JS), which would silently
    // re-fabricate the exact "₹0 instead of no data" bug DashboardRangeService.java's own doc
    // comment describes fixing on the backend. currentBalance must be null-checked here, not
    // handed to fmt() unconditionally.
    // Bug fix: these 4 icon badges used to hardcode raw Tailwind palette classes (bg-blue-100
    // etc.) with no dark: variant -- invisible to Tailwind's class-based dark mode (see
    // tailwind.config.js's darkMode: 'class'), so they stayed light-mode pastel even when the
    // rest of the page switched to dark. Income/Expenses use the app's own success/danger theme
    // tokens (the same green=good/red=bad meaning the value text elsewhere in the app already
    // carries -- e.g. Investments.tsx pairs valueColor="text-success" with these same raw green/
    // red classes, so the tokens are the more consistent choice, not a new convention). Balance
    // and Savings Rate have no such semantic meaning (neither is "good" or "bad"), so they use
    // the decorative accent-* tokens instead (see index.css's comment on those) rather than being
    // folded into an unrelated success/danger token. This used to be scoped to Dashboard's 5 KPI
    // cards only, with the same un-dark-mode-aware pattern left in 10 other pages across the app
    // as "a separate, much larger change nobody has asked for yet" -- that change is this one;
    // see the accent-* additions to index.css/tailwind.config.js and their use across the rest of
    // this file and the other pages that had the same bug.
    {
      label: 'Balance',
      value: rangeSummary.currentBalance !== null ? fmt(rangeSummary.currentBalance) : '—',
      caption: rangeSummary.currentBalanceAsOf ? `as of ${dayLabel(rangeSummary.currentBalanceAsOf)}` : undefined,
      delta: rangeSummary.balanceDeltaPct, deltaLabel: 'vs previous period',
      icon: Wallet, iconBg: 'bg-accent-blue-bg', iconColor: 'text-accent-blue', gateReasonText: balanceGateReasonText,
    },
    { label: `Income (${rangeCardSuffix})`, value: fmt(rangeSummary.incomeTotal), delta: rangeSummary.incomeDeltaPct, deltaLabel: rangeComparisonLabel, icon: ArrowDownCircle, iconBg: 'bg-success-bg', iconColor: 'text-success', gateReasonText: rangeGateReasonText },
    { label: `Expenses (${rangeCardSuffix})`, value: fmt(rangeSummary.expenseTotal), delta: rangeSummary.expenseDeltaPct, deltaLabel: rangeComparisonLabel, icon: ArrowUpCircle, iconBg: 'bg-danger-bg', iconColor: 'text-danger', invertDelta: true, gateReasonText: rangeGateReasonText },
    { label: `Net Savings (${rangeCardSuffix})`, value: fmt(rangeSummary.netSavingsTotal), delta: rangeSummary.netDeltaPct, deltaLabel: rangeComparisonLabel, icon: PiggyBank, iconBg: 'bg-primary-light', iconColor: 'text-primary', gateReasonText: rangeGateReasonText },
    { label: `Savings Rate (${rangeCardSuffix})`, value: rangeSummary.savingsRatePct.toFixed(0) + '%', delta: null as number | null, deltaLabel: rangeComparisonLabel, icon: PieChart, iconBg: 'bg-accent-purple-bg', iconColor: 'text-accent-purple' },
  ] : [];

  return (
    <div>
      <ChecklistWidget />
      <JourneyWidget />
      <div className="relative overflow-hidden bg-card rounded-xl2 border border-border shadow-card mb-8 px-6 py-6 lg:pr-4">
        <div className="relative z-10 lg:max-w-[62%]">
          <h1 className="text-display-sm font-bold text-ink mb-1">{greeting(settingsQ.data?.timezone)}, {firstName}! 👋</h1>
          <p className="text-muted text-sm mb-4">
            Here's what's happening with your finances today.
            {!summary.reportingMonthIsCurrent && summary.reportingMonth && (
              // Not a warning -- reporting on the newest month with data is the intended behaviour.
              // What was missing is that nothing said which month, so the figures read as current.
              <> Your latest figures are from <span className="font-medium text-ink">{periodLabel}</span>.</>
            )}
          </p>
        </div>
        {/* Purely decorative -- the illustration and quote carry no information the heading/chips
            above don't already state, so the whole region is hidden from assistive tech rather
            than given (unhelpful, made-up) alt text. Hidden below `lg`: there isn't room for a
            side illustration without shrinking or overlapping the greeting text on a narrow
            viewport. */}
        <div
          data-testid="dashboard-hero-illustration"
          aria-hidden="true"
          className="hidden lg:block absolute inset-y-0 right-0 w-[42%]"
        >
          <p className="absolute top-0 right-1 max-w-[190px] text-right text-xs italic text-muted leading-snug">
            "Small steps today, bigger goals tomorrow."
            <span className="block not-italic font-semibold text-ink/50 mt-1 text-2xs">— Fynora</span>
          </p>
          {/* "meet" (scale-to-fit), not "slice" (scale-to-cover): slice crops vertically on any
              container wider than the viewBox's own 380:220 ratio, and that crop is unbounded --
              on a wide-but-short hero it pushes the polyline's peak/dot up past the container's
              own top edge, directly into the quote text's space. "meet" scales to fit the
              container's height exactly with no cropping, so the ~33% of viewBox height above the
              peak (y=72 of 220) stays proportionally clear regardless of how wide the container
              gets -- the trade-off is empty space on the left on a very wide container, which is
              fine for a decorative background element with this much room already. */}
          <svg viewBox="0 0 380 220" className="absolute inset-0 w-full h-full" preserveAspectRatio="xMaxYMid meet">
            <polygon
              points="0,220 40,150 70,158 110,120 150,138 190,100 230,122 270,86 310,108 340,72 380,92 380,220"
              className="fill-primary/[0.05]"
            />
            <polyline
              points="0,170 40,150 70,158 110,120 150,138 190,100 230,122 270,86 310,108 340,72 380,92"
              className="stroke-primary/[0.35]"
              fill="none" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"
            />
            <circle cx="340" cy="72" r="4.5" className="fill-primary" />
          </svg>
        </div>
      </div>

      {/* Limited-history banner. The KPI deltas and health score below are real, computed numbers
          -- neither is hidden here -- but both are prone to thin-data artifacts this far below
          limitedHistoryMonthFloor: a trend delta dividing against a near-empty prior month (see
          pct() in DashboardService, still capable of a 900%+ swing off one stray transaction), and
          a health score built from too few comparable months (see the Spend Consistency / Cash
          Flow Stability partial-month fix). Shown once, above everything it explains, rather than
          leaving a user to notice the numbers look strange and wonder why. Hidden once isEmpty --
          the zero-transaction empty state below already covers that case on its own terms. */}
      {!isEmpty && summary.limitedHistory && (
        <div className="bg-warning-bg border border-warning/30 rounded-xl2 px-5 py-3.5 flex items-start gap-2.5 mb-6">
          <AlertTriangle size={16} className="text-warning flex-shrink-0 mt-0.5" />
          <div className="flex-1 min-w-0">
            <div className="flex items-center justify-between gap-2">
              <p className="text-sm font-medium text-ink">Limited financial history</p>
              <button
                type="button"
                onClick={() => setHistoryBannerCollapsed((c) => !c)}
                aria-expanded={!historyBannerCollapsed}
                aria-label={historyBannerCollapsed ? 'Expand details' : 'Collapse details'}
                className="flex-shrink-0 text-warning/70 hover:text-warning transition-colors"
              >
                <ChevronDown size={16} className={`transition-transform ${historyBannerCollapsed ? '-rotate-90' : ''}`} />
              </button>
            </div>
            {!historyBannerCollapsed && (
              <p className="text-xs text-muted mt-0.5">
                Based on {summary.statementCount} statement{summary.statementCount === 1 ? '' : 's'} across{' '}
                {summary.accountCount} account{summary.accountCount === 1 ? '' : 's'} and{' '}
                {summary.historyMonthCount} month{summary.historyMonthCount === 1 ? '' : 's'} of activity.
                Trends and the Financial Health Score below may be unreliable until at least{' '}
                {summary.limitedHistoryMonthFloor} months of history are imported.
              </p>
            )}
          </div>
        </div>
      )}

      {/* KPI cards -- driven by the unified range picker (dashboardRange), a separate query from
          `summary` above (see rangeSummaryQ). Each card carries its OWN deltaLabel now: Balance
          compares an ending-balance snapshot ("vs previous period"), the other three compare
          summed totals over the same range length ("vs previous 6 months" / "vs previous N
          days" for a custom range) -- see DashboardRangeService's own doc comment for why the
          two comparison shapes differ. */}
      {/* Bug fix: a disabled react-query (CUSTOM picked, dates not both filled in yet) has
          isLoading === false (no fetch is in flight) and no data -- it used to fall through to
          the isError-or-no-data branch below and show "Couldn't load your KPI cards", a false
          error for a state where nothing has actually gone wrong. Checked first, and explicitly,
          rather than folded into the loading/error branches below. */}
      {!isCustomRangeReady ? (
        <p className="text-sm text-muted mb-6">Pick a start and end date to see your KPI cards.</p>
      ) : rangeSummaryQ.isLoading ? (
        showRangeSummarySkeleton && (
          <Skeleton.Region label="Loading KPI cards">
            <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-4 mb-6">
              {[0, 1, 2, 3, 4].map((i) => (
                <div key={i} className="bg-card rounded-xl2 border border-border shadow-card p-4 space-y-2">
                  <Skeleton.Text width="w-16" className="h-2.5" />
                  <Skeleton.Text width="w-20" className="h-5" />
                </div>
              ))}
            </div>
          </Skeleton.Region>
        )
      ) : rangeSummaryQ.isError || !rangeSummary ? (
        // Bug fix: a fixed string here didn't say WHY the request failed (network vs. a genuine
        // 400 from an invalid custom range, say) -- (e as any).response?.data?.message ?? fallback
        // is the same pattern Budgets.tsx/Billing.tsx already use for a failed mutation, applied
        // here to a failed query instead.
        <p className="text-sm text-muted mb-6">
          {(rangeSummaryQ.error as any)?.response?.data?.message ?? "Couldn't load your KPI cards — please try again later."}
        </p>
      ) : (
        <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-4 mb-6">
          {kpis.map((k) => (
            <MetricCard
              key={k.label}
              label={k.label}
              value={k.value}
              caption={k.caption}
              icon={k.icon}
              iconBg={k.iconBg}
              iconColor={k.iconColor}
              delta={k.delta}
              deltaLabel={k.deltaLabel}
              invertDelta={k.invertDelta}
              gateReasonText={k.gateReasonText}
              variant="elevated"
            />
          ))}
        </div>
      )}

      {/* Financial Health Score — DashboardService.computeHealthScore has always returned this
          (score, label, a 5-component breakdown), sent to the frontend on every load; nothing
          rendered it until now. D-19 Step 1. Hidden entirely while isEmpty -- a score computed
          from zero transactions has nothing real behind it, same reasoning Subscriptions &
          Recurring below already applies to itself. D-25 PR3-A: below isEmpty is not the whole
          gap -- a handful of transactions can still score under 40 ("Needs Attention") by
          construction, a harsh first impression over incomplete data rather than a true reading.
          healthScoreAvailable (a real transaction-count floor, not just isEmpty) covers the
          thin-but-not-zero range isEmpty never did, showing onboarding progress instead of a
          number. */}
      {!isEmpty && (
      <FinoraCard padding="lg" tier="primary" className="mb-6">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-8 h-8 rounded-full bg-primary-light flex items-center justify-center">
            <ShieldCheck size={15} className="text-primary" />
          </div>
          <h2 className="font-semibold text-ink">Financial Health Score</h2>
        </div>
        {summary.healthScoreAvailable ? (
          <div className="space-y-6">
            {/* Gauge + range legend: ~40% of this card's visual weight, factor cards below take the
                rest -- an unfamiliar "51/100" needs the explanation more than it needs a bigger
                gauge, since (unlike a credit score) this number has no meaning outside this app. */}
            <div className="grid md:grid-cols-[auto_1fr] gap-6 items-center">
              <div className="flex flex-col items-center" data-testid="health-score-summary">
                <HealthScoreGauge score={summary.healthScore!} />
                <AnimatedHealthScoreNumber
                  score={summary.healthScore!}
                  className={`text-3xl font-bold -mt-2 ${healthColor(summary.healthLabel!)}`}
                />
                <p className={`text-sm font-medium ${healthColor(summary.healthLabel!)}`}>{summary.healthLabel}</p>
                {/* Deliberately NOT "vs last month" -- healthScoreDeltaVsLastMonth compares
                    against the most recent PRIOR snapshot, which HealthScoreSnapshotRepository's
                    own gap-skipping query can resolve to a month further back than the immediately
                    preceding calendar one (see check-reporting-period-labels.py, the same class of
                    bug it exists to catch: a client naming a period the server didn't actually
                    report). "vs your last recorded score" makes no month claim at all, so it's
                    correct regardless of how far back that snapshot actually was. */}
                {summary.healthScoreDeltaVsLastMonth !== null && (
                  summary.healthScoreDeltaVsLastMonth === 0 ? (
                    // Neutral, not "↑ 0" -- an unchanged score isn't an improvement, and coloring
                    // it success-green alongside an up-arrow would misleadingly read as one.
                    <p className="text-xs mt-1 text-muted">No change vs your last recorded score</p>
                  ) : (
                    <p className={`text-xs mt-1 ${summary.healthScoreDeltaVsLastMonth > 0 ? 'text-success' : 'text-danger'}`}>
                      {summary.healthScoreDeltaVsLastMonth > 0 ? '↑' : '↓'} {Math.abs(summary.healthScoreDeltaVsLastMonth)} vs your last recorded score
                    </p>
                  )
                )}
                <p className="text-2xs text-muted mt-2 text-center max-w-[200px]">
                  Calculated from savings, debt, emergency fund, spending consistency, and cash-flow stability.
                </p>
              </div>
              <div className="w-full max-w-[220px] md:max-w-none">
                <HealthScoreRangeLegend score={summary.healthScore!} />
              </div>
            </div>

            {summary.healthSparkline.length >= 2 && (
              <div>
                <p className="text-xs font-medium text-ink mb-1">6-month trend</p>
                <HealthScoreSparkline points={summary.healthSparkline} />
              </div>
            )}

            {/* Factor cards -- replaces the old horizontal progress bars. journey-reveal-item is
                the same shared staggered-fade-in class the Getting Started checklist uses (also
                used by Ledger.tsx) -- reduced-motion-aware via its own @media rule in index.css. */}
            <div className="grid sm:grid-cols-2 lg:grid-cols-3 gap-3">
              {Object.entries(summary.healthBreakdown).map(([name, score], i) => {
                const isTopOpportunity = name === summary.healthTopOpportunityFactor;
                return (
                  <div
                    key={name}
                    data-testid={`health-factor-${name}`}
                    className="journey-reveal-item rounded-xl2 border border-border bg-bg p-4"
                    style={{ animationDelay: `${i * 80}ms` }}
                  >
                    <div className="flex items-center justify-between mb-1">
                      <span className="text-sm font-medium text-ink">{name}</span>
                      <Badge tone={badgeToneForScore(score)} label={scoreLabel(score)} />
                    </div>
                    <p className="text-lg font-bold text-ink mb-1">{Math.round(score)} / 100</p>
                    <p className="text-xs text-muted">{summary.healthBreakdownDetail[name]}</p>
                    <p className="text-xs text-ink mt-1.5">{healthImprovementSuggestion(name, score)}</p>
                    {isTopOpportunity && summary.healthTopOpportunityPotentialGain !== null && (
                      <p className="text-xs font-semibold text-primary mt-1.5">
                        ↑ +{summary.healthTopOpportunityPotentialGain} point opportunity
                      </p>
                    )}
                  </div>
                );
              })}
            </div>

            {/* AI Insight card -- only when there's a real (>= 3 point) opportunity. Delay picks
                up right after the last factor card's own stagger (5 cards * 80ms). */}
            {summary.healthTopOpportunityFactor && summary.healthTopOpportunityPotentialGain !== null && (
              <div
                data-testid="health-score-insight"
                className="journey-reveal-item rounded-xl2 border border-primary/30 bg-primary-light p-4 flex items-start justify-between gap-4 flex-wrap"
                style={{ animationDelay: `${Object.keys(summary.healthBreakdown).length * 80}ms` }}
              >
                <div>
                  <p className="text-sm font-medium text-ink">
                    Your {summary.healthTopOpportunityFactor.toLowerCase()} is the biggest opportunity to improve your score.
                  </p>
                  <p className="text-xs text-muted mt-0.5">
                    Potential gain: <span className="font-semibold text-primary">+{summary.healthTopOpportunityPotentialGain} points</span>
                  </p>
                </div>
                <Link
                  to="/app/goals"
                  className="inline-flex items-center gap-1.5 rounded-lg bg-primary text-on-primary hover:bg-primary-dark px-3.5 py-2 text-xs font-semibold transition-colors flex-shrink-0"
                >
                  Create Goal
                </Link>
              </div>
            )}
          </div>
        ) : (
          <div className="flex flex-col items-center text-center py-4 px-2">
            <div className="w-12 h-12 rounded-full bg-primary-light flex items-center justify-center mb-3">
              <ShieldCheck size={22} className="text-primary" />
            </div>
            <p className="text-sm font-semibold text-ink mb-1">Getting Started</p>
            <p className="text-xs text-muted mb-4 max-w-[240px]">
              Import more transactions to unlock your Financial Health Score.
            </p>
            <div className="w-full max-w-[240px]">
              <div className="flex justify-between text-xs text-muted mb-1.5">
                <span>{summary.healthScoreTransactionCount} / {summary.healthScoreMinTransactions} transactions</span>
                <span>
                  {Math.round(Math.min(100, (summary.healthScoreTransactionCount / summary.healthScoreMinTransactions) * 100))}%
                </span>
              </div>
              <div className="h-1.5 bg-bg rounded-full overflow-hidden">
                <div
                  className="h-full bg-primary rounded-full"
                  style={{ width: `${Math.min(100, (summary.healthScoreTransactionCount / summary.healthScoreMinTransactions) * 100)}%` }}
                />
              </div>
            </div>
          </div>
        )}
      </FinoraCard>
      )}

      {/* Categorization Confidence -- how sure the categorization engine was, on average, about
          the categories it assigned this month. A positive, ongoing data-quality signal, distinct
          from the category-review warning below (which only fires when spend is badly
          miscategorized) -- this can read "Excellent" in the very same month that warning fires,
          if a small number of genuinely low-confidence transactions sit alongside a lot of
          high-confidence ones. Hidden below categorizationConfidenceMinTransactions
          engine-decided transactions this month (server-side floor, same reasoning as
          healthScoreAvailable above): an average of one or two decisions isn't a real reading. */}
      {!isEmpty && summary.categorizationConfidenceScore !== null && (
      <FinoraCard padding="lg" className="mb-6">
        <div className="flex items-center gap-2 mb-3">
          <div className="w-8 h-8 rounded-full bg-primary-light flex items-center justify-center">
            <BadgeCheck size={15} className="text-primary" />
          </div>
          <h2 className="font-semibold text-ink">Categorization Confidence</h2>
        </div>
        <div className="flex items-baseline gap-2">
          <p className={`text-4xl font-bold ${healthColor(scoreLabel(summary.categorizationConfidenceScore))}`}>
            {summary.categorizationConfidenceScore}
          </p>
          <p className="text-xs text-muted">out of 100</p>
        </div>
        <p className={`text-sm font-medium mt-1 ${healthColor(scoreLabel(summary.categorizationConfidenceScore))}`}>
          {scoreLabel(summary.categorizationConfidenceScore)}
        </p>
        <p className="text-xs text-muted mt-2">
          Based on {summary.categorizationConfidenceTransactionCount} automatically categorized transaction
          {summary.categorizationConfidenceTransactionCount === 1 ? '' : 's'} {periodLabel}.
        </p>
      </FinoraCard>
      )}

      {/* Next Actions -- summary.notifications (DashboardService.buildNotifications: credit-card
          payments due soon, low-balance warnings, budget-threshold alerts) has always been
          computed and sent on every dashboard load, but was only ever rendered in TopBar's
          bell-icon dropdown -- easy to miss entirely if a user doesn't happen to open it. This
          surfaces the SAME list, unchanged, directly on the page it's actually about, rather
          than computing anything new. Hidden while isEmpty, same reasoning as Financial Health
          Score above: a brand-new account has nothing computed here to act on yet. */}
      {!isEmpty && (
      <FinoraCard padding="lg" tier="primary" className="mb-6">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-8 h-8 rounded-full bg-primary-light flex items-center justify-center">
            <ListChecks size={15} className="text-primary" />
          </div>
          <h2 className="font-semibold text-ink">Next Actions</h2>
        </div>
        {summary.notifications.length === 0 ? (
          <p className="text-sm text-muted">Nothing needs your attention right now.</p>
        ) : (
          <ul className="space-y-2.5">
            {summary.notifications.map((n, i) => (
              <li key={i} className="flex items-start gap-2.5">
                <AlertTriangle size={14} className="text-warning flex-shrink-0 mt-0.5" />
                <span className="text-sm text-ink">{n}</span>
              </li>
            ))}
          </ul>
        )}
      </FinoraCard>
      )}

      {/* Detected Issues -- ReconciliationService's own duplicate pass already silently excludes
          a row from every total above the moment it runs (Transaction.isDuplicateOf), and until
          now nothing told the user it happened. transactionsApi.confirmNotDuplicate (BH-027,
          "no, these really are two separate transactions") already existed on the backend to let
          a human overrule that guess -- it simply had no caller anywhere in the product. Shown
          only when something was actually flagged (unlike Next Actions above, which stays visible
          with a positive empty state): this is a conditional alert like Limited History and the
          category-review warning, not a standing destination worth checking when empty. */}
      {summary.duplicateTransactionCount > 0 && (
      <FinoraCard padding="lg" tier="primary" className="mb-6">
        <div className="flex items-center gap-2 mb-1">
          <div className="w-8 h-8 rounded-full bg-warning-bg flex items-center justify-center">
            <Copy size={15} className="text-warning" />
          </div>
          <h2 className="font-semibold text-ink">Detected Issues</h2>
        </div>
        <p className="text-xs text-muted mb-4 ml-10">
          {summary.duplicateTransactionCount === 1
            ? "We found 1 transaction that looks like a duplicate and excluded it from your totals."
            : `We found ${summary.duplicateTransactionCount} transactions that look like duplicates and excluded them from your totals.`}
        </p>
        {duplicateConfirmError && (
          <p className="text-danger text-xs mb-3">{duplicateConfirmError}</p>
        )}
        <ul className="divide-y divide-border">
          {summary.detectedDuplicates.map((d) => (
            <li key={d.transactionId} className="flex items-center justify-between gap-3 py-2.5">
              <div className="min-w-0">
                <p className="text-sm text-ink truncate">{d.merchant}</p>
                <p className="text-xs text-muted">
                  {new Date(d.date + 'T00:00:00').toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })} · {fmt(d.amount)}
                </p>
              </div>
              <button
                type="button"
                onClick={() => void handleConfirmNotDuplicate(d.transactionId)}
                disabled={confirmingDuplicateId === d.transactionId}
                className="text-xs text-primary font-medium flex-shrink-0 disabled:opacity-50"
              >
                {confirmingDuplicateId === d.transactionId ? 'Confirming…' : 'Not a duplicate'}
              </button>
            </li>
          ))}
        </ul>
        {summary.duplicateTransactionCount > summary.detectedDuplicates.length && (
          <p className="text-xs text-muted mt-2.5">
            and {summary.duplicateTransactionCount - summary.detectedDuplicates.length} more
          </p>
        )}
      </FinoraCard>
      )}

      {/* Cash flow + Spending breakdown */}
      <div className="grid lg:grid-cols-[1.6fr_1fr] gap-6 mb-6">
        <FinoraCard padding="lg">
          {/* Not SectionHeader -- the right side is a range filter, not a "View All" link. Same
              dashboardRange control the KPI cards above use -- one picker for both. */}
          <div className="flex items-center justify-between mb-1 flex-wrap gap-2">
            <h2 className="font-semibold text-ink">Cash Flow Overview</h2>
            <select
              value={dashboardRange}
              onChange={(e) => setDashboardRange(e.target.value as DashboardRangeType)}
              className="text-xs border border-border rounded-lg px-2.5 py-1.5 text-muted"
            >
              <option value="LAST_3_MONTHS">Last 3 Months</option>
              <option value="LAST_6_MONTHS">Last 6 Months</option>
              <option value="LAST_12_MONTHS">Last 12 Months</option>
              <option value="LAST_24_MONTHS">Last 24 Months</option>
              <option value="CUSTOM">Custom</option>
            </select>
          </div>
          {dashboardRange === 'CUSTOM' && (
            <div className="flex items-center gap-2 mb-3 text-xs">
              <input
                type="date"
                value={customStart}
                max={customEnd || undefined}
                onChange={(e) => setCustomStart(e.target.value)}
                className="border border-border rounded-lg px-2 py-1 text-ink"
                aria-label="Custom range start date"
              />
              <span className="text-muted">to</span>
              <input
                type="date"
                value={customEnd}
                min={customStart || undefined}
                onChange={(e) => setCustomEnd(e.target.value)}
                className="border border-border rounded-lg px-2 py-1 text-ink"
                aria-label="Custom range end date"
              />
            </div>
          )}
          <p className="text-sm text-muted mb-4">
            {rangeSummary
              ? <>You've earned {fmt(rangeSummary.incomeTotal)} and spent {fmt(rangeSummary.expenseTotal)} {rangePeriodPhrase}.</>
              : <>&nbsp;</>}
          </p>
          <ChartContainer
            height={256}
            loading={cashFlowLoading}
            loadingLabel="Loading trend…"
            isEmpty={cashFlowSeries.length === 0}
            emptyState={
              // Bug fix: with Custom selected and no dates filled in yet, monthsInRange is
              // deliberately [] (see that computation above), which made cashFlowSeries empty and
              // showed "No data yet -- import a statement", implying the account has no history
              // at all. That's wrong for a user who has real data but simply hasn't finished
              // picking a range -- same underlying gap as the KPI cards' isCustomRangeReady check
              // above, just in this section instead.
              !isCustomRangeReady ? (
                <EmptyState
                  icon={LineChartIcon}
                  iconBg="bg-primary-light"
                  iconColor="text-primary"
                  title="Pick a date range"
                  desc="Choose a start and end date above to see your cash flow trend."
                />
              ) : (
                <EmptyState
                  icon={LineChartIcon}
                  iconBg="bg-primary-light"
                  iconColor="text-primary"
                  title="No data yet"
                  desc="Import a statement or add transactions to see your cash flow trend."
                  cta={
                    <Link
                      to="/app/import"
                      className="inline-flex items-center gap-1.5 bg-primary text-on-primary hover:bg-primary-dark rounded-lg px-4 py-2 text-xs font-semibold"
                    >
                      <UploadCloud size={14} /> Import Statement
                    </Link>
                  }
                />
              )
            }
          >
            <CashFlowChart series={cashFlowSeries} />
          </ChartContainer>
        </FinoraCard>

        <FinoraCard padding="lg" className="flex flex-col">
          <SectionHeader title="Spending Breakdown" viewAllTo="/app/reports" />
          {categoryEntries.length === 0 ? (
            <div className="flex-1 flex items-center justify-center">
              <EmptyState
                icon={PieChart}
                iconBg="bg-accent-purple-bg"
                iconColor="text-accent-purple"
                title="No spending data yet"
                desc="Your top spending categories will appear here."
                cta={
                  <Link to="/app/reports" className="inline-block border border-border text-ink hover:bg-bg rounded-lg px-4 py-2 text-xs font-semibold">
                    View Reports
                  </Link>
                }
              />
            </div>
          ) : (
            <>
              <div className="relative w-40 h-40 mx-auto mb-4">
                {/* Bug fix: unlike the Cash Flow line chart, this canvas has no aria-label of its
                    own -- but it doesn't need one, since the category list rendered right below
                    (categoryEntries.slice(0, 6), with name/%/amount as real text) already gives a
                    screen reader the exact same data. aria-hidden here says so explicitly, rather
                    than leaving an unlabelled interactive canvas for assistive tech to guess at. */}
                <Doughnut
                  aria-hidden="true"
                  data={{
                    labels: categoryEntries.map(([k]) => k),
                    datasets: [{
                      data: categoryEntries.map(([, v]) => v),
                      backgroundColor: categoryEntries.map((_, i) => donutColors[i % donutColors.length]),
                      borderWidth: 0,
                      // The hovered slice pushes outward -- Chart.js's own built-in affordance for
                      // "this wedge is interactive", not a plugin. No hoverBorderColor: a canvas
                      // fillStyle/strokeStyle needs a resolved color, not a live `var(--color-card)`
                      // reference (canvas isn't part of the CSS cascade), and hardcoding one shade
                      // would be wrong in the other theme.
                      hoverOffset: 10,
                    }],
                  }}
                  options={{
                    cutout: '72%',
                    // Room for hoverOffset to push a slice outward without it clipping against the
                    // canvas edge -- with zero padding the pushed-out arc was drawing past the
                    // canvas bounds and getting flattened wherever it did, instead of staying round.
                    layout: { padding: 12 },
                    plugins: {
                      legend: { display: false },
                      // The floating tooltip had nowhere to go on a donut this small without
                      // overlapping the center Total label -- replaced by driving that same label
                      // from hover state instead (below), one label, never two competing for the
                      // same 160x160px.
                      tooltip: { enabled: false },
                    },
                    // animateScale (grow from center) alongside the default rotate -- Chart.js's
                    // usual arc-only rotate reads as static on a donut this small; the two together
                    // are what actually reads as "the chart appearing", not just a color change.
                    animation: { animateRotate: true, animateScale: true, duration: 900, easing: 'easeOutQuart' },
                    onHover: (_event, elements) => {
                      setHoveredCategoryIndex(elements.length > 0 ? elements[0].index : null);
                    },
                  }}
                />
                {/* pointer-events-none: this overlay's `inset-0` box, not just its centered text,
                    was sitting directly on top of the canvas -- it swallowed every mouse event
                    across the whole donut before Chart.js's own hover handling ever saw them, so
                    hoverOffset (and onHover below) never fired no matter what the chart's own
                    options said. */}
                <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none px-6 text-center">
                  {hoveredCategoryIndex !== null && categoryEntries[hoveredCategoryIndex] ? (
                    <>
                      {/* max-w here is deliberately narrower than the container's own px-6 gap:
                          at cutout 72% + layout.padding 12 on a 160px canvas the hole is only
                          ~98px across at its vertical center, and the two-line label's top line
                          sits above center where the circle is narrower still. max-w-full let text
                          reach the container's 112px, well past the hole, so it visually spilled
                          into the ring instead of stopping at its edge. */}
                      <span className="text-sm font-bold text-ink truncate max-w-[76px]">{categoryEntries[hoveredCategoryIndex][0]}</span>
                      <span className="text-2xs text-muted">{fmt(categoryEntries[hoveredCategoryIndex][1])}</span>
                    </>
                  ) : (
                    <>
                      <span className="text-lg font-bold text-ink">{fmt(totalSpend)}</span>
                      <span className="text-2xs text-muted">Total</span>
                    </>
                  )}
                </div>
              </div>
              <div className="space-y-2 flex-1">
                {categoryEntries.slice(0, 6).map(([name, val], i) => (
                  <div key={name} className="flex items-center justify-between text-xs">
                    <span className="flex items-center gap-2 text-ink">
                      <span className="w-2 h-2 rounded-full" style={{ background: donutColors[i % donutColors.length] }} />
                      {name}
                    </span>
                    {/* Bug 44. categoryEntries can be non-empty with every amount at zero, which
                        the length check above doesn't catch -- totalSpend is then 0 and val /
                        totalSpend is 0/0, rendering the literal string "NaN%". */}
                    <span className="text-muted">{totalSpend > 0 ? ((val / totalSpend) * 100).toFixed(0) : '0'}%</span>
                    <span className="font-medium text-ink">{fmt(val)}</span>
                  </div>
                ))}
              </div>
              {/* Not gated on the "Other" category name -- "Other" is a real, resolvable category
                  (the categorization engine's fallback when nothing matched), so a transaction
                  landing there isn't necessarily uncategorized. categoryReviewWarning instead
                  reuses Transaction.needsCategoryReview, the same per-transaction signal Ledger's
                  "needs review" badge already shows -- flagged only when a default("Other")-
                  sourced guess ALSO misses the user's own confidence threshold. */}
              {summary.categoryReviewWarning && (
                <div className="bg-warning-bg border border-warning/30 rounded-xl2 px-4 py-3 flex items-start gap-2.5 mt-4">
                  <AlertTriangle size={15} className="text-warning flex-shrink-0 mt-0.5" />
                  <div className="flex-1 min-w-0">
                    <p className="text-xs font-medium text-ink">Spending needs category review</p>
                    <p data-testid="category-review-detail" className="text-2xs text-muted mt-0.5">
                      {`${fmt(summary.categoryReviewSpendAmount)} (${summary.categoryReviewSpendPct.toFixed(0)}%) across ${summary.categoryReviewTransactionCount} transaction${summary.categoryReviewTransactionCount === 1 ? '' : 's'} ${periodLabel} landed in a generic category and could use a closer look.`}
                    </p>
                    <Link to="/app/transactions" className="inline-block mt-2 text-2xs font-medium text-primary">
                      Review transactions →
                    </Link>
                  </div>
                </div>
              )}
              <Link to="/app/reports" className="mt-4 text-center text-xs font-medium text-primary bg-primary-light rounded-lg py-2.5">
                View Full Report →
              </Link>
            </>
          )}
        </FinoraCard>
      </div>

      {/* Accounts / Recent Transactions / Budget Progress / Goals */}
      <div className="grid md:grid-cols-2 xl:grid-cols-4 gap-6 mb-6">
        <FinoraCard>
          <SectionHeader title="Accounts Overview" viewAllTo="/app/accounts" size="sm" />
          <div className="space-y-3">
            {accountsQ.isLoading ? (
              showAccountsSkeleton && (
                <Skeleton.Region label="Loading accounts">
                  <div className="space-y-3">
                    {[0, 1].map((i) => (
                      <div key={i} className="flex items-center gap-3">
                        <Skeleton.Circle size={36} />
                        <div className="min-w-0 flex-1 space-y-1">
                          <Skeleton.Text width="w-28" />
                          <Skeleton.Text width="w-16" className="h-2.5" />
                        </div>
                        <Skeleton.Text width="w-14" className="h-4" />
                      </div>
                    ))}
                  </div>
                </Skeleton.Region>
              )
            ) : accounts.length === 0 ? (
              <EmptyState
                icon={Wallet}
                iconBg="bg-accent-blue-bg"
                iconColor="text-accent-blue"
                title="No accounts yet"
                desc="Add your bank accounts to get a complete view."
                cta={
                  <Link to="/app/setup" className="inline-block bg-primary text-on-primary hover:bg-primary-dark rounded-lg px-4 py-2 text-xs font-semibold">
                    + Add Account
                  </Link>
                }
              />
            ) : accounts.map((a) => (
              <div key={a.id} className="flex items-center gap-3">
                <BankLogo bank={a.bank} size={36} />
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-medium text-ink truncate">{a.name}</p>
                  <p className="text-2xs text-muted truncate">
                    {a.accountNumberMasked ? a.accountNumberMasked : a.accountType.replace('_', ' ')}
                  </p>
                </div>
                <span className="text-sm font-semibold text-ink flex-shrink-0">{fmt(a.balance)}</span>
              </div>
            ))}
          </div>
        </FinoraCard>

        <FinoraCard>
          <SectionHeader title="Recent Transactions" viewAllTo="/app/transactions" size="sm" />
          <div className="space-y-3">
            {recentTxns.length === 0 ? (
              <EmptyState
                icon={Receipt}
                iconBg="bg-accent-green-bg"
                iconColor="text-accent-green"
                title="No transactions yet"
                desc="Your recent transactions will appear here."
                cta={
                  <Button onClick={() => setShowAddModal(true)}>
                    + Add Transaction
                  </Button>
                }
              />
            ) : recentTxns.map((t) => {
              const cat = categoriesById[t.categoryId];
              const Icon = ICON_COMPONENTS[cat?.icon ?? 'tag'] ?? ShoppingBag;
              const color = t.type === 'INCOME' ? '#16a34a' : (COLOR_HEX[cat?.color ?? 'gray'] ?? '#262A33');
              return (
                <div key={t.id} className="flex items-center gap-3">
                  <div className="w-9 h-9 rounded-full flex items-center justify-center flex-shrink-0" style={{ background: color + '20' }}>
                    <MerchantLogo
                      merchant={t.merchant}
                      size={36}
                      className="rounded-full"
                      fallback={<Icon size={16} style={{ color }} />}
                    />
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-medium text-ink truncate">{t.description || t.merchant}</p>
                    <p className="text-2xs text-muted">{t.date}</p>
                  </div>
                  <span className={`text-sm font-semibold flex-shrink-0 ${t.type === 'INCOME' ? 'text-success' : 'text-danger'}`}>
                    {t.type === 'INCOME' ? '+' : '-'}{fmt(t.amount)}
                  </span>
                </div>
              );
            })}
          </div>
        </FinoraCard>

        <FinoraCard>
          <SectionHeader title="Budget Progress" viewAllTo="/app/budgets" size="sm" />
          <div className="space-y-4">
            {budgetsQ.isLoading ? (
              showBudgetsSkeleton && (
                <Skeleton.Region label="Loading budgets">
                  <div className="space-y-4">
                    {[0, 1].map((i) => (
                      <div key={i}>
                        <div className="flex justify-between items-baseline mb-1.5">
                          <Skeleton.Text width="w-20" />
                          <Skeleton.Text width="w-8" className="h-2.5" />
                        </div>
                        <Skeleton.Block className="h-1.5 w-full" />
                      </div>
                    ))}
                  </div>
                </Skeleton.Region>
              )
            ) : budgets.length === 0 ? (
              <EmptyState
                icon={PiggyBank}
                iconBg="bg-accent-orange-bg"
                iconColor="text-accent-orange"
                title="No budgets set"
                desc="Create budgets to track your spending and stay on track."
                cta={
                  <Link to="/app/budgets" className="inline-block bg-primary text-on-primary hover:bg-primary-dark rounded-lg px-4 py-2 text-xs font-semibold">
                    Create Budget
                  </Link>
                }
              />
            ) : (
              <>
                {budgets.map((b) => {
                  const pct = b.monthlyLimit > 0 ? Math.min(100, (b.spentThisMonth / b.monthlyLimit) * 100) : 0;
                  const over = b.spentThisMonth > b.monthlyLimit;
                  return (
                    <div key={b.id}>
                      <div className="flex justify-between items-baseline mb-1.5">
                        <span className="text-sm font-medium text-ink">{b.categoryName}</span>
                        <span className={`text-xs ${over ? 'text-danger font-medium' : 'text-muted'}`}>{pct.toFixed(0)}%</span>
                      </div>
                      <div className="h-1.5 bg-bg rounded-full overflow-hidden mb-1">
                        <div className={`h-full rounded-full ${over ? 'bg-danger' : 'bg-primary'}`} style={{ width: `${pct}%` }} />
                      </div>
                      <p className="text-2xs text-muted">{fmt(b.spentThisMonth)} of {fmt(b.monthlyLimit)}</p>
                    </div>
                  );
                })}
                <Link to="/app/budgets" className="block text-center text-xs font-medium text-primary bg-primary-light rounded-lg py-2.5">
                  <Target size={12} className="inline mr-1 -mt-0.5" /> Manage Budgets
                </Link>
              </>
            )}
          </div>
        </FinoraCard>

        <FinoraCard>
          <SectionHeader title="Goals" viewAllTo="/app/goals" size="sm" />
          <div className="space-y-4">
            {goalsQ.isLoading ? (
              showGoalsSkeleton && (
                <Skeleton.Region label="Loading goals">
                  <div className="space-y-4">
                    {[0, 1].map((i) => (
                      <div key={i}>
                        <div className="flex justify-between items-baseline mb-1.5">
                          <Skeleton.Text width="w-20" />
                          <Skeleton.Text width="w-8" className="h-2.5" />
                        </div>
                        <Skeleton.Block className="h-1.5 w-full" />
                      </div>
                    ))}
                  </div>
                </Skeleton.Region>
              )
            ) : goals.length === 0 ? (
              <EmptyState
                icon={Target}
                iconBg="bg-accent-red-bg"
                iconColor="text-accent-red"
                title="No goals yet"
                desc="Set your financial goals and achieve them step by step."
                cta={
                  <Link to="/app/goals" className="inline-block bg-primary text-on-primary hover:bg-primary-dark rounded-lg px-4 py-2 text-xs font-semibold">
                    + Create Goal
                  </Link>
                }
              />
            ) : (
              <>
                {goals.map((g) => {
                  const pct = g.targetAmount > 0 ? Math.min(100, (g.currentAmount / g.targetAmount) * 100) : 0;
                  return (
                    <div key={g.id}>
                      <div className="flex justify-between items-baseline mb-1.5">
                        <span className="text-sm font-medium text-ink">{g.name}</span>
                        <span className="text-xs text-muted">{pct.toFixed(0)}%</span>
                      </div>
                      <div className="h-1.5 bg-bg rounded-full overflow-hidden mb-1">
                        <div className="h-full bg-primary rounded-full" style={{ width: `${pct}%` }} />
                      </div>
                      <p className="text-2xs text-muted">{fmt(g.currentAmount)} of {fmt(g.targetAmount)}</p>
                    </div>
                  );
                })}
                <Link to="/app/goals" className="block text-center text-xs font-medium text-primary bg-primary-light rounded-lg py-2.5">
                  + Create New Goal
                </Link>
              </>
            )}
          </div>
        </FinoraCard>
      </div>

      {/* AI Insights + Quick Actions. AI Insights used to hide entirely with nothing computed yet
          -- now always visible, generic starter tips in place of real sentences/movers, so a new
          user sees the section exists rather than it silently not being there. Quick Actions is
          new: a shortcut grid to the same destinations scattered across this page's own empty
          states and CTAs, gathered in one place the way the reference design has it. */}
      <div className="grid lg:grid-cols-[1.6fr_1fr] gap-6 mb-6">
        <FinoraCard padding="none" className="overflow-hidden">
          <div className="flex items-center justify-between px-6 pt-5 pb-4">
            <div className="flex items-center gap-2">
              <div className="w-8 h-8 rounded-full bg-primary-light flex items-center justify-center">
                <Sparkles size={15} className="text-primary" />
              </div>
              <h2 className="font-semibold text-ink">AI Insights</h2>
              <Badge label="Beta" />
            </div>
            <Link to="/app/insights" className="bg-primary text-on-primary text-xs font-semibold rounded-lg px-4 py-2">
              View Insights
            </Link>
          </div>
          {sentences.length === 0 && movers.length === 0 ? (
            <div className="px-6 pb-5">
              <p className="text-xs text-muted mb-3">Get personalized insights to improve your financial health.</p>
              <ul className="space-y-2">
                {[
                  // Bug fix: this used to say "get AI-powered insights", contradicting the honest
                  // "rule-based statistics, not an LLM assistant" framing the Insights page and
                  // mobile screen both state plainly -- someone who only ever saw this Dashboard
                  // card would come away with the wrong idea about what the feature actually is.
                  'Upload or import more transactions to see spending insights.',
                  'Track your spending to identify patterns and save more.',
                  'Set budgets to stay in control of your finances.',
                ].map((tip) => (
                  <li key={tip} className="text-sm text-ink flex items-start gap-2">
                    <span className="w-1.5 h-1.5 rounded-full bg-primary mt-1.5 flex-shrink-0" />
                    {tip}
                  </li>
                ))}
              </ul>
            </div>
          ) : (
            <div className="px-6 pb-5 grid md:grid-cols-2 gap-x-8 gap-y-3">
              {sentences.length > 0 && (
                <div className="space-y-2">
                  {sentences.slice(0, 3).map((s, i) => (
                    <p key={i} className="text-sm text-ink flex items-start gap-2">
                      <span className="w-1.5 h-1.5 rounded-full bg-primary mt-1.5 flex-shrink-0" />
                      {s}
                    </p>
                  ))}
                </div>
              )}
              {movers.length > 0 && (
                <div className="space-y-2">
                  {/* Deliberately no period in this heading. These movers come from the INSIGHTS
                      query, which resolves its own reporting month over a differently-filtered set
                      (expenses only, where the dashboard also counts income), so the two can pick
                      different months for an account whose newest month holds only income.
                      `periodLabel` describes the dashboard's month and would be asserting a period
                      this list does not necessarily belong to -- the same class of claim as Bug 05.
                      The insight sentences rendered above already carry their own period wording,
                      built server-side by InsightsService. */}
                  <p className="text-2xs uppercase tracking-wide text-muted mb-1">Biggest movers</p>
                  {movers.map((m) => (
                    <div key={m.category} className="flex items-center justify-between text-sm">
                      <span className="text-ink">{m.category}</span>
                      <span className={`flex items-center gap-1 font-medium ${(m.pctChange ?? 0) >= 0 ? 'text-danger' : 'text-success'}`}>
                        {(m.pctChange ?? 0) >= 0 ? <TrendingUp size={13} /> : <TrendingDown size={13} />}
                        {Math.abs(m.pctChange ?? 0).toFixed(0)}%
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </FinoraCard>

        <FinoraCard>
          <h2 className="font-semibold text-ink text-sm mb-4">Quick Actions</h2>
          <div className="grid grid-cols-3 gap-3">
            {[
              { icon: UploadCloud, label: 'Import Statement', to: '/app/import' },
              { icon: Plus, label: 'Add Transaction', onClick: () => setShowAddModal(true) },
              // D-21 originally scoped three setup paths (import, Gmail, manual) -- Gmail has no
              // per-section empty-state card of its own the way Import (Cash Flow) and Add
              // Transaction (Recent Transactions) do, so it lives here instead rather than being
              // dropped from the redesign entirely.
              { icon: Mail, label: 'Connect Gmail', to: '/app/settings' },
              { icon: Target, label: 'Create Budget', to: '/app/budgets' },
              { icon: PieChart, label: 'View Reports', to: '/app/reports' },
              { icon: TrendingUp, label: 'Manage Goals', to: '/app/goals' },
              { icon: LineChartIcon, label: 'Investments', to: '/app/investments' },
            ].map((action) => (
              <QuickActionCard key={action.label} icon={action.icon} label={action.label} to={action.to} onClick={action.onClick} />
            ))}
          </div>
        </FinoraCard>
      </div>

      {/* Subscriptions & Recurring Payments — RecurringService.detectForUser has computed this
          (merchant, cadence, average amount, projected next charge) since before this session,
          consumed by nothing until now: the Ledger/Reports "recurring" badge is the only place
          this data ever reached a screen. Read-only surfacing, same as Financial Health Score and
          AI Insights above -- no new detection logic, just showing what already exists. */}
      {upcomingRecurring.length > 0 && (
        <FinoraCard padding="none" className="mb-6 overflow-hidden">
          <div className="flex items-center gap-2 px-6 pt-5 pb-4">
            <div className="w-8 h-8 rounded-full bg-primary-light flex items-center justify-center">
              <Repeat size={15} className="text-primary" />
            </div>
            <h2 className="font-semibold text-ink">Subscriptions &amp; Recurring Payments</h2>
          </div>
          <ul className="px-6 pb-5 space-y-3">
            {upcomingRecurring.map((r) => (
              <li key={r.merchant} className="flex items-center justify-between text-sm gap-3">
                <div>
                  <span className="text-ink font-medium">{r.merchant}</span>
                  <Badge label={r.label} className="ml-2" />
                </div>
                <div className="flex items-center gap-3">
                  <div className="text-right">
                    <p className="text-ink font-medium">{fmt(r.averageAmount)}</p>
                    <p className="text-xs text-muted">{expectedLabel(r.nextEstimate)}</p>
                  </div>
                  <button
                    type="button"
                    onClick={() => dismissRecurring.mutate(r.merchant)}
                    disabled={dismissRecurring.isPending}
                    aria-label={`Not recurring: dismiss ${r.merchant}`}
                    title="Not recurring"
                    className="text-muted hover:text-ink disabled:opacity-50 shrink-0"
                  >
                    <X size={15} />
                  </button>
                </div>
              </li>
            ))}
          </ul>
        </FinoraCard>
      )}

      {/* Floating action button — was purely decorative before (no onClick at all). Statement
          import is the primary way new data is meant to enter Finora, so that's what this
          now opens rather than, say, a generic "add transaction" menu. */}
      <MotionLink
        to="/app/import"
        whileTap={prefersReducedMotion ? undefined : { scale: 0.92 }}
        whileHover={prefersReducedMotion ? undefined : { scale: 1.05 }}
        className="fixed bottom-8 right-8 w-14 h-14 rounded-full bg-primary text-on-primary shadow-soft flex items-center justify-center hover:bg-primary-dark"
        title="Import a bank or credit card statement"
      >
        <Plus size={24} />
      </MotionLink>

      {showAddModal && (
        <AddTransactionModal onClose={() => setShowAddModal(false)} onSaved={onTransactionAdded} />
      )}
    </div>
  );
}

/**
 * Shown while summaryQ/recentTxnsQ (the two structurally-blocking queries -- see the comment
 * above `blockingLoading`) are still in flight. Approximates the real page's shape (greeting, KPI
 * grid, health score block, cash-flow + spending-breakdown row) closely enough that swapping in
 * real content doesn't itself cause a layout jump, without trying to pixel-match every card.
 */
function DashboardSkeleton() {
  return (
    <Skeleton.Region label="Loading your dashboard" className="space-y-6">
      <div>
        <Skeleton.Text width="w-64" className="h-7 mb-2" />
        <Skeleton.Text width="w-96" />
      </div>
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-4">
        {Array.from({ length: 5 }).map((_, i) => <Skeleton.Card key={i} />)}
      </div>
      <Skeleton.Block className="h-40 rounded-xl2" />
      <div className="grid lg:grid-cols-[1.6fr_1fr] gap-6">
        <Skeleton.Block className="h-72 rounded-xl2" />
        <Skeleton.Block className="h-72 rounded-xl2" />
      </div>
    </Skeleton.Region>
  );
}

// A vertical guide line at the hovered month, tying the two lines together at a glance --
// Chart.js has no built-in crosshair, and pulling in a plugin for one dashed line is more
// dependency than the effect is worth, so this is the ~15 lines it would otherwise cost.
// Scoped to this one chart via <Line plugins={[...]}>, not ChartJS.register(), so it can't affect
// any other chart on the page.
const cashFlowCrosshairPlugin: Plugin<'line'> = {
  id: 'cashFlowCrosshair',
  afterDraw(chart) {
    const active = chart.tooltip?.getActiveElements();
    if (!active || active.length === 0) return;
    const { ctx, chartArea } = chart;
    const x = active[0].element.x;
    ctx.save();
    ctx.beginPath();
    ctx.setLineDash([4, 4]);
    ctx.moveTo(x, chartArea.top);
    ctx.lineTo(x, chartArea.bottom);
    ctx.lineWidth = 1;
    // rgba(), not a `--color-*` custom property -- canvas draw calls need a resolved color (see
    // the Spending Breakdown donut's own hoverBorderColor comment above for why var() silently
    // fails here). This is --color-muted's light-mode value; a fixed slate reads fine as a subtle
    // guide line on the dark-mode card too, so it isn't worth threading theme state into a plugin
    // that has no access to React context.
    ctx.strokeStyle = 'rgba(100, 116, 139, 0.35)';
    ctx.stroke();
    ctx.restore();
  },
};

function CashFlowChart({ series }: { series: { month: string; income: number; expense: number }[] }) {
  const labels = series.map((s) => monthLabel(s.month));
  // Bug fix: a bare <canvas> is invisible to screen readers -- Chart.js/react-chartjs-2 render
  // no accessible text equivalent on their own (see the Charting Data design guideline: "provide
  // both accessibility labels that describe chart values and components"). ChartProps extends
  // CanvasHTMLAttributes, so role/aria-label pass straight through to the underlying canvas.
  // Unlike the Spending Breakdown donut below, this line chart has no adjacent text table that
  // already carries the same data, so the label has to summarize the trend itself.
  const totalIncome = series.reduce((s, m) => s + m.income, 0);
  const totalExpense = series.reduce((s, m) => s + m.expense, 0);
  const chartAriaLabel = series.length === 0
    ? 'Cash flow line chart, no data yet'
    : `Cash flow line chart from ${monthLabel(series[0].month)} to ${monthLabel(series[series.length - 1].month)}: `
      + `total income ${fmt(totalIncome)}, total expenses ${fmt(totalExpense)}.`;
  return (
    <Line
      role="img"
      aria-label={chartAriaLabel}
      data={{
        labels,
        datasets: [
          {
            label: 'Income', data: series.map((s) => s.income), borderColor: '#16a34a', backgroundColor: 'rgba(22,163,74,0.08)', fill: true, tension: 0.3,
            // Points stay invisible at rest (radius 0, matching how this chart already looked) and
            // only appear on hover -- pointHoverRadius is what actually reads as "hovering did
            // something", not just the tooltip box appearing off to the side.
            pointRadius: 0, pointHoverRadius: 5, pointHoverBackgroundColor: '#16a34a', pointHoverBorderColor: '#fff', pointHoverBorderWidth: 2,
          },
          {
            label: 'Expenses', data: series.map((s) => s.expense), borderColor: '#ef4444', backgroundColor: 'rgba(239,68,68,0.08)', fill: true, tension: 0.3,
            pointRadius: 0, pointHoverRadius: 5, pointHoverBackgroundColor: '#ef4444', pointHoverBorderColor: '#fff', pointHoverBorderWidth: 2,
          },
        ],
      }}
      options={{
        ...baseChartOptions,
        // fmt(), not string concatenation: a negative tick must render as "-₹500", not "₹-500".
        // Dormant while this chart plots only income and expense (non-negative by construction in
        // DashboardService) and live the moment the component or this options object is reused
        // for a net series -- which is exactly how the same bug got everywhere else it was fixed.
        scales: { y: { ticks: { callback: (v) => fmt(Number(v)) } } },
        animation: { duration: 900, easing: 'easeOutQuart' },
        // mode: 'index' + intersect: false -- hovering anywhere along a month's x-position shows
        // both Income and Expenses together, not just whichever line's pixel the cursor happens to
        // sit exactly on (Chart.js's default `intersect: true` misses if the cursor is a pixel off
        // a thin line, which is most of the chart's area on a click-and-drag trackpad).
        interaction: { mode: 'index' as const, intersect: false },
      }}
      plugins={[cashFlowCrosshairPlugin]}
    />
  );
}

