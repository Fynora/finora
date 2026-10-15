import { Pressable, StyleSheet, Text, View } from 'react-native';
import { spacing, typography, useTheme } from '../../theme';

export type ReportTab = 'overview' | 'spending' | 'income';

const TABS: { key: ReportTab; label: string }[] = [
  { key: 'overview', label: 'Overview' },
  { key: 'spending', label: 'Spending' },
  { key: 'income', label: 'Income' },
];

/**
 * Overview, Spending and Income, so each screen-full answers one question: how the month went, where
 * the money went, and what came in. The selected tab is marked by an underline and by its state, not
 * by colour alone.
 */
export function ReportTabs({ selected, onSelect }: { selected: ReportTab; onSelect: (tab: ReportTab) => void }) {
  const c = useTheme();
  return (
    <View style={[styles.row, { borderBottomColor: c.border }]} accessibilityRole="tablist">
      {TABS.map((t) => {
        const active = t.key === selected;
        return (
          <Pressable
            key={t.key} onPress={() => onSelect(t.key)} accessibilityRole="tab" accessibilityState={{ selected: active }}
            accessibilityLabel={t.label} style={[styles.tab, active && { borderBottomColor: c.brass }]} hitSlop={4}
          >
            <Text style={[typography.labelM, { color: active ? c.ink : c.mutedInk }, active && styles.activeText]}>{t.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', borderBottomWidth: StyleSheet.hairlineWidth, marginBottom: spacing.md },
  tab: { flex: 1, minHeight: 44, alignItems: 'center', justifyContent: 'center', borderBottomWidth: 2, borderBottomColor: 'transparent' },
  activeText: { fontWeight: '700' },
});
