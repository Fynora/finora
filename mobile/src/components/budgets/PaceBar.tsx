import { StyleSheet, View } from 'react-native';
import { useTheme } from '../../theme';
import type { BudgetGroup } from '../../lib/budgetPace';

export function groupFill(c: ReturnType<typeof useTheme>, group: BudgetGroup) {
  return group === 'over' ? c.danger : group === 'heading-over' ? c.warning : c.success;
}

export function groupInk(c: ReturnType<typeof useTheme>, group: BudgetGroup) {
  return group === 'over' ? c.dangerInk : group === 'heading-over' ? c.warningInk : c.successInk;
}

/**
 * Spent bar with a pace marker at the share of the month already gone. Hidden from screen
 * readers: the card it sits in reads the same facts as one sentence.
 */
export function PaceBar({ spentFrac, monthFrac, group }: { spentFrac: number; monthFrac: number; group: BudgetGroup }) {
  const c = useTheme();
  const pct = Number.isFinite(spentFrac) ? Math.max(0, Math.min(1, spentFrac)) * 100 : 0;
  return (
    <View style={[styles.track, { backgroundColor: c.border }]} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
      {pct > 0 ? <View style={[styles.fill, { width: `${pct}%`, backgroundColor: groupFill(c, group) }]} /> : null}
      <View testID="pace-marker" style={[styles.marker, { left: `${monthFrac * 100}%`, backgroundColor: c.ink }]} />
    </View>
  );
}

const styles = StyleSheet.create({
  track: { height: 8, borderRadius: 4, justifyContent: 'center' },
  fill: { position: 'absolute', left: 0, top: 0, bottom: 0, borderRadius: 4 },
  marker: { position: 'absolute', top: -4, width: 2, height: 16, marginLeft: -1, borderRadius: 1 },
});
