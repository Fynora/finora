import { StyleSheet, Text, View } from 'react-native';
import { Card } from '../Card';
import { shareLabel, type HoldingLike, type PortfolioSummary } from '../../lib/investmentSummary';
import { spacing, typography, useTheme } from '../../theme';
import { GROUP_COLOR } from './groupColors';

/** The total, and how it is spread across types of holding. */
export function PortfolioCard<T extends HoldingLike>({ summary, subtitle, money }: {
  summary: PortfolioSummary<T>; subtitle: string | null; money: (n: number) => string;
}) {
  const c = useTheme();
  const mixLabel = summary.mix.map((m) => `${m.label} ${shareLabel(m.share)}`).join(', ');
  return (
    <Card style={styles.card}>
      <Text style={[typography.labelS, { color: c.mutedInk }]}>Total value</Text>
      {/* One line at any text size: a wrapped amount breaks at a comma and reads as two numbers. */}
      <Text style={[typography.numberL, { color: c.ink }]} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.5}>
        {money(summary.total)}
      </Text>
      {subtitle ? <Text style={[typography.bodyS, { color: c.mutedInk }]}>{subtitle}</Text> : null}
      {summary.mix.length > 0 ? (
        <>
          <View testID="portfolio-mix" accessible accessibilityRole="image" accessibilityLabel={mixLabel} style={styles.bar}>
            {summary.mix.map((m) => (
              <View key={m.key} style={{ width: `${m.share * 100}%`, backgroundColor: GROUP_COLOR[m.key] }} />
            ))}
          </View>
          {summary.mix.map((m) => (
            <View key={m.key} style={styles.legendRow}>
              <View style={styles.legendName}>
                <View style={[styles.dot, { backgroundColor: GROUP_COLOR[m.key] }]} />
                <Text style={[typography.labelS, styles.flexShrink, { color: c.ink }]}>{m.label}</Text>
              </View>
              <Text style={[typography.bodyS, { color: c.mutedInk }]}>{`${money(m.total)} · ${shareLabel(m.share)}`}</Text>
            </View>
          ))}
        </>
      ) : null}
    </Card>
  );
}

const styles = StyleSheet.create({
  card: { gap: spacing.xs },
  bar: { flexDirection: 'row', height: 10, gap: 2, borderRadius: 5, overflow: 'hidden', marginVertical: spacing.sm },
  legendRow: { flexDirection: 'row', justifyContent: 'space-between', flexWrap: 'wrap', gap: spacing.sm },
  legendName: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, flexShrink: 1 },
  flexShrink: { flexShrink: 1 },
  dot: { width: 8, height: 8, borderRadius: 4, flexShrink: 0 },
});
