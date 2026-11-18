import { Pressable, StyleSheet, Text, View } from 'react-native';
import { DashboardCard } from '../dashboard/DashboardCard';
import { SkeletonGroup, SkeletonText } from '../skeletons/Skeletons';
import { fmtCurrency } from '../../lib/format';
import type { LedgerFilterTotals } from '../../lib/ledgerFilterTotals';
import { spacing, typography, useTheme } from '../../theme';

const count = (n: number) => n.toLocaleString('en-IN');

function noteFor(totals: LedgerFilterTotals): string | null {
  if (!totals.complete) {
    return `Covers the newest ${count(totals.rowsRead)} of ${count(totals.totalElements)} transactions.`;
  }
  if (totals.notCounted === 0) return null;
  return totals.notCounted === 1
    ? '1 duplicate or replaced row is not counted.'
    : `${count(totals.notCounted)} duplicate or replaced rows are not counted.`;
}

/**
 * Stands in for LedgerMonthCard while a filter is on: what the rows in the list add up to. Named
 * Money in and Money out, not Income and Expenses, because those are the server's own month figures
 * and a row does not carry what decides them (see addUpLedgerRows).
 */
export function LedgerFilteredCard({
  totals, failed, onRetry, stacked,
}: {
  /** Undefined until the first answer for these filters arrives. */
  totals: LedgerFilterTotals | undefined;
  failed: boolean;
  onRetry: () => void;
  stacked: boolean;
}) {
  const c = useTheme();
  const note = totals ? noteFor(totals) : null;
  const columns = stacked ? styles.stacked : styles.sideBySide;

  function column(label: string, testID: string, value: number) {
    return (
      <View style={styles.column}>
        <Text style={[typography.labelS, { color: c.mutedInk }]}>{label}</Text>
        <Text testID={testID} style={[typography.numberM, { color: c.ink }]}>{fmtCurrency(value)}</Text>
      </View>
    );
  }

  return (
    <DashboardCard testID="ledger-filtered-card" style={styles.card}>
      <Text style={[typography.eyebrow, styles.title, { color: c.mutedInk }]}>Matching transactions</Text>
      {totals ? (
        <View testID="ledger-filtered-columns" style={columns}>
          {column('Money in', 'filtered-money-in', totals.moneyIn)}
          {column('Money out', 'filtered-money-out', totals.moneyOut)}
        </View>
      ) : failed ? (
        // A request that failed is not an answer of zero.
        <View style={styles.failed}>
          <Text style={[typography.caption, styles.failedText, { color: c.mutedInk }]}>Couldn't add these up.</Text>
          <Pressable onPress={onRetry} accessibilityRole="button" hitSlop={spacing.sm}>
            <Text style={[typography.labelS, { color: c.primary }]}>Try again</Text>
          </Pressable>
        </View>
      ) : (
        <SkeletonGroup style={columns}>
          <View style={styles.column}>
            <SkeletonText size="meta" width="50%" />
            <SkeletonText size="heading" width="70%" />
          </View>
          <View style={styles.column}>
            <SkeletonText size="meta" width="50%" />
            <SkeletonText size="heading" width="70%" />
          </View>
        </SkeletonGroup>
      )}
      {note ? (
        <Text testID="filtered-note" style={[typography.caption, styles.note, { color: c.mutedInk }]}>{note}</Text>
      ) : null}
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  card: { paddingVertical: 14, paddingHorizontal: spacing.md },
  title: { marginBottom: spacing.sm },
  note: { marginTop: spacing.sm },
  sideBySide: { flexDirection: 'row', gap: spacing.md },
  stacked: { flexDirection: 'column', gap: spacing.ms },
  column: { flex: 1, gap: spacing.xs },
  failed: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.md },
  failedText: { flexShrink: 1 },
});
