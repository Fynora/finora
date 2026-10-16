import { useMemo, useState } from 'react';
import {
  Pressable, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import { AppRefreshControl } from '../components/AppRefreshControl';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigation } from '@react-navigation/native';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { usePreventScreenCapture } from '../lib/screenCapture';
import { Card, EmptyState, SectionHeading } from '../components/Card';
import { SkeletonCard, SkeletonGroup } from '../components/skeletons/Skeletons';
import { OptionPickerModal } from '../components/OptionPickerModal';
import { BiggestSpends } from '../components/reportSections/BiggestSpends';
import { CategoryBreakdown } from '../components/reportSections/CategoryBreakdown';
import { MerchantsCard } from '../components/reportSections/MerchantsCard';
import { Observations } from '../components/reportSections/Observations';
import { ReportHero } from '../components/reportSections/ReportHero';
import { ReportStatCards } from '../components/reportSections/ReportStatCards';
import { ReportTabs, type ReportTab } from '../components/reportSections/ReportTabs';
import { ReviewNotice } from '../components/reportSections/ReviewNotice';
import { SpendingCalendar } from '../components/reportSections/SpendingCalendar';
import { TrendPanel } from '../components/reportSections/TrendPanel';
import { reportsApi, type BiggestSpend } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import {
  fmtCurrency, monthDateRange, monthLabel, monthLabelLong,
} from '../lib/format';
import { buildObservations } from '../lib/reportSections/observationText';
import { shortDate } from '../lib/reportSections/trend';
import { shareCsv, sharePdf } from '../lib/reportExport';
import { useReportExtras } from '../lib/useReportExtras';
import { radius, spacing, useTheme } from '../theme';
import type { AppTabParamList, MoreStackParamList } from '../navigation/types';
import { withBypass } from '../lib/changeSync';
import { trackNavigation } from '../lib/trackNavigation';
import { GlassScreen } from '../components/GlassScreen';
import { padForTabBar, useTabBarSpace } from '../navigation/useTabBarSpace';

type Exporting = 'csv' | 'pdf' | null;

/** The label the server gives a purchase with no merchant and no description. */
const NO_LABEL = 'Transaction';

/** The hero, stat cards and a section: the skeleton shape shared by the months-still-loading shell
 *  and the report-still-loading (uncached month) branch below -- one definition means the two
 *  loading states can't silently drift apart from each other. */
function ReportBodySkeleton() {
  return (
    <SkeletonGroup>
      <SkeletonCard style={styles.section} lines={3} />
      <View style={styles.totals}>
        <SkeletonCard style={styles.totalCard} lines={1} />
        <SkeletonCard style={styles.totalCard} lines={1} />
      </View>
      <SkeletonCard style={styles.section} lines={4} />
    </SkeletonGroup>
  );
}

/** YYYY-MM-DD of a YYYY-MM month's last day. */
function lastDayOf(month: string): string {
  const [y, m] = month.split('-').map(Number);
  return `${month}-${String(new Date(y, m, 0).getDate()).padStart(2, '0')}`;
}

/**
 * Port of frontend/src/pages/Reports.tsx, as three tabs so each screen-full answers one question:
 * Overview (how the month went), Spending (where the money went) and Income (what came in).
 *
 * The web page hand-rolls its month fetch with useState/useEffect and needs useAsyncGuard so a
 * slower response for a month the user already navigated away from can't overwrite the one on
 * screen. Here the report is a TanStack query keyed by month, which cannot have that bug at all --
 * an old month's response resolves into its OWN cache entry, never the current one. Same guarantee,
 * and it shares `['report', month]` with the Dashboard's cash-flow chart, so a month that screen
 * already loaded opens instantly here instead of being fetched a second time. The extras (insights,
 * trend, categories) are keyed the same way, and each leaves its section out if it fails.
 */
export function ReportsScreen() {
  // D3 (Track D security cleanup). Category breakdowns and totals are financial figures like any
  // other -- same screenshot/screen-recording exposure Dashboard/Accounts/Statement History
  // already guard against.
  usePreventScreenCapture();
  const c = useTheme();
  const tabBar = useTabBarSpace();
  const queryClient = useQueryClient();
  // Lives inside the More stack, not on the tab bar itself -- see BudgetsScreen's identical
  // comment (Track C/C4).
  const navigation = useNavigation();
  // Null means "whatever the latest month is", not "none" -- see `month` below. Storing the
  // user's explicit pick rather than a resolved value is what lets the default keep tracking the
  // newest month as data arrives, without ever overriding a choice they made.
  const [pickedMonth, setPickedMonth] = useState<string | null>(null);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [tab, setTab] = useState<ReportTab>('overview');
  const [exporting, setExporting] = useState<Exporting>(null);
  const [exportError, setExportError] = useState<string | null>(null);

  const {
    data: months = [], isLoading: monthsLoading, isError: monthsError,
  } = useQuery({ queryKey: ['report-months'], queryFn: () => reportsApi.availableMonths() });

  // Derived, not stored: the server returns months ascending, so the last is the most recent.
  // Writing this default into state from an effect would render once with no month and then again
  // with one, and leave two sources of truth for which month is showing.
  const month = pickedMonth ?? (months.length > 0 ? months[months.length - 1] : null);
  const monthsNewestFirst = useMemo(() => [...months].reverse(), [months]);
  const monthIndex = month ? months.indexOf(month) : -1;
  const olderMonth = monthIndex > 0 ? months[monthIndex - 1] : null;
  const newerMonth = monthIndex >= 0 && monthIndex < months.length - 1 ? months[monthIndex + 1] : null;

  // OptionPickerModal renders each option's own string as both the row label and the value it
  // hands back to onSelect -- unlike a native <select>, it has no separate value/label slots. So
  // the picker's options list is the formatted "August 2026" labels users read, and this map is
  // how a tap on one resolves back to the raw "2026-08" the rest of the screen's queries key on.
  const monthLabelToRaw = useMemo(
    () => new Map(monthsNewestFirst.map((m) => [monthLabelLong(m), m])),
    [monthsNewestFirst]
  );
  const monthOptionLabels = useMemo(
    () => monthsNewestFirst.map((m) => monthLabelLong(m)),
    [monthsNewestFirst]
  );

  const {
    data: report, isLoading: reportLoading, isError: reportError, isFetching,
  } = useQuery({
    queryKey: ['report', month],
    queryFn: () => reportsApi.forMonth(month as string),
    enabled: month !== null,
    staleTime: 5 * 60_000, // a past month's totals don't change once the month is over
  });

  const extras = useReportExtras(month);
  const observations = useMemo(
    () => (report ? buildObservations(extras.insights, monthLabelLong(report.month).split(' ')[0], fmtCurrency) : []),
    [report, extras.insights]
  );

  function pickMonth(next: string) {
    setPickedMonth(next);
    // Cleared with the month: an "export failed" line left over from July, sitting under May's
    // figures, reads as though May failed to export too.
    setExportError(null);
  }

  // withBypass: a pull is a read, not an edit -- it must not wait for the change stamp (lib/changeSync.ts).
  // The extras are keyed by month under different second elements, so ['report', month] does not
  // reach them: without these a pull would refresh the totals and leave the trend and the
  // observations on the old figures.
  function refreshReport() {
    withBypass(() => {
      void queryClient.invalidateQueries({ queryKey: ['report', month] });
      void queryClient.invalidateQueries({ queryKey: ['report', 'trend', month] });
      void queryClient.invalidateQueries({ queryKey: ['insights', 'reports', month] });
    });
  }

  async function runExport(kind: 'csv' | 'pdf') {
    if (!report || exporting) return;
    setExportError(null);
    setExporting(kind);
    try {
      await (kind === 'csv' ? shareCsv(report) : sharePdf(report));
    } catch (e) {
      setExportError(toUserMessage(e, `Could not export this report as ${kind.toUpperCase()}.`));
    } finally {
      setExporting(null);
    }
  }

  /** One drill-through into the Transactions tab, narrowed however the caller needs. */
  function openTransactions(filters: { categoryName?: string; keyword?: string; dateFrom: string; dateTo: string; label: string }) {
    trackNavigation('transactions', 'contextual');
    navigation.getParent<BottomTabNavigationProp<AppTabParamList>>()?.navigate('Transactions', {
      filters: { ...filters, nonce: Date.now() },
    });
  }

  const moreNav = () => navigation as unknown as NativeStackNavigationProp<MoreStackParamList>;

  if (monthsLoading) {
    return (
      <GlassScreen style={styles.glassRoot}>
      <ScrollView contentContainerStyle={padForTabBar(styles.content, tabBar)} scrollIndicatorInsets={{ bottom: tabBar }}>
        <SkeletonGroup><SkeletonCard lines={2} /></SkeletonGroup>
        <ReportBodySkeleton />
      </ScrollView>
      </GlassScreen>
    );
  }

  if (monthsError) {
    return (
      <GlassScreen style={styles.centered}>
        <Text style={[styles.message, { color: c.muted }]}>
          Couldn&apos;t load reports — please try again later.
        </Text>
      </GlassScreen>
    );
  }

  if (months.length === 0) {
    return (
      <GlassScreen style={styles.centered}>
        <Text style={[styles.message, { color: c.muted }]}>
          No transactions yet — import a statement to see reports.
        </Text>
      </GlassScreen>
    );
  }

  const hasCalendar = !!report?.dailySpend && report.dailySpend.length > 0;

  return (
    <GlassScreen style={styles.glassRoot}>
    <ScrollView
      contentContainerStyle={padForTabBar(styles.content, tabBar)} scrollIndicatorInsets={{ bottom: tabBar }}
      refreshControl={
        <AppRefreshControl
          refreshing={isFetching && !reportLoading}
          onRefresh={refreshReport}
          tintColor={c.primary}
        />
      }
    >
      <Card>
        <Text style={[styles.fieldLabel, { color: c.muted }]}>Month</Text>
        <View style={styles.monthRow}>
          <Pressable
            onPress={() => olderMonth && pickMonth(olderMonth)} disabled={olderMonth === null} hitSlop={6}
            accessibilityRole="button" accessibilityLabel="Previous month" accessibilityState={{ disabled: olderMonth === null }}
            style={[styles.arrow, { borderColor: c.border }, olderMonth === null && styles.disabled]}
          >
            <Text style={[styles.chevron, { color: c.ink }]} accessibilityElementsHidden importantForAccessibility="no">‹</Text>
          </Pressable>
          <Pressable
            onPress={() => setPickerOpen(true)}
            style={[styles.picker, { backgroundColor: c.inputBg, borderColor: c.border }]}
            accessibilityRole="button"
            accessibilityLabel={`Month: ${month ? monthLabel(month) : 'none'}. Change`}
          >
            <Text style={[styles.pickerText, { color: c.ink }]}>{month ? monthLabelLong(month) : ''}</Text>
            <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">
              ›
            </Text>
          </Pressable>
          <Pressable
            onPress={() => newerMonth && pickMonth(newerMonth)} disabled={newerMonth === null} hitSlop={6}
            accessibilityRole="button" accessibilityLabel="Next month" accessibilityState={{ disabled: newerMonth === null }}
            style={[styles.arrow, { borderColor: c.border }, newerMonth === null && styles.disabled]}
          >
            <Text style={[styles.chevron, { color: c.ink }]} accessibilityElementsHidden importantForAccessibility="no">›</Text>
          </Pressable>
        </View>

        <View style={styles.exportRow}>
          {(['csv', 'pdf'] as const).map((kind) => (
            <Pressable
              key={kind}
              onPress={() => void runExport(kind)}
              disabled={!report || exporting !== null}
              style={[
                styles.exportButton,
                { borderColor: c.border },
                (!report || exporting !== null) && styles.disabled,
              ]}
              accessibilityRole="button"
              accessibilityState={{ disabled: !report || exporting !== null, busy: exporting === kind }}
              accessibilityLabel={kind === 'csv' ? 'Export as CSV' : 'Export as PDF'}
            >
              <Text style={[styles.exportText, { color: c.primary }]}>
                {exporting === kind ? 'Preparing…' : kind === 'csv' ? 'Export CSV' : 'Export PDF'}
              </Text>
            </Pressable>
          ))}
        </View>
        {exportError ? <Text style={[styles.error, { color: c.dangerInk }]}>{exportError}</Text> : null}
      </Card>

      <View style={styles.section}>
        <ReportTabs selected={tab} onSelect={setTab} />
      </View>

      {reportLoading ? (
        <ReportBodySkeleton />
      ) : reportError || !report ? (
        <Card style={styles.section}>
          <Text style={[styles.error, { color: c.dangerInk }]}>
            Couldn&apos;t load {monthLabelLong(month as string)}&apos;s report — pull down to try again.
          </Text>
        </Card>
      ) : tab === 'overview' ? (
        <View style={styles.stack}>
          <ReportHero report={report} />
          <ReviewNotice
            report={report}
            onReview={() => {
              trackNavigation('money-review', 'contextual');
              moreNav().navigate('MoneyReview', { start: `${report.month}-01`, end: lastDayOf(report.month) });
            }}
          />
          <ReportStatCards report={report} />
          <TrendPanel points={extras.trend} selectedMonth={report.month} />
        </View>
      ) : tab === 'spending' ? (
        <View style={styles.stack}>
          <CategoryBreakdown
            report={report}
            categories={extras.categories}
            movers={extras.insights?.movers ?? null}
            onOpenCategory={(category) => {
              const { dateFrom, dateTo } = monthDateRange(report.month);
              openTransactions({ categoryName: category, dateFrom, dateTo, label: `${category} · ${monthLabel(report.month)}` });
            }}
          />
          <Observations items={observations} />
          {hasCalendar ? (
            <SpendingCalendar
              month={report.month} days={report.dailySpend} complete={report.complete !== false}
              onOpenDay={(date) => openTransactions({ dateFrom: date, dateTo: date, label: shortDate(date) })}
            />
          ) : null}
          <BiggestSpends
            items={report.biggestSpends}
            onOpen={(s: BiggestSpend) => openTransactions({
              // "Transaction" is what the server writes when a row has neither a merchant nor a
              // description (ReportService.spendLabel): no row contains that word, so searching
              // for it would open an empty list. The day alone is the honest narrowing then.
              keyword: s.label === NO_LABEL ? undefined : s.label,
              dateFrom: s.date, dateTo: s.date, label: `${s.label} · ${shortDate(s.date)}`,
            })}
          />
          <MerchantsCard
            month={report.month}
            onOpenAdvanced={() => { trackNavigation('advanced-reports', 'contextual'); moreNav().navigate('AdvancedReports'); }}
            onViewPlans={() => { trackNavigation('subscription', 'contextual'); moreNav().navigate('Subscription'); }}
          />
        </View>
      ) : (
        <View style={styles.stack}>
          {report.incomeByKind && report.incomeByKind.length > 1 ? (
            <Card>
              <SectionHeading title="Income by kind" />
              {report.incomeByKind.map((line) => (
                <View key={line.label} style={styles.incomeLine}>
                  <Text style={[styles.message, { color: c.ink }]}>{line.label}</Text>
                  <Text style={[styles.message, { color: c.ink }]}>{fmtCurrency(line.amount)}</Text>
                </View>
              ))}
            </Card>
          ) : report.income > 0 ? (
            <Card>
              <SectionHeading title="Income" />
              <View style={styles.incomeLine}>
                <Text style={[styles.message, { color: c.ink }]}>{report.incomeByKind?.[0]?.label ?? 'Total income'}</Text>
                <Text style={[styles.message, { color: c.ink }]}>{fmtCurrency(report.income)}</Text>
              </View>
            </Card>
          ) : (
            <Card><EmptyState message={`No income was counted in ${monthLabelLong(report.month)}.`} /></Card>
          )}
          <ReviewNotice
            report={report}
            onReview={() => {
              trackNavigation('money-review', 'contextual');
              moreNav().navigate('MoneyReview', { start: `${report.month}-01`, end: lastDayOf(report.month) });
            }}
          />
        </View>
      )}

      <OptionPickerModal
        visible={pickerOpen}
        title="Month"
        // Reversed: the server sends these oldest-first, and the month someone opens this picker
        // for is nearly always a recent one. Newest at the top makes that a zero-scroll choice.
        options={monthOptionLabels}
        selected={month ? monthLabelLong(month) : null}
        onSelect={(label) => {
          const m = monthLabelToRaw.get(label);
          if (!m) return;
          pickMonth(m);
          setPickerOpen(false);
        }}
        onClose={() => setPickerOpen(false)}
      />
    </ScrollView>
    </GlassScreen>
  );
}

const styles = StyleSheet.create({
  glassRoot: { flex: 1 },
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: spacing.lg },
  message: { fontSize: 14, textAlign: 'center' },
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  fieldLabel: { fontSize: 12, fontWeight: '500', marginBottom: 6 },
  monthRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  arrow: { width: 44, height: 48, borderWidth: 1, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  picker: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: 12,
    minHeight: 48,
  },
  pickerText: { fontSize: 15 },
  chevron: { fontSize: 20, lineHeight: 20 },
  exportRow: { flexDirection: 'row', gap: spacing.sm, marginTop: spacing.sm },
  exportButton: {
    flex: 1,
    borderWidth: 1,
    borderRadius: radius.md,
    minHeight: 44,
    alignItems: 'center',
    justifyContent: 'center',
  },
  exportText: { fontSize: 13, fontWeight: '600' },
  disabled: { opacity: 0.5 },
  error: { fontSize: 13, marginTop: spacing.sm },
  totals: { flexDirection: 'row', gap: spacing.sm, marginTop: spacing.md },
  totalCard: { flex: 1, paddingHorizontal: spacing.sm },
  section: { marginTop: spacing.md },
  stack: { gap: spacing.md },
  incomeLine: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: spacing.xs },
});
