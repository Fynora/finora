import { StyleSheet, Text, View } from 'react-native';
import type { ReportData } from '../../api/endpoints';
import { fmtCurrency } from '../../lib/format';
import { comparisonMonthName } from '../../lib/reportSections/comparison';
import { spacing, typography, useTheme } from '../../theme';
import { DashboardCard } from '../dashboard/DashboardCard';
import { DeltaChip } from '../dashboard/DeltaChip';

function StatCard({ label, value, valueColor, delta, good, deltaLabel, testID }: {
  label: string; value: string; valueColor: string; delta: number | null; good: boolean; deltaLabel: string | undefined; testID: string;
}) {
  const c = useTheme();
  return (
    <DashboardCard testID={testID} style={styles.card} padding="compact">
      <Text style={[typography.eyebrow, { color: c.mutedInk }]}>{label}</Text>
      <Text style={[typography.numberM, { color: valueColor }]} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.7}>{value}</Text>
      {delta !== null && deltaLabel ? (
        <View style={styles.delta}>
          <DeltaChip delta={delta} good={good} />
          <Text style={[typography.caption, { color: c.mutedInk }]}>{deltaLabel}</Text>
        </View>
      ) : null}
    </DashboardCard>
  );
}

/**
 * Income and Expenses, each with how it moved since the month before. Every figure is the server's:
 * when it did not trust the comparison, the delta line is left off and the hero says why in words.
 * A fall in expenses reads as good.
 */
export function ReportStatCards({ report }: { report: ReportData }) {
  const c = useTheme();
  const cmp = report.comparison;
  const reliable = !!cmp?.reliable;
  const priorName = cmp ? comparisonMonthName(cmp.previousMonth, report.month) : null;
  const deltaLabel = reliable && priorName ? `vs ${priorName}` : undefined;
  const incomeDelta = reliable ? cmp!.incomeDeltaPct : null;
  const expenseDelta = reliable ? cmp!.expenseDeltaPct : null;
  return (
    <View style={styles.row}>
      <StatCard
        testID="stat-income" label="Total income" value={fmtCurrency(report.income)} valueColor={c.successInk}
        delta={incomeDelta} good={(incomeDelta ?? 0) >= 0} deltaLabel={deltaLabel}
      />
      <StatCard
        testID="stat-expense" label="Total expenses" value={fmtCurrency(report.expense)} valueColor={c.dangerInk}
        delta={expenseDelta} good={(expenseDelta ?? 0) <= 0} deltaLabel={deltaLabel}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', gap: spacing.sm },
  card: { flex: 1, gap: 4 },
  delta: { gap: 4, marginTop: 2 },
});
