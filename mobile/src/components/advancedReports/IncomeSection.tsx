import { StyleSheet, Text, View } from 'react-native';
import type { AdvancedReport } from '../../api/endpoints';
import { againstName, monthYear } from '../../lib/advancedReports/advancedFormat';
import { fmtCurrency } from '../../lib/format';
import { spacing, typography, useTheme } from '../../theme';
import { DashboardCard } from '../dashboard/DashboardCard';
import { SectionHeading } from '../Card';
import { ChangeText, shareText } from './parts';

/**
 * Income in the period: where it came from, month by month, and how steady it was. Steadiness is
 * described, not scored: how many months had income and how the lowest whole month compares with the
 * highest. Only credits Fynora counts as income are here; credits it cannot yet call income are named
 * in a line of their own, never folded in.
 */
export function IncomeSection({ report }: { report: AdvancedReport }) {
  const c = useTheme();
  const { income, months, totals, period } = report;
  const against = againstName(period);
  const maxMonth = Math.max(...months.map((m) => m.income), 1);

  if (totals.income <= 0 && totals.unresolvedInflow <= 0) {
    return (
      <DashboardCard testID="income-empty">
        <SectionHeading title="Income" />
        <Text style={[typography.bodyS, { color: c.mutedInk }]}>No income in this period. Credits that Fynora counts as income appear here, by source and by month.</Text>
      </DashboardCard>
    );
  }

  return (
    <View style={styles.stack}>
      <DashboardCard testID="income-sources">
        <SectionHeading title="Income by source" />
        {income.sources.length === 0 ? (
          <Text style={[typography.bodyS, { color: c.mutedInk }]}>No credit in this period is counted as income yet.</Text>
        ) : income.sources.map((s) => (
          <View key={s.label} style={styles.source} accessible accessibilityLabel={`${s.label}: ${fmtCurrency(s.amount)}, ${shareText(s.sharePct)} of income`}>
            <View style={styles.line}>
              <Text style={[typography.bodyM, { color: c.ink }]}>{s.label}</Text>
              <Text style={[typography.bodyM, { color: c.ink }]}>{fmtCurrency(s.amount)} <Text style={[typography.caption, { color: c.mutedInk }]}>{shareText(s.sharePct)}</Text></Text>
            </View>
            <View style={[styles.track, { backgroundColor: c.border }]}><View style={[styles.fill, { backgroundColor: c.success, width: `${Math.min(100, s.sharePct)}%` }]} /></View>
            {s.previousAmount != null ? (
              <View style={styles.line}>
                <Text style={[typography.caption, { color: c.mutedInk }]}>{s.previousAmount === 0 ? `New, nothing in ${against}` : `${fmtCurrency(s.previousAmount)} in ${against}`}</Text>
                {s.previousAmount > 0 ? <ChangeText previousAmount={s.previousAmount} changeAmount={s.amount - s.previousAmount} changePct={((s.amount - s.previousAmount) / s.previousAmount) * 100} /> : null}
              </View>
            ) : null}
          </View>
        ))}
        {totals.unresolvedInflow > 0 ? (
          <Text style={[typography.caption, { color: c.mutedInk }]} testID="unresolved-inflow">
            {fmtCurrency(totals.unresolvedInflow)} in {totals.unresolvedInflowCount} {totals.unresolvedInflowCount === 1 ? 'credit is' : 'credits are'} not counted as income yet, because Fynora cannot tell what {totals.unresolvedInflowCount === 1 ? 'it was' : 'they were'}. Classifying {totals.unresolvedInflowCount === 1 ? 'it' : 'them'} in Review changes these figures.
          </Text>
        ) : null}
      </DashboardCard>

      <DashboardCard testID="income-months">
        <SectionHeading title="Income by month" />
        {months.map((m) => (
          <View key={m.month} style={styles.monthRow} accessible accessibilityLabel={`${monthYear(m.month)}${m.complete ? '' : ', only part of the month'}: ${m.income > 0 ? fmtCurrency(m.income) : 'no income'}`}>
            <Text style={[typography.bodyS, styles.monthName, { color: c.mutedInk }]}>{monthYear(m.month)}{m.complete ? '' : '*'}</Text>
            <View style={[styles.track, styles.monthTrack, { backgroundColor: c.border }]}><View style={[styles.fill, { backgroundColor: c.success, width: `${(m.income / maxMonth) * 100}%` }]} /></View>
            <Text style={[typography.bodyS, { color: c.ink }]}>{m.income > 0 ? fmtCurrency(m.income) : '—'}</Text>
          </View>
        ))}
        {months.some((m) => !m.complete) ? <Text style={[typography.caption, { color: c.mutedInk }]}>* Only part of the month is in your data.</Text> : null}
        <Text style={[typography.caption, styles.steady, { color: c.mutedInk }]} testID="income-steadiness">
          Income came in during {income.monthsWithIncome} of {months.length} {months.length === 1 ? 'month' : 'months'}.
          {income.highestMonth && income.lowestMonth && income.lowestAsPctOfHighest != null
            ? ` Among whole months the highest was ${monthYear(income.highestMonth)} (${fmtCurrency(income.highestMonthAmount ?? 0)}) and the lowest ${monthYear(income.lowestMonth)} (${fmtCurrency(income.lowestMonthAmount ?? 0)}), ${Math.round(income.lowestAsPctOfHighest)}% of the highest.`
            : ''}
        </Text>
      </DashboardCard>
    </View>
  );
}

const styles = StyleSheet.create({
  stack: { gap: spacing.md },
  source: { gap: 4, marginBottom: spacing.md },
  line: { flexDirection: 'row', justifyContent: 'space-between', gap: spacing.sm },
  track: { height: 6, borderRadius: 3, overflow: 'hidden' },
  fill: { height: '100%', borderRadius: 3 },
  monthRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, marginBottom: spacing.xs, minHeight: 28 },
  monthName: { width: 70 },
  monthTrack: { flex: 1 },
  steady: { marginTop: spacing.sm },
});
