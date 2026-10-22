import { StyleSheet, Text, View } from 'react-native';
import type { AdvancedReport } from '../../api/endpoints';
import { againstName, percentChange, pointsChange } from '../../lib/advancedReports/advancedFormat';
import { fmtCurrency } from '../../lib/format';
import { spacing, typography, useTheme } from '../../theme';
import { DashboardCard } from '../dashboard/DashboardCard';

const RATE_WITHHELD: Record<string, string> = {
  NO_INCOME: 'No income counted',
  UNRESOLVED_EXCEEDS_INCOME: 'Held back: unclassified credits',
};

type Tone = 'good' | 'bad' | 'plain';

function Change({ text, tone, against, testID }: { text: string | null; tone: Tone; against: string; testID?: string }) {
  const c = useTheme();
  if (!text) return null;
  // "No change" is neither good nor bad: it is never coloured.
  const color = text === 'No change' ? c.mutedInk : tone === 'good' ? c.successInk : tone === 'bad' ? c.dangerInk : c.ink;
  return (
    <Text style={[typography.caption, { color: c.mutedInk }]} testID={testID}>
      <Text style={{ color }}>{text}</Text> vs {against}
    </Text>
  );
}

/**
 * Net cash flow as the headline of the period, with income, expenses, the average month and the
 * savings rate under it, each against the period it is compared with. Every figure and change is the
 * server's. With the comparison withheld the change lines are left off, and the notice under the
 * pickers says why once.
 */
export function AdvancedKpis({ report }: { report: AdvancedReport }) {
  const c = useTheme();
  const { totals, comparison: cmp, period } = report;
  const against = againstName(period);
  const ok = cmp.reliable;
  const netChange = ok && cmp.netDelta != null
    ? (Math.round(cmp.netDelta) === 0 ? 'No change' : `${cmp.netDelta > 0 ? '↑' : '↓'} ${fmtCurrency(Math.abs(cmp.netDelta))}`)
    : null;
  const rate = totals.savingsRatePct;

  return (
    <View style={styles.stack} testID="advanced-kpis">
      <DashboardCard>
        <Text style={[typography.eyebrow, { color: c.mutedInk }]}>Net cash flow</Text>
        <Text
          style={[typography.numberL, { color: totals.net >= 0 ? c.successInk : c.dangerInk }]} testID="kpi-net"
          accessibilityLabel={`Net cash flow ${fmtCurrency(totals.net)}`}
        >
          {fmtCurrency(totals.net)}
        </Text>
        <Change text={netChange} tone={(cmp.netDelta ?? 0) >= 0 ? 'good' : 'bad'} against={against} testID="kpi-net-change" />
      </DashboardCard>

      <View style={styles.tiles}>
        <DashboardCard padding="compact" style={styles.tile}>
          <Text style={[typography.eyebrow, { color: c.mutedInk }]}>Income</Text>
          <Text style={[typography.numberS, { color: c.successInk }]} testID="kpi-income">{fmtCurrency(totals.income)}</Text>
          <Change text={ok ? percentChange(cmp.incomeDeltaPct) : null} tone={(cmp.incomeDeltaPct ?? 0) >= 0 ? 'good' : 'bad'} against={against} />
        </DashboardCard>
        <DashboardCard padding="compact" style={styles.tile}>
          <Text style={[typography.eyebrow, { color: c.mutedInk }]}>Expenses</Text>
          <Text style={[typography.numberS, { color: c.dangerInk }]} testID="kpi-expense">{fmtCurrency(totals.expense)}</Text>
          <Change text={ok ? percentChange(cmp.expenseDeltaPct) : null} tone={(cmp.expenseDeltaPct ?? 0) > 0 ? 'bad' : 'good'} against={against} />
        </DashboardCard>
      </View>
      <View style={styles.tiles}>
        <DashboardCard padding="compact" style={styles.tile}>
          <Text style={[typography.eyebrow, { color: c.mutedInk }]}>Average month</Text>
          <Text style={[typography.numberS, { color: c.ink }]} testID="kpi-average">
            {totals.avgMonthlyExpense == null ? '—' : fmtCurrency(totals.avgMonthlyExpense)}
          </Text>
          <Change text={ok ? percentChange(cmp.avgMonthlyExpenseDeltaPct) : null} tone={(cmp.avgMonthlyExpenseDeltaPct ?? 0) > 0 ? 'bad' : 'good'} against={against} />
        </DashboardCard>
        <DashboardCard padding="compact" style={styles.tile}>
          <Text style={[typography.eyebrow, { color: c.mutedInk }]}>Savings rate</Text>
          <Text style={[typography.numberS, { color: c.ink }]} testID="kpi-savings">{rate == null ? '—' : `${Math.round(rate)}%`}</Text>
          {rate == null ? (
            <Text style={[typography.caption, { color: c.mutedInk }]}>{RATE_WITHHELD[totals.savingsRateGateReason ?? ''] ?? 'Not enough to work out a rate'}</Text>
          ) : (
            <Change text={ok ? pointsChange(cmp.savingsRateDeltaPp) : null} tone={(cmp.savingsRateDeltaPp ?? 0) >= 0 ? 'good' : 'bad'} against={against} />
          )}
        </DashboardCard>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  stack: { gap: spacing.sm },
  tiles: { flexDirection: 'row', gap: spacing.sm },
  tile: { flex: 1, gap: 2 },
});
