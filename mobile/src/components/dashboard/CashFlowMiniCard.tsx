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

  if (points.length === 0) {
    return (
      <Card style={styles.card}>
        <SectionHeading title="Cash Flow Trend" />
        <Text style={[styles.empty, { color: c.muted }]}>No monthly data yet.</Text>
      </Card>
    );
  }

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
          <Text style={[styles.label, { color: c.muted }]}>Average Monthly Savings</Text>
          <Text style={[styles.value, { color: c.ink }]}>{fmtCurrency(average)}</Text>
        </View>
        {deltaPct !== null ? (
          <Text style={[styles.delta, { color: deltaPct >= 0 ? c.success : c.danger }]}>
            {deltaPct >= 0 ? '▲' : '▼'} {Math.abs(deltaPct).toFixed(1)}%
          </Text>
        ) : null}
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  card: {},
  empty: { fontSize: 13, textAlign: 'center', paddingVertical: spacing.md },
  footer: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-end', marginTop: spacing.sm },
  label: { fontSize: 11 },
  value: { fontSize: 18, fontWeight: '700', marginTop: 2 },
  delta: { fontSize: 13, fontWeight: '700' },
});
