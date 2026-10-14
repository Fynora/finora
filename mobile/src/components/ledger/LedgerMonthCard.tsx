import { StyleSheet, Text, View } from 'react-native';
import { AnimatedNumber } from '../AnimatedNumber';
import { DashboardCard } from '../dashboard/DashboardCard';
import { DeltaChip } from '../dashboard/DeltaChip';
import type { KpiItem } from '../dashboard/LedgerSnapshotCard';
import { spacing, typography, useTheme } from '../../theme';

/**
 * The Transactions tab's month card: Income and Expenses from the dashboard's own figures. The
 * period heading and the comparison wording are props from useDashboardKpis and are never written
 * here as text; scripts/check-reporting-period-labels.py scans this file to keep it so.
 */
export function LedgerMonthCard({
  kpis, title, deltaLabel, deltaSpokenLabel, stacked,
}: { kpis: KpiItem[]; title: string; deltaLabel: string; deltaSpokenLabel: string; stacked: boolean }) {
  const c = useTheme();
  const income = kpis.find((k) => k.label === 'Income');
  const expenses = kpis.find((k) => k.label === 'Expenses');
  if (!income || !expenses) return null;
  const hasDelta = (k: KpiItem) => k.delta !== null && k.delta !== undefined;

  function column(k: KpiItem) {
    const delta = k.delta ?? 0;
    return (
      <View style={styles.column}>
        <View style={styles.labelRow}>
          <Text style={[typography.labelS, { color: c.mutedInk }]}>{k.label}</Text>
          {hasDelta(k) ? (
            <View
              accessible
              accessibilityLabel={`${k.label} ${delta >= 0 ? 'up' : 'down'} ${Math.abs(delta).toFixed(1)} percent ${deltaSpokenLabel}`}
            >
              <DeltaChip delta={delta} good={k.invert ? delta < 0 : delta >= 0} />
            </View>
          ) : null}
        </View>
        {/* Income and Expenses are always numbers; only Savings Rate can be null. */}
        <AnimatedNumber testID={`kpi-${k.label}`} value={k.value ?? 0} style={[typography.numberM, { color: c.ink }]} />
      </View>
    );
  }

  return (
    <DashboardCard testID="ledger-month-card" style={styles.card}>
      <View style={styles.head}>
        <Text style={[typography.eyebrow, { color: c.mutedInk }]}>{title}</Text>
        {hasDelta(income) || hasDelta(expenses) ? (
          <Text style={[typography.caption, styles.comparison, { color: c.mutedInk }]}>{deltaLabel}</Text>
        ) : null}
      </View>
      <View testID="ledger-month-columns" style={stacked ? styles.stacked : styles.sideBySide}>
        {column(income)}
        {column(expenses)}
      </View>
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  card: { paddingVertical: 14, paddingHorizontal: spacing.md },
  head: {
    flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline', gap: spacing.sm,
    marginBottom: spacing.sm,
  },
  comparison: { flexShrink: 1, textAlign: 'right' },
  sideBySide: { flexDirection: 'row', gap: spacing.md },
  stacked: { flexDirection: 'column', gap: spacing.ms },
  column: { flex: 1, gap: spacing.xs },
  labelRow: { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: spacing.sm },
});
