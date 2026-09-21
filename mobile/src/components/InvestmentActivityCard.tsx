import { useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { Card, EmptyState, SectionHeading } from './Card';
import { categoriesApi, transactionsApi } from '../api/endpoints';
import { fmtCurrency, fmtDate, toLocalDateString } from '../lib/format';
import { isPausedCold } from '../lib/refreshingIndicator';
import { spacing, useTheme } from '../theme';
import type { Transaction } from '../types';

export const INVESTMENT_ACTIVITY_QUERY_KEY = 'investment-activity';

const INVESTMENTS_CATEGORY = 'Investments';
// The backend clamps a page to 100 rows, so a longer period is fetched page by page. The cap keeps
// a pathological account from turning this card into an unbounded loop; hitting it is reported on
// screen ("At least"), not hidden.
const PAGE_SIZE = 100;
const MAX_PAGES = 10;
// How many of the newest rows are listed. The total above them covers the whole period.
const LISTED_ROWS = 8;
// A row the reconciliation engine judged to be a second copy of another, or that a re-uploaded
// statement replaced, is not a second investment. Everything else stays counted: INVESTMENT_TRANSFER
// is the normal status for these rows, and TRANSFER means the user tracks the receiving holding too,
// which is still money invested.
const NOT_REAL_ACTIVITY = new Set(['DUPLICATE', 'SUPERSEDED']);

const PERIODS = [
  { months: 3, chip: '3M', label: 'last 3 months' },
  { months: 6, chip: '6M', label: 'last 6 months' },
  { months: 12, chip: '12M', label: 'last 12 months' },
] as const;

interface Activity {
  rows: Transaction[];
  total: number;
  count: number;
  truncated: boolean;
}

function periodStart(months: number): string {
  const d = new Date();
  d.setMonth(d.getMonth() - months);
  return toLocalDateString(d);
}

async function loadActivity(months: number): Promise<Activity> {
  const categories = await categoriesApi.list();
  // Every user is seeded with the system "Investments" category; one that somehow lacks it has, by
  // definition, nothing filed under it.
  const category = categories.find((c) => c.name.trim().toLowerCase() === INVESTMENTS_CATEGORY.toLowerCase());
  if (!category) return { rows: [], total: 0, count: 0, truncated: false };

  const collected: Transaction[] = [];
  let truncated = false;
  for (let page = 0; page < MAX_PAGES; page++) {
    const result = await transactionsApi.search({
      categoryId: category.id,
      // Outflows only: these are the SIPs and broker transfers. A redemption or dividend is money
      // coming back, not money invested, and would only make the total mean something else.
      type: 'EXPENSE',
      dateFrom: periodStart(months),
      dateTo: toLocalDateString(new Date()),
      page,
      size: PAGE_SIZE,
      sortField: 'date',
      sortDir: 'desc',
    });
    collected.push(...result.content);
    if (page + 1 >= result.totalPages) break;
    if (page + 1 >= MAX_PAGES) truncated = true;
  }

  const rows = collected.filter((t) => !NOT_REAL_ACTIVITY.has(t.reconciliationStatus));
  return { rows, total: rows.reduce((sum, t) => sum + t.amount, 0), count: rows.length, truncated };
}

/**
 * The SIPs and broker transfers found in the user's statements: everything filed under the
 * Investments category, newest first, with what was invested over the chosen period. Port of
 * frontend/src/components/InvestmentActivity.tsx.
 *
 * Separate from the Holdings card on purpose: a holding is a balance the user types in, these are
 * transactions that arrived by importing a statement, and the two are never reconciled against each
 * other -- so this card makes no claim about what the holdings are worth.
 */
export function InvestmentActivityCard() {
  const c = useTheme();
  const [months, setMonths] = useState<number>(12);
  const q = useQuery({
    queryKey: [INVESTMENT_ACTIVITY_QUERY_KEY, months],
    queryFn: () => loadActivity(months),
  });
  const periodLabel = PERIODS.find((p) => p.months === months)?.label ?? '';
  const activity = q.data;

  return (
    <Card style={styles.card}>
      <SectionHeading
        title="SIPs & broker transfers"
        action={
          <View style={styles.chips} accessibilityRole="radiogroup">
            {PERIODS.map((p) => {
              const selected = p.months === months;
              return (
                <Pressable
                  key={p.months}
                  onPress={() => setMonths(p.months)}
                  hitSlop={6}
                  style={[
                    styles.chip,
                    { borderColor: selected ? c.primary : c.border },
                  ]}
                  accessibilityRole="radio"
                  accessibilityState={{ selected }}
                  accessibilityLabel={`Show the ${p.label}`}
                >
                  <Text style={[styles.chipText, { color: selected ? c.primary : c.muted }]}>{p.chip}</Text>
                </Pressable>
              );
            })}
          </View>
        }
      />
      <Text style={[styles.caption, { color: c.muted }]}>
        Payments to brokers and mutual funds found in the statements you import, filed under Investments.
      </Text>

      {q.isLoading && !isPausedCold(q) ? (
        <ActivityIndicator color={c.primary} />
      ) : q.isError || isPausedCold(q) || !activity ? (
        // Not the empty state: "no SIPs yet" would be a false claim to someone whose fetch failed.
        <Text style={[styles.inlineError, { color: c.danger }]}>Could not load your investment activity.</Text>
      ) : activity.count === 0 ? (
        <EmptyState message={`No SIPs or broker transfers yet. Nothing is filed under Investments in the ${periodLabel}.`} />
      ) : (
        <>
          <Text style={[styles.totalLabel, { color: c.muted }]}>Invested in the {periodLabel}</Text>
          <Text style={[styles.totalValue, { color: c.ink }]} testID="invested-total">
            {activity.truncated ? 'At least ' : ''}{fmtCurrency(activity.total)}
          </Text>
          <Text style={[styles.totalCount, { color: c.muted }]}>
            {activity.count} {activity.count === 1 ? 'payment' : 'payments'}
            {activity.truncated ? ` — counting the most recent ${MAX_PAGES * PAGE_SIZE} only` : ''}
          </Text>
          {activity.rows.slice(0, LISTED_ROWS).map((t) => (
            <View key={t.id} style={[styles.row, { borderBottomColor: c.border }]}>
              <View style={styles.rowMain}>
                <Text style={[styles.rowName, { color: c.ink }]} numberOfLines={2}>{t.description}</Text>
                <Text style={[styles.rowDate, { color: c.muted }]}>{fmtDate(t.date)}</Text>
              </View>
              <Text style={[styles.rowAmount, { color: c.ink }]}>{fmtCurrency(t.amount)}</Text>
            </View>
          ))}
          {activity.count > LISTED_ROWS ? (
            <Text style={[styles.more, { color: c.muted }]}>
              Showing the latest {LISTED_ROWS} of {activity.count}. The full list is in your Ledger.
            </Text>
          ) : null}
        </>
      )}
    </Card>
  );
}

const styles = StyleSheet.create({
  card: { marginTop: spacing.md },
  chips: { flexDirection: 'row', gap: spacing.xs },
  chip: {
    borderWidth: 1,
    borderRadius: 999,
    paddingHorizontal: 10,
    minHeight: 32,
    justifyContent: 'center',
  },
  chipText: { fontSize: 11, fontWeight: '600' },
  caption: { fontSize: 12, lineHeight: 17, marginBottom: spacing.sm },
  inlineError: { fontSize: 13, paddingVertical: spacing.sm },
  totalLabel: { fontSize: 10, textTransform: 'uppercase', letterSpacing: 0.5 },
  totalValue: { fontSize: 20, fontWeight: '700', marginTop: 2 },
  totalCount: { fontSize: 11, marginTop: 2, marginBottom: spacing.sm },
  row: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  rowMain: { flex: 1, marginRight: spacing.sm },
  rowName: { fontSize: 13, fontWeight: '600' },
  rowDate: { fontSize: 11, marginTop: 2 },
  rowAmount: { fontSize: 13, fontWeight: '700' },
  more: { fontSize: 11, marginTop: spacing.sm },
});
