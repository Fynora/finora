import { useMemo, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useNavigation } from '@react-navigation/native';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Ionicons from '@expo/vector-icons/Ionicons';
import { AppRefreshControl } from '../components/AppRefreshControl';
import { Card, EmptyState } from '../components/Card';
import { GlassScreen } from '../components/GlassScreen';
import { OptionPickerModal } from '../components/OptionPickerModal';
import { PremiumFeatureGate } from '../components/PremiumFeatureGate';
import { SkeletonCard, SkeletonGroup } from '../components/skeletons/Skeletons';
import { LoadFailed } from '../components/LoadFailed';
import { AdvancedKpis } from '../components/advancedReports/AdvancedKpis';
import { AdvancedTabs, type AdvancedTab } from '../components/advancedReports/AdvancedTabs';
import { CategoryList } from '../components/advancedReports/CategoryList';
import { CategorisationSection } from '../components/advancedReports/CategorisationSection';
import { CategoryTrendChart } from '../components/advancedReports/CategoryTrendChart';
import { FlowChart } from '../components/advancedReports/FlowChart';
import { IncomeSection } from '../components/advancedReports/IncomeSection';
import { InsightCards } from '../components/advancedReports/InsightCards';
import { InternationalSection } from '../components/advancedReports/InternationalSection';
import { MerchantList } from '../components/advancedReports/MerchantList';
import { PeriodNotice } from '../components/advancedReports/PeriodNotice';
import { UnusualList } from '../components/advancedReports/UnusualList';
import { PaymentMethods } from '../components/reportSections/PaymentMethods';
import {
  analyticsApi, reportsApi, type AdvancedCompare, type AdvancedRange, type AdvancedReportParams,
} from '../api/endpoints';
import {
  compareOptionLabel, incompleteNote, noDataCopy, periodSpan, RANGE_OPTIONS, type TransactionsFilter,
} from '../lib/advancedReports/advancedFormat';
import { shareAdvancedCsv } from '../lib/advancedReports/shareAdvancedCsv';
import { toUserMessage } from '../lib/apiError';
import { monthLabelLong } from '../lib/format';
import { PREMIUM_PLAN_VISIBLE } from '../lib/premiumVisibility';
import { methodBreakdown } from '../lib/reportSections/methodBreakdown';
import { usePreventScreenCapture } from '../lib/screenCapture';
import { trackNavigation } from '../lib/trackNavigation';
import type { AppTabParamList } from '../navigation/types';
import { padForTabBar, useTabBarSpace } from '../navigation/useTabBarSpace';
import { spacing, typography, useTheme } from '../theme';

const DEFAULT_RANGE: AdvancedRange = 'LAST_12_MONTHS';

/**
 * Mobile counterpart to frontend/src/pages/AdvancedReports.tsx: one period (last 3, 6 or 12 months,
 * this year, last year, all time, or a custom run) and one comparison drive every figure on the
 * Overview, Spending, Income and Merchants tabs, all read from GET /analytics/advanced; the Category
 * insights tab is the categorisation engine's own history and does not follow the period. The same
 * ADVANCED_REPORTS gate as web. Reached from the More menu, always visible even to a Free user -- the
 * gate shows the upgrade prompt on open rather than hiding the entry point, same reasoning as web's
 * Sidebar.tsx.
 */
export function AdvancedReportsScreen() {
  usePreventScreenCapture();
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const navigation = useNavigation();

  return (
    <GlassScreen style={[styles.flex, { paddingTop: insets.top }]}>
      <View style={styles.titleRow}>
        <Pressable onPress={() => navigation.goBack()} hitSlop={10} accessibilityRole="button" accessibilityLabel="Back">
          <Ionicons name="chevron-back" size={22} color={c.ink} />
        </Pressable>
        <View style={styles.titleTextCol}>
          <View style={styles.titleWithIcon}>
            <Ionicons name="ribbon" size={18} color={c.primary} />
            <Text style={[typography.screenTitle, { color: c.ink }]}>Advanced Reports</Text>
          </View>
          <Text style={[typography.bodyS, { color: c.mutedInk }]}>
            How your income, spending and savings have changed over time, and what is behind it.
          </Text>
        </View>
      </View>

      {/* PremiumFeatureGate, not AdvancedReportsContent, owns the ScrollView/RefreshControl below it --
          the gate's own fallback (a single short card) needs neither, and the queries a pull-to-refresh
          here would refetch live inside the gated content, one level down. */}
      <PremiumFeatureGate featureKey="ADVANCED_REPORTS" fallback={<UpgradePrompt />}>
        <AdvancedReportsContent />
      </PremiumFeatureGate>
    </GlassScreen>
  );
}

/** More context than PremiumFeatureGate's own generic default, since this gates an entire screen
 *  rather than one widget -- same reasoning as web's identical UpgradePrompt. */
function UpgradePrompt() {
  const c = useTheme();
  return (
    <Card style={styles.upgradePrompt}>
      <EmptyState message={`Advanced Reports is a ${PREMIUM_PLAN_VISIBLE ? 'Plus & Premium' : 'Plus'} feature -- how your income, spending, categories and merchants have changed over months and years, compared with the period before, all built from your own transaction history.`} />
      <Text style={[styles.upgradeHint, { color: c.primary }]}>Open Settings › Subscription to view plans.</Text>
    </Card>
  );
}

type Picker = 'range' | 'compare' | 'start' | 'end' | null;

function BodySkeleton() {
  return (
    <SkeletonGroup>
      <SkeletonCard style={styles.section} lines={2} />
      <SkeletonCard style={styles.section} lines={3} />
      <SkeletonCard style={styles.section} lines={4} />
    </SkeletonGroup>
  );
}

function AdvancedReportsContent() {
  const c = useTheme();
  const tabBar = useTabBarSpace();
  const navigation = useNavigation();
  const [range, setRange] = useState<AdvancedRange>(DEFAULT_RANGE);
  const [startMonth, setStartMonth] = useState('');
  const [endMonth, setEndMonth] = useState('');
  const [compare, setCompare] = useState<AdvancedCompare | null>(null);
  const [tab, setTab] = useState<AdvancedTab>('overview');
  const [picker, setPicker] = useState<Picker>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);

  const customReady = range !== 'CUSTOM' || (startMonth !== '' && endMonth !== '' && startMonth <= endMonth);
  const request: AdvancedReportParams = {
    range,
    ...(range === 'CUSTOM' ? { startMonth, endMonth } : {}),
    ...(compare ? { compare } : {}),
  };
  const reportQ = useQuery({
    queryKey: ['advanced-report', request],
    queryFn: () => analyticsApi.advanced(request),
    // Nothing to ask until a custom run has both months; the categorisation tab does not use the period.
    enabled: customReady && tab !== 'categorisation',
    placeholderData: keepPreviousData,
  });
  const monthsQ = useQuery({ queryKey: ['report-months'], queryFn: () => reportsApi.availableMonths(), enabled: range === 'CUSTOM' });
  const report = reportQ.data ?? null;
  const period = report?.period ?? null;
  const busy = reportQ.isFetching && report !== null;
  // While a new period loads, `period` is still the one on screen: its months and its ways to compare
  // belong to the old choice, so they are not shown beside the new one.
  const current = period !== null && period.range === range ? period : null;

  const rangeLabel = RANGE_OPTIONS.find((o) => o.value === range)?.label ?? 'Last 12 months';
  const compareOptions = current?.compareOptions ?? [];
  const compareLabel = current && current.compare ? compareOptionLabel(current.compare, current) : null;
  const monthsNewestFirst = useMemo(() => [...(monthsQ.data ?? [])].reverse(), [monthsQ.data]);
  const monthLabelToRaw = useMemo(() => new Map(monthsNewestFirst.map((m) => [monthLabelLong(m), m])), [monthsNewestFirst]);

  function chooseRange(label: string) {
    const next = RANGE_OPTIONS.find((o) => o.label === label)?.value;
    if (!next) return;
    if (next === 'CUSTOM' && (!startMonth || !endMonth) && report) {
      // A custom run starts from the months already on screen, so its pickers are never empty.
      setStartMonth(report.period.startMonth ?? '');
      setEndMonth(report.period.endMonth ?? '');
    }
    // A comparison choice belongs to one period shape; changing the range starts from its default.
    if (next !== range) setCompare(null);
    setRange(next);
    setExportError(null);
  }

  function open(filter: TransactionsFilter) {
    trackNavigation('transactions', 'contextual');
    navigation.getParent<BottomTabNavigationProp<AppTabParamList>>()?.navigate('Transactions', {
      filters: { ...filter, nonce: Date.now() },
    });
  }

  async function exportCsv() {
    if (!report || !report.period.hasData || exporting) return;
    setExportError(null);
    setExporting(true);
    try {
      await shareAdvancedCsv(report);
    } catch (e) {
      setExportError(toUserMessage(e, 'Could not export this report as CSV.'));
    } finally {
      setExporting(false);
    }
  }

  async function refresh() {
    setRefreshing(true);
    try {
      await reportQ.refetch();
    } finally {
      setRefreshing(false);
    }
  }

  const methods = report ? methodBreakdown(report.paymentMethods) : null;
  const partial = period ? incompleteNote(period) : null;
  const copy = period && !period.hasData ? noDataCopy(period) : null;

  return (
    <ScrollView
      style={styles.flex} contentContainerStyle={padForTabBar(styles.content, tabBar)} scrollIndicatorInsets={{ bottom: tabBar }}
      refreshControl={<AppRefreshControl refreshing={refreshing} onRefresh={() => void refresh()} tintColor={c.primary} />}
    >
      {/* The categorisation tab describes Fynora's learning, not a period of your money, so the period
          pickers are not offered there. */}
      {tab !== 'categorisation' ? (
        <Card style={styles.section}>
          <PickerField label="Period" value={rangeLabel} onPress={() => setPicker('range')} hint={current && range !== 'CUSTOM' ? periodSpan(current) : null} testID="period-field" />
          {range === 'CUSTOM' ? (
            <View style={styles.pair}>
              <View style={styles.half}><PickerField label="From" value={startMonth ? monthLabelLong(startMonth) : 'Choose'} onPress={() => setPicker('start')} testID="start-field" /></View>
              <View style={styles.half}><PickerField label="To" value={endMonth ? monthLabelLong(endMonth) : 'Choose'} onPress={() => setPicker('end')} testID="end-field" /></View>
            </View>
          ) : null}
          {compareOptions.length > 1 && compareLabel ? (
            <PickerField label="Compare with" value={compareLabel} onPress={() => setPicker('compare')} testID="compare-field" />
          ) : null}
          <Pressable
            onPress={() => void exportCsv()} disabled={!report || !report.period.hasData || exporting || busy}
            style={[styles.exportButton, { borderColor: c.border }, (!report || !report.period.hasData || exporting || busy) && styles.disabled]}
            accessibilityRole="button" accessibilityLabel="Export as CSV"
            accessibilityState={{ disabled: !report || !report.period.hasData || exporting || busy, busy: exporting }}
          >
            <Text style={[typography.labelM, { color: c.primary }]}>{exporting ? 'Preparing…' : 'Export CSV'}</Text>
          </Pressable>
          {exportError ? <Text style={[typography.bodyS, { color: c.dangerInk }]}>{exportError}</Text> : null}
        </Card>
      ) : null}

      <AdvancedTabs selected={tab} onSelect={setTab} />

      {tab === 'categorisation' ? (
        <CategorisationSection />
      ) : !customReady ? (
        <Card style={styles.section}><EmptyState message="Choose a start and an end month. A custom period needs both, with the start not after the end." /></Card>
      ) : reportQ.isLoading ? (
        <BodySkeleton />
      ) : reportQ.isError && !report ? (
        <LoadFailed message="Couldn't load Advanced Reports." tone="danger" onRetry={() => void reportQ.refetch()} />
      ) : report && copy ? (
        <Card style={styles.section}>
          <Text style={[typography.cardTitle, { color: c.ink }]}>{copy.title}</Text>
          <Text style={[typography.bodyS, { color: c.mutedInk }]}>{copy.desc}</Text>
        </Card>
      ) : report ? (
        <View style={[styles.stack, busy && styles.busy]} accessibilityState={{ busy }}>
          <PeriodNotice report={report} />
          {tab === 'overview' ? (
            <>
              <AdvancedKpis report={report} />
              <FlowChart months={report.months} incompleteNote={partial} />
              <InsightCards insights={report.insights} onOpen={open} />
              <CategoryList report={report} limit={5} onViewAll={() => setTab('spending')} onOpen={open} />
              <MerchantList report={report} limit={5} onViewAll={() => setTab('merchants')} onOpen={open} />
              <PaymentMethods breakdown={methods} />
            </>
          ) : tab === 'spending' ? (
            <>
              <CategoryList report={report} onOpen={open} />
              <CategoryTrendChart months={report.months} categories={report.categories.rows} />
              <PaymentMethods breakdown={methods} />
              <UnusualList items={report.unusualSpends} onOpen={open} />
              <InternationalSection data={report.international} />
            </>
          ) : tab === 'income' ? (
            <IncomeSection report={report} />
          ) : (
            <MerchantList report={report} onOpen={open} />
          )}
        </View>
      ) : null}

      <OptionPickerModal
        visible={picker === 'range'} title="Period" options={RANGE_OPTIONS.map((o) => o.label)} selected={rangeLabel}
        onSelect={(label) => { chooseRange(label); setPicker(null); }} onClose={() => setPicker(null)}
      />
      <OptionPickerModal
        visible={picker === 'compare'} title="Compare with" options={compareOptions.map((o) => compareOptionLabel(o, current!))} selected={compareLabel}
        onSelect={(label) => {
          const next = compareOptions.find((o) => compareOptionLabel(o, current!) === label);
          if (next) setCompare(next);
          setPicker(null);
        }}
        onClose={() => setPicker(null)}
      />
      <OptionPickerModal
        visible={picker === 'start' || picker === 'end'} title={picker === 'end' ? 'To' : 'From'}
        options={monthsNewestFirst.map(monthLabelLong)} selected={picker === 'end' ? (endMonth ? monthLabelLong(endMonth) : null) : (startMonth ? monthLabelLong(startMonth) : null)}
        onSelect={(label) => {
          const raw = monthLabelToRaw.get(label);
          if (raw) (picker === 'end' ? setEndMonth : setStartMonth)(raw);
          setPicker(null);
        }}
        onClose={() => setPicker(null)}
      />
    </ScrollView>
  );
}

function PickerField({ label, value, hint, onPress, testID }: { label: string; value: string; hint?: string | null; onPress: () => void; testID?: string }) {
  const c = useTheme();
  return (
    <View style={styles.field}>
      <Text style={[typography.eyebrow, { color: c.mutedInk }]}>{label}</Text>
      <Pressable
        onPress={onPress} accessibilityRole="button" accessibilityLabel={`${label}: ${value}. Change`} testID={testID}
        style={[styles.picker, { backgroundColor: c.inputBg, borderColor: c.border }]}
      >
        <Text style={[typography.bodyM, { color: c.ink }]}>{value}</Text>
        <Text style={[typography.bodyM, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">›</Text>
      </Pressable>
      {hint ? <Text style={[typography.caption, { color: c.mutedInk }]}>{hint}</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  titleRow: {
    flexDirection: 'row', alignItems: 'flex-start', gap: spacing.sm,
    paddingHorizontal: spacing.md, paddingTop: spacing.md, marginBottom: spacing.md,
  },
  titleTextCol: { flex: 1, gap: 4 },
  titleWithIcon: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  section: { marginBottom: spacing.md, gap: spacing.sm },
  stack: { gap: spacing.md },
  upgradePrompt: { marginHorizontal: spacing.md, marginBottom: spacing.md },
  upgradeHint: { fontSize: 12, fontWeight: '600', marginTop: spacing.sm, textAlign: 'center' },
  field: { gap: 4 },
  pair: { flexDirection: 'row', gap: spacing.sm },
  half: { flex: 1 },
  picker: {
    minHeight: 44, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    borderWidth: StyleSheet.hairlineWidth, borderRadius: 10, paddingHorizontal: spacing.sm,
  },
  exportButton: { minHeight: 44, borderWidth: StyleSheet.hairlineWidth, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  disabled: { opacity: 0.5 },
  // The figures on screen are the old period's while another loads: dimmed so they are not read as the new choice's.
  busy: { opacity: 0.5 },
});
