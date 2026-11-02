import { StyleSheet, Text, View } from 'react-native';
import { Card } from '../../components/Card';
import { fmtCurrency } from '../../lib/format';
import { radius, spacing, typography, useTheme } from '../../theme';
import type { StagedRow } from '../../types';

/**
 * The top of the Import check step (spec 2026-11-02, Step 2): what this statement is, and how much
 * money comes in and goes out across the rows that will be imported. Income in green, expenses in
 * red, as everywhere else in the app.
 */
export function ReviewSummary({
  name, masked, periodStart, periodEnd, fileFormat, rows, included,
}: {
  name: string;
  masked: string | null;
  periodStart: string | null;
  periodEnd: string | null;
  fileFormat: string | null;
  rows: StagedRow[];
  included: boolean[];
}) {
  const c = useTheme();
  let inTotal = 0;
  let outTotal = 0;
  rows.forEach((row, i) => {
    if (!included[i]) return;
    if (row.type === 'INCOME') inTotal += Math.abs(row.amount);
    else outTotal += Math.abs(row.amount);
  });
  const count = `${rows.length} transaction${rows.length === 1 ? '' : 's'}`;
  const meta = periodStart && periodEnd ? `${periodStart} to ${periodEnd} · ${count}` : count;

  return (
    <Card testID="review-summary">
      <View style={styles.nameRow}>
        <Text style={[typography.cardTitle, styles.name, { color: c.ink }]}>{name}</Text>
        {fileFormat ? (
          // primaryLight on white is ~1.13:1; the border gives the badge an edge (same as before).
          <Text style={[typography.labelS, styles.badge, { color: c.primary, backgroundColor: c.primaryLight, borderColor: c.border }]}>
            {fileFormat}
          </Text>
        ) : null}
      </View>
      {masked ? <Text style={[typography.bodyM, { color: c.mutedInk }]}>{masked}</Text> : null}
      <Text style={[typography.bodyM, { color: c.mutedInk }]}>{meta}</Text>
      <View style={styles.totals}>
        <Text style={[typography.labelM, { color: c.successInk }]}>{`In ${fmtCurrency(inTotal)}`}</Text>
        <Text style={[typography.labelM, { color: c.dangerInk }]}>{`Out ${fmtCurrency(outTotal)}`}</Text>
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  nameRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  name: { flexShrink: 1 },
  badge: { borderWidth: 1, borderRadius: radius.md, paddingHorizontal: spacing.sm, paddingVertical: 2, overflow: 'hidden' },
  totals: { flexDirection: 'row', flexWrap: 'wrap', columnGap: spacing.lg, marginTop: spacing.xs },
});
