import { Pressable, StyleSheet, Text } from 'react-native';
import type { AdvancedReport } from '../../api/endpoints';
import { monthBounds, type TransactionsFilter } from '../../lib/advancedReports/advancedFormat';
import { fmtCurrency } from '../../lib/format';
import { typography, useTheme } from '../../theme';
import { DashboardCard } from '../dashboard/DashboardCard';
import { MerchantLogo } from '../MerchantLogo';
import { SectionHeading } from '../Card';
import { ChangeText, RankedRow, shareText, spokenChange } from './parts';

/**
 * The merchants the period's spending went to, ranked. Only spending that names a merchant can be
 * ranked, so the card says how much of the spending that is rather than letting the ranking pass for
 * the whole. Tapping a row opens the Transactions tab searching for the merchant's name over the
 * period: a search, not a replay of the figure, so its rows can differ from the amount shown.
 */
export function MerchantList({
  report, limit, onViewAll, onOpen,
}: { report: AdvancedReport; limit?: number; onViewAll?: () => void; onOpen: (filter: TransactionsFilter) => void }) {
  const c = useTheme();
  const { merchants, period } = report;
  const rows = limit ? merchants.rows.slice(0, limit) : merchants.rows;
  const largest = Math.max(...merchants.rows.map((m) => m.amount), 1);
  const span = period.startMonth && period.endMonth
    ? { dateFrom: monthBounds(period.startMonth).from, dateTo: monthBounds(period.endMonth).to }
    : null;

  return (
    <DashboardCard testID="merchant-list">
      <SectionHeading title="Top merchants" />
      {rows.length === 0 ? (
        <Text style={[typography.bodyS, { color: c.mutedInk }]}>No merchant spending yet. Import a statement or add transactions to see where you spend the most.</Text>
      ) : (
        <>
          {rows.map((m) => {
            const change = spokenChange(m.changeAmount, m.changePct, m.previousAmount);
            return (
              <RankedRow
                key={m.merchantId} testID={`merchant-${m.name}`} label={m.name} amount={m.amount} share={m.sharePct} bar={largest}
                leading={<MerchantLogo merchant={m.name} domain={m.logoDomain} size={28} />}
                detail={`${m.transactionCount} ${m.transactionCount === 1 ? 'purchase' : 'purchases'}`}
                change={<ChangeText previousAmount={m.previousAmount} changeAmount={m.changeAmount} changePct={m.changePct} />}
                accessibilityLabel={`${m.name}: ${fmtCurrency(m.amount)}, ${shareText(m.sharePct)} of spending${change ? `, ${change}` : ''}`}
                onPress={span ? () => onOpen({ ...span, label: m.name, keyword: m.name }) : undefined}
              />
            );
          })}
          <Text style={[typography.caption, { color: c.mutedInk }]} testID="merchant-coverage">
            {Math.round(merchants.attributedSharePct)}% of your spending is tied to a named merchant, so this ranks that part. The five largest take {Math.round(merchants.topFiveSharePct)}% of all your spending.
          </Text>
        </>
      )}
      {limit && onViewAll && merchants.rows.length > limit ? (
        <Pressable onPress={onViewAll} accessibilityRole="button" accessibilityLabel="View all merchants" style={styles.viewAll}>
          <Text style={[typography.labelS, { color: c.primary }]}>View all merchants</Text>
        </Pressable>
      ) : null}
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  viewAll: { minHeight: 44, justifyContent: 'center' },
});
