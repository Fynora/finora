import { StyleSheet, Text, View } from 'react-native';
import Svg, { Circle, Defs, LinearGradient, Path, Stop } from 'react-native-svg';
import { DashboardCard } from './DashboardCard';
import { DeltaChip } from './DeltaChip';
import type { CashFlowPoint } from '../charts/CashFlowChart';
import { averageMonthlySavings, deriveNetSavingsSeries } from '../../lib/dashboardMetrics';
import { fmtCurrency } from '../../lib/format';
import { spacing, typography, useTheme } from '../../theme';

const WIDTH = 320;
const HEIGHT = 72;
const PAD = 4; // keeps the 2 point stroke inside the viewBox at the extremes

/**
 * Card redesign (2026-10-10): full width, the average as the headline and the net-savings trend
 * as an area chart under it. Before that it was a half-width card beside Accounts.
 */
export function CashFlowMiniCard({
  points, deltaPct, deltaLabel,
}: {
  points: CashFlowPoint[];
  deltaPct: number | null;
  // What deltaPct compares against -- useDashboardKpis's deltaLabel, since it is the summary's
  // netDeltaPct: "vs last month" only when the reporting month is the current one.
  deltaLabel: string;
}) {
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
  const xAt = (i: number) => (series.length <= 1 ? WIDTH / 2 : PAD + (i / (series.length - 1)) * (WIDTH - PAD * 2));
  const yAt = (v: number) => HEIGHT - PAD - ((v - min) / range) * (HEIGHT - PAD * 2);
  const line = series.map((s, i) => `${i === 0 ? 'M' : 'L'} ${xAt(i)} ${yAt(s.net)}`).join(' ');
  const area = `${line} L ${xAt(series.length - 1)} ${HEIGHT} L ${xAt(0)} ${HEIGHT} Z`;
  const average = averageMonthlySavings(points);

  return (
    <DashboardCard style={styles.card}>
      <View style={styles.top}>
        <View style={styles.text}>
          <Text style={[typography.eyebrow, { color: c.mutedInk }]}>Cash Flow Trend</Text>
          <Text style={[typography.numberL, { color: c.ink }]} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.7}>
            {fmtCurrency(average)}
          </Text>
          {/* States the actual window this average spans -- points.length tracks whatever range
              is currently selected on the full Cash Flow card below (they share cashFlowRange
              state), so this stays honest regardless of which chip is picked there, rather than
              a fixed "6 months" that would silently go wrong the moment that selection changes. */}
          <Text style={[typography.caption, { color: c.mutedInk }]}>
            Average Monthly Savings ({points.length} mo{points.length === 1 ? '' : 's'})
          </Text>
        </View>
        {deltaPct !== null ? (
          // The comparison is named right under the chip, never left as a bare percentage: this
          // compares the reporting month's net cash flow against the one before it
          // (summary.netDeltaPct), a DIFFERENT comparison than the multi-month average beside it.
          // Unlabeled, the pair reads as if the average itself moved by this percentage.
          <View style={styles.delta}>
            <DeltaChip delta={deltaPct} good={deltaPct >= 0} align="end" />
            <Text style={[typography.caption, styles.deltaLabel, { color: c.mutedInk }]}>{deltaLabel}</Text>
          </View>
        ) : null}
      </View>
      <Svg
        testID="cash-flow-trend-chart"
        width="100%"
        height={HEIGHT}
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        preserveAspectRatio="none"
      >
        <Defs>
          <LinearGradient id="cashFlowTrendFill" x1="0" y1="0" x2="0" y2="1">
            <Stop offset="0" stopColor={c.success} stopOpacity={0.22} />
            <Stop offset="1" stopColor={c.success} stopOpacity={0} />
          </LinearGradient>
        </Defs>
        {series.length > 1 ? (
          <>
            <Path d={area} fill="url(#cashFlowTrendFill)" />
            <Path d={line} fill="none" stroke={c.success} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
          </>
        ) : (
          // One month is a point, not a line; a path with a single "M" draws nothing at all.
          <Circle testID="cash-flow-trend-dot" cx={xAt(0)} cy={yAt(series[0].net)} r={3} fill={c.success} />
        )}
      </Svg>
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  card: { gap: spacing.md },
  top: { flexDirection: 'row', justifyContent: 'space-between', gap: spacing.ms },
  text: { flex: 1, gap: spacing.xs },
  delta: { alignItems: 'flex-end', gap: spacing.xs, maxWidth: 150 },
  deltaLabel: { textAlign: 'right' },
});
