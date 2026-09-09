import { StyleSheet, Text, View } from 'react-native';
import Svg, { Polyline } from 'react-native-svg';
import { Card, SectionHeading } from '../Card';
import type { CashFlowPoint } from '../charts/CashFlowChart';
import { averageMonthlySavings, deriveNetSavingsSeries } from '../../lib/dashboardMetrics';
import { fmtCurrency } from '../../lib/format';
import { spacing, useTheme } from '../../theme';

const WIDTH = 280;
const HEIGHT = 56;

export function CashFlowMiniCard({ points, deltaPct }: { points: CashFlowPoint[]; deltaPct: number | null }) {
  const c = useTheme();

  // Renders nothing rather than its own "no data" copy -- covers loading, unavailable, and
  // genuinely-empty alike without needing to replicate the full Cash Flow card's three-way
  // loading/error/empty distinction here. That card (further down the screen) already explains
  // which of those it actually is; a second, identically-worded "No monthly data yet." here would
  // both duplicate that explanation and collide with its exact text in an accessibility query.
  if (points.length === 0) return null;

  const series = deriveNetSavingsSeries(points);
  const values = series.map((s) => s.net);
  const min = Math.min(...values, 0);
  const max = Math.max(...values, 0);
  const range = max - min || 1;
  const xAt = (i: number) => (series.length <= 1 ? 0 : (i / (series.length - 1)) * WIDTH);
  const yAt = (v: number) => HEIGHT - ((v - min) / range) * HEIGHT;
  const linePoints = series.map((s, i) => `${xAt(i)},${yAt(s.net)}`).join(' ');
  const average = averageMonthlySavings(points);

  return (
    <Card style={styles.card}>
      <SectionHeading title="Cash Flow Trend" />
      <Svg width="100%" height={HEIGHT} viewBox={`0 0 ${WIDTH} ${HEIGHT}`}>
        <Polyline points={linePoints} fill="none" stroke={c.success} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
      </Svg>
      <View style={styles.footer}>
        <View>
          {/* States the actual window this average spans -- points.length tracks whatever range
              is currently selected on the full Cash Flow card below (they share cashFlowRange
              state), so this stays honest regardless of which chip is picked there, rather than
              a fixed "6 months" that would silently go wrong the moment that selection changes. */}
          <Text style={[styles.label, { color: c.muted }]}>
            Average Monthly Savings ({points.length} mo{points.length === 1 ? '' : 's'})
          </Text>
          <Text style={[styles.value, { color: c.ink }]}>{fmtCurrency(average)}</Text>
        </View>
        {deltaPct !== null ? (
          // "vs last month" spelled out, not a bare percentage -- this compares the single most
          // recent month's net cash flow against the one before it (summary.netDeltaPct), a
          // DIFFERENT comparison than the multi-month average beside it. Left unlabeled, the pair
          // reads as if the average itself moved by this percentage, which it did not.
          <Text style={[styles.delta, { color: deltaPct >= 0 ? c.success : c.danger }]}>
            {deltaPct >= 0 ? '▲' : '▼'} {Math.abs(deltaPct).toFixed(1)}% vs last month
          </Text>
        ) : null}
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  card: {},
  footer: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-end', marginTop: spacing.sm },
  label: { fontSize: 11 },
  value: { fontSize: 18, fontWeight: '700', marginTop: 2 },
  delta: { fontSize: 13, fontWeight: '700' },
});
