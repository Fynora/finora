import { StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import type { RecurringItem } from '../../api/endpoints';
import { Card, SectionHeading } from '../Card';
import { fmtCurrency, fromLocalDateString, withSep } from '../../lib/format';
import { radius, spacing, typography, useTheme } from '../../theme';

interface Props {
  rows: { item: RecurringItem; past: boolean }[];
  stillToGoOut: number;
  /** The newest statement period end across the user's accounts (YYYY-MM-DD), or null. */
  latestStatementEnd: string | null;
}

/** "5 Oct": a day and a short month, Sep never Sept. */
function dayMonth(date: string): string {
  return withSep(fromLocalDateString(date).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' }));
}

/** "October": the month a date falls in, in full. */
function monthName(date: string): string {
  return fromLocalDateString(date).toLocaleDateString('en-US', { month: 'long' });
}

/**
 * "Coming up": repeating payments by their next expected date. The data stops at the latest
 * statement, so a date already past may have been paid: it reads "expected around", is greyed, is
 * left out of what is still to go out, and the note says which statement would settle it.
 */
export function ComingUpSection({ rows, stillToGoOut, latestStatementEnd }: Props) {
  const c = useTheme();
  if (rows.length === 0) return null;
  const firstPast = rows.find((r) => r.past);
  return (
    <View>
      <SectionHeading title="Coming up" />
      <Card>
        {rows.map(({ item, past }, i) => {
          const when = past ? `expected around ${dayMonth(item.nextEstimate)}` : `around ${dayMonth(item.nextEstimate)}`;
          const ink = past ? c.mutedInk : c.ink;
          return (
            <View
              key={item.merchant}
              accessible
              accessibilityLabel={`${item.merchant}, ${fmtCurrency(item.averageAmount)}, ${when}${past ? ', may already be paid' : ''}`}
              style={[styles.row, i > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: c.border }]}
            >
              <View style={[styles.icon, { backgroundColor: past ? c.inputBg : c.primaryLight }]}>
                <Ionicons name="calendar-outline" size={16} color={past ? c.mutedInk : c.primary} />
              </View>
              <View style={styles.text}>
                <Text style={[typography.labelM, { color: ink }]}>{item.merchant}</Text>
                <Text style={[typography.bodyS, { color: c.mutedInk }]}>{when}</Text>
              </View>
              <Text style={[typography.numberS, { color: ink }]}>{fmtCurrency(item.averageAmount)}</Text>
            </View>
          );
        })}
        <View style={[styles.total, { borderTopColor: c.border }]}>
          <Text style={[typography.bodyM, styles.text, { color: c.mutedInk }]}>Still to go out this month</Text>
          <Text style={[typography.numberS, { color: c.ink }]}>{fmtCurrency(stillToGoOut)}</Text>
        </View>
      </Card>
      {firstPast ? (
        <Text style={[typography.bodyS, styles.note, { color: c.mutedInk }]}>
          {latestStatementEnd
            ? `Your latest statement ends ${dayMonth(latestStatementEnd)}. Upload ${monthName(firstPast.item.nextEstimate)}'s to see if these were paid.`
            : 'Upload your latest statements to see if these were paid.'}
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, paddingVertical: spacing.sm, minHeight: 44 },
  icon: { width: 28, height: 28, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  text: { flex: 1 },
  total: {
    flexDirection: 'row', alignItems: 'center', gap: spacing.sm, paddingVertical: spacing.sm,
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  note: { marginTop: spacing.xs, marginHorizontal: spacing.xs },
});
