import { StyleSheet, Text, View } from 'react-native';
import { fmtCurrency } from '../../lib/format';
import { formatShare, type MethodBreakdown, type MethodRow } from '../../lib/reportSections/methodBreakdown';
import { spacing, typography, useTheme } from '../../theme';
import { DashboardCard } from '../dashboard/DashboardCard';
import { SectionHeading } from '../Card';

const spoken = (r: MethodRow) =>
  `${r.label}: ${fmtCurrency(r.amount)}, ${formatShare(r.share).replace('<1%', 'under 1 percent').replace('%', ' percent')} of spending`;

/**
 * How the month's spending was paid, ranked, with each method's share of the whole. The methods are
 * read from the account type and the wording on the statements, so some spending names none: it is
 * shown as its own "Not identified" row with its share rather than spread over the others, and a
 * line under the card says where the methods come from. The card is not drawn at all when too little
 * of the spending has a named method (see methodBreakdown).
 */
export function PaymentMethods({ breakdown }: { breakdown: MethodBreakdown | null }) {
  const c = useTheme();
  if (!breakdown) return null;
  const largest = breakdown.named[0].amount;
  return (
    <DashboardCard testID="payment-methods">
      <SectionHeading title="How you paid" />
      {breakdown.named.map((r) => (
        <View key={r.method} style={styles.row} accessible accessibilityLabel={spoken(r)}>
          <View style={styles.line}>
            <Text style={[typography.bodyM, styles.name, { color: c.ink }]} numberOfLines={1}>{r.label}</Text>
            <Text style={[typography.bodyM, { color: c.ink }]}>
              {fmtCurrency(r.amount)} <Text style={[typography.caption, { color: c.mutedInk }]}>{formatShare(r.share)}</Text>
            </Text>
          </View>
          <View style={[styles.track, { backgroundColor: c.border }]}>
            <View style={{ width: `${(r.amount / largest) * 100}%`, height: '100%', borderRadius: 3, backgroundColor: c.primary }} />
          </View>
        </View>
      ))}
      {breakdown.unidentified ? (
        <View
          testID="payment-methods-unidentified" style={[styles.unidentified, { borderTopColor: c.border }]}
          accessible accessibilityLabel={spoken(breakdown.unidentified)}
        >
          <Text style={[typography.bodyS, { color: c.mutedInk }]}>{breakdown.unidentified.label}</Text>
          <Text style={[typography.bodyS, { color: c.mutedInk }]}>
            {fmtCurrency(breakdown.unidentified.amount)} {formatShare(breakdown.unidentified.share)}
          </Text>
        </View>
      ) : null}
      <Text style={[typography.caption, { color: c.mutedInk }]}>
        Worked out from the account and the wording on your statements. Not every bank prints how a payment was made.
      </Text>
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  row: { gap: 4, marginBottom: spacing.sm },
  line: { flexDirection: 'row', justifyContent: 'space-between', gap: spacing.sm },
  name: { flex: 1 },
  track: { height: 6, borderRadius: 3, overflow: 'hidden' },
  unidentified: {
    flexDirection: 'row', justifyContent: 'space-between', gap: spacing.sm,
    borderTopWidth: StyleSheet.hairlineWidth, paddingTop: spacing.sm, marginBottom: spacing.sm,
  },
});
