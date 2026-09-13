import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Pressable, RefreshControl, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigation } from '@react-navigation/native';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import { usePreventScreenCapture } from 'expo-screen-capture';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Card, EmptyState, SectionHeading } from '../components/Card';
import { DonutChart, type Slice } from '../components/charts/DonutChart';
import { OnTrackIllustration } from '../components/insights/OnTrackIllustration';
import { SkeletonCard } from '../components/skeletons/Skeletons';
import {
  categoriesApi, dashboardApi, insightsApi, onboardingApi, recurringApi, reportsApi, type RecurringItem,
} from '../api/endpoints';
import { OptionPickerModal } from '../components/OptionPickerModal';
import { CHART_PALETTE, bucketTopSlices } from '../lib/chartGeometry';
import { colorHexFor, iconNameFor } from '../lib/categoryIcons';
import { fmtCurrency, fmtDate, monthDateRange, monthLabel, monthLabelLong } from '../lib/format';
import { deriveRefreshing } from '../lib/refreshingIndicator';
import { useDashboardKpis } from '../lib/useDashboardKpis';
import { useLargeFontScale } from '../lib/useLargeFontScale';
import { radius, spacing, useTheme } from '../theme';
import type { AppTabParamList, LedgerDrillThroughFilters } from '../navigation/types';

const OTHER_LABEL = 'Other';

type TabKey = 'overview' | 'spending' | 'income' | 'recurring' | 'trends';
const TABS: { key: TabKey; label: string }[] = [
  { key: 'overview', label: 'Overview' },
  { key: 'spending', label: 'Spending' },
  { key: 'income', label: 'Income' },
  { key: 'recurring', label: 'Recurring' },
  { key: 'trends', label: 'Trends' },
];

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
  // Sits directly on the bottom tab bar (promoted from a MoreStack row -- swapped with Goals,
  // which moved the other way; see AppTabs.tsx). One navigation object, not a separate
  // stack-scoped one: unlike a MoreStack-nested screen, this IS the Tab.Navigator's own prop
  // object, so a same-tab-bar jump (Transactions) is a direct `navigate`, and a cross-into-More
  // jump (Settings, Reports) is `navigate('More', { screen: ... })` -- the exact same nested-
  // navigate pattern DashboardScreen already uses for Budgets/Reports/Investments.
  const navigation = useNavigation<BottomTabNavigationProp<AppTabParamList>>();

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

  const scrollRef = useRef<ScrollView>(null);
  // Set true by Overview's "View Recurring" link, consumed by Spending's own Recurring section
  // onLayout (Task 13) -- switching tabs re-renders before Spending's content has mounted or
  // measured, so a scrollTo call fired synchronously in this link's own onPress would land on a
  // stale/zero y. Deferring to the real onLayout event is what makes the scroll land correctly.
  const pendingScrollToRecurring = useRef(false);
  const [activeTab, setActiveTab] = useState<TabKey>('overview');
  // A single ScrollView holds every tab's content (swapped below, not a separate ScrollView per
  // tab) -- switching tabs doesn't reset its scroll offset on its own, so a plain pill tap would
  // otherwise leave the new tab's content showing mid-scroll.
  function switchTab(tab: TabKey) {
    setActiveTab(tab);
    scrollRef.current?.scrollTo({ y: 0, animated: false });
  }

  // Spending tab's month picker. `undefined` means "no explicit pick yet" -- see the query key
  // below for why that's load-bearing, not just a default value.
  const [month, setMonth] = useState<string | undefined>(undefined);
  const [monthPickerOpen, setMonthPickerOpen] = useState(false);
  const [showAllMovers, setShowAllMovers] = useState(false);

  // Bug found in review: a naive `['insights', month]` key is a DIFFERENT cache entry from
  // Overview's (and DashboardScreen's) own `['insights']` even when `month` is `undefined` --
  // that would fire a redundant network call for identical data on every Insights screen open,
  // and flash a skeleton Spending doesn't need. Sharing the literal `['insights']` key until a
  // month is actually picked means both `useQuery` calls below coalesce into the one request
  // TanStack Query already dedupes for an identical key mounted twice.
  const spendingInsightsQ = useQuery({
    queryKey: month ? ['insights', month] : ['insights'],
    queryFn: () => insightsApi.get(month),
  });
  const monthsQ = useQuery({ queryKey: ['report-months'], queryFn: () => reportsApi.availableMonths() });
  // Newest first, same reasoning as AdvancedReportsScreen's own identical picker.
  const monthsNewestFirst = useMemo(() => [...(monthsQ.data ?? [])].reverse(), [monthsQ.data]);
  const monthOptions = useMemo(() => monthsNewestFirst.map(monthLabelLong), [monthsNewestFirst]);
  const labelToMonth = useMemo(() => {
    const map: Record<string, string> = {};
    monthsNewestFirst.forEach((m) => { map[monthLabelLong(m)] = m; });
    return map;
  }, [monthsNewestFirst]);
  // Before any explicit pick, show the same current reporting month Overview's own summary
  // already carries -- avoids a second source of truth for "what month is this by default".
  const selectedMonthLabel = month
    ? monthLabelLong(month)
    : summary?.reportingMonth ? monthLabelLong(summary.reportingMonth) : '';
  const spendingSentences = spendingInsightsQ.data?.sentences ?? [];

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
    navigation.navigate('Transactions', {
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
    <>
    <ScrollView
      ref={scrollRef}
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
          onPress={() => navigation.navigate('More', { screen: 'Settings' })}
          hitSlop={10}
          accessibilityRole="button"
          accessibilityLabel="Settings"
        >
          <Ionicons name="settings-outline" size={22} color={c.ink} />
        </Pressable>
      </View>

      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        style={styles.tabRow}
        contentContainerStyle={styles.tabRowContent}
      >
        {TABS.map((t) => (
          <Pressable
            key={t.key}
            onPress={() => switchTab(t.key)}
            style={[styles.tabPill, activeTab === t.key ? { backgroundColor: c.primaryLight } : null]}
            accessibilityRole="button"
            accessibilityState={{ selected: activeTab === t.key }}
            accessibilityLabel={t.label}
          >
            <Text
              style={[styles.tabPillText, { color: activeTab === t.key ? c.primary : c.mutedInk }]}
              numberOfLines={largeText ? 2 : 1}
            >
              {t.label}
            </Text>
          </Pressable>
        ))}
      </ScrollView>

      {activeTab === 'overview' ? (
        <>
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
          <SectionHeading
            title="Key Insights"
            action={sentences.length > 0 ? (
              <Pressable onPress={() => setShowAllInsights((v) => !v)} accessibilityRole="button">
                <Text style={[styles.seeAll, { color: c.primary }]}>
                  {showAllInsights ? 'Show less' : 'See all insights'}
                </Text>
              </Pressable>
            ) : undefined}
          />
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
                navigation.navigate('Transactions', {
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

      {recurring.length > 0 ? (
        <Card style={styles.section}>
          <View style={styles.recurringSummaryRow}>
            <View>
              <Text style={[styles.recurringSummaryCount, { color: c.ink }]}>{recurring.length} active</Text>
              <Text style={[styles.recurringSummaryTotal, { color: c.mutedInk }]}>
                {fmtCurrency(recurring.reduce((s, r) => s + r.averageAmount, 0))} / month
              </Text>
            </View>
            <Pressable
              onPress={() => {
                pendingScrollToRecurring.current = true;
                switchTab('spending');
              }}
              accessibilityRole="button"
            >
              <Text style={[styles.viewRecurring, { color: c.primary }]}>View Recurring →</Text>
            </Pressable>
          </View>
        </Card>
      ) : null}

      {/* Reuses the same expenseDelta the top banner and This Month at a Glance already computed
          -- see that const's own comment. Deliberately repeated content (per the mockup, kept
          rather than dropped): "View Details" is a real, new destination, not a placeholder. */}
      {expenseDelta !== null ? (
        <View style={[styles.bottomBanner, { backgroundColor: c.primaryLight }]}>
          <Text style={[styles.bottomBannerText, { color: c.ink }]}>
            {expenseDelta <= 0
              ? `You're spending ${Math.abs(expenseDelta).toFixed(0)}% less than last month.`
              : `You're spending ${expenseDelta.toFixed(0)}% more than last month.`}
          </Text>
          <Pressable onPress={() => navigation.navigate('More', { screen: 'Reports' })} accessibilityRole="button">
            <Text style={[styles.bottomBannerLink, { color: c.primary }]}>View Details →</Text>
          </Pressable>
        </View>
      ) : null}
        </>
      ) : null}

      {activeTab === 'spending' ? (
        <>
          <View style={[styles.notice, { backgroundColor: c.primaryLight, borderLeftColor: c.primary }]}>
            <Text style={[styles.noticeText, { color: c.ink }]}>
              These are rule-based statistical observations from your own transaction history —
              not an AI-generated assistant.
            </Text>
          </View>

          {spendingInsightsQ.isLoading ? (
            <SkeletonCard style={styles.section} lines={5} />
          ) : (
            <Card style={styles.section}>
              <SectionHeading
                title="This Month's Observations"
                action={
                  <Pressable
                    onPress={() => setMonthPickerOpen(true)}
                    style={styles.monthPickerButton}
                    accessibilityRole="button"
                    accessibilityLabel={`Change month, currently ${selectedMonthLabel}`}
                  >
                    <Text style={[styles.monthPickerText, { color: c.ink }]} numberOfLines={1}>
                      {selectedMonthLabel}
                    </Text>
                    <Ionicons name="chevron-down" size={14} color={c.muted} />
                  </Pressable>
                }
              />
              {spendingInsightsQ.isError ? (
                <Text style={[styles.error, { color: c.danger }]}>
                  Couldn&apos;t load your insights — pull down to try again.
                </Text>
              ) : spendingSentences.length === 0 ? (
                <EmptyState message="Nothing stands out this month yet — observations appear as more transactions land." />
              ) : (
                spendingSentences.map((s, i) => (
                  <View key={i} style={[styles.observation, { borderLeftColor: c.border }]}>
                    <Text style={[styles.observationText, { color: c.ink }]}>{s}</Text>
                  </View>
                ))
              )}
            </Card>
          )}

          {/* Task 13 adds the moved Recurring Payments list here. */}
          {/* Task 14 adds the Category Movers section here. */}
        </>
      ) : null}
    </ScrollView>
    <OptionPickerModal
      visible={monthPickerOpen}
      title="Month"
      options={monthOptions}
      selected={selectedMonthLabel}
      onSelect={(label) => { setMonth(labelToMonth[label]); setMonthPickerOpen(false); }}
      onClose={() => setMonthPickerOpen(false)}
    />
  </>
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
  tabRow: { marginBottom: spacing.md },
  tabRowContent: { gap: spacing.xs, paddingRight: spacing.md },
  tabPill: {
    paddingHorizontal: spacing.md, paddingVertical: 6, borderRadius: radius.lg,
  },
  tabPillText: { fontSize: 13, fontWeight: '600' },
  monthPickerButton: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  monthPickerText: { fontSize: 13, fontWeight: '600' },
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
  recurringSummaryRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  recurringSummaryCount: { fontSize: 15, fontWeight: '700' },
  recurringSummaryTotal: { fontSize: 12, marginTop: 2 },
  viewRecurring: { fontSize: 12, fontWeight: '600' },
  bottomBanner: {
    flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center',
    borderRadius: radius.md, padding: spacing.md, marginTop: spacing.md,
  },
  bottomBannerText: { flex: 1, fontSize: 13, marginRight: spacing.sm },
  bottomBannerLink: { fontSize: 12, fontWeight: '700' },
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
