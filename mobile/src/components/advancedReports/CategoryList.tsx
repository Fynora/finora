import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import type { AdvancedCategory, AdvancedReport } from '../../api/endpoints';
import { monthBounds, type TransactionsFilter } from '../../lib/advancedReports/advancedFormat';
import { fmtCurrency } from '../../lib/format';
import { spacing, typography, useTheme } from '../../theme';
import { DashboardCard } from '../dashboard/DashboardCard';
import { SectionHeading } from '../Card';
import { ChangeText, RankedRow, shareText, spokenChange } from './parts';

/**
 * Where the period's spending went, by category. How much (the amount and the bar), how big a part
 * of the spending (the share) and how it moved (the change against the earlier period) are shown
 * together and never merged. Sorting by change finds fast-growing categories that the amount ranking
 * would bury. Tapping a row opens the Transactions tab for that category over the whole period.
 */
export function CategoryList({
  report, limit, onViewAll, onOpen,
}: { report: AdvancedReport; limit?: number; onViewAll?: () => void; onOpen: (filter: TransactionsFilter) => void }) {
  const c = useTheme();
  const { categories, comparison, period } = report;
  const [sort, setSort] = useState<'amount' | 'change'>('amount');
  const canSortByChange = comparison.reliable;
  const rows: AdvancedCategory[] = sort === 'change' && canSortByChange
    ? [...categories.rows].sort((a, b) => Math.abs(b.changeAmount ?? 0) - Math.abs(a.changeAmount ?? 0))
    : categories.rows;
  const shown = limit ? rows.slice(0, limit) : rows;
  const largest = Math.max(...categories.rows.map((r) => r.amount), 1);
  const span = period.startMonth && period.endMonth
    ? { dateFrom: monthBounds(period.startMonth).from, dateTo: monthBounds(period.endMonth).to }
    : null;

  return (
    <DashboardCard testID="category-list">
      <SectionHeading title="Top spending categories" />
      {canSortByChange && !limit ? (
        <View style={styles.sort}>
          {(['amount', 'change'] as const).map((s) => (
            <Pressable
              key={s} onPress={() => setSort(s)} accessibilityRole="button" accessibilityState={{ selected: sort === s }}
              accessibilityLabel={s === 'amount' ? 'Sort by amount' : 'Sort by change'}
              style={[styles.chip, { borderColor: c.border }, sort === s && { backgroundColor: c.primary }]}
            >
              <Text style={[typography.labelS, { color: sort === s ? c.onPrimary : c.ink }]}>{s === 'amount' ? 'By amount' : 'By change'}</Text>
            </Pressable>
          ))}
        </View>
      ) : null}
      {shown.length === 0 ? (
        <Text style={[typography.bodyS, { color: c.mutedInk }]}>No spending in this period.</Text>
      ) : shown.map((r) => {
        const change = spokenChange(r.changeAmount, r.changePct, r.previousAmount);
        return (
          <RankedRow
            key={r.name} testID={`category-${r.name}`} label={r.name} amount={r.amount} share={r.sharePct} bar={largest}
            detail={`${r.transactionCount} ${r.transactionCount === 1 ? 'payment' : 'payments'}`}
            change={<ChangeText previousAmount={r.previousAmount} changeAmount={r.changeAmount} changePct={r.changePct} />}
            accessibilityLabel={`${r.name}: ${fmtCurrency(r.amount)}, ${shareText(r.sharePct)} of spending${change ? `, ${change}` : ''}`}
            onPress={span ? () => onOpen({ ...span, label: r.name, ...(r.name !== 'Uncategorized' ? { categoryName: r.name } : {}) }) : undefined}
          />
        );
      })}
      {!limit && categories.otherAmount > 0 ? (
        <View style={[styles.other, { borderTopColor: c.border }]} testID="category-other" accessible
          accessibilityLabel={`Other, ${categories.categoryCount - categories.rows.length} more categories: ${fmtCurrency(categories.otherAmount)}`}>
          <Text style={[typography.bodyS, { color: c.mutedInk }]}>Other ({categories.categoryCount - categories.rows.length} more)</Text>
          <Text style={[typography.bodyS, { color: c.mutedInk }]}>{fmtCurrency(categories.otherAmount)} {shareText(categories.otherSharePct)}</Text>
        </View>
      ) : null}
      {limit && onViewAll && categories.rows.length > limit ? (
        <Pressable onPress={onViewAll} accessibilityRole="button" accessibilityLabel="View all categories" style={styles.viewAll}>
          <Text style={[typography.labelS, { color: c.primary }]}>View all categories</Text>
        </Pressable>
      ) : null}
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  sort: { flexDirection: 'row', gap: spacing.sm, marginBottom: spacing.sm },
  chip: { minHeight: 32, paddingHorizontal: spacing.sm, borderRadius: 16, borderWidth: StyleSheet.hairlineWidth, alignItems: 'center', justifyContent: 'center' },
  other: { flexDirection: 'row', justifyContent: 'space-between', borderTopWidth: StyleSheet.hairlineWidth, paddingTop: spacing.sm },
  viewAll: { minHeight: 44, justifyContent: 'center' },
});
