import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { Card } from '../Card';
import { SkeletonGroup, SkeletonText } from '../skeletons/Skeletons';
import { categoriesApi, transactionsApi } from '../../api/endpoints';
import { fmtCurrency } from '../../lib/format';
import { moneyInPeriod, summarizeMoneyIn } from '../../lib/investmentSummary';
import { isPausedCold } from '../../lib/refreshingIndicator';
import { spacing, typography, useTheme } from '../../theme';
import type { Transaction } from '../../types';

export const INVESTMENT_ACTIVITY_QUERY_KEY = 'investment-activity';

const INVESTMENTS_CATEGORY = 'Investments';
// The backend clamps a page to 100 rows, so a year is read page by page. The cap keeps a
// pathological account from becoming an unbounded loop; hitting it is said on screen ("At least").
const PAGE_SIZE = 100;
const MAX_PAGES = 10;
// The month letters sit under narrow bars, so they stop growing before they collide.
const LETTER_MAX_FONT_SCALE = 1.2;

/** Everything filed under Investments in the period: outflows only, the SIPs and broker transfers. */
async function loadPayments(dateFrom: string, dateTo: string) {
  const categories = await categoriesApi.list();
  const category = categories.find((c) => c.name.trim().toLowerCase() === INVESTMENTS_CATEGORY.toLowerCase());
  if (!category) return { categoryId: null, rows: [] as Transaction[], truncated: false };
  const rows: Transaction[] = [];
  let truncated = false;
  for (let page = 0; page < MAX_PAGES; page++) {
    const result = await transactionsApi.search({
      categoryId: category.id, type: 'EXPENSE', dateFrom, dateTo, page, size: PAGE_SIZE, sortField: 'date', sortDir: 'desc',
    });
    rows.push(...result.content);
    if (page + 1 >= result.totalPages) break;
    if (page + 1 >= MAX_PAGES) truncated = true;
  }
  return { categoryId: category.id, rows, truncated };
}

/**
 * Money put into investments over 12 months, found in imported statements. Deliberately separate
 * from the holdings: a holding is a value someone typed, these are bank transactions, and the two
 * are never matched, so this says nothing about what the holdings are worth.
 */
export function MoneyGoingInCard({ today, onSeePayments }: {
  today: Date; onSeePayments: (categoryId: string, dateFrom: string, dateTo: string) => void;
}) {
  const c = useTheme();
  const { dateFrom, dateTo } = moneyInPeriod(today);
  const q = useQuery({
    queryKey: [INVESTMENT_ACTIVITY_QUERY_KEY, dateFrom, dateTo],
    queryFn: () => loadPayments(dateFrom, dateTo),
  });
  const summary = q.data ? summarizeMoneyIn(q.data.rows, today) : null;
  const total = summary ? `${q.data?.truncated ? 'At least ' : ''}${fmtCurrency(summary.total)}` : '';
  const average = summary ? fmtCurrency(summary.monthlyAverage) : '';
  const categoryId = q.data?.categoryId;

  return (
    <Card style={styles.card}>
      <View style={styles.heading}>
        <Text accessibilityRole="header" style={[typography.cardTitle, { color: c.ink }]}>Money going in</Text>
        <Text style={[typography.bodyS, { color: c.mutedInk }]}>Last 12 months</Text>
      </View>
      {q.isLoading && !isPausedCold(q) ? (
        <SkeletonGroup>
          <SkeletonText size="value" width="50%" />
          <SkeletonText size="meta" width="80%" />
        </SkeletonGroup>
      ) : !summary ? (
        // Not the empty state: "nothing found" would be a false claim to someone whose fetch failed.
        <Text style={[typography.bodyS, { color: c.dangerInk }]}>Could not load your investment payments.</Text>
      ) : summary.count === 0 ? (
        <Text style={[typography.bodyS, { color: c.mutedInk }]}>No SIPs or broker payments found in your statements yet.</Text>
      ) : (
        <>
          <Text style={[typography.numberM, { color: c.ink }]} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.5}>
            {total}
          </Text>
          <Text style={[typography.bodyS, { color: c.mutedInk }]}>
            {`About ${average} a month · ${summary.count} ${summary.count === 1 ? 'payment' : 'payments'}`}
          </Text>
          <View accessible accessibilityRole="image" accessibilityLabel={`Last 12 months: ${total}, about ${average} a month`} style={styles.bars}>
            {summary.months.map((m) => (
              <View key={m.key} style={styles.month}>
                <View style={styles.barSlot}>
                  <View style={[styles.bar, { height: `${m.heightFrac * 100}%`, backgroundColor: c.success }]} />
                </View>
                <Text maxFontSizeMultiplier={LETTER_MAX_FONT_SCALE} style={[typography.caption, { color: c.mutedInk }]}>{m.letter}</Text>
              </View>
            ))}
          </View>
          {categoryId ? (
            <Pressable
              onPress={() => onSeePayments(categoryId, dateFrom, dateTo)}
              style={styles.link}
              accessibilityRole="button"
              accessibilityLabel="See payments"
            >
              <Text style={[typography.labelS, { color: c.primary }]}>See payments</Text>
            </Pressable>
          ) : null}
        </>
      )}
    </Card>
  );
}

const styles = StyleSheet.create({
  card: { gap: spacing.xs },
  heading: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: spacing.sm },
  bars: { flexDirection: 'row', gap: 4, marginTop: spacing.sm },
  month: { flex: 1, alignItems: 'center', gap: 4 },
  barSlot: { height: 64, alignSelf: 'stretch', justifyContent: 'flex-end' },
  bar: { borderTopLeftRadius: 3, borderTopRightRadius: 3 },
  link: { minHeight: 44, justifyContent: 'center', alignSelf: 'flex-start' },
});
