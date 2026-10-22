import { Pressable, ScrollView, StyleSheet, Text } from 'react-native';
import { spacing, typography, useTheme } from '../../theme';

export type AdvancedTab = 'overview' | 'spending' | 'income' | 'merchants' | 'categorisation';

export const ADVANCED_TABS: { key: AdvancedTab; label: string }[] = [
  { key: 'overview', label: 'Overview' },
  { key: 'spending', label: 'Spending' },
  { key: 'income', label: 'Income' },
  { key: 'merchants', label: 'Merchants' },
  { key: 'categorisation', label: 'Category insights' },
];

/**
 * The five sections, each answering one question. Five do not fit a phone's width at a readable
 * size, so the row scrolls sideways; the selected tab is marked by an underline and by its state, not
 * by colour alone.
 */
export function AdvancedTabs({ selected, onSelect }: { selected: AdvancedTab; onSelect: (tab: AdvancedTab) => void }) {
  const c = useTheme();
  return (
    <ScrollView
      horizontal showsHorizontalScrollIndicator={false} accessibilityRole="tablist"
      style={[styles.scroll, { borderBottomColor: c.border }]} contentContainerStyle={styles.row}
    >
      {ADVANCED_TABS.map((t) => {
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
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll: { flexGrow: 0, borderBottomWidth: StyleSheet.hairlineWidth, marginBottom: spacing.md },
  row: { gap: spacing.md },
  tab: { minHeight: 44, paddingHorizontal: spacing.xs, alignItems: 'center', justifyContent: 'center', borderBottomWidth: 2, borderBottomColor: 'transparent' },
  activeText: { fontWeight: '700' },
});
