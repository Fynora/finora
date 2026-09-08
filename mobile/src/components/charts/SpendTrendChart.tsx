import { StyleSheet, Text, View } from 'react-native';
import Svg, { Circle, Line } from 'react-native-svg';
import { fmtCurrency } from '../../lib/format';
import {
  CASHFLOW_HEIGHT, CASHFLOW_PAD_TOP, CASHFLOW_PLOT_HEIGHT, polylineLength, spendTrendScale, toSvgPoints,
} from '../../lib/chartGeometry';
import { spacing, useTheme } from '../../theme';
import { RevealPolyline } from './ChartReveal';

export interface SpendTrendPoint {
  /** Pre-formatted for display (e.g. "Mar '26") -- this component draws whatever label it's
   *  given rather than assuming a date shape, unlike TrendChart's own {date, value} (a real
   *  LocalDate string it formats itself). Advanced Reports' six trend points are each a bare
   *  "YYYY-MM" month, not a date, so there is no single day to format. */
  label: string;
  value: number;
}

/**
 * Mobile counterpart to frontend/src/pages/AdvancedReports.tsx's Spend Trend `<Line>`. Single
 * series, zero-anchored (see spendTrendScale's own doc comment for why that differs from
 * TrendChart's net-worth scale) -- otherwise the same draw-in technique as TrendChart/CashFlowChart.
 */
export function SpendTrendChart({ points, width }: { points: SpendTrendPoint[]; width: number }) {
  const c = useTheme();

  if (points.length === 0) {
    return <Text style={[styles.empty, { color: c.muted }]}>No trend yet.</Text>;
  }

  const { xAt, yAt } = spendTrendScale(points.map((p) => p.value), width);
  const linePoints = points.map((p, i) => ({ x: xAt(i), y: yAt(p.value) }));

  return (
    <View>
      <View
        accessible
        accessibilityLabel={`Spend trend over ${points.length} months. ${points
          .map((p) => `${p.label}: ${fmtCurrency(p.value)}`)
          .join('. ')}`}
      >
        <Svg width={width} height={CASHFLOW_HEIGHT}>
          <Line
            x1={0}
            y1={CASHFLOW_PAD_TOP + CASHFLOW_PLOT_HEIGHT}
            x2={width}
            y2={CASHFLOW_PAD_TOP + CASHFLOW_PLOT_HEIGHT}
            stroke={c.border}
            strokeWidth={1}
          />
          <RevealPolyline
            points={toSvgPoints(linePoints)}
            length={polylineLength(linePoints)}
            color={c.primary}
            strokeWidth={2}
          />
          {points.map((p, i) => (
            <Circle key={p.label} cx={xAt(i)} cy={yAt(p.value)} r={3} fill={c.primary} />
          ))}
        </Svg>
      </View>

      <View style={[styles.axis, { width }]} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
        {points.map((p) => (
          <Text key={p.label} style={[styles.axisLabel, { color: c.muted }]} numberOfLines={1}>
            {p.label}
          </Text>
        ))}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  axis: { flexDirection: 'row', justifyContent: 'space-between', marginTop: -18 },
  axisLabel: { fontSize: 9, flex: 1, textAlign: 'center' },
  empty: { fontSize: 13, textAlign: 'center', paddingVertical: spacing.md },
});
