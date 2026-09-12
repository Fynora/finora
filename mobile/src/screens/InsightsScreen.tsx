import { useEffect, useMemo, useState } from 'react';
import {
  Pressable, RefreshControl, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigation } from '@react-navigation/native';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { usePreventScreenCapture } from 'expo-screen-capture';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Card, EmptyState, SectionHeading } from '../components/Card';
import { DonutChart, type Slice } from '../components/charts/DonutChart';
import { OnTrackIllustration } from '../components/insights/OnTrackIllustration';
import { SkeletonCard } from '../components/skeletons/Skeletons';
import {
  categoriesApi, dashboardApi, insightsApi, onboardingApi, recurringApi, type RecurringItem,
} from '../api/endpoints';
import { CHART_PALETTE, bucketTopSlices } from '../lib/chartGeometry';
import { colorHexFor, iconNameFor } from '../lib/categoryIcons';
import { fmtCurrency, fmtDate, monthDateRange, monthLabel } from '../lib/format';
import { deriveRefreshing } from '../lib/refreshingIndicator';
import { useDashboardKpis } from '../lib/useDashboardKpis';
import { useLargeFontScale } from '../lib/useLargeFontScale';
import { radius, spacing, useTheme } from '../theme';
import type { AppTabParamList, LedgerDrillThroughFilters, MoreStackParamList } from '../navigation/types';

const OTHER_LABEL = 'Other';

/** Port of frontend/src/pages/Insights.tsx. */
export function InsightsScreen() {
  // D3 (Track D security cleanup). Spend movers and observations name real merchants and amounts
  // -- same screenshot/screen-recording exposure Dashboard/Accounts/Statement History already
  // guard against.
  usePreventScreenCapture();
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const largeText = useLargeFontScale();
  const queryClient = useQueryClient();
  // Lives inside the More stack, not on the tab bar itself -- see BudgetsScreen's identical
  // comment (Track C/C4).
  const navigation = useNavigation();
  // Same underlying nav object as `navigation` above, typed for same-stack pushes (Settings,
  // Reports) -- `navigation.getParent<BottomTabNavigationProp<...>>()` above is for the OTHER
  // direction, a cross-tab jump into Transactions.
  const stackNavigation = useNavigation<NativeStackNavigationProp<MoreStackParamList>>();

  // useQueries, not Promise.all: the web page loses BOTH sections when either endpoint fails,
  // because one rejected promise fails the pair. Recurring payments and observations are
  // independent, so one being unavailable shouldn't blank the other.
  const [insightsQ, recurringQ] = useQueries({
    queries: [
      { queryKey: ['insights'], queryFn: () => insightsApi.get() },
      { queryKey: ['recurring'], queryFn: () => recurringApi.list() },
    ],
  });

  // Under Dashboard's own ['dashboard-summary'] key -- see LedgerScreen.tsx's identical query and
  // its own comment on why (one network call shared across every screen that visits it this
  // session, not a fresh one per screen).
  const { data: summary } = useQuery({
    queryKey: ['dashboard-summary'],
    queryFn: () => dashboardApi.summary(),
  });
  const { snapshotKpis } = useDashboardKpis(summary);
  const expenseDelta = snapshotKpis.find((k) => k.label === 'Expenses')?.delta ?? null;

  // Loaded lazily, same reasoning as LedgerScreen's identical query: cheap, shared cache, needed
  // by Key Insights' category icons below.
  const { data: categories = [] } = useQuery({
    queryKey: ['categories'],
    queryFn: () => categoriesApi.list(),
    staleTime: 5 * 60_000,
  });
  const [showAllInsights, setShowAllInsights] = useState(false);
  const trackBannerTitle = expenseDelta === null ? 'This month' : expenseDelta <= 0 ? "You're on track!" : 'Heads up';
  const trackBannerBody = expenseDelta === null
    ? 'Keep an eye on your spending this month.'
    : expenseDelta <= 0
      ? `Your spending is ${Math.abs(expenseDelta).toFixed(0)}% lower than last month. Keep it up!`
      : `Your spending is ${expenseDelta.toFixed(0)}% higher than last month.`;

  const donutSlices: Slice[] = useMemo(() => {
    if (!summary) return [];
    return bucketTopSlices(Object.entries(summary.spendByCategory), CHART_PALETTE, OTHER_LABEL);
  }, [summary]);

  // Getting-started checklist: "View insights" fires once, on a 1.5s dwell rather than on mount
  // itself, so a user who opens this tab and immediately switches away doesn't get credited for a
  // screen they never actually looked at.
  const checklistQuery = useQuery({ queryKey: ['onboarding', 'checklist'], queryFn: onboardingApi.getChecklist });
  useEffect(() => {
    const item = checklistQuery.data?.items.find((i) => i.key === 'VIEW_INSIGHTS');
    if (!item || item.completed) return;
    const timer = setTimeout(() => {
      // Bug fix: same gap and same fix as LedgerScreen.tsx's REVIEW_TRANSACTIONS dwell timer --
      // without invalidating ['onboarding'], DashboardScreen's ChecklistWidget could keep showing
      // "View insights" as unchecked after it was actually completed here.
      onboardingApi.completeChecklistItem('VIEW_INSIGHTS')
        .then(() => queryClient.invalidateQueries({ queryKey: ['onboarding'] }))
        .catch(() => {});
    }, 1500);
    return () => clearTimeout(timer);
  }, [checklistQuery.data, queryClient]);

  const refreshing = deriveRefreshing([insightsQ, recurringQ], insightsQ.isLoading || recurringQ.isLoading);
  const insightsData = insightsQ.data;
  const sentences = insightsData?.sentences ?? [];
  const recurring = recurringQ.data ?? [];
  // Already sorted by the backend, most significant first (InsightsService.java's own
  // Math.abs(pctChange) descending sort) -- capped at 3 here, matching MAX_MOVER_SENTENCES on the
  // backend (the same cap that already governs which movers ever get a sentence), since these
  // rows are now the only place a mover appears on this screen.
  const movers = (insightsData?.movers ?? []).filter((m) => m.pctChange !== null).slice(0, 3);

  const iconTokenForCategory = (categoryName: string) =>
    categories.find((cat) => cat.name === categoryName)?.icon ?? 'tag';
  const colorTokenForCategory = (categoryName: string) =>
    categories.find((cat) => cat.name === categoryName)?.color ?? 'gray';

  function openTransactionsFiltered(filters: Omit<LedgerDrillThroughFilters, 'nonce'>) {
    navigation.getParent<BottomTabNavigationProp<AppTabParamList>>()?.navigate('Transactions', {
      filters: { ...filters, nonce: Date.now() },
    });
  }

  // A wrongly-detected group (a one-off large purchase RecurringService mistook for a
  // subscription, e.g.) had no way to be dismissed until now -- see recurringApi.dismiss's own
  // comment on why `merchant`, not an id, is the identity. Optimistic removal, same reasoning as
  // DashboardScreen's identical mutation: this list is purely informational, so there is no real
  // cost to a rare rollback flashing the row back in on a failed request.
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

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['insights'] });
    void queryClient.invalidateQueries({ queryKey: ['recurring'] });
  }

  return (
    <ScrollView
      style={{ backgroundColor: c.bg }}
      contentContainerStyle={styles.content}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={c.primary} />}
    >
      <View style={[styles.header, { paddingTop: insets.top + spacing.sm }]}>
        <View style={styles.headerText}>
          <Text style={[styles.headerTitle, { color: c.ink }]}>Insights</Text>
          <Text style={[styles.headerSubtitle, { color: c.muted }]}>
            Understand your money. Make better decisions.
          </Text>
        </View>
        <Pressable
          onPress={() => stackNavigation.navigate('Settings')}
          hitSlop={10}
          accessibilityRole="button"
          accessibilityLabel="Settings"
        >
          <Ionicons name="settings-outline" size={22} color={c.ink} />
        </Pressable>
      </View>

      {summary ? (
        <View style={[styles.trackBanner, { backgroundColor: c.primaryLight }]}>
          <View style={styles.trackBannerText}>
            <Text style={[styles.trackBannerTitle, { color: c.ink }]}>{trackBannerTitle}</Text>
            <Text style={[styles.trackBannerBody, { color: c.mutedInk }]}>{trackBannerBody}</Text>
          </View>
          <OnTrackIllustration />
        </View>
      ) : null}

      {summary ? (
        <View style={[styles.glanceCard, { backgroundColor: c.card, borderColor: c.border }]}>
          <Text style={[styles.glanceHeading, { color: c.ink }]}>This Month at a Glance</Text>
          <View style={styles.glanceRow}>
            {[
              { label: 'Income', value: summary.monthlyIncome, delta: summary.incomeDeltaPct, invert: false, isCount: false },
              { label: 'Expenses', value: summary.monthlyExpense, delta: summary.expenseDeltaPct, invert: true, isCount: false },
              { label: 'Categories', value: Object.keys(summary.spendByCategory).length, delta: null, invert: false, isCount: true },
              { label: 'Net Savings', value: summary.netCashFlow, delta: summary.netDeltaPct, invert: false, isCount: false },
            ].map((stat) => (
              <View key={stat.label} style={styles.glanceStat}>
                <Text style={[styles.glanceValue, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>
                  {stat.isCount ? stat.value : fmtCurrency(stat.value)}
                </Text>
                <Text style={[styles.glanceLabel, { color: c.mutedInk }]}>{stat.label}</Text>
                {stat.delta !== null ? (
                  <Text style={[
                    styles.glanceDelta,
                    { color: (stat.invert ? stat.delta < 0 : stat.delta >= 0) ? c.success : c.danger },
                  ]}>
                    {stat.delta >= 0 ? '▲' : '▼'} {Math.abs(stat.delta).toFixed(0)}%
                  </Text>
                ) : null}
              </View>
            ))}
          </View>
        </View>
      ) : null}

      {/* Static -- no data dependency -- so it renders on the very first frame, before either
          query has a chance to resolve. Kept verbatim in spirit from the web page: saying plainly
          that these are rule-based statistics and not an AI assistant is the honest framing, and
          dropping it on mobile would let the same numbers read as something they aren't. */}
      <View style={[styles.notice, { backgroundColor: c.primaryLight, borderLeftColor: c.primary }]}>
        <Text style={[styles.noticeText, { color: c.ink }]}>
          These are rule-based statistical observations from your own transaction history — not an
          AI-generated assistant.
        </Text>
      </View>

      {/* Each card gates on only the query its own data comes from -- Key Insights reads
          insightsQ, Recurring Payments reads recurringQ -- so a slow one doesn't hold the other
          on its skeleton after its own data has already arrived. */}
      {insightsQ.isLoading ? (
        <SkeletonCard style={styles.section} lines={5} />
      ) : (
        <Card style={styles.section}>
          <View style={styles.keyInsightsHeader}>
            <SectionHeading title="Key Insights" />
            {sentences.length > 0 ? (
              <Pressable onPress={() => setShowAllInsights((v) => !v)} accessibilityRole="button">
                <Text style={[styles.seeAll, { color: c.primary }]}>
                  {showAllInsights ? 'Show less' : 'See all insights'}
                </Text>
              </Pressable>
            ) : null}
          </View>
          {insightsQ.isError ? (
            <Text style={[styles.error, { color: c.danger }]}>
              Couldn&apos;t load your insights — pull down to try again.
            </Text>
          ) : !insightsData?.biggestCategory && !insightsData?.topMerchant && movers.length === 0 && sentences.length === 0 ? (
            <EmptyState message="Nothing stands out this month yet — observations appear as more transactions land." />
          ) : (
            <>
              {insightsData?.biggestCategory ? (
                <Pressable
                  style={[styles.insightRow, { borderBottomColor: c.border }]}
                  accessibilityRole="button"
                  accessibilityLabel={`Biggest category: ${insightsData.biggestCategory.name} at ${fmtCurrency(insightsData.biggestCategory.amount)}`}
                  accessibilityHint="Opens these transactions"
                  android_ripple={{ color: c.border }}
                  onPress={() => openTransactionsFiltered({
                    categoryName: insightsData.biggestCategory!.name,
                    label: insightsData.biggestCategory!.name,
                  })}
                >
                  <View style={[styles.insightIcon, { backgroundColor: colorHexFor(colorTokenForCategory(insightsData.biggestCategory.name)) }]}>
                    <Ionicons name={iconNameFor(iconTokenForCategory(insightsData.biggestCategory.name))} size={16} color="#fff" />
                  </View>
                  <Text style={[styles.insightText, { color: c.ink }]} numberOfLines={largeText ? 3 : 2}>
                    <Text style={styles.insightBold}>{insightsData.biggestCategory.name}</Text> was your
                    biggest category at <Text style={styles.insightBold}>{fmtCurrency(insightsData.biggestCategory.amount)}</Text>.
                  </Text>
                  <Ionicons name="chevron-forward" size={16} color={c.muted} />
                </Pressable>
              ) : null}

              {insightsData?.topMerchant ? (
                <Pressable
                  style={[styles.insightRow, { borderBottomColor: c.border }]}
                  accessibilityRole="button"
                  accessibilityLabel={`Top merchant: ${insightsData.topMerchant.name} at ${fmtCurrency(insightsData.topMerchant.amount)}`}
                  accessibilityHint="Opens these transactions"
                  android_ripple={{ color: c.border }}
                  onPress={() => openTransactionsFiltered({
                    keyword: insightsData.topMerchant!.name,
                    label: insightsData.topMerchant!.name,
                  })}
                >
                  <View style={[styles.insightIcon, { backgroundColor: c.mutedInk }]}>
                    <Ionicons name="trophy-outline" size={16} color="#fff" />
                  </View>
                  <Text style={[styles.insightText, { color: c.ink }]} numberOfLines={largeText ? 3 : 2}>
                    Your top merchant this month was{' '}
                    <Text style={styles.insightBold}>"{insightsData.topMerchant.name}"</Text> at{' '}
                    <Text style={styles.insightBold}>{fmtCurrency(insightsData.topMerchant.amount)}</Text>.
                  </Text>
                  <Ionicons name="chevron-forward" size={16} color={c.muted} />
                </Pressable>
              ) : null}

              {movers.map((m) => (
                // Track C/C4. categoryName only, no date range: this endpoint reports a category
                // mover, not which calendar month it moved in (InsightsData carries no month
                // field at all, unlike DashboardSummary), so there is no server-given period here
                // to anchor a range to -- an invented one would be a guess dressed up as a fact.
                // The category alone is still a real, honest narrowing.
                <Pressable
                  key={m.category}
                  style={[styles.insightRow, { borderBottomColor: c.border }]}
                  accessibilityRole="button"
                  accessibilityLabel={`${m.category} spend was ${Math.abs(m.pctChange ?? 0).toFixed(0)}% ${
                    (m.pctChange ?? 0) >= 0 ? 'more' : 'lower'
                  } than your recent average, ${fmtCurrency(m.current)} versus usual ${fmtCurrency(m.priorAverage)}`}
                  accessibilityHint="Opens these transactions"
                  android_ripple={{ color: c.border }}
                  onPress={() => openTransactionsFiltered({ categoryName: m.category, label: m.category })}
                >
                  <View style={[styles.insightIcon, { backgroundColor: colorHexFor(colorTokenForCategory(m.category)) }]}>
                    <Ionicons name={iconNameFor(iconTokenForCategory(m.category))} size={16} color="#fff" />
                  </View>
                  <Text style={[styles.insightText, { color: c.ink }]} numberOfLines={largeText ? 3 : 2}>
                    <Text style={styles.insightBold}>{m.category}</Text> spend was{' '}
                    <Text style={styles.insightBold}>
                      {Math.abs(m.pctChange ?? 0).toFixed(0)}% {(m.pctChange ?? 0) >= 0 ? 'more' : 'lower'}
                    </Text>{' '}
                    than your recent average ({fmtCurrency(m.current)} vs {fmtCurrency(m.priorAverage)}).
                  </Text>
                  <Ionicons name="chevron-forward" size={16} color={c.muted} />
                </Pressable>
              ))}

              {showAllInsights ? (
                <View style={styles.allInsights}>
                  {/* Keyed by position: these sentences carry no id, the list never reorders or
                      filters, and two identical observations would collide on the text itself. */}
                  {sentences.map((s, i) => (
                    <View key={i} style={[styles.observation, { borderLeftColor: c.border }]}>
                      <Text style={[styles.observationText, { color: c.ink }]}>{s}</Text>
                    </View>
                  ))}
                </View>
              ) : null}
            </>
          )}
        </Card>
      )}

      {summary ? (
        <Card style={styles.section}>
          <SectionHeading title="Spending by Category" />
          {donutSlices.length === 0 ? (
            <EmptyState message="No spending recorded this month yet." />
          ) : (
            <DonutChart
              slices={donutSlices}
              centerLabel={fmtCurrency(donutSlices.reduce((s, x) => s + x.value, 0))}
              onSlicePress={(categoryName) => {
                // reportingMonth can't be null here -- donutSlices is only non-empty when summary
                // has real category spend, which requires a real reporting month behind it. Same
                // guard DashboardScreen's identical donut uses.
                const { dateFrom, dateTo } = monthDateRange(summary!.reportingMonth!);
                navigation.getParent<BottomTabNavigationProp<AppTabParamList>>()?.navigate('Transactions', {
                  filters: {
                    categoryName, dateFrom, dateTo,
                    label: `${categoryName} · ${monthLabel(summary!.reportingMonth!)}`,
                    nonce: Date.now(),
                  },
                });
              }}
            />
          )}
        </Card>
      ) : null}

      {recurringQ.isLoading ? (
        <SkeletonCard style={styles.section} lines={4} />
      ) : (
        <Card style={styles.section}>
          <SectionHeading title="Recurring Payments & Subscriptions" />
          {recurringQ.isError ? (
            <Text style={[styles.error, { color: c.danger }]}>
              Couldn&apos;t load recurring payments — pull down to try again.
            </Text>
          ) : recurring.length === 0 ? (
            <EmptyState message="No recurring payments detected yet — this needs at least 2 charges from the same merchant on a regular interval to spot a pattern." />
          ) : (
            recurring.map((r) => (
              // Same accessibilityActions pattern as LedgerScreen's row (delete/edit/explain): a
              // nested Pressable inside an already-accessible={true} View isn't independently
              // reachable by a screen reader either way, so the reachable path for that user is
              // this action, not the icon below (which stays a sighted-only affordance,
              // accessible={false}). eslint-disable-next-line is for
              // react-native-a11y/no-nested-touchables -- see comment above.
              // eslint-disable-next-line react-native-a11y/no-nested-touchables
              <View
                key={r.merchant}
                style={[styles.row, { borderBottomColor: c.border }]}
                accessible
                accessibilityLabel={`${r.merchant}, ${r.label}. ${fmtCurrency(r.averageAmount)} on average, seen ${
                  r.occurrences
                } times. Next expected around ${fmtDate(r.nextEstimate) ?? r.nextEstimate}`}
                accessibilityActions={[{ name: 'dismiss', label: 'Not recurring' }]}
                onAccessibilityAction={(e) => {
                  if (e.nativeEvent.actionName === 'dismiss') dismissRecurring.mutate(r.merchant);
                }}
              >
                <View style={styles.rowMain}>
                  <Text style={[styles.rowTitle, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>
                    {r.merchant}
                  </Text>
                  <Text style={[styles.rowMeta, { color: c.mutedInk }]}>
                    {fmtCurrency(r.averageAmount)} · seen {r.occurrences}×
                  </Text>
                </View>
                <View style={styles.rowRight}>
                  {/* primaryLight on white is a ~1.13:1 contrast (computed) -- same invisible-pill
                      bug found and fixed on Dashboard/HealthFactorsRow and Upcoming; a border makes
                      the badge's own boundary visible without changing its fill color. */}
                  <Text style={[styles.badge, { color: c.primary, backgroundColor: c.primaryLight, borderWidth: 1, borderColor: c.border }]}>{r.label}</Text>
                  <Text style={[styles.rowMeta, { color: c.mutedInk }]}>next ~{fmtDate(r.nextEstimate) ?? r.nextEstimate}</Text>
                </View>
                <Pressable
                  onPress={() => dismissRecurring.mutate(r.merchant)}
                  disabled={dismissRecurring.isPending}
                  hitSlop={10}
                  style={styles.dismissButton}
                  accessible={false}
                  testID={`dismiss-recurring-${r.merchant}`}
                >
                  <Ionicons name="close" size={16} color={c.muted} />
                </Pressable>
              </View>
            ))
          )}
        </Card>
      )}

    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  // Deliberately no paddingHorizontal of its own -- this sits inside the same ScrollView
  // contentContainerStyle={styles.content} as everything else in this file, and `content`'s own
  // `padding: spacing.md` already gives it (and the static disclaimer banner right below it) the
  // standard horizontal inset. A second, separate paddingHorizontal here would double that inset
  // for the header only, indenting its title further than the cards below it.
  header: {
    flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start',
    paddingBottom: spacing.md,
  },
  headerText: { flex: 1, marginRight: spacing.sm },
  headerTitle: { fontSize: 22, fontWeight: '700' },
  headerSubtitle: { fontSize: 13, marginTop: 2 },
  // No marginHorizontal on either card below -- content's own padding already gives every
  // top-level child the standard horizontal inset; a second one here would double it, making
  // these two narrower than the .section-styled Cards elsewhere on this screen.
  trackBanner: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    borderRadius: radius.md, padding: spacing.md, marginBottom: spacing.md,
  },
  trackBannerText: { flex: 1, marginRight: spacing.sm },
  trackBannerTitle: { fontSize: 16, fontWeight: '700' },
  trackBannerBody: { fontSize: 12, marginTop: 4, lineHeight: 17 },
  glanceCard: {
    borderWidth: 1, borderRadius: radius.md, padding: spacing.md, marginBottom: spacing.md,
  },
  glanceHeading: { fontSize: 14, fontWeight: '700', marginBottom: spacing.sm },
  glanceRow: { flexDirection: 'row', justifyContent: 'space-between' },
  glanceStat: { flex: 1, alignItems: 'flex-start' },
  glanceValue: { fontSize: 15, fontWeight: '700' },
  glanceLabel: { fontSize: 10, marginTop: 2 },
  glanceDelta: { fontSize: 10, fontWeight: '600', marginTop: 2 },
  notice: {
    borderLeftWidth: 3,
    borderRadius: radius.md,
    padding: spacing.sm,
  },
  noticeText: { fontSize: 12, lineHeight: 18 },
  section: { marginTop: spacing.md },
  error: { fontSize: 13, paddingVertical: spacing.sm },
  observation: {
    borderLeftWidth: 3,
    paddingLeft: spacing.sm,
    paddingVertical: 6,
    marginBottom: spacing.sm,
  },
  observationText: { fontSize: 13, lineHeight: 20 },
  keyInsightsHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  seeAll: { fontSize: 12, fontWeight: '600' },
  insightRow: {
    flexDirection: 'row', alignItems: 'center', paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth, gap: spacing.sm,
  },
  insightIcon: {
    width: 32, height: 32, borderRadius: 16, alignItems: 'center', justifyContent: 'center',
  },
  insightText: { flex: 1, fontSize: 13, lineHeight: 18 },
  insightBold: { fontWeight: '700' },
  allInsights: { marginTop: spacing.sm },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  rowMain: { flex: 1, marginRight: spacing.sm },
  rowRight: { alignItems: 'flex-end', gap: 4 },
  rowTitle: { fontSize: 14, fontWeight: '500', textTransform: 'capitalize' },
  rowMeta: { fontSize: 11, marginTop: 2 },
  badge: {
    fontSize: 10,
    fontWeight: '600',
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: radius.md,
    overflow: 'hidden',
    textTransform: 'uppercase',
  },
  dismissButton: { marginLeft: spacing.xs, padding: 2 },
});
