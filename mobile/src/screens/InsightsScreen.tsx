import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useFocusEffect, useNavigation, useScrollToTop } from '@react-navigation/native';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import Ionicons from '@expo/vector-icons/Ionicons';
import { AppRefreshControl } from '../components/AppRefreshControl';
import { Card, EmptyState } from '../components/Card';
import { GlassScreen } from '../components/GlassScreen';
import { OptionPickerModal } from '../components/OptionPickerModal';
import { SkeletonCard, SkeletonGroup } from '../components/skeletons/Skeletons';
import { ComingUpSection } from '../components/insights/ComingUpSection';
import { InsightsQuestions, type InsightsSection } from '../components/insights/InsightsQuestions';
import { LookAtSection } from '../components/insights/LookAtSection';
import { PaidRegularlySection } from '../components/insights/PaidRegularlySection';
import { WithPeopleSection } from '../components/insights/WithPeopleSection';
import { useAskForMoney } from '../components/people/AskForMoney';
import { BillPickerSheet } from '../components/people/BillPickerSheet';
import {
  accountsApi, inflowApi, insightsApi, onboardingApi, peopleApi, recurringApi, reportsApi, usageApi,
  type AckKind, type InsightAcknowledgement, type PeopleSuggestion, type RecurringItem,
} from '../api/endpoints';
import { withBypass } from '../lib/changeSync';
import {
  fmtCurrency, fromLocalDateString, monthDateRange, monthLabel, monthLabelLong, toLocalDateString, withSep,
} from '../lib/format';
import { hapticError, hapticSuccess } from '../lib/haptics';
import {
  ackEntry, buildToLookAt, comingUp, expectedSharesIn, paidRegularly, withPeople, type LookAtRow,
} from '../lib/insightsFeed';
import { deriveRefreshing } from '../lib/refreshingIndicator';
import { usePreventScreenCapture } from '../lib/screenCapture';
import { trackNavigation } from '../lib/trackNavigation';
import type { AppTabParamList, LedgerDrillThroughFilters } from '../navigation/types';
import { padForTabBar, useTabBarSpace } from '../navigation/useTabBarSpace';
import { spacing, typography, useTheme } from '../theme';

/** "August": a YYYY-MM month's name alone. */
function monthNameOf(month: string): string {
  const [y, m] = month.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'long' });
}

/** The YYYY-MM before {@code month}. */
function previousMonth(month: string): string {
  const [y, m] = month.split('-').map(Number);
  const d = new Date(y, m - 2, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

/**
 * Insights as one screen of what is worth knowing (docs/superpowers/specs/2026-10-16-mobile-
 * insights-redesign-design.md): a sentence about the month, plain questions into the sections, then
 * what needs a look, what is coming up and what is paid regularly. Totals and charts stay in Reports
 * ("what happened?"); this screen answers "what should I notice?". Every figure on it is rule-based,
 * from the user's own transactions.
 */
export function InsightsScreen() {
  // Rows name real merchants and amounts -- same screenshot exposure Dashboard/Accounts guard against.
  usePreventScreenCapture();
  const c = useTheme();
  const tabBar = useTabBarSpace();
  const insets = useSafeAreaInsets();
  const queryClient = useQueryClient();
  // This IS the Tab.Navigator's navigation: Transactions and Import are direct navigates, a screen
  // in the More stack is navigate('More', { screen }).
  const navigation = useNavigation<BottomTabNavigationProp<AppTabParamList>>();

  // `undefined` = no pick yet, the server's newest month. The literal ['insights'] key until a pick
  // is shared with the Dashboard's own insights query, so opening this screen costs no extra call.
  const [month, setMonth] = useState<string | undefined>(undefined);
  const [monthPickerOpen, setMonthPickerOpen] = useState(false);

  const insightsQ = useQuery({
    queryKey: month ? ['insights', month] : ['insights'],
    queryFn: () => insightsApi.get(month),
  });
  const recurringQ = useQuery({ queryKey: ['recurring'], queryFn: () => recurringApi.list() });
  // Saved answers whose payee's amount moved, for payees no longer detected. Optional on an older
  // server, so a failure is quiet.
  const changedQ = useQuery({
    queryKey: ['recurring-changed-amounts'], queryFn: () => recurringApi.changedAmounts(), retry: false,
  });
  // An older server has no such endpoint: a failure means nothing is hidden, never an error.
  const acksQ = useQuery({
    queryKey: ['insight-acknowledgements'], queryFn: () => insightsApi.acknowledgements(), retry: false,
  });
  // Shared payments and loans. Optional on an older server: a failure shows none of it, never an error.
  const peopleSuggestionsQ = useQuery({
    queryKey: ['people', 'suggestions'], queryFn: () => peopleApi.suggestions(), retry: false,
  });
  const peopleQ = useQuery({ queryKey: ['people'], queryFn: () => peopleApi.list(), retry: false });
  // The built-in Paid back kind, which "Always do this for <person>" remembers the sender with.
  const kindsQ = useQuery({ queryKey: ['inflow-kinds'], queryFn: () => inflowApi.kinds(), retry: false });
  const monthsQ = useQuery({ queryKey: ['report-months'], queryFn: () => reportsApi.availableMonths() });
  const accountsQ = useQuery({ queryKey: ['accounts'], queryFn: () => accountsApi.list() });

  // The month every figure here describes: the pick, else the month the insights response reports on.
  const reportingMonth = month ?? insightsQ.data?.reportingMonth ?? null;
  // Reports' own trend query (lib/useReportExtras), so the month line and Reports share one request
  // and can never disagree.
  const trendQ = useQuery({
    queryKey: ['report', 'trend', reportingMonth],
    queryFn: () => reportsApi.trend(reportingMonth as string, 6),
    enabled: reportingMonth !== null,
  });

  const recurring = useMemo(() => recurringQ.data ?? [], [recurringQ.data]);
  const acks = useMemo(
    () => new Set((acksQ.data ?? []).map((a) => ackEntry(a.kind, a.itemKey))),
    [acksQ.data],
  );
  const lookAt = useMemo(
    () => buildToLookAt({
      insights: insightsQ.data, recurring, changed: changedQ.data ?? [], acks, people: peopleSuggestionsQ.data?.items ?? [],
    }),
    [insightsQ.data, recurring, changedQ.data, acks, peopleSuggestionsQ.data],
  );
  const expectedIn = useMemo(() => expectedSharesIn(peopleSuggestionsQ.data?.expected ?? []), [peopleSuggestionsQ.data]);
  const people = useMemo(() => withPeople(peopleQ.data ?? []), [peopleQ.data]);
  const peopleCount = people.owedToYou.length + people.youOwe.length;
  const paidBackKindId = (kindsQ.data ?? []).find((k) => k.builtIn === 'PAID_BACK')?.id ?? null;
  const today = toLocalDateString(new Date());
  // "Yes, link it to a bill": the credit whose bill is being picked.
  const [picking, setPicking] = useState<PeopleSuggestion | null>(null);
  const { ask: askForMoney, prompt: askPrompt } = useAskForMoney();
  const upcoming = useMemo(() => comingUp(recurring, toLocalDateString(new Date())), [recurring]);
  const regular = useMemo(() => paidRegularly(recurring), [recurring]);
  const latestStatementEnd = useMemo(() => {
    const ends = (accountsQ.data ?? [])
      .map((a) => a.lastStatementPeriodEnd)
      .filter((d): d is string => typeof d === 'string' && d.length > 0)
      .sort();
    return ends.length > 0 ? ends[ends.length - 1] : null;
  }, [accountsQ.data]);
  const newestMonth = monthsQ.data && monthsQ.data.length > 0 ? monthsQ.data[monthsQ.data.length - 1] : null;
  const olderMonthPicked = month !== undefined && month !== newestMonth;

  const monthLine = useMemo(() => {
    if (!reportingMonth) return null;
    const points = trendQ.data ?? [];
    const current = points.find((p) => p.month === reportingMonth);
    if (!current) return null;
    const monthLong = monthLabelLong(reportingMonth);
    if (current.complete === false) return `You've spent ${fmtCurrency(current.expense)} in ${monthLong} so far.`;
    const before = points.find((p) => p.month === previousMonth(reportingMonth));
    if (!before || before.expense <= 0) return `You spent ${fmtCurrency(current.expense)} in ${monthLong}.`;
    const pct = Math.round(((current.expense - before.expense) / before.expense) * 100);
    const beforeName = monthNameOf(before.month);
    const comparison = pct === 0 ? `the same as ${beforeName}`
      : `${Math.abs(pct)}% ${pct > 0 ? 'more' : 'less'} than ${beforeName}`;
    return `You spent ${fmtCurrency(current.expense)} in ${monthLong}, ${comparison}.`;
  }, [reportingMonth, trendQ.data]);

  const scrollRef = useRef<ScrollView>(null);
  // A second tap on the Insights tab scrolls this screen back to the top.
  useScrollToTop(scrollRef);
  // Where each section sits in the list, measured as it lays out, for the questions' jumps.
  const sectionY = useRef<Partial<Record<InsightsSection, number>>>({});
  function jumpTo(section: InsightsSection) {
    const y = sectionY.current[section];
    if (y !== undefined) scrollRef.current?.scrollTo({ y, animated: true });
  }

  // Getting-started checklist: "View insights" completes on a 1.5s dwell, not on mount, so a
  // bounced visit is not credited.
  const checklistQuery = useQuery({ queryKey: ['onboarding', 'checklist'], queryFn: onboardingApi.getChecklist });
  useEffect(() => {
    const item = checklistQuery.data?.items.find((i) => i.key === 'VIEW_INSIGHTS');
    if (!item || item.completed) return;
    const timer = setTimeout(() => {
      onboardingApi.completeChecklistItem('VIEW_INSIGHTS')
        .then(() => queryClient.invalidateQueries({ queryKey: ['onboarding'] }))
        .catch(() => {});
    }, 1500);
    return () => clearTimeout(timer);
  }, [checklistQuery.data, queryClient]);

  // Feeds MySubscriptionScreen's "Smart Insights" usage tile: once per focus, after the same dwell.
  useFocusEffect(
    useCallback(() => {
      const timer = setTimeout(() => {
        usageApi.recordView('insights').catch(() => {});
      }, 1500);
      return () => clearTimeout(timer);
    }, []),
  );

  // "Got it": the row leaves at once and comes back if the server refuses. On an older server the
  // POST fails too, so the row returns rather than vanishing only on this device.
  const acknowledge = useMutation({
    mutationFn: ({ kind, itemKey }: InsightAcknowledgement) => insightsApi.acknowledge(kind, itemKey),
    onMutate: async (ack: InsightAcknowledgement) => {
      await queryClient.cancelQueries({ queryKey: ['insight-acknowledgements'] });
      const previous = queryClient.getQueryData<InsightAcknowledgement[]>(['insight-acknowledgements']);
      queryClient.setQueryData<InsightAcknowledgement[]>(['insight-acknowledgements'], (list) => [...(list ?? []), ack]);
      return { previous };
    },
    onSuccess: () => hapticSuccess(),
    onError: (_err, _ack, context) => {
      hapticError();
      queryClient.setQueryData(['insight-acknowledgements'], context?.previous);
    },
  });

  // "Not recurring": same optimistic shape as the Dashboard's identical mutation.
  const dismissRecurring = useMutation({
    mutationFn: (merchant: string) => recurringApi.dismiss(merchant),
    onMutate: async (merchant) => {
      await queryClient.cancelQueries({ queryKey: ['recurring'] });
      const previous = queryClient.getQueryData<RecurringItem[]>(['recurring']);
      queryClient.setQueryData<RecurringItem[]>(['recurring'], (items) => (items ?? []).filter((i) => i.merchant !== merchant));
      return { previous };
    },
    onSuccess: () => hapticSuccess(),
    onError: (_err, _merchant, context) => {
      hapticError();
      if (context?.previous) queryClient.setQueryData(['recurring'], context.previous);
    },
  });

  function openTransactions(filters: Omit<LedgerDrillThroughFilters, 'nonce'>) {
    trackNavigation('transactions', 'contextual');
    navigation.navigate('Transactions', { filters: { ...filters, nonce: Date.now() } });
  }

  function openReports() {
    trackNavigation('reports', 'contextual');
    navigation.navigate('More', { screen: 'Reports', params: reportingMonth ? { month: reportingMonth } : undefined });
  }

  function openImport() {
    trackNavigation('import-statement', 'contextual');
    navigation.navigate('Import');
  }

  function openRow(row: LookAtRow) {
    if (row.kind === 'running-high') {
      openTransactions({ categoryName: row.category, ...monthDateRange(row.month), label: `${row.category} · ${monthLabel(row.month)}` });
    } else if (row.kind === 'amount-changed') {
      openTransactions({ keyword: row.merchant, label: row.merchant });
    }
  }

  function ack(row: LookAtRow) {
    if (row.kind === 'new-recurring') return;
    if (row.kind === 'people') {
      acknowledge.mutate({ kind: row.item.kind, itemKey: row.item.ackKey });
      return;
    }
    acknowledge.mutate({ kind: row.ackKind as AckKind, itemKey: row.ackItemKey });
  }

  // Not tracked: a person's page is not a navigation-taxonomy destination, and trackNavigation
  // drops anything outside it.
  function openPerson(personKey: string) {
    navigation.navigate('More', { screen: 'Person', params: { personKey } });
  }

  // "Paid for something": that day's payments, to give this one a category. By date alone: the
  // person's name is the server's label, not words the payment's own text is known to contain.
  function openPayment(item: PeopleSuggestion) {
    if (!item.date) return;
    const day = withSep(fromLocalDateString(item.date).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' }));
    openTransactions({ dateFrom: item.date, dateTo: item.date, label: `${item.label} · ${day}` });
  }

  function refresh() {
    // withBypass: a pull is a read, not an edit -- it must not wait for the change stamp (lib/changeSync.ts).
    withBypass(() => {
      for (const key of [['insights'], ['recurring'], ['recurring-changed-amounts'], ['insight-acknowledgements'],
        ['report-months'], ['report', 'trend'], ['accounts'], ['people']]) {
        void queryClient.invalidateQueries({ queryKey: key });
      }
    });
  }

  const loading = insightsQ.isLoading || recurringQ.isLoading;
  const refreshing = deriveRefreshing([insightsQ, recurringQ, acksQ, trendQ], loading);
  const monthLong = reportingMonth ? monthLabelLong(reportingMonth) : '';
  const caveat = insightsQ.data?.coverageCaveat ?? null;
  // Nothing to say yet: no month with spending and nothing repeating -- a first-run screen, not empty sections.
  const nothingYet = !loading && insightsQ.data !== undefined && !insightsQ.data.reportingMonth && recurring.length === 0;
  // The insights call failed with nothing cached: say so rather than an empty "To look at".
  const insightsFailed = insightsQ.isError && !insightsQ.data;
  const monthOptions = useMemo(() => [...(monthsQ.data ?? [])].reverse(), [monthsQ.data]);

  function section(key: InsightsSection, child: React.ReactNode) {
    return (
      <View
        testID={`insights-section-${key}`}
        style={styles.section}
        onLayout={(e) => { sectionY.current[key] = e.nativeEvent.layout.y; }}
      >
        {child}
      </View>
    );
  }

  return (
    <>
      <GlassScreen style={styles.glassRoot}>
        <ScrollView
          ref={scrollRef}
          contentContainerStyle={padForTabBar(styles.content, tabBar)}
          scrollIndicatorInsets={{ bottom: tabBar }}
          refreshControl={<AppRefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={c.primary} />}
        >
          <View style={[styles.header, { paddingTop: insets.top + spacing.sm }]}>
            <View style={styles.headerText}>
              <Text style={[typography.screenTitle, { color: c.ink }]}>Insights</Text>
              {reportingMonth ? (
                <Pressable
                  onPress={() => setMonthPickerOpen(true)}
                  accessibilityRole="button"
                  accessibilityLabel={`Change month, currently ${monthLong}`}
                  hitSlop={8}
                  style={styles.monthButton}
                >
                  <Text style={[typography.labelM, { color: c.mutedInk }]}>{monthLong}</Text>
                  <Ionicons name="chevron-down" size={14} color={c.mutedInk} />
                </Pressable>
              ) : null}
            </View>
            <Pressable
              onPress={() => { trackNavigation('settings', 'contextual'); navigation.navigate('More', { screen: 'Settings' }); }}
              hitSlop={10}
              accessibilityRole="button"
              accessibilityLabel="Settings"
            >
              <Ionicons name="settings-outline" size={22} color={c.ink} />
            </Pressable>
          </View>

          {monthLine ? <Text style={[typography.bodyM, styles.monthLine, { color: c.ink }]}>{monthLine}</Text> : null}

          {caveat ? (
            <Pressable
              onPress={openImport}
              accessibilityRole="button"
              accessibilityLabel={`Possible gap in your ${monthLabelLong(caveat.month)} history. Import that statement to complete it.`}
              accessibilityHint="Opens the Import screen"
              style={styles.section}
            >
              <Card style={{ backgroundColor: c.warningBg }}>
                <Text style={[typography.labelM, { color: c.warningInk }]}>{`Possible gap in ${monthLabelLong(caveat.month)}`}</Text>
                <Text style={[typography.bodyS, { color: c.warningInk }]}>Some transactions may be missing from your history.</Text>
                <Text style={[typography.labelS, styles.caveatCta, { color: c.primary }]}>Import that statement ›</Text>
              </Card>
            </Pressable>
          ) : null}

          {loading ? (
            <SkeletonGroup style={styles.section}>
              <SkeletonCard lines={4} />
              <SkeletonCard lines={5} />
              <SkeletonCard lines={4} />
            </SkeletonGroup>
          ) : nothingYet ? (
            <Card style={styles.section}>
              <EmptyState
                message="Upload a statement and Insights will show what needs a look, what is coming up and what you pay regularly."
                actionLabel="Import a statement"
                onAction={openImport}
              />
            </Card>
          ) : (
            <>
              <View style={styles.section}>
                <InsightsQuestions
                  counts={{
                    lookAt: lookAt.length, comingUp: upcoming.rows.length + expectedIn.length,
                    paidRegularly: regular.rows.length, withPeople: peopleCount,
                  }}
                  onJump={jumpTo}
                  onOpenReports={openReports}
                />
              </View>
              {section('lookAt', insightsFailed ? (
                <Card>
                  <Text style={[typography.bodyM, { color: c.mutedInk }]}>Couldn't load what needs a look. Pull down to try again.</Text>
                </Card>
              ) : (
                <LookAtSection
                  rows={lookAt}
                  monthLong={monthLong}
                  onOpenRow={openRow}
                  onAck={ack}
                  onUpload={openImport}
                  today={today}
                  paidBackKindId={paidBackKindId}
                  onPickBill={setPicking}
                  onAsk={(item) => askForMoney({ label: item.label, owed: item.owed ?? item.amount })}
                  onOpenPayment={openPayment}
                />
              ))}
              {upcoming.rows.length > 0 || expectedIn.length > 0 ? section('comingUp', (
                <ComingUpSection
                  rows={upcoming.rows}
                  stillToGoOut={upcoming.stillToGoOut}
                  latestStatementEnd={latestStatementEnd}
                  expected={expectedIn}
                />
              )) : null}
              {regular.rows.length > 0 ? section('paidRegularly', (
                <PaidRegularlySection
                  rows={regular.rows}
                  monthlyTotal={regular.monthlyTotal}
                  yearlyTotal={regular.yearlyTotal}
                  olderMonthPicked={olderMonthPicked}
                  onDismiss={(merchant) => dismissRecurring.mutate(merchant)}
                />
              )) : null}
              {peopleCount > 0 ? section('withPeople', (
                <WithPeopleSection
                  owedToYou={people.owedToYou}
                  youOwe={people.youOwe}
                  onOpenPerson={(p) => openPerson(p.personKey)}
                />
              )) : null}
              <Pressable onPress={openReports} accessibilityRole="button" accessibilityLabel="See the full month in Reports" style={styles.reportsLink}>
                <Text style={[typography.labelM, { color: c.primary }]}>See the full month in Reports</Text>
                <Ionicons name="arrow-forward" size={16} color={c.primary} />
              </Pressable>
            </>
          )}

          <Text style={[typography.caption, styles.note, { color: c.mutedInk }]}>
            These are rule-based statistical observations from your own transaction history — not an AI-generated assistant.
          </Text>
        </ScrollView>
      </GlassScreen>
      <OptionPickerModal
        visible={monthPickerOpen}
        title="Month"
        options={monthOptions.map(monthLabelLong)}
        selected={monthLong}
        onSelect={(label) => {
          const picked = monthOptions.find((m) => monthLabelLong(m) === label);
          if (picked) setMonth(picked);
          setMonthPickerOpen(false);
        }}
        onClose={() => setMonthPickerOpen(false)}
      />
      {picking && picking.txnId ? (
        <BillPickerSheet
          mode="bill"
          credit={{ txnId: picking.txnId, label: picking.label, amount: picking.amount, date: picking.date ?? today }}
          options={picking.bills}
          onClose={() => setPicking(null)}
        />
      ) : null}
      {askPrompt}
    </>
  );
}

const styles = StyleSheet.create({
  glassRoot: { flex: 1 },
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', paddingBottom: spacing.sm },
  headerText: { flex: 1, marginRight: spacing.sm },
  monthButton: { flexDirection: 'row', alignItems: 'center', gap: 4, marginTop: 2, alignSelf: 'flex-start', minHeight: 28 },
  monthLine: { marginBottom: spacing.md },
  section: { marginBottom: spacing.md },
  caveatCta: { marginTop: spacing.xs },
  reportsLink: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: spacing.xs, minHeight: 44 },
  note: { textAlign: 'center', marginTop: spacing.sm },
});
