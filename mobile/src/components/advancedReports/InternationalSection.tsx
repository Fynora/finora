import { StyleSheet, Text, View } from 'react-native';
import type { InternationalSpend } from '../../api/endpoints';
import { fmtCurrency, fmtForeignAmount } from '../../lib/format';
import { spacing, typography, useTheme } from '../../theme';
import { DashboardCard } from '../dashboard/DashboardCard';
import { SectionHeading } from '../Card';

/**
 * Spend the statements themselves marked international (an "International Transactions" table), split
 * into foreign-currency purchases and everything else (GST, FX markup, anything billed in rupees --
 * never labelled "fees", since a rupee-billed purchase lands there too), with purchases grouped by the
 * currency they were made in. Rupee figures are what was billed. Only what the statements list is
 * counted: nothing is inferred from a merchant's name.
 */
export function InternationalSection({ data }: { data: InternationalSpend }) {
  const c = useTheme();
  const max = Math.max(...data.byCurrency.map((x) => x.rupeeTotal), 1);
  return (
    <DashboardCard testID="international">
      <SectionHeading title="International spend" />
      {data.transactionCount === 0 ? (
        <Text style={[typography.bodyS, { color: c.mutedInk }]}>No international spend. Card transactions your statement lists under International Transactions will appear here.</Text>
      ) : (
        <View style={styles.body}>
          <View style={styles.stats}>
            {[
              { label: 'Total', value: data.totalSpend, sub: `${data.transactionCount} txns` },
              { label: 'Foreign purchases', value: data.purchasesSpend },
              { label: 'Other charges', value: data.otherChargesSpend },
            ].map((s) => (
              <View key={s.label} style={styles.stat}>
                <Text style={[typography.eyebrow, { color: c.mutedInk }]}>{s.label}</Text>
                <Text style={[typography.numberS, { color: c.ink }]}>{fmtCurrency(s.value)}</Text>
                {s.sub ? <Text style={[typography.caption, { color: c.mutedInk }]}>{s.sub}</Text> : null}
              </View>
            ))}
          </View>
          <Text style={[typography.caption, { color: c.mutedInk }]}>
            Other charges are international rows with no foreign amount printed: GST, FX markup, and anything billed in rupees.
          </Text>
          {data.byCurrency.map((cur) => (
            <View key={cur.currency} style={styles.currency} accessible accessibilityLabel={`${cur.currency}: ${fmtCurrency(cur.rupeeTotal)} billed, ${fmtForeignAmount(cur.currency, cur.foreignTotal)}, ${cur.transactionCount} transactions`}>
              <View style={styles.line}>
                <Text style={[typography.bodyM, { color: c.ink }]}>{cur.currency}</Text>
                <Text style={[typography.bodyM, { color: c.ink }]}>{fmtCurrency(cur.rupeeTotal)}</Text>
              </View>
              <View style={[styles.track, { backgroundColor: c.border }]}><View style={[styles.fill, { backgroundColor: c.primary, width: `${(cur.rupeeTotal / max) * 100}%` }]} /></View>
              <Text style={[typography.caption, { color: c.mutedInk }]}>{fmtForeignAmount(cur.currency, cur.foreignTotal)} · {cur.transactionCount} txns</Text>
            </View>
          ))}
        </View>
      )}
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  body: { gap: spacing.sm },
  stats: { flexDirection: 'row', gap: spacing.md },
  stat: { flex: 1, gap: 2 },
  currency: { gap: 4 },
  line: { flexDirection: 'row', justifyContent: 'space-between' },
  track: { height: 6, borderRadius: 3, overflow: 'hidden' },
  fill: { height: '100%', borderRadius: 3 },
});
