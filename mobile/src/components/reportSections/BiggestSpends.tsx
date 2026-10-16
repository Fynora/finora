import { Pressable, StyleSheet, Text, View } from 'react-native';
import type { BiggestSpend } from '../../api/endpoints';
import { fmtCurrency } from '../../lib/format';
import { shortDate } from '../../lib/reportSections/trend';
import { spacing, typography, useTheme } from '../../theme';
import { DashboardCard } from '../dashboard/DashboardCard';
import { SectionHeading } from '../Card';

/**
 * The month's largest purchases, from the server: the same rows and refund netting as the category
 * list, so each one is something the report counted as spend. Drawn only when there is one. A row
 * opens that purchase's name on its day in the Transactions tab.
 */
export function BiggestSpends({ items, onOpen }: { items: BiggestSpend[] | undefined; onOpen: (spend: BiggestSpend) => void }) {
  const c = useTheme();
  if (!items || items.length === 0) return null;
  return (
    <DashboardCard testID="biggest-spends">
      <SectionHeading title="Biggest spends" />
      {items.map((s, i) => (
        <Pressable
          key={s.id} onPress={() => onOpen(s)} accessibilityRole="button"
          accessibilityLabel={`${s.label}, ${fmtCurrency(s.amount)} on ${shortDate(s.date)}, ${s.category}`}
          accessibilityHint="Opens it in your transactions"
          style={[styles.row, i > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: c.border }]}
        >
          <View style={styles.main}>
            <Text style={[typography.bodyM, { color: c.ink }]} numberOfLines={1}>{s.label}</Text>
            <Text style={[typography.caption, { color: c.mutedInk }]} numberOfLines={1}>{shortDate(s.date)} · {s.category}</Text>
          </View>
          <Text style={[typography.bodyM, { color: c.ink }]}>{fmtCurrency(s.amount)}</Text>
        </Pressable>
      ))}
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, minHeight: 52, paddingVertical: 6 },
  main: { flex: 1, minWidth: 0 },
});
