import { StyleSheet, Text, View } from 'react-native';
import Svg, { G, Rect } from 'react-native-svg';
import { spacing, useTheme } from '../../theme';

export interface LearningGrowthPoint {
  /** Pre-formatted for display (e.g. "Mar '26") -- same reasoning as SpendTrendChart's own point
   *  shape: the caller already knows how to format a "YYYY-MM" month, this component just draws. */
  label: string;
  learnedCount: number;
  correctedCount: number;
}

const HEIGHT = 150;
const PAD_TOP = 8;
const PAD_BOTTOM = 22;
const PLOT_HEIGHT = HEIGHT - PAD_TOP - PAD_BOTTOM;
const BAR_GAP = 4;

/**
 * Mobile counterpart to frontend/src/pages/AdvancedReports.tsx's Learning Growth `<Bar>`. A
 * stacked bar, not side-by-side bars: "of everything categorized this month, how much the engine
 * learned on its own vs. how much you corrected" is inherently a share-of-total question, which a
 * stack answers more directly than two bars the eye has to compare separately -- and it halves the
 * horizontal space each month needs, which matters more here than on web's fixed-width chart
 * since learningGrowth() is NOT capped to a trailing window (see that method's own doc comment) --
 * a long-lived account's history can run to many months on a phone-width screen.
 *
 * No draw-in animation, unlike the other hand-rolled charts here -- see ChartReveal.tsx's
 * RevealPolyline/RevealArc for why those need one (a donut/line chart's shape reads as more
 * "alive" filling in); a bar chart's static rectangles don't lose anything by rendering directly,
 * and this keeps a five-panel report page from stacking five animation timers on mount.
 */
export function LearningGrowthChart({ points, width }: { points: LearningGrowthPoint[]; width: number }) {
  const c = useTheme();

  if (points.length === 0) {
    return <Text style={[styles.empty, { color: c.muted }]}>No learning history yet.</Text>;
  }

  const max = Math.max(1, ...points.map((p) => p.learnedCount + p.correctedCount));
  const barWidth = Math.max(1, width / points.length - BAR_GAP);

  return (
    <View>
      <View
        accessible
        accessibilityLabel={`Learning growth over ${points.length} months. ${points
          .map((p) => `${p.label}: ${p.learnedCount} learned, ${p.correctedCount} corrected`)
          .join('. ')}`}
      >
        <Svg width={width} height={HEIGHT}>
          {points.map((p, i) => {
            const total = p.learnedCount + p.correctedCount;
            const totalHeight = (total / max) * PLOT_HEIGHT;
            const learnedHeight = total > 0 ? (p.learnedCount / total) * totalHeight : 0;
            const correctedHeight = totalHeight - learnedHeight;
            const x = i * (barWidth + BAR_GAP);
            const barBottom = PAD_TOP + PLOT_HEIGHT;
            return (
              <G key={p.label}>
                {/* Corrected sits on top of learned -- reading order top-to-bottom then matches
                    "learned first, corrected on top of it," the same order the legend lists them. */}
                <Rect
                  x={x} y={barBottom - totalHeight} width={barWidth} height={correctedHeight}
                  fill={c.warning} rx={2}
                />
                <Rect
                  x={x} y={barBottom - learnedHeight} width={barWidth} height={learnedHeight}
                  fill={c.success} rx={2}
                />
              </G>
            );
          })}
        </Svg>
      </View>

      <View style={[styles.axis, { width }]} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
        {points.map((p) => (
          <Text key={p.label} style={[styles.axisLabel, { color: c.muted }]} numberOfLines={1}>
            {p.label}
          </Text>
        ))}
      </View>

      <View style={styles.legend}>
        <View style={styles.legendItem}>
          <View style={[styles.swatch, { backgroundColor: c.success }]} />
          <Text style={[styles.legendText, { color: c.muted }]}>Learned</Text>
        </View>
        <View style={styles.legendItem}>
          <View style={[styles.swatch, { backgroundColor: c.warning }]} />
          <Text style={[styles.legendText, { color: c.muted }]}>Corrected</Text>
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  axis: { flexDirection: 'row', justifyContent: 'space-between', marginTop: 2 },
  axisLabel: { fontSize: 8, flex: 1, textAlign: 'center' },
  legend: { flexDirection: 'row', gap: spacing.md, marginTop: spacing.sm, justifyContent: 'center' },
  legendItem: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  swatch: { width: 10, height: 10, borderRadius: 2 },
  legendText: { fontSize: 11 },
  empty: { fontSize: 13, textAlign: 'center', paddingVertical: spacing.md },
});
