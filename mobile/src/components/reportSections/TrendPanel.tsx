import { StyleSheet, Text, View } from 'react-native';
import Svg, { G, Line, Rect, Text as SvgText } from 'react-native-svg';
import type { ReportTrendPoint } from '../../api/endpoints';
import { fmtCurrency } from '../../lib/format';
import { averageSpend, shortMonth, trendCeiling } from '../../lib/reportSections/trend';
import { spacing, typography, useTheme } from '../../theme';
import { DashboardCard } from '../dashboard/DashboardCard';
import { SectionHeading } from '../Card';

const WIDTH = 320;
const HEIGHT = 150;
const BASELINE = 124;
const TOP = 12;
const PLOT = BASELINE - TOP;
const BAR = 14;
const SIDE = 8;

const barHeight = (value: number, ceiling: number) => (value > 0 ? Math.max((value / ceiling) * PLOT, 1.5) : 0);

/**
 * Income and expenses for the last months, as paired bars, with a dashed line at the average spend of
 * the months that are whole. A month only partly in the user's data is a dashed outline and starred:
 * its bars are real but lower than the month's true totals, so it is shown, not dropped. Drawn from
 * two months up: one month is not a trend.
 */
export function TrendPanel({ points, selectedMonth }: { points: ReportTrendPoint[] | null; selectedMonth: string }) {
  const c = useTheme();
  if (!points || points.length < 2) return null;
  const ceiling = trendCeiling(points);
  const average = averageSpend(points);
  const group = (WIDTH - SIDE * 2) / points.length;
  const anyPartial = points.some((p) => !p.complete);
  const summary = points
    .map((p) => `${shortMonth(p.month)}${p.complete ? '' : ' (only part of the month is in)'}: income ${fmtCurrency(p.income)}, expenses ${fmtCurrency(p.expense)}`)
    .join('. ');
  const y = (v: number) => BASELINE - (v / ceiling) * PLOT;

  return (
    <DashboardCard testID="trend-panel">
      <SectionHeading title={`Income and expenses, last ${points.length} months`} />
      <View testID="trend-chart" accessible accessibilityRole="image" accessibilityLabel={summary}>
        <Svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} style={styles.svg}>
          <Line x1={SIDE} x2={WIDTH - SIDE} y1={BASELINE} y2={BASELINE} stroke={c.border} strokeWidth={1} />
          {points.map((p, i) => {
            const cx = SIDE + group * (i + 0.5);
            const income = barHeight(p.income, ceiling);
            const expense = barHeight(p.expense, ceiling);
            const fill = (color: string) => (p.complete
              ? { fill: color }
              : { fill: 'none', stroke: color, strokeWidth: 1.5, strokeDasharray: '4 3' });
            return (
              <G key={p.month}>
                <Rect x={cx - BAR - 2} y={BASELINE - income} width={BAR} height={income} rx={2} {...fill(c.success)} />
                <Rect x={cx + 2} y={BASELINE - expense} width={BAR} height={expense} rx={2} {...fill(c.danger)} />
                <SvgText
                  x={cx} y={BASELINE + 17} fontSize={11} textAnchor="middle"
                  fill={p.month === selectedMonth ? c.ink : c.mutedInk} fontWeight={p.month === selectedMonth ? '700' : '400'}
                >
                  {`${shortMonth(p.month)}${p.complete ? '' : '*'}`}
                </SvgText>
              </G>
            );
          })}
          {average !== null ? (
            <Line x1={SIDE} x2={WIDTH - SIDE} y1={y(average)} y2={y(average)} stroke={c.danger} strokeOpacity={0.7} strokeWidth={1} strokeDasharray="5 4" />
          ) : null}
        </Svg>
      </View>
      <View style={styles.legend}>
        <Text style={[typography.caption, { color: c.mutedInk }]}>● Income  ● Expenses</Text>
        {average !== null ? (
          <Text testID="trend-average-note" style={[typography.caption, { color: c.mutedInk }]}>
            Dashed line: average spend, {fmtCurrency(average)}, over the months that are whole
          </Text>
        ) : null}
        {anyPartial ? (
          <Text testID="trend-partial-note" style={[typography.caption, { color: c.mutedInk }]}>
            * Only part of the month is in, so its bars run lower than the real totals
          </Text>
        ) : null}
      </View>
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  svg: { width: '100%', aspectRatio: WIDTH / HEIGHT },
  legend: { gap: 2, marginTop: spacing.xs },
});
