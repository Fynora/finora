import { useMemo, useState } from 'react';
import {
  Pressable, RefreshControl, ScrollView, StyleSheet, Text, useWindowDimensions, View,
} from 'react-native';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigation, useRoute, type RouteProp } from '@react-navigation/native';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { usePreventScreenCapture } from 'expo-screen-capture';
import Ionicons from '@expo/vector-icons/Ionicons';
import { AddTransactionSheet } from './AddTransactionSheet';
import { AccountsCard } from '../components/dashboard/AccountsCard';
import { FinancialNoteCard } from '../components/dashboard/FinancialNoteCard';
import { Card, EmptyState, SectionHeading } from '../components/Card';
import { CashFlowMiniCard } from '../components/dashboard/CashFlowMiniCard';
import { GoalsRow } from '../components/dashboard/GoalsRow';
import { HealthFactorsRow } from '../components/dashboard/HealthFactorsRow';
import { HealthHero } from '../components/dashboard/HealthHero';
import { LedgerSnapshotCard } from '../components/dashboard/LedgerSnapshotCard';
import { SkeletonCard, SkeletonChart, SkeletonTransactionRow } from '../components/skeletons/Skeletons';
import { ChecklistWidget } from '../onboarding/ChecklistWidget';
import { DonutChart, type Slice } from '../components/charts/DonutChart';
import { CashFlowChart } from '../components/charts/CashFlowChart';
import {
  accountsApi, budgetsApi, dashboardApi, goalsApi, insightsApi, recurringApi, reportsApi,
  transactionsApi, userApi, type RecurringItem,
} from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import { CHART_PALETTE, bucketTopSlices } from '../lib/chartGeometry';
import {
  fmtCurrency, fromLocalDateString, greeting, monthDateRange, monthLabel, monthLabelLong,
} from '../lib/format';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import { usePrefetchAdjacentScreens } from '../lib/prefetchAdjacentScreens';
import { scoreLabel, healthColor } from '../lib/health';
import { deriveRefreshing, isPausedCold } from '../lib/refreshingIndicator';
import { reviewNudgeLabel, reviewQueueCount } from '../lib/reviewQueue';
import { useDashboardKpis } from '../lib/useDashboardKpis';
import { useLargeFontScale } from '../lib/useLargeFontScale';
import { radius, spacing, useTheme } from '../theme';
import type { AppTabParamList } from '../navigation/types';

type CashFlowRange = '3M' | '6M' | '12M';
const RANGE_MONTHS: Record<CashFlowRange, number> = { '3M': 3, '6M': 6, '12M': 12 };

/**
 * The label for the remainder bucket, and also a category name the backend really assigns -- which
 * is exactly why it is a named constant: the two have to be compared, not merely both spelled the
 * same way in two places.
 */
const OTHER_LABEL = 'Other';

/**
 * Ported from frontend/src/pages/Dashboard.tsx's identical expectedLabel -- with one deliberate
 * change: web parses `nextEstimate` with a raw `new Date(dateStr + 'T00:00:00')`, which is safe on
 * a browser's own local clock but is exactly the UTC-midnight parsing bug fromLocalDateString
 * exists to avoid on this app's other LocalDate fields (see that function's own doc comment) --
 * RecurringItem.nextEstimate is a LocalDate ("2026-09-15"), not an Instant, so it gets the same
 * local-midnight treatment every other LocalDate on this screen already does.
 */
function recurringExpectedLabel(nextEstimate: string): string {
  const target = fromLocalDateString(nextEstimate);
  const days = Math.round((target.getTime() - new Date().setHours(0, 0, 0, 0)) / 86_400_000);
  const date = target.toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
  if (days < 0) return `expected around ${date}`;
  if (days === 0) return 'expected today';
  if (days === 1) return 'expected tomorrow';
  return `expected in ${days} days (${date})`;
}

export function DashboardScreen() {
  // SEC-17 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Balances and
  // account totals render on this screen the moment it mounts -- prevents screenshots/screen
  // recording for as long as it stays mounted, and automatically stops preventing them the
  // instant it unmounts (navigating away, or the app backgrounding through AppLockGate). iOS 13+/
  // all Android versions per expo-screen-capture's own platform notes; older iOS silently does
  // nothing rather than erroring, which is an acceptable degrade, not a broken state.
  usePreventScreenCapture();
  usePrefetchAdjacentScreens();
  const c = useTheme();
  const largeText = useLargeFontScale();
  const insets = useSafeAreaInsets();
  const { width } = useWindowDimensions();
  const { fullName } = useAuth();
  const navigation = useNavigation<BottomTabNavigationProp<AppTabParamList>>();
  const queryClient = useQueryClient();
  const route = useRoute<RouteProp<AppTabParamList, 'Home'>>();
  const { showToast } = useToast();
  const [cashFlowRange, setCashFlowRange] = useState<CashFlowRange>('6M');
  const [confirmingDuplicateId, setConfirmingDuplicateId] = useState<string | null>(null);
  const [duplicateConfirmError, setDuplicateConfirmError] = useState<string | null>(null);
  // Quick Actions' "Add Transaction" -- same controlled-sheet pattern LedgerScreen already uses.
  const [addingTransaction, setAddingTransaction] = useState(false);
  // Set by the bottom-nav floating "+" button's "Add Transaction" row (QuickActionSheet, via
  // AppTabs) -- opens the same sheet Quick Actions' own "Add Transaction" cell already opens.
  const [consumedAddTransactionNonce, setConsumedAddTransactionNonce] = useState<number | null>(null);

  // Adjusted during render, not in an effect -- same "reset state when an input changes" pattern
  // LedgerScreen's own activeDrillThrough/consumedNonce pair uses (see that screen's own comment):
  // this tab stays mounted, so route.params alone can't be read directly (a second tap on the FAB
  // has nothing to compare against without a state copy of the last-consumed nonce), and adjusting
  // in render avoids the extra commit a useEffect-based version would cost.
  const incomingAddTransaction = route.params;
  if (
    incomingAddTransaction?.openAddTransaction &&
    incomingAddTransaction.nonce !== undefined &&
    incomingAddTransaction.nonce !== consumedAddTransactionNonce
  ) {
    setConsumedAddTransactionNonce(incomingAddTransaction.nonce);
    setAddingTransaction(true);
  }

  // useQueries (not one Promise.all) so a single failing endpoint degrades to one empty section
  // instead of blanking the screen -- same reasoning as the web Dashboard's own comment.
  const [summaryQ, accountsQ, recentTxnsQ, goalsQ, insightsQ, settingsQ] = useQueries({
    queries: [
      { queryKey: ['dashboard-summary'], queryFn: () => dashboardApi.summary() },
      { queryKey: ['accounts'], queryFn: () => accountsApi.list() },
      {
        queryKey: ['recent-transactions'],
        queryFn: () => transactionsApi.search({ page: 0, size: 5, sortField: 'date', sortDir: 'desc' }),
      },
      { queryKey: ['goals'], queryFn: () => goalsApi.list() },
      { queryKey: ['insights'], queryFn: () => insightsApi.get(), retry: false },
      { queryKey: ['user-settings'], queryFn: () => userApi.get() },
    ],
  });

  const availableMonthsQ = useQuery({
    queryKey: ['report-months'],
    queryFn: () => reportsApi.availableMonths(),
  });

  // Phase 4 (Medium-Tier Parity). Same ['budgets'] key usePrefetchAdjacentScreens already
  // prefetches on mount, so this reads that warm cache rather than firing a second request. Kept
  // out of the useQueries block above for the same reason reviewSinglesQ/reviewGroupsQ are below.
  const budgetsQ = useQuery({
    queryKey: ['budgets'],
    queryFn: () => budgetsApi.list(),
  });

  // Phase 4. RecurringService.detectForUser has computed this (merchant, cadence, average amount,
  // projected next charge) since before this session; the Ledger/Reports "recurring" badge was the
  // only place it ever reached a screen. Read-only surfacing, no new detection logic.
  const recurringQ = useQuery({
    queryKey: ['recurring'],
    queryFn: () => recurringApi.list(),
  });

  // Phase 6. A wrongly-detected group (a one-off large purchase RecurringService mistook for a
  // subscription, e.g.) had no way to be dismissed until now -- see recurringApi.dismiss's own
  // comment on why `merchant`, not an id, is the identity. Optimistic removal, same reasoning as
  // web's identical mutation (frontend/src/pages/Dashboard.tsx): this list is purely informational,
  // so there is no real cost to a rare rollback flashing the row back in on a failed request.
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

  // The categorization backlog behind the nudge below. Kept out of the useQueries block above so
  // the destructured indices there stay stable. These two keys never retry -- that policy is set
  // once in api/queryClient.ts rather than here, because per-observer options on a shared key are
  // last-writer-wins across screens (see that file's comment).
  const reviewSinglesQ = useQuery({
    queryKey: ['needs-review'],
    queryFn: () => transactionsApi.needsReview(),
  });
  const reviewGroupsQ = useQuery({
    queryKey: ['needs-review-groups'],
    queryFn: () => transactionsApi.needsReviewGroups(),
  });
  // BOTH halves have to have loaded before the nudge may state a number. The backlog is the sum of
  // two disjoint queries, so if one fails and the other returns rows, `data ?? []` yields a
  // specific, confident, WRONG total -- "2 transactions need a quick look" when the real figure is
  // 12 -- and it is used verbatim as the accessibilityLabel too. Failing silently was always the
  // intent here; that only actually holds when a partial failure suppresses the nudge as well.
  // CategoryReviewScreen (still reachable from More) is where a partial outage gets disclosed.
  const reviewCountKnown = reviewSinglesQ.isSuccess && reviewGroupsQ.isSuccess;
  const reviewCount = reviewQueueCount({
    singles: reviewSinglesQ.data ?? [],
    groups: reviewGroupsQ.data ?? [],
  });
  // Server returns these ascending, so the tail is the most recent N. Depends on
  // availableMonthsQ.data directly, not a `?? []`-derived local -- that fallback would build a new
  // array reference every render while data is still undefined, which defeats this memo entirely.
  const monthsInRange = useMemo(
    () => (availableMonthsQ.data ?? []).slice(-RANGE_MONTHS[cashFlowRange]),
    [availableMonthsQ.data, cashFlowRange]
  );
  const monthlyReportsQ = useQueries({
    queries: monthsInRange.map((month) => ({
      queryKey: ['report', month],
      queryFn: () => reportsApi.forMonth(month),
      staleTime: 5 * 60_000, // a past month's totals don't change once the month is over
    })),
  });
  const cashFlowPoints = useMemo(
    () =>
      monthlyReportsQ
        .map((q) => q.data)
        .filter((d): d is NonNullable<typeof d> => !!d)
        .map((d) => ({ label: monthLabel(d.month), income: d.income, expense: d.expense })),
    [monthlyReportsQ]
  );

  // Cash Flow's own loading/error state, kept separate from the screen-wide gates below because it
  // is fed by its own two-step chain (report-months, then one report query per month in range) that
  // neither `summary` nor initialLoad knows anything about.
  //
  // isPending rather than isLoading: a query that has settled as an error is not pending, so a
  // failure resolves the skeleton instead of leaving it spinning forever, while a background
  // refetch of already-successful data leaves the chart on screen rather than blanking it.
  // isPausedCold is excluded from "settling" deliberately: a query paused for lack of connectivity
  // is pending and will STAY pending until the network returns, so treating it as loading would
  // replace the old false empty state with a skeleton that spins forever. Offline with nothing
  // cached, this card should say it cannot show the chart -- not imply one is on its way.
  const cashFlowSettling =
    (availableMonthsQ.isPending && !isPausedCold(availableMonthsQ)) ||
    monthlyReportsQ.some((q) => q.isPending && !isPausedCold(q));
  // Paused months count as missing for the same reason they are counted as failed: either way the
  // month is absent from an index-based chart that would otherwise close the gap silently.
  const cashFlowMissingMonths = monthlyReportsQ.filter((q) => q.isError || isPausedCold(q)).length;
  // "Genuinely nothing to draw" is monthsInRange being empty -- that is a real answer and must keep
  // reaching CashFlowChart's own empty state. This is the other case: months exist but not one of
  // them could be loaded.
  const cashFlowUnavailable =
    availableMonthsQ.isError ||
    isPausedCold(availableMonthsQ) ||
    (monthsInRange.length > 0 && cashFlowPoints.length === 0);
  // Mirrors CashFlowMiniCard's own `points.length === 0` null-return exactly (same points array
  // it's handed below) -- when it renders nothing, its sibling cardRowItem View still claims half
  // the row's width via flex:1, leaving a blank gap the same size as the card that isn't there.
  // AccountsCard goes full-width instead of sitting in a half-width column with nothing beside it.
  const showCashFlowMini = !(cashFlowSettling || cashFlowUnavailable) && cashFlowPoints.length > 0;

  // Bound now (AccountsCard, below) -- previously fetched only to prewarm AccountsScreen's cache.
  // Still kept out of BOTH the initial-load gate (the shell shouldn't wait on a fetch whose
  // result renders inside its own card, same reasoning as goals/insights below) and the
  // refreshing indicator (a pull gesture that visibly finishes shouldn't keep spinning on a fetch
  // that resolves independently of everything else the user is staring at) -- refresh() below
  // still unconditionally invalidates it, so AccountsCard never shows stale data after a pull.
  //
  // Tracks every query whose data IS rendered and that refresh() (below) actually invalidates --
  // summary/recent-transactions plus goals, insights, the available-months list, and the Cash
  // Flow chart's per-month report queries. Missing any of these would let the spinner disappear
  // while a visible section is still silently updating underneath it -- availableMonthsQ itself
  // has to be included too, not just the per-month queries it drives, or a refresh that adds a
  // newly-available month shows nothing happening until that new month's own query mounts a beat
  // later. deriveRefreshing's per-query isLoading gate (not just this initialLoad flag) is what
  // keeps that later-mounted query from flipping the spinner back on with no pull gesture.
  const initialLoad = summaryQ.isLoading || recentTxnsQ.isLoading;
  const refreshing = deriveRefreshing(
    [summaryQ, recentTxnsQ, goalsQ, insightsQ, availableMonthsQ, ...monthlyReportsQ,
     reviewSinglesQ, reviewGroupsQ, budgetsQ, recurringQ],
    initialLoad
  );

  function refresh() {
    ['dashboard-summary', 'accounts', 'recent-transactions', 'goals', 'insights', 'report-months',
      'report', 'needs-review', 'needs-review-groups', 'budgets', 'recurring']
      .forEach((key) => void queryClient.invalidateQueries({ queryKey: [key] }));
  }

  // BH-027's own service-layer doc comment: "the user asked for this row to count, so it counts
  // now." transactionsApi.confirmNotDuplicate already existed and already worked on the backend --
  // this is the first mobile UI that calls it. invalidateFinancialData covers dashboard-summary
  // (the card itself), recent-transactions/transactions (the row's own status just changed), and
  // every KPI the reinstated transaction now counts toward.
  async function handleConfirmNotDuplicate(transactionId: string) {
    setConfirmingDuplicateId(transactionId);
    setDuplicateConfirmError(null);
    try {
      await transactionsApi.confirmNotDuplicate(transactionId);
      invalidateFinancialData(queryClient);
    } catch {
      setDuplicateConfirmError("Couldn't update this transaction. Please try again.");
    } finally {
      setConfirmingDuplicateId(null);
    }
  }

  const summary = summaryQ.data;
  const recentTxns = recentTxnsQ.data?.content ?? [];
  // Same signal frontend/src/pages/Dashboard.tsx uses to hide Financial Health Score,
  // Categorization Confidence and Detected Issues below: a score, average, or duplicate flag
  // computed from zero transactions has nothing real behind it.
  const isEmpty = (recentTxnsQ.data?.totalElements ?? 0) === 0;
  // Same cap as web's Dashboard.tsx -- a preview, not the whole list; "Manage Budgets" opens the
  // full screen for everything beyond the top 3.
  const budgets = (budgetsQ.data ?? []).slice(0, 3);
  // RecurringItem[] already arrives sorted by nextEstimate (RecurringService's own doc comment) --
  // slicing is enough, no client-side sort needed.
  const upcomingRecurring = (recurringQ.data ?? []).slice(0, 5);
  const coverageCaveat = insightsQ.data?.coverageCaveat ?? null;
  // The coverage-caveat sentence (Track C/C2) is promoted to its own banner below rather than said
  // twice -- filtered out of the bullet list by the one fixed, always-English substring
  // InsightsService's template ever produces it with ("may be missing"). Safe as an exact
  // correspondence, not a heuristic: that phrase appears in exactly one sentence template, added
  // if and only if coverageCaveat is non-null (see InsightsService.build).
  const sentences = (insightsQ.data?.sentences ?? []).filter((s) => !s.includes('may be missing'));
  const firstName = fullName?.split(' ')[0] ?? 'there';

  /**
   * Every category is accounted for, either as its own slice or inside "Other".
   *
   * This used to take the top six and stop, and the centre label summed only what survived. With
   * seven or more categories that produced two different spend totals on one screen -- the donut
   * saying 34,000 while the Expenses KPI beside it said 35,500 -- and the smaller one carried the
   * authority of sitting inside the chart. The backend builds spendByCategory and monthlyExpense
   * from the same filtered list (DashboardService.java:104), so the full sum IS the period's
   * expense figure; anything less is not a rounding difference, it is wrong.
   *
   * Folding the remainder into a final slice keeps the chart readable without dropping money out
   * of it, so the slices, the centre and the KPI all agree by construction rather than by luck.
   */
  const donutSlices: Slice[] = useMemo(() => {
    if (!summary) return [];
    return bucketTopSlices(Object.entries(summary.spendByCategory), CHART_PALETTE, OTHER_LABEL);
  }, [summary]);

  // Called unconditionally, before the early return just below -- it's a Hook (wraps useMemo), and
  // Hooks can never be called only on some renders. summary can still be undefined here -- a
  // settled failure returns right after this, but a still-loading first fetch falls through to the
  // shell, which renders these off default values.
  const { balanceKpi, snapshotKpis, periodIsCurrent, periodLabel, deltaLabel, deltaSpokenLabel } =
    useDashboardKpis(summary);

  // summaryQ can fail on its own (the whole point of useQueries above) -- say so rather than
  // rendering a screen of zeroes that reads as "you have no money". Only on a SETTLED failure,
  // though -- summaryQ.isLoading with no cached data yet falls through to the shell below, which
  // shows its own per-section skeletons instead of blocking the whole screen behind one spinner.
  if (!summaryQ.isLoading && !summary) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <Text style={[styles.errorText, { color: c.mutedInk }]}>Couldn't load your dashboard.</Text>
        <Pressable onPress={refresh} hitSlop={12} accessibilityRole="button">
          <Text style={[styles.retry, { color: c.primary }]}>Try again</Text>
        </Pressable>
      </View>
    );
  }

  const chartWidth = width - spacing.md * 2 - spacing.md * 2;

  return (
    <>
    <ScrollView
      style={{ backgroundColor: c.bg }}
      contentContainerStyle={[styles.content, { paddingTop: insets.top + spacing.md }]}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={c.primary} />}
    >
      <View style={styles.greetingRow}>
        <View style={styles.greetingText}>
          <Text style={[styles.greeting, { color: c.ink }]}>
            {greeting(settingsQ.data?.timezone)}, {firstName}
          </Text>
          <Text style={[styles.subGreeting, { color: c.mutedInk }]}>
            Here's what's happening with your finances.
            {!periodIsCurrent && ` Your latest figures are from ${periodLabel}.`}
          </Text>
        </View>
        {/* Phase 5 (Low-Priority Polish). Ledger's own search box (LedgerScreen.tsx) already
            works -- "one tap away via Transactions tab" -- this is purely a shorter path to it
            from the screen people actually open first, not a second search implementation. No
            query to seed: whoever taps this hasn't typed anything yet, so a plain navigate is
            the whole job, same as every other tab-bar tap. */}
        <Pressable
          onPress={() => navigation.navigate('Transactions')}
          hitSlop={10}
          style={styles.searchButton}
          accessibilityRole="button"
          accessibilityLabel="Search transactions"
        >
          <Ionicons name="search-outline" size={22} color={c.muted} />
        </Pressable>
      </View>

      {/* Track C/C2. InsightsService has always computed this (aggregated across every live
          account, so it needs no per-account plumbing) and said so as one bullet buried in the
          Insights card below -- easy to miss entirely on a screen full of other numbers. A gap in
          the statement history means every total on this screen may be undercounting the current
          month, which is a data-completeness warning, not a spending observation, so it is
          promoted above everything it could be silently distorting, with a way to actually fix it.
          Ranked above the review-queue nudge below: a category label can wait, a missing
          statement calls the KPIs above into question right now. */}
      {coverageCaveat ? (
        <Pressable
          onPress={() => navigation.navigate('Import')}
          accessibilityRole="button"
          accessibilityLabel={`Possible gap in your ${monthLabelLong(coverageCaveat.month)} history. Import that statement to complete it.`}
          accessibilityHint="Opens the Import screen"
        >
          <Card style={{ ...styles.coverageBanner, backgroundColor: c.warningBg }}>
            <Text style={[styles.coverageBannerTitle, { color: c.warningInk }]}>
              Possible gap in {monthLabelLong(coverageCaveat.month)}
            </Text>
            <Text style={[styles.coverageBannerBody, { color: c.warningInk }]}>
              Some transactions may be missing from your history.
            </Text>
            <Text style={[styles.coverageBannerCta, { color: c.primary }]}>Import that statement ›</Text>
          </Card>
        </Pressable>
      ) : null}

      {/* A count of work, never a chart slice. The categorization design spec (§3) draws a hard
          line here: "Other" is a real category a user chose, while "needs review" is a queue
          state, and rendering the latter as a wedge in the spending donut is an admission of not
          knowing dressed up as information about their money. So it lives here, above the numbers
          it would otherwise quietly distort, as a nudge with somewhere to go. */}
      {reviewCountKnown && reviewCount > 0 ? (
        <Pressable
          onPress={() => navigation.navigate('More', { screen: 'CategoryReview' })}
          accessibilityRole="button"
          accessibilityLabel={reviewNudgeLabel(reviewCount)}
          accessibilityHint="Opens the category review queue"
        >
          <Card style={styles.nudge}>
            <View style={styles.nudgeText}>
              <Text style={[styles.nudgeTitle, { color: c.ink }]}>{reviewNudgeLabel(reviewCount)}</Text>
              <Text style={[styles.nudgeBody, { color: c.mutedInk }]} numberOfLines={2}>
                Label them once and Fynora remembers the merchant for good.
              </Text>
            </View>
            <Text style={[styles.nudgeChevron, { color: c.primary }]} accessibilityElementsHidden importantForAccessibility="no">›</Text>
          </Card>
        </Pressable>
      ) : null}

      {/* Phase 4 (Medium-Tier Parity). The KPI deltas below and the Financial Health Score further
          down are real, computed numbers -- neither is hidden by this -- but both are prone to
          thin-data artifacts this far below limitedHistoryMonthFloor: a trend delta dividing
          against a near-empty prior month, and a health score built from too few comparable
          months. Shown once, above everything it explains, mirroring
          frontend/src/pages/Dashboard.tsx's identical banner (mobile skips its collapse/expand
          toggle -- this screen is already scroll-based). Hidden while isEmpty, same as the
          coverage-caveat banner above: the zero-transaction empty state below covers that case on
          its own terms. */}
      {!isEmpty && summary && summary.limitedHistory ? (
        <Card style={{ ...styles.limitedHistoryBanner, backgroundColor: c.warningBg }}>
          <Text style={[styles.limitedHistoryTitle, { color: c.warningInk }]}>
            Limited financial history
          </Text>
          <Text style={[styles.limitedHistoryBody, { color: c.warningInk }]}>
            Based on {summary.statementCount} statement{summary.statementCount === 1 ? '' : 's'}{' '}
            across {summary.accountCount} account{summary.accountCount === 1 ? '' : 's'} and{' '}
            {summary.historyMonthCount} month{summary.historyMonthCount === 1 ? '' : 's'} of
            activity. Trends and the Financial Health Score below may be unreliable until at least{' '}
            {summary.limitedHistoryMonthFloor} months of history are imported.
          </Text>
        </Card>
      ) : null}

      {/* Financial Health Score -- DashboardService.computeHealthScore has always returned this
          (score, label, a breakdown), sent on every load. Hidden entirely while isEmpty, same
          reasoning as web: a score computed from zero transactions has nothing real behind it. */}
      {!isEmpty && summary ? (
        <>
          <HealthHero
            available={summary.healthScoreAvailable}
            healthScore={summary.healthScore ?? 0}
            healthLabel={summary.healthLabel ?? ''}
            healthScoreDeltaVsLastMonth={summary.healthScoreDeltaVsLastMonth}
            healthSparkline={summary.healthSparkline}
            healthScoreTransactionCount={summary.healthScoreTransactionCount}
            healthScoreMinTransactions={summary.healthScoreMinTransactions}
            onImportPress={() => navigation.navigate('Import')}
          />
          <HealthFactorsRow
            available={summary.healthScoreAvailable}
            breakdown={summary.healthBreakdown}
            breakdownDetail={summary.healthBreakdownDetail}
            topOpportunityFactor={summary.healthTopOpportunityFactor}
            topOpportunityPotentialGain={summary.healthTopOpportunityPotentialGain}
          />
        </>
      ) : null}

      <View style={styles.section}>
        {summary ? (
          <LedgerSnapshotCard kpis={snapshotKpis} deltaLabel={deltaLabel} deltaSpokenLabel={deltaSpokenLabel} />
        ) : (
          <SkeletonCard lines={4} />
        )}
      </View>

      {summary ? (
        showCashFlowMini ? (
          <View style={styles.cardRow}>
            <View style={styles.cardRowItem}>
              <CashFlowMiniCard points={cashFlowPoints} deltaPct={summary.netDeltaPct} />
            </View>
            <View style={styles.cardRowItem}>
              <AccountsCard
                accounts={accountsQ.data ?? []}
                totalBalance={balanceKpi?.value ?? 0}
                caption={balanceKpi?.caption ?? ''}
                onViewAll={() => navigation.navigate('More', { screen: 'Accounts' })}
              />
            </View>
          </View>
        ) : (
          <View style={styles.cardRowSingle}>
            <AccountsCard
              accounts={accountsQ.data ?? []}
              totalBalance={balanceKpi?.value ?? 0}
              caption={balanceKpi?.caption ?? ''}
              onViewAll={() => navigation.navigate('More', { screen: 'Accounts' })}
            />
          </View>
        )
      ) : null}

      <Card style={styles.section}>
        <SectionHeading title="Spending by Category" />
        {summary ? (
          donutSlices.length === 0 ? (
            <EmptyState message={`No spending recorded ${periodLabel} yet.`} />
          ) : (
            <DonutChart
              slices={donutSlices}
              centerLabel={fmtCurrency(donutSlices.reduce((s, x) => s + x.value, 0))}
              onSlicePress={(categoryName) => {
                // reportingMonth can't be null here -- donutSlices is only non-empty when summary
                // has real category spend, which requires a real reporting month behind it.
                const { dateFrom, dateTo } = monthDateRange(summary!.reportingMonth!);
                navigation.navigate('Transactions', {
                  filters: {
                    categoryName, dateFrom, dateTo,
                    label: `${categoryName} · ${monthLabel(summary!.reportingMonth!)}`,
                    nonce: Date.now(),
                  },
                });
              }}
            />
          )
        ) : (
          <SkeletonChart variant="donut" />
        )}
      </Card>

      <View style={styles.section}>
        <SectionHeading title="Goals" />
        <GoalsRow goals={goalsQ.data ?? []} />
      </View>

      <FinancialNoteCard
        factor={summary?.healthTopOpportunityFactor ?? null}
        potentialGain={summary?.healthTopOpportunityPotentialGain ?? null}
        onCreateGoal={() => navigation.navigate('Goals')}
      />

      {/* Passbook reorder (2026-09-10): Recent Transactions, Quick Actions and Upcoming/Recurring
          moved here as a block -- the curated "financial story" (Hero through Financial Note)
          stays first, everything below this point is the operational layer. See
          docs/superpowers/specs/2026-09-10-dashboard-passbook-redesign-design.md's resolved
          section-order decision; none of these three sections' own content changed, only where
          they sit on the screen. */}
      <Card style={styles.section}>
        <SectionHeading title="Recent Transactions" />
        {recentTxnsQ.isLoading ? (
          <>
            <SkeletonTransactionRow />
            <SkeletonTransactionRow />
            <SkeletonTransactionRow />
          </>
        ) : recentTxnsQ.isError ? (
          // A failed request is not an answer of zero -- same reasoning as LedgerScreen's own
          // isError branch. Without this, a persistent failure here would fall through to the
          // empty-state message below and tell someone with years of history they have none.
          <Text style={[styles.errorText, { color: c.danger }]}>
            Couldn&apos;t load your transactions — pull down to try again.
          </Text>
        ) : recentTxns.length === 0 ? (
          <EmptyState
            message="No transactions yet. Import a statement to get started."
            actionLabel="Import a statement"
            onAction={() => navigation.navigate('Import')}
          />
        ) : (
          recentTxns.map((t) => (
            <View key={t.id} style={[styles.txnRow, { borderBottomColor: c.border }]}>
              <View style={styles.txnMain}>
                <Text style={[styles.txnDesc, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>
                  {t.description || t.merchant || 'Transaction'}
                </Text>
                <Text style={[styles.txnMeta, { color: c.mutedInk }]} numberOfLines={1}>
                  {t.categoryName} · {t.date}
                </Text>
              </View>
              <Text style={[styles.txnAmount, { color: t.type === 'INCOME' ? c.success : c.danger }]}>
                {t.type === 'INCOME' ? '+' : '-'}
                {fmtCurrency(Math.abs(t.amount))}
              </Text>
            </View>
          ))
        )}
      </Card>

      {/* Quick Actions -- Phase 4, ported from frontend/src/pages/Dashboard.tsx:1216-1235. A
          shortcut grid to the same destinations already scattered across this screen's own empty
          states and CTAs, gathered in one place. Drops web's "Connect Gmail" entry: web includes
          it only because it lacks a dedicated empty-state card of its own to live in (unlike
          Import/Add Transaction), and mobile's Gmail connect is already one tap away from
          Settings -- it isn't missing an entry point the way it is on web. */}
      <Card style={styles.section}>
        <SectionHeading title="Quick Actions" />
        <View style={styles.quickActionsGrid}>
          {(
            [
              { icon: 'cloud-upload-outline', label: 'Import Statement', onPress: () => navigation.navigate('Import') },
              { icon: 'add-circle-outline', label: 'Add Transaction', onPress: () => setAddingTransaction(true) },
              { icon: 'wallet-outline', label: 'Create Budget', onPress: () => navigation.navigate('More', { screen: 'Budgets' }) },
              { icon: 'bar-chart-outline', label: 'View Reports', onPress: () => navigation.navigate('More', { screen: 'Reports' }) },
              { icon: 'flag-outline', label: 'Manage Goals', onPress: () => navigation.navigate('Goals') },
              { icon: 'trending-up-outline', label: 'Investments', onPress: () => navigation.navigate('More', { screen: 'Investments' }) },
            ] as const
          ).map((action) => (
            <Pressable
              key={action.label}
              onPress={action.onPress}
              style={[styles.quickActionCell, { backgroundColor: c.bg, borderColor: c.border }]}
              accessibilityRole="button"
              accessibilityLabel={action.label}
            >
              <Ionicons name={action.icon} size={20} color={c.primary} />
              <Text style={[styles.quickActionLabel, { color: c.ink }]} numberOfLines={2}>
                {action.label}
              </Text>
            </Pressable>
          ))}
        </View>
      </Card>

      {/* Upcoming -- the same "Subscriptions & Recurring Payments" card RecurringService has
          always fed. Hidden entirely when there's nothing detected: "no recurring payments found"
          isn't information worth a card of its own the way "no budgets set yet" is, since this
          isn't a feature the user set up themselves. */}
      {upcomingRecurring.length > 0 ? (
        <Card style={styles.section}>
          <SectionHeading title="Upcoming" />
          {upcomingRecurring.map((r) => (
            <View key={r.merchant} style={[styles.recurringRow, { borderBottomColor: c.border }]}>
              <View style={styles.recurringMain}>
                <Text style={[styles.recurringMerchant, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>
                  {r.merchant}
                </Text>
                {/* primaryLight on Card's white background is a 1.13:1 contrast (computed, same
                    class of bug as the FinancialHealthFactorCard "Good" pill) -- the badge's fill
                    was invisible, not just subtle. A border makes the pill's own boundary visible
                    without introducing a new fill color into this still-unredesigned section. */}
                <Text
                  style={[styles.recurringBadge, { color: c.primary, backgroundColor: c.primaryLight, borderWidth: 1, borderColor: c.border }]}
                  numberOfLines={1}
                >
                  {r.label}
                </Text>
              </View>
              <View style={styles.recurringRight}>
                {/* RecurringService filters to Transaction.Type.EXPENSE only (confirmed by
                    reading the backend, not assumed) -- same debit color as Recent Transactions.
                    No "-" prefix, unlike that list: this is a forward-looking "what's coming due"
                    figure, not a past ledger entry, and the pinned test for this card asserts the
                    bare amount ('₹499', no sign). */}
                <Text style={[styles.recurringAmount, { color: c.danger }]}>{fmtCurrency(r.averageAmount)}</Text>
                <Text style={[styles.recurringMeta, { color: c.mutedInk }]} numberOfLines={1}>
                  {recurringExpectedLabel(r.nextEstimate)}
                </Text>
              </View>
              <Pressable
                onPress={() => dismissRecurring.mutate(r.merchant)}
                disabled={dismissRecurring.isPending}
                hitSlop={10}
                accessibilityRole="button"
                accessibilityLabel={`Not recurring: dismiss ${r.merchant}`}
              >
                <Ionicons name="close" size={16} color={c.muted} />
              </Pressable>
            </View>
          ))}
        </Card>
      ) : null}

      {/* Categorization Confidence -- how sure the categorization engine was, on average, about
          the categories it assigned this month. A positive, ongoing data-quality signal, distinct
          from the category-review warning (which only fires when spend is badly miscategorized).
          Hidden below categorizationConfidenceMinTransactions engine-decided transactions this
          month (server-side floor, same reasoning as healthScoreAvailable above). */}
      {!isEmpty && summary && summary.categorizationConfidenceScore !== null ? (
        <Card style={styles.section}>
          <SectionHeading title="Categorization Confidence" />
          <View style={styles.confidenceRow}>
            <Text
              style={[
                styles.healthScoreValue,
                { color: healthColor(scoreLabel(summary.categorizationConfidenceScore), c) },
              ]}
            >
              {summary.categorizationConfidenceScore}
            </Text>
            <Text style={[styles.body, { color: c.mutedInk }]}>out of 100</Text>
          </View>
          <Text
            style={[
              styles.healthScoreLabel,
              { color: healthColor(scoreLabel(summary.categorizationConfidenceScore), c) },
            ]}
          >
            {scoreLabel(summary.categorizationConfidenceScore)}
          </Text>
          <Text style={[styles.body, styles.confidenceCaption, { color: c.mutedInk }]}>
            Based on {summary.categorizationConfidenceTransactionCount} automatically categorized
            transaction{summary.categorizationConfidenceTransactionCount === 1 ? '' : 's'} {periodLabel}.
          </Text>
        </Card>
      ) : null}

      {/* Next Actions -- summary.notifications (DashboardService.buildNotifications: credit-card
          payments due soon, low-balance warnings, budget-threshold alerts) has always been
          computed and sent on every dashboard load, mirroring frontend/src/pages/Dashboard.tsx's
          identical card -- this was previously computed and thrown away on mobile entirely, with
          no equivalent of web's TopBar bell-icon dropdown to fall back on either. Hidden while
          isEmpty, same reasoning as Financial Health Score above: a brand-new account has nothing
          computed here to act on yet. */}
      {!isEmpty && summary ? (
        <Card style={styles.section}>
          <SectionHeading title="Next Actions" />
          {summary.notifications.length === 0 ? (
            <Text style={[styles.body, { color: c.mutedInk }]}>Nothing needs your attention right now.</Text>
          ) : (
            <View style={styles.notificationList}>
              {summary.notifications.map((n, i) => (
                <View key={i} style={styles.notificationRow}>
                  <Ionicons name="warning-outline" size={14} color={c.warningInk} style={styles.notificationIcon} />
                  <Text style={[styles.body, styles.notificationText, { color: c.ink }]}>{n}</Text>
                </View>
              ))}
            </View>
          )}
        </Card>
      ) : null}

      {/* Detected Issues -- ReconciliationService's own duplicate pass already silently excludes a
          row from every total above the moment it runs, and until now nothing told the user it
          happened. transactionsApi.confirmNotDuplicate (BH-027, "no, these really are two separate
          transactions") already existed on the backend to let a human overrule that guess -- this
          is the first mobile UI that calls it. Shown only when something was actually flagged. */}
      {summary && summary.duplicateTransactionCount > 0 ? (
        <Card style={styles.section}>
          <SectionHeading title="Detected Issues" />
          <Text style={[styles.body, { color: c.mutedInk, marginBottom: spacing.sm }]}>
            {summary.duplicateTransactionCount === 1
              ? 'We found 1 transaction that looks like a duplicate and excluded it from your totals.'
              : `We found ${summary.duplicateTransactionCount} transactions that look like duplicates and excluded them from your totals.`}
          </Text>
          {duplicateConfirmError ? (
            <Text style={[styles.body, { color: c.danger, marginBottom: spacing.sm }]}>
              {duplicateConfirmError}
            </Text>
          ) : null}
          {summary.detectedDuplicates.map((d) => (
            <View key={d.transactionId} style={[styles.duplicateRow, { borderBottomColor: c.border }]}>
              <View style={styles.duplicateMain}>
                <Text style={[styles.duplicateMerchant, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>
                  {d.merchant}
                </Text>
                <Text style={[styles.duplicateMeta, { color: c.mutedInk }]}>
                  {fromLocalDateString(d.date).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })}
                  {' · '}
                  {fmtCurrency(d.amount)}
                </Text>
              </View>
              <Pressable
                onPress={() => void handleConfirmNotDuplicate(d.transactionId)}
                disabled={confirmingDuplicateId === d.transactionId}
                hitSlop={8}
                accessibilityRole="button"
                accessibilityLabel={`Not a duplicate: ${d.merchant}`}
              >
                <Text
                  style={[
                    styles.duplicateAction,
                    { color: c.primary },
                    confirmingDuplicateId === d.transactionId && styles.duplicateActionDisabled,
                  ]}
                >
                  {confirmingDuplicateId === d.transactionId ? 'Confirming…' : 'Not a duplicate'}
                </Text>
              </Pressable>
            </View>
          ))}
          {summary.duplicateTransactionCount > summary.detectedDuplicates.length ? (
            <Text style={[styles.body, { color: c.mutedInk, marginTop: spacing.sm }]}>
              and {summary.duplicateTransactionCount - summary.detectedDuplicates.length} more
            </Text>
          ) : null}
        </Card>
      ) : null}

      <Card style={styles.section}>
        <SectionHeading
          title="Cash Flow"
          action={
            <View style={[styles.rangeRow, { borderColor: c.border }]}>
              {(Object.keys(RANGE_MONTHS) as CashFlowRange[]).map((r) => (
                <Pressable
                  key={r}
                  onPress={() => setCashFlowRange(r)}
                  accessibilityRole="button"
                  accessibilityState={{ selected: cashFlowRange === r }}
                  accessibilityLabel={`Show ${RANGE_MONTHS[r]} months`}
                  style={[styles.rangeChip, cashFlowRange === r && { backgroundColor: c.primaryLight }]}
                >
                  <Text style={[styles.rangeText, { color: cashFlowRange === r ? c.primary : c.mutedInk }]}>{r}</Text>
                </Pressable>
              ))}
            </View>
          }
        />
        {/* Gated on the queries that actually FEED this chart, not on `summary`. Those are
            different requests -- and sequential ones, since the per-month reports can't be issued
            until the months list resolves -- so on any cold start there was a window where
            `summary` had arrived, cashFlowPoints was still [], and CashFlowChart's own empty state
            told a user with years of statements "No monthly data yet."

            The error branches matter for a subtler reason: a dropped month does not leave a gap.
            CashFlowChart's x-axis is index-based, so filtering a failed month out of the series
            re-spaces the survivors and joins two non-adjacent months into one continuous segment --
            the missing month's spike is smoothed away rather than shown as missing, and the range
            chip still claims the full period. Better to say so than to draw a shape that isn't
            true. */}
        {cashFlowSettling ? (
          <SkeletonChart width={chartWidth} />
        ) : cashFlowUnavailable ? (
          <Text style={[styles.errorText, { color: c.danger }]}>Couldn’t load your cash flow.</Text>
        ) : (
          <>
            <CashFlowChart points={cashFlowPoints} width={chartWidth} />
            {cashFlowMissingMonths > 0 ? (
              <Text style={[styles.errorText, { color: c.mutedInk }]}>
                {cashFlowMissingMonths === 1
                  ? 'One month couldn’t be loaded, so it isn’t shown.'
                  : `${cashFlowMissingMonths} months couldn’t be loaded, so they aren’t shown.`}
              </Text>
            ) : null}
          </>
        )}
      </Card>

      {/* Budget Progress -- Phase 4 (Medium-Tier Parity), ported from
          frontend/src/pages/Dashboard.tsx:1023-1079. Always rendered, unlike Goals just below (its
          own empty state is the point: "no budgets yet" is itself useful information about a
          feature the user hasn't tried, the same reason Recent Transactions/Cash Flow always
          render on this screen rather than vanishing with nothing to show). isError is checked
          before length === 0 for the same reason every other card on this screen does: a failed
          fetch is not the same answer as a genuinely empty list. */}
      <Card style={styles.section}>
        <SectionHeading title="Budget Progress" />
        {budgetsQ.isLoading ? (
          <SkeletonCard lines={2} />
        ) : budgetsQ.isError ? (
          <Text style={[styles.errorText, { color: c.danger }]}>Couldn&apos;t load your budgets.</Text>
        ) : budgets.length === 0 ? (
          <EmptyState message="No budgets set. Create one to track your spending." />
        ) : (
          <>
            {budgets.map((b) => {
              const pct = b.monthlyLimit > 0 ? Math.min(100, (b.spentThisMonth / b.monthlyLimit) * 100) : 0;
              const over = b.spentThisMonth > b.monthlyLimit;
              return (
                <View key={b.id} style={styles.budgetRow}>
                  <View style={styles.budgetHeader}>
                    <Text style={[styles.budgetName, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>
                      {b.categoryName}
                    </Text>
                    <Text style={[styles.budgetPct, { color: over ? c.danger : c.mutedInk }]}>
                      {pct.toFixed(0)}%
                    </Text>
                  </View>
                  <View style={[styles.progressTrack, { backgroundColor: c.border }]}>
                    <View
                      style={[
                        styles.progressFill,
                        { width: `${pct}%`, backgroundColor: over ? c.danger : c.primary },
                      ]}
                    />
                  </View>
                  <Text style={[styles.budgetMeta, { color: c.mutedInk }]}>
                    {fmtCurrency(b.spentThisMonth)} of {fmtCurrency(b.monthlyLimit)}
                  </Text>
                </View>
              );
            })}
            <Pressable
              onPress={() => navigation.navigate('More', { screen: 'Budgets' })}
              hitSlop={8}
              style={[styles.manageBudgets, { backgroundColor: c.primaryLight }]}
              accessibilityRole="button"
              accessibilityLabel="Manage Budgets"
            >
              <Text style={[styles.manageBudgetsText, { color: c.primary }]}>Manage Budgets</Text>
            </Pressable>
          </>
        )}
      </Card>

      {sentences.length > 0 ? (
        <Card style={styles.section}>
          <SectionHeading title="Insights" />
          {sentences.slice(0, 3).map((s) => (
            <Text key={s} style={[styles.insight, { color: c.ink }]}>
              • {s}
            </Text>
          ))}
        </Card>
      ) : null}

      <ChecklistWidget />
    </ScrollView>
    {addingTransaction ? (
      <AddTransactionSheet
        onClose={() => setAddingTransaction(false)}
        onSaved={() => {
          setAddingTransaction(false);
          showToast('Transaction Added', 'Your new transaction is now in your ledger.');
        }}
      />
    ) : null}
    </>
  );
}

const styles = StyleSheet.create({
  nudge: { flexDirection: 'row', alignItems: 'center', marginBottom: spacing.md },
  nudgeText: { flex: 1, marginRight: spacing.sm },
  nudgeTitle: { fontSize: 14, fontWeight: '600' },
  nudgeBody: { fontSize: 12, marginTop: 2 },
  nudgeChevron: { fontSize: 20, lineHeight: 20 },
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: spacing.sm },
  errorText: { fontSize: 14 },
  retry: { fontSize: 14, fontWeight: '600' },
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  greetingRow: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.sm },
  greetingText: { flex: 1 },
  searchButton: { padding: 4 },
  greeting: { fontSize: 22, fontWeight: '700' },
  subGreeting: { fontSize: 13, marginTop: 2, marginBottom: spacing.md },
  section: { marginTop: spacing.md },
  cardRow: { flexDirection: 'row', gap: spacing.sm, marginTop: spacing.md },
  cardRowItem: { flex: 1 },
  cardRowSingle: { marginTop: spacing.md },
  rangeRow: { flexDirection: 'row', borderWidth: 1, borderRadius: radius.md, overflow: 'hidden' },
  // 44pt minimum touch target -- see the same note in LedgerScreen's filter chips.
  rangeChip: { paddingHorizontal: 14, minHeight: 44, justifyContent: 'center' },
  rangeText: { fontSize: 11, fontWeight: '600' },
  txnRow: { flexDirection: 'row', alignItems: 'center', paddingVertical: 10, borderBottomWidth: StyleSheet.hairlineWidth },
  txnMain: { flex: 1, marginRight: spacing.sm },
  txnDesc: { fontSize: 14, fontWeight: '500' },
  txnMeta: { fontSize: 11, marginTop: 2 },
  txnAmount: { fontSize: 14, fontWeight: '700' },
  progressTrack: { height: 6, borderRadius: 3, overflow: 'hidden' },
  progressFill: { height: 6, borderRadius: 3 },
  // Phase 4.
  budgetRow: { marginBottom: spacing.sm },
  budgetHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 6 },
  budgetName: { fontSize: 13, fontWeight: '600', flex: 1 },
  budgetPct: { fontSize: 12, fontWeight: '600' },
  budgetMeta: { fontSize: 11, marginTop: 4 },
  manageBudgets: { minHeight: 40, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center', marginTop: spacing.xs },
  manageBudgetsText: { fontSize: 12, fontWeight: '600' },
  recurringRow: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingVertical: 10, borderBottomWidth: StyleSheet.hairlineWidth, gap: spacing.sm,
  },
  recurringMain: { flex: 1, minWidth: 0 },
  recurringMerchant: { fontSize: 14, fontWeight: '500' },
  recurringBadge: {
    alignSelf: 'flex-start', fontSize: 10, fontWeight: '600', marginTop: 4,
    paddingHorizontal: 8, paddingVertical: 2, borderRadius: 999, overflow: 'hidden',
  },
  recurringRight: { alignItems: 'flex-end' },
  recurringAmount: { fontSize: 14, fontWeight: '700' },
  recurringMeta: { fontSize: 11, marginTop: 2 },
  quickActionsGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  quickActionCell: {
    width: '31%', minHeight: 76, borderWidth: 1, borderRadius: radius.md,
    alignItems: 'center', justifyContent: 'center', paddingVertical: spacing.sm, paddingHorizontal: 4, gap: 6,
  },
  quickActionLabel: { fontSize: 11, fontWeight: '600', textAlign: 'center' },
  insight: { fontSize: 13, lineHeight: 20, marginBottom: 4 },
  body: { fontSize: 13, lineHeight: 19 },
  // Track C/C1. healthScoreValue/healthScoreLabel now shared with Categorization Confidence
  // only -- the Financial Health Score's own score/label rendering moved into HealthHero.
  healthScoreValue: { fontSize: 32, fontWeight: '700' },
  healthScoreLabel: { fontSize: 13, fontWeight: '600', marginTop: 2 },
  confidenceRow: { flexDirection: 'row', alignItems: 'baseline', gap: spacing.xs },
  confidenceCaption: { marginTop: spacing.xs },
  notificationList: { gap: spacing.sm },
  notificationRow: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.xs },
  notificationIcon: { marginTop: 2 },
  notificationText: { flex: 1, fontSize: 14 },
  duplicateRow: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingVertical: 10, borderBottomWidth: StyleSheet.hairlineWidth, gap: spacing.sm,
  },
  duplicateMain: { flex: 1 },
  duplicateMerchant: { fontSize: 14 },
  duplicateMeta: { fontSize: 11, marginTop: 2 },
  duplicateAction: { fontSize: 12, fontWeight: '600' },
  duplicateActionDisabled: { opacity: 0.5 },
  // Track C/C2.
  coverageBanner: { marginBottom: spacing.md },
  coverageBannerTitle: { fontSize: 14, fontWeight: '600' },
  coverageBannerBody: { fontSize: 12, marginTop: 2 },
  coverageBannerCta: { fontSize: 12, fontWeight: '600', marginTop: spacing.sm },
  // Phase 4.
  limitedHistoryBanner: { marginBottom: spacing.md },
  limitedHistoryTitle: { fontSize: 14, fontWeight: '600' },
  limitedHistoryBody: { fontSize: 12, lineHeight: 17, marginTop: 2 },
});
