import { StyleSheet, Text, View } from 'react-native';
import { fmtCurrency } from '../../lib/format';
import type { CategoryChange } from '../../lib/reportSections/changeVsMonth';
import { spacing, typography, useTheme } from '../../theme';
import { DashboardCard } from '../dashboard/DashboardCard';
import { SectionHeading } from '../Card';

/** "+₹2,000" / "-₹1,200": the sign is part of the text, so direction never rests on colour alone. */
const signed = (n: number) => (n > 0 ? '+' : '') + fmtCurrency(n);

function detail(c: CategoryChange, priorName: string): string {
  switch (c.kind) {
    case 'new': return `New this month, nothing in ${priorName}`;
    case 'gone': return `Nothing this month, ${fmtCurrency(c.before)} in ${priorName}`;
    default: return `${fmtCurrency(c.now)} now, ${fmtCurrency(c.before)} in ${priorName}`;
  }
}

/**
 * Where the month's spending moved against the month before, by category, in rupees. Drawn only when
 * the server trusted the comparison between the two months and there is a change to show. More spent
 * reads in the danger colour and less in the success colour, but the sign says it too.
 */
export function CategoryChanges({ changes, priorName }: { changes: CategoryChange[]; priorName: string }) {
  const c = useTheme();
  if (changes.length === 0) return null;
  return (
    <DashboardCard testID="category-changes">
      <SectionHeading title={`Biggest changes since ${priorName}`} />
      {changes.map((change, i) => (
        <View
          key={change.category}
          style={[styles.row, i > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: c.border }]}
          accessible accessibilityLabel={`${change.category}, ${signed(change.delta)}. ${detail(change, priorName)}`}
        >
          <View style={styles.main}>
            <Text style={[typography.bodyM, { color: c.ink }]} numberOfLines={1}>{change.category}</Text>
            <Text style={[typography.caption, { color: c.mutedInk }]} numberOfLines={2}>{detail(change, priorName)}</Text>
          </View>
          <Text style={[typography.bodyM, { color: change.delta > 0 ? c.dangerInk : c.successInk }]}>{signed(change.delta)}</Text>
        </View>
      ))}
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, minHeight: 52, paddingVertical: 6 },
  main: { flex: 1, minWidth: 0 },
});
