import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Svg, { G, Line, Path, Text as SvgText } from 'react-native-svg';
import type { AdvancedCategory, AdvancedMonth } from '../../api/endpoints';
import { compactAmount, monthShort, monthYear } from '../../lib/advancedReports/advancedFormat';
import { flowTicks, niceCeiling } from '../../lib/advancedReports/cashFlow';
import { fmtCurrency } from '../../lib/format';
import { spacing, typography, useTheme } from '../../theme';
import { DashboardCard } from '../dashboard/DashboardCard';
import { SectionHeading } from '../Card';

const WIDTH = 320;
const HEIGHT = 190;
const LEFT = 40;
const RIGHT = 8;
const TOP = 10;
const BOTTOM = 24;
const PLOT_W = WIDTH - LEFT - RIGHT;
const PLOT_H = HEIGHT - TOP - BOTTOM;
/** Lines are told apart by colour AND by dash, so the chart never rests on colour alone. */
const DASHES = [undefined, '6 4', '2 3', '10 3 2 3'];

/**
 * How the largest categories moved month by month across the period, one line each, with a list of
 * the same figures one tap away. Drawn from two months up: one month is not a trend. Three or four
 * lines at most, which is as many as a phone-width chart can tell apart.
 */
export function CategoryTrendChart({ months, categories }: { months: AdvancedMonth[]; categories: AdvancedCategory[] }) {
  const c = useTheme();
  const [count, setCount] = useState(3);
  const [asList, setAsList] = useState(false);
  if (months.length < 2 || categories.length === 0) return null;

  const colours = [c.primary, c.brass, c.danger, c.success];
  const series = categories.slice(0, count);
  const ceiling = niceCeiling(Math.max(...series.flatMap((s) => s.monthly), 0)) || 1;
  const y = (v: number) => TOP + PLOT_H - (v / ceiling) * PLOT_H;
  const x = (i: number) => LEFT + (PLOT_W * i) / (months.length - 1);
  const labelEvery = Math.ceil(months.length / 6);
  const summary = series
    .map((s) => `${s.name}: ${s.monthly.map((v, i) => `${monthShort(months[i].month)} ${fmtCurrency(v)}`).join(', ')}`)
    .join('. ');

  return (
    <DashboardCard testID="category-trend">
      <SectionHeading title="Spending trend by category" />
      <View style={styles.controls}>
        {[3, 4].filter((n) => n <= Math.max(categories.length, 3)).map((n) => (
          <Pressable
            key={n} onPress={() => setCount(n)} accessibilityRole="button" accessibilityState={{ selected: count === n }}
            accessibilityLabel={`Top ${n} categories`} style={[styles.chip, { borderColor: c.border }, count === n && { backgroundColor: c.primary }]}
          >
            <Text style={[typography.labelS, { color: count === n ? c.onPrimary : c.ink }]}>Top {n}</Text>
          </Pressable>
        ))}
        <Pressable
          onPress={() => setAsList((v) => !v)} accessibilityRole="button" accessibilityState={{ selected: asList }}
          accessibilityLabel={asList ? 'Show the chart' : 'Show as a list'} style={[styles.chip, { borderColor: c.border }, asList && { backgroundColor: c.primary }]}
        >
          <Text style={[typography.labelS, { color: asList ? c.onPrimary : c.ink }]}>{asList ? 'Chart' : 'List'}</Text>
        </Pressable>
      </View>

      {asList ? (
        <View testID="trend-list">
          {months.map((m, i) => (
            <View key={m.month} style={[styles.listRow, { borderBottomColor: c.border }]} accessible
              accessibilityLabel={`${monthYear(m.month)}: ${series.map((s) => `${s.name} ${fmtCurrency(s.monthly[i])}`).join(', ')}`}>
              <Text style={[typography.labelM, { color: c.ink }]}>{monthYear(m.month)}{m.complete ? '' : '*'}</Text>
              <Text style={[typography.bodyS, { color: c.mutedInk }]}>{series.map((s) => `${s.name} ${fmtCurrency(s.monthly[i])}`).join(' · ')}</Text>
            </View>
          ))}
        </View>
      ) : (
        <View accessible accessibilityRole="image" accessibilityLabel={summary} testID="trend-chart">
          <Svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} style={styles.svg}>
            {flowTicks({ lo: 0, hi: ceiling }, 3).map((t) => (
              <G key={t}>
                <Line x1={LEFT} x2={WIDTH - RIGHT} y1={y(t)} y2={y(t)} stroke={c.border} strokeWidth={1} strokeDasharray={t === 0 ? undefined : '3 4'} />
                <SvgText x={LEFT - 6} y={y(t) + 4} fontSize={10} textAnchor="end" fill={c.mutedInk}>{compactAmount(t)}</SvgText>
              </G>
            ))}
            {months.map((m, i) => (i % labelEvery === 0 ? (
              <SvgText key={m.month} x={x(i)} y={HEIGHT - 8} fontSize={10} textAnchor="middle" fill={c.mutedInk}>{`${monthShort(m.month)}${m.complete ? '' : '*'}`}</SvgText>
            ) : null))}
            {series.map((s, k) => (
              <Path
                key={s.name} fill="none" stroke={colours[k % colours.length]} strokeWidth={2} strokeDasharray={DASHES[k % DASHES.length]} strokeLinejoin="round"
                d={s.monthly.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ')}
              />
            ))}
          </Svg>
        </View>
      )}
      <View style={styles.legend} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
        {series.map((s, k) => (
          <View key={s.name} style={styles.legendItem}>
            <Svg width={26} height={10}>
              <Line x1={0} x2={26} y1={5} y2={5} stroke={colours[k % colours.length]} strokeWidth={2} strokeDasharray={DASHES[k % DASHES.length]} />
            </Svg>
            <Text style={[typography.caption, { color: c.mutedInk }]}>{s.name}</Text>
          </View>
        ))}
      </View>
      {months.some((m) => !m.complete) ? <Text style={[typography.caption, { color: c.mutedInk }]}>* Only part of the month is in your data.</Text> : null}
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  controls: { flexDirection: 'row', gap: spacing.sm, justifyContent: 'flex-end', marginBottom: spacing.sm },
  chip: { minHeight: 32, paddingHorizontal: spacing.sm, borderRadius: 16, borderWidth: StyleSheet.hairlineWidth, alignItems: 'center', justifyContent: 'center' },
  svg: { width: '100%', aspectRatio: WIDTH / HEIGHT },
  legend: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.md, marginTop: spacing.xs },
  legendItem: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  listRow: { paddingVertical: spacing.sm, borderBottomWidth: StyleSheet.hairlineWidth, gap: 2 },
});
