import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Svg, { Circle, G, Line, Path, Rect, Text as SvgText } from 'react-native-svg';
import type { AdvancedMonth } from '../../api/endpoints';
import { compactAmount } from '../../lib/advancedReports/advancedFormat';
import {
  flowDomain, flowTicks, monthlyPoints, yearlyPoints, YEARLY_ABOVE_MONTHS, type FlowPoint,
} from '../../lib/advancedReports/cashFlow';
import { fmtCurrency } from '../../lib/format';
import { spacing, typography, useTheme } from '../../theme';
import { DashboardCard } from '../dashboard/DashboardCard';
import { SectionHeading } from '../Card';

const WIDTH = 320;
const HEIGHT = 190;
const LEFT = 40;
const RIGHT = 6;
const TOP = 10;
const BOTTOM = 24;
const PLOT_W = WIDTH - LEFT - RIGHT;
const PLOT_H = HEIGHT - TOP - BOTTOM;

/**
 * Income and expenses for each month as paired bars with net cash flow as a line, drawn at the width
 * of a phone. More than a year is drawn a calendar year at a time, and the toggle says so. A month
 * that is only part of a month is a dashed, starred outline. "View as list" gives the same figures as
 * rows, which is also what a screen reader is read.
 */
export function FlowChart({ months, incompleteNote }: { months: AdvancedMonth[]; incompleteNote: string | null }) {
  const c = useTheme();
  const [grain, setGrain] = useState<'monthly' | 'yearly'>(months.length > YEARLY_ABOVE_MONTHS ? 'yearly' : 'monthly');
  const [asList, setAsList] = useState(false);
  if (months.length === 0) return null;
  const yearly = months.length > YEARLY_ABOVE_MONTHS && grain === 'yearly';
  const points = yearly ? yearlyPoints(months) : monthlyPoints(months);
  const domain = flowDomain(points);
  const span = domain.hi - domain.lo;
  const y = (v: number) => TOP + PLOT_H - ((v - domain.lo) / span) * PLOT_H;
  const group = PLOT_W / points.length;
  const barW = Math.min(11, group * 0.36);
  const cx = (i: number) => LEFT + group * (i + 0.5);
  const labelEvery = Math.ceil(points.length / 7);
  const netPath = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${cx(i).toFixed(1)},${y(p.net).toFixed(1)}`).join(' ');
  const anyPartial = points.some((p) => !p.complete);
  const summary = points
    .map((p) => `${p.name}${p.complete ? '' : ', only part is in'}: income ${fmtCurrency(p.income)}, expenses ${fmtCurrency(p.expense)}, net ${fmtCurrency(p.net)}`)
    .join('. ');

  return (
    <DashboardCard testID="flow-card">
      <SectionHeading title="Income vs expenses" />
      <View style={styles.controls}>
        {months.length > 12 && (['monthly', 'yearly'] as const).map((g) => (
          <Pressable
            key={g} onPress={() => setGrain(g)} accessibilityRole="button" accessibilityState={{ selected: grain === g }}
            accessibilityLabel={`Show ${g}`} style={[styles.chip, { borderColor: c.border }, grain === g && { backgroundColor: c.primary }]}
          >
            <Text style={[typography.labelS, { color: grain === g ? c.onPrimary : c.ink }]}>{g === 'monthly' ? 'Monthly' : 'Yearly'}</Text>
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
        <View testID="flow-list">
          {points.map((p) => (
            <View key={p.key} style={[styles.listRow, { borderBottomColor: c.border }]} accessible accessibilityLabel={`${p.name}: income ${fmtCurrency(p.income)}, expenses ${fmtCurrency(p.expense)}, net ${fmtCurrency(p.net)}`}>
              <Text style={[typography.labelM, { color: c.ink }]}>{p.name}{p.complete ? '' : '*'}</Text>
              <Text style={[typography.bodyS, { color: c.mutedInk }]}>
                {fmtCurrency(p.income)} in · {fmtCurrency(p.expense)} out · net {fmtCurrency(p.net)}
              </Text>
            </View>
          ))}
        </View>
      ) : (
        <View accessible accessibilityRole="image" accessibilityLabel={summary} testID="flow-chart">
          <Svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} style={styles.svg}>
            {flowTicks(domain, 3).map((t) => (
              <G key={t}>
                <Line x1={LEFT} x2={WIDTH - RIGHT} y1={y(t)} y2={y(t)} stroke={c.border} strokeWidth={1} strokeDasharray={t === 0 ? undefined : '3 4'} />
                <SvgText x={LEFT - 6} y={y(t) + 4} fontSize={10} textAnchor="end" fill={c.mutedInk}>{compactAmount(t)}</SvgText>
              </G>
            ))}
            {points.map((p: FlowPoint, i) => {
              const bar = (value: number, color: string, offset: number) => {
                const top = y(Math.max(value, 0));
                const height = Math.max(Math.abs(y(value) - y(0)), value > 0 ? 1.5 : 0);
                const fill = p.complete
                  ? { fill: color }
                  : { fill: 'none', stroke: color, strokeWidth: 1.5, strokeDasharray: '4 3' };
                return <Rect x={cx(i) + offset} y={top} width={barW} height={height} rx={2} {...fill} />;
              };
              return (
                <G key={p.key}>
                  {bar(p.income, c.success, -barW - 1)}
                  {bar(p.expense, c.danger, 1)}
                  {i % labelEvery === 0 ? (
                    <SvgText x={cx(i)} y={HEIGHT - 8} fontSize={10} textAnchor="middle" fill={c.mutedInk}>{`${p.label}${p.complete ? '' : '*'}`}</SvgText>
                  ) : null}
                </G>
              );
            })}
            <Path d={netPath} fill="none" stroke={c.ink} strokeWidth={2} strokeLinejoin="round" />
            {points.map((p, i) => <Circle key={p.key} cx={cx(i)} cy={y(p.net)} r={2.6} fill={c.card} stroke={c.ink} strokeWidth={1.5} />)}
          </Svg>
        </View>
      )}

      <Text style={[typography.caption, styles.legend, { color: c.mutedInk }]}>● Income  ● Expenses  ━● Net cash flow</Text>
      {anyPartial ? (
        <Text style={[typography.caption, { color: c.mutedInk }]} testID="flow-partial-note">
          * {yearly ? 'Only part of the year is in the period' : 'Only part of the month is in'}, so its bars run lower than the real figures.
        </Text>
      ) : null}
      {incompleteNote && !asList ? <Text style={[typography.caption, { color: c.mutedInk }]}>{incompleteNote}</Text> : null}
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  controls: { flexDirection: 'row', gap: spacing.sm, justifyContent: 'flex-end', marginBottom: spacing.sm },
  chip: { minHeight: 32, paddingHorizontal: spacing.sm, borderRadius: 16, borderWidth: StyleSheet.hairlineWidth, alignItems: 'center', justifyContent: 'center' },
  svg: { width: '100%', aspectRatio: WIDTH / HEIGHT },
  legend: { marginTop: spacing.xs },
  listRow: { paddingVertical: spacing.sm, borderBottomWidth: StyleSheet.hairlineWidth, gap: 2 },
});
