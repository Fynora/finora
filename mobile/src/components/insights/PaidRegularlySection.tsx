import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import type { RecurringItem } from '../../api/endpoints';
import { Card, SectionHeading } from '../Card';
import { fmtCurrency } from '../../lib/format';
import { radius, spacing, typography, useTheme } from '../../theme';

interface Props {
  rows: { item: RecurringItem; monthly: number; rising: boolean }[];
  monthlyTotal: number;
  yearlyTotal: number;
  /** An older month is picked: this list still describes every statement, and says so. */
  olderMonthPicked: boolean;
  onDismiss: (merchant: string) => void;
}

/**
 * "Paid regularly": every repeating payment the server detected -- rent, EMIs and SIPs as well as
 * subscriptions, hence not "Subscriptions" -- with the month's and the year's total.
 */
export function PaidRegularlySection({ rows, monthlyTotal, yearlyTotal, olderMonthPicked, onDismiss }: Props) {
  const c = useTheme();
  if (rows.length === 0) return null;
  return (
    <View>
      <SectionHeading title="Paid regularly" />
      <Text style={[typography.labelM, styles.totals, { color: c.ink }]}>
        {`${fmtCurrency(monthlyTotal)} a month · ${fmtCurrency(yearlyTotal)} a year`}
      </Text>
      {olderMonthPicked ? (
        <Text style={[typography.bodyS, styles.totals, { color: c.mutedInk }]}>Based on all your statements</Text>
      ) : null}
      <Card>
        {rows.map(({ item, rising }, i) => (
          <View
            key={item.merchant}
            style={[styles.row, i > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: c.border }]}
          >
            <View
              accessible
              accessibilityLabel={`${item.merchant}, ${item.label}, ${fmtCurrency(item.averageAmount)}${rising ? ', latest payment went up' : ''}`}
              style={styles.main}
            >
              <View style={styles.text}>
                <Text style={[typography.labelM, { color: c.ink }]}>{item.merchant}</Text>
                <Text style={[typography.bodyS, { color: c.mutedInk }]}>{`${item.label} · seen ${item.occurrences}×`}</Text>
              </View>
              <Text style={[typography.numberS, { color: c.ink }]}>
                {fmtCurrency(item.averageAmount)}
                {rising ? <Text style={{ color: c.dangerInk }}> ▲</Text> : null}
              </Text>
            </View>
            <Pressable
              onPress={() => onDismiss(item.merchant)}
              accessibilityRole="button"
              accessibilityLabel={`Not recurring: dismiss ${item.merchant}`}
              hitSlop={8}
              style={styles.dismiss}
            >
              <Ionicons name="close" size={16} color={c.mutedInk} />
            </Pressable>
          </View>
        ))}
      </Card>
    </View>
  );
}

const styles = StyleSheet.create({
  totals: { marginBottom: spacing.xs, marginHorizontal: spacing.xs },
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs, paddingVertical: spacing.sm, minHeight: 44 },
  main: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  text: { flex: 1 },
  dismiss: { width: 28, height: 28, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
});
