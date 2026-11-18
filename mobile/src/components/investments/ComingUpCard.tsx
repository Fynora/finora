import { StyleSheet, Text, View } from 'react-native';
import { Card } from '../Card';
import type { ComingUpRow, HoldingLike } from '../../lib/investmentSummary';
import { spacing, typography, useTheme } from '../../theme';

/** The next deposits to mature and how recurring deposits are going. Hidden when there are none. */
export function ComingUpCard<T extends HoldingLike>({ rows, money }: { rows: ComingUpRow<T>[]; money: (n: number) => string }) {
  const c = useTheme();
  if (rows.length === 0) return null;
  return (
    <Card style={styles.card}>
      <Text accessibilityRole="header" style={[typography.cardTitle, { color: c.ink }]}>Coming up</Text>
      {rows.map((row) => {
        const paidLabel = row.paid !== null ? `${row.paid} of ${row.instalments} paid` : null;
        if (row.dateLabel) {
          return (
            <View key={row.holding.id} style={styles.row}>
              <View style={styles.main}>
                <Text style={[typography.labelM, { color: c.ink }]}>{`${row.holding.name} matures`}</Text>
                <Text style={[typography.bodyS, { color: c.mutedInk }]}>{[row.dateLabel, row.whenLabel, paidLabel].filter(Boolean).join(' · ')}</Text>
              </View>
              {row.amount !== null ? <Text style={[typography.labelM, { color: c.ink }]}>{money(row.amount)}</Text> : null}
            </View>
          );
        }
        return (
          <View key={row.holding.id} style={styles.plan}>
            <View style={styles.row}>
              <Text style={[typography.labelM, styles.main, { color: c.ink }]}>{row.holding.name}</Text>
              <Text style={[typography.bodyS, { color: c.mutedInk }]}>{paidLabel}</Text>
            </View>
            <View
              accessible
              accessibilityRole="progressbar"
              accessibilityLabel={`${row.holding.name}: ${paidLabel}`}
              accessibilityValue={{ min: 0, max: 100, now: Math.round((row.progress ?? 0) * 100) }}
              style={[styles.track, { backgroundColor: c.border }]}
            >
              <View style={[styles.fill, { width: `${(row.progress ?? 0) * 100}%`, backgroundColor: c.success }]} />
            </View>
          </View>
        );
      })}
    </Card>
  );
}

const styles = StyleSheet.create({
  card: { gap: spacing.ms },
  row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: spacing.sm },
  main: { flexShrink: 1 },
  plan: { gap: spacing.sm },
  track: { height: 8, borderRadius: 4, overflow: 'hidden' },
  fill: { height: '100%', borderRadius: 4 },
});
