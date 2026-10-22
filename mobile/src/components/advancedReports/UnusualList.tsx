import { Pressable, StyleSheet, Text, View } from 'react-native';
import type { AdvancedUnusualSpend } from '../../api/endpoints';
import type { TransactionsFilter } from '../../lib/advancedReports/advancedFormat';
import { fmtCurrency } from '../../lib/format';
import { shortDate } from '../../lib/reportSections/trend';
import { spacing, typography, useTheme } from '../../theme';
import { DashboardCard } from '../dashboard/DashboardCard';
import { SectionHeading } from '../Card';

/**
 * Purchases that were much larger than is usual for their category, by a rule written on the card:
 * at least three times the median of that category's other purchases. It says "larger than usual",
 * never "wrong": a big one-off is often exactly what the money was for. Left out when there are none.
 */
export function UnusualList({ items, onOpen }: { items: AdvancedUnusualSpend[]; onOpen: (filter: TransactionsFilter) => void }) {
  const c = useTheme();
  if (items.length === 0) return null;
  return (
    <DashboardCard testID="unusual-list">
      <SectionHeading title="Larger than usual" />
      {items.map((u) => {
        const times = Number(u.timesTypical.toFixed(1));
        return (
          <Pressable
            key={u.transactionId} style={styles.item} accessibilityRole="button"
            accessibilityLabel={`${u.label}, ${fmtCurrency(u.amount)}, ${shortDate(u.date)}, ${u.category}, about ${times} times the usual ${fmtCurrency(u.typicalAmount)}`}
            accessibilityHint="Opens that day's transactions"
            onPress={() => onOpen({ dateFrom: u.date, dateTo: u.date, categoryName: u.category, label: u.label })}
          >
            <View style={styles.line}>
              <Text style={[typography.bodyM, styles.name, { color: c.ink }]} numberOfLines={1}>{u.label}</Text>
              <Text style={[typography.bodyM, { color: c.ink }]}>{fmtCurrency(u.amount)}</Text>
            </View>
            <Text style={[typography.caption, { color: c.mutedInk }]}>
              {shortDate(u.date)} · {u.category} · about {times} times the usual {fmtCurrency(u.typicalAmount)}
            </Text>
          </Pressable>
        );
      })}
      <Text style={[typography.caption, { color: c.mutedInk }]}>
        A purchase shows here when it is at least three times the middle amount of your other purchases in the same category, and a meaningful part of the period&apos;s spending. It is a prompt to look, not a sign that anything is wrong.
      </Text>
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  item: { gap: 2, marginBottom: spacing.md, minHeight: 44 },
  line: { flexDirection: 'row', justifyContent: 'space-between', gap: spacing.sm },
  name: { flex: 1 },
});
