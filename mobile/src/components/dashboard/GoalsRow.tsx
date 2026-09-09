import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { fmtCurrency } from '../../lib/format';
import { radius, spacing, useTheme } from '../../theme';
import type { Goal } from '../../types';

export function GoalsRow({ goals }: { goals: Goal[] }) {
  const c = useTheme();
  if (goals.length === 0) return null;

  return (
    <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.row}>
      {goals.map((g) => {
        const pct = g.targetAmount > 0 ? Math.min(100, (g.currentAmount / g.targetAmount) * 100) : 0;
        return (
          <View key={g.id} style={[styles.card, { backgroundColor: c.card, borderColor: c.border }]}>
            <View style={styles.header}>
              <Text style={[styles.name, { color: c.ink }]} numberOfLines={1}>{g.name}</Text>
              <Text style={[styles.pct, { color: c.mutedInk }]}>{pct.toFixed(0)}%</Text>
            </View>
            <View style={[styles.track, { backgroundColor: c.border }]}>
              <View style={[styles.fill, { width: `${pct}%`, backgroundColor: c.primary }]} />
            </View>
            <Text style={[styles.meta, { color: c.mutedInk }]}>
              {fmtCurrency(g.currentAmount)} of {fmtCurrency(g.targetAmount)}
            </Text>
          </View>
        );
      })}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  row: { gap: spacing.sm, paddingVertical: spacing.xs },
  card: { width: 180, borderWidth: 1, borderRadius: radius.lg, padding: spacing.md },
  header: { flexDirection: 'row', justifyContent: 'space-between', marginBottom: 6 },
  name: { fontSize: 13, fontWeight: '600', flex: 1 },
  pct: { fontSize: 12 },
  track: { height: 6, borderRadius: 3, overflow: 'hidden' },
  fill: { height: 6, borderRadius: 3 },
  meta: { fontSize: 11, marginTop: 6 },
});
