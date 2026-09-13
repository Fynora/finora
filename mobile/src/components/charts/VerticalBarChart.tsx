import { StyleSheet, Text, View } from 'react-native';
import { barFillPercent } from '../../lib/chartGeometry';
import { spacing, useTheme } from '../../theme';

export interface VerticalBarPoint {
  /** Pre-formatted for display (e.g. "Apr") -- this component draws whatever label it's given
   *  rather than assuming a date shape, same reasoning as SpendTrendChart's own {label, value}. */
  label: string;
  value: number;
}

const BAR_CHART_HEIGHT = 120;

/**
 * A month-by-month bar chart with plain Views, not SVG -- same reasoning HorizontalBarList's own
 * doc comment gives for its ranked rows: each bar's height is just a percentage of a fixed track,
 * which a styled View already does simpler than an SVG chart would. Used by the Insights screen's
 * Income tab, whose mockup calls for vertical bars rather than the line style SpendTrendChart/
 * TrendChart already use elsewhere.
 */
export function VerticalBarChart({
  points, width, valueLabel,
}: {
  points: VerticalBarPoint[];
  width: number;
  valueLabel: (value: number) => string;
}) {
  const c = useTheme();

  if (points.length === 0) {
    return <Text style={[styles.empty, { color: c.muted }]}>No trend yet.</Text>;
  }

  const max = Math.max(...points.map((p) => p.value), 1);
  const columnWidth = width / points.length;

  return (
    <View
      accessible
      accessibilityLabel={`Trend over ${points.length} months. ${points
        .map((p) => `${p.label}: ${valueLabel(p.value)}`)
        .join('. ')}`}
    >
      <View style={styles.row}>
        {points.map((p, i) => (
          <View key={i} style={[styles.column, { width: columnWidth }]}>
            <Text style={[styles.value, { color: c.ink }]} numberOfLines={1}>{valueLabel(p.value)}</Text>
            <View style={[styles.track, { backgroundColor: c.border }]}>
              <View
                style={[
                  styles.bar,
                  { height: `${barFillPercent(p.value, max)}%`, backgroundColor: c.primary },
                ]}
              />
            </View>
            <Text style={[styles.label, { color: c.muted }]} numberOfLines={1}>{p.label}</Text>
          </View>
        ))}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  empty: { fontSize: 13, textAlign: 'center', paddingVertical: spacing.md },
  row: { flexDirection: 'row', alignItems: 'flex-end' },
  column: { alignItems: 'center', gap: 4 },
  value: { fontSize: 11, fontWeight: '600' },
  track: {
    width: 20, height: BAR_CHART_HEIGHT, borderRadius: 4, overflow: 'hidden', justifyContent: 'flex-end',
  },
  bar: { width: '100%', borderRadius: 4 },
  label: { fontSize: 11 },
});
