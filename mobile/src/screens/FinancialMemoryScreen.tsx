import { useEffect } from 'react';
import { ActivityIndicator, RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { Card, EmptyState, SectionHeading } from '../components/Card';
import { recurringApi, workspaceApi } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { fmtCurrency } from '../lib/format';
import { reportTransportFailure } from '../lib/monitoring';
import { isPausedCold } from '../lib/refreshingIndicator';
import { usePreventScreenCapture } from '../lib/screenCapture';
import { spacing, useTheme } from '../theme';

const DASH = '—';

function formatMonths(months: number | null): string {
  if (months === null) return DASH;
  if (months < 12) return `${months} month${months === 1 ? '' : 's'}`;
  const years = Math.floor(months / 12);
  const remainder = months % 12;
  if (remainder === 0) return `${years} year${years === 1 ? '' : 's'}`;
  return `${years}y ${remainder}m`;
}

/** Port of frontend/src/pages/FinancialMemory.tsx (issue #1450). Deliberately plain, like the web
 *  page: a factual report of how much history Fynora holds -- no badges, no progress-bar styling.
 *  completenessPercent is honestly allowed to read below 100%. The two queries are independent, so
 *  the recurring list failing never takes the metrics down with it, or the reverse. */
export function FinancialMemoryScreen() {
  // Names real merchants and recurring amounts -- same exposure the other financial screens guard.
  usePreventScreenCapture();
  const c = useTheme();
  const summaryQ = useQuery({
    queryKey: ['workspace-dashboard'], queryFn: () => workspaceApi.dashboard(), staleTime: 30_000,
  });
  const recurringQ = useQuery({
    queryKey: ['recurring'], queryFn: () => recurringApi.list(), staleTime: 30_000, retry: false,
  });

  // No startedAt -- see TransactionSourceModal's identical comment for why.
  useEffect(() => {
    if (summaryQ.isError) reportTransportFailure(summaryQ.error, 'financial-memory:summary');
  }, [summaryQ.isError, summaryQ.error]);

  if (summaryQ.isLoading) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <ActivityIndicator size="large" color={c.primary} />
      </View>
    );
  }

  const data = summaryQ.data;
  const tiles = data
    ? [
        { label: 'History', value: formatMonths(data.monthsOfHistory), caption: 'since your first statement' },
        {
          label: 'Completeness',
          value: data.completenessPercent != null ? `${data.completenessPercent}%` : DASH,
          caption: 'months covered, no gaps',
        },
        { label: 'Accounts connected', value: String(data.totalAccounts) },
        { label: 'Transactions processed', value: data.totalTransactions.toLocaleString('en-IN') },
        // identifiedMerchants, not totalMerchants: the total includes ~34 starter brands seeded at
        // signup, so it read 34 for an account that had imported nothing.
        // A dash, not "undefined", if this build is talking to a backend that predates the field.
        {
          label: 'Merchants identified',
          value: data.identifiedMerchants != null ? String(data.identifiedMerchants) : DASH,
          caption: `${data.learnedMerchants} learned`,
        },
        { label: 'Rules learned', value: String(data.activeRules) },
        {
          label: 'Manual corrections', value: String(data.totalManualCorrections),
          caption: "auto-categorized imports you've corrected",
        },
      ]
    : [];
  const recurring = recurringQ.data ?? [];
  // isError alone is not "nothing to show": React Query keeps the previous data when a background
  // refetch fails, so a flaky pull-to-refresh must not replace good figures with an error.
  const recurringFailed = (recurringQ.isError && recurringQ.data === undefined) || isPausedCold(recurringQ);
  const refreshFailed = (summaryQ.isError && data !== undefined) || (recurringQ.isError && recurringQ.data !== undefined);

  return (
    <ScrollView
      style={{ backgroundColor: c.bg }}
      contentContainerStyle={styles.content}
      refreshControl={
        <RefreshControl
          refreshing={(summaryQ.isFetching || recurringQ.isFetching) && !summaryQ.isLoading}
          onRefresh={() => { void summaryQ.refetch(); void recurringQ.refetch(); }}
          tintColor={c.primary}
        />
      }
    >
      <Text style={[styles.intro, { color: c.muted }]}>
        A factual record of how much of your financial history Fynora has built up so far.
      </Text>
      {refreshFailed ? (
        <Text style={[styles.intro, { color: c.muted }]}>Couldn't refresh. Showing what was last loaded.</Text>
      ) : null}

      {/* A failed fetch must not fall through to a grid of false zeros, which would read the same
          as a brand-new account with nothing imported. */}
      {!data ? (
        <Text style={[styles.intro, { color: c.muted }]}>
          {toUserMessage(summaryQ.error, "Couldn't load your financial memory. Try again later.")}
        </Text>
      ) : (
        <View style={styles.grid}>
          {tiles.map((t) => (
            <View
              key={t.label}
              accessible
              accessibilityLabel={`${t.label}: ${t.value}${t.caption ? `, ${t.caption}` : ''}`}
              style={[styles.tile, { borderColor: c.border, backgroundColor: c.card }]}
            >
              <Text style={[styles.value, { color: c.ink }]}>{t.value}</Text>
              <Text style={[styles.label, { color: c.muted }]}>{t.label}</Text>
              {t.caption ? <Text style={[styles.caption, { color: c.muted }]}>{t.caption}</Text> : null}
            </View>
          ))}
        </View>
      )}

      <Card>
        <SectionHeading title="Recognized recurring payments" />
        {recurringQ.isLoading ? (
          <ActivityIndicator color={c.primary} />
        ) : recurringFailed ? (
          // isPausedCold: offline with nothing loaded is not "no recurring payments".
          <Text style={[styles.intro, { color: c.muted }]}>
            Couldn't load your recurring payments. Try again later.
          </Text>
        ) : recurring.length === 0 ? (
          <EmptyState message="No recurring payments recognized yet. This needs at least 2 charges from the same merchant with a regular interval to spot a pattern." />
        ) : (
          recurring.map((r) => (
            <View key={r.merchant} style={[styles.row, { borderBottomColor: c.border }]}>
              <View style={styles.rowMain}>
                <Text style={[styles.merchant, { color: c.ink }]}>{r.merchant}</Text>
                <Text style={[styles.caption, { color: c.muted }]}>{r.label}</Text>
              </View>
              <Text style={[styles.amount, { color: c.muted }]}>{fmtCurrency(r.averageAmount)}</Text>
            </View>
          ))
        )}
      </Card>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  content: { padding: spacing.md, gap: spacing.md },
  intro: { fontSize: 13 },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  tile: { flexBasis: '47%', flexGrow: 1, borderWidth: 1, borderRadius: 12, padding: spacing.sm },
  value: { fontSize: 20, fontWeight: '700' },
  label: { fontSize: 12, marginTop: 2 },
  caption: { fontSize: 11, marginTop: 2 },
  row: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingVertical: spacing.sm, borderBottomWidth: StyleSheet.hairlineWidth, gap: spacing.sm,
  },
  rowMain: { flexShrink: 1 },
  merchant: { fontSize: 14, fontWeight: '500' },
  amount: { fontSize: 13 },
});
