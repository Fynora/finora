import { StyleSheet, Text, View } from 'react-native';
import { barFillPercent } from '../../lib/chartGeometry';
import { spacing, useTheme } from '../../theme';

export interface HorizontalBarRow {
  key: string;
  label: string;
  sub: string;
  value: number;
}

interface Props {
  rows: HorizontalBarRow[];
  /** Bars scale against this when given (e.g. 100 for a percentage row) -- otherwise against the
   *  largest value actually in `rows`, matching web's identical RankedBarList (Top Merchants/Top
   *  Categories both scale relative to the list, Category Confidence scales against a fixed 100). */
  maxValue?: number;
  valueLabel: (value: number) => string;
  emptyMessage: string;
}

/**
 * Mobile counterpart to frontend/src/pages/AdvancedReports.tsx's RankedBarList -- a label, a bar
 * sized relative to `maxValue` (or the list's own max), and a trailing value. Plain Views with a
 * width percentage, not an SVG chart: unlike Spend Trend or Learning Growth, a ranked list has no
 * shared x/y axis to plot against, so there's nothing an SVG buys here that a styled View doesn't
 * already do simpler (same reasoning DashboardScreen's own category-breakdown rows use).
 */
export function HorizontalBarList({ rows, maxValue, valueLabel, emptyMessage }: Props) {
  const c = useTheme();

  if (rows.length === 0) {
    return <Text style={[styles.empty, { color: c.muted }]}>{emptyMessage}</Text>;
  }

  const max = maxValue ?? Math.max(...rows.map((r) => r.value), 1);

  return (
    <View style={styles.list}>
      {rows.map((r) => (
        <View key={r.key} style={styles.row}>
          <View style={styles.rowTop}>
            <Text style={[styles.label, { color: c.ink }]} numberOfLines={1}>{r.label}</Text>
            <Text style={[styles.sub, { color: c.muted }]}>{r.sub}</Text>
          </View>
          <View style={[styles.track, { backgroundColor: c.border }]}>
            <View style={[styles.fill, { backgroundColor: c.primary, width: `${barFillPercent(r.value, max)}%` }]} />
          </View>
          <Text style={[styles.value, { color: c.ink }]}>{valueLabel(r.value)}</Text>
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  empty: { fontSize: 13, textAlign: 'center', paddingVertical: spacing.md },
  list: { gap: spacing.sm },
  row: { gap: 2 },
  rowTop: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline', gap: spacing.sm },
  label: { fontSize: 14, fontWeight: '500', flexShrink: 1 },
  sub: { fontSize: 11, flexShrink: 0 },
  track: { height: 6, borderRadius: 3, overflow: 'hidden' },
  fill: { height: '100%', borderRadius: 3 },
  value: { fontSize: 12, fontWeight: '600', textAlign: 'right' },
});
