import { StyleSheet, Text, View } from 'react-native';
import Svg, { Circle } from 'react-native-svg';
import type { ReportData } from '../../api/endpoints';
import { fmtCurrency, monthLabelLong } from '../../lib/format';
import { comparisonGateNote, comparisonMonthName, netChangePhrase } from '../../lib/reportSections/comparison';
import { spacing, typography, useTheme } from '../../theme';
import { DashboardCard } from '../dashboard/DashboardCard';

const RING = 84;
const STROKE = 9;
const RADIUS = (RING - STROKE) / 2;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;

/**
 * The one sentence the month says about itself. The savings rate is the server's (the Dashboard's
 * own rule, carried on the report), never computed on the device: when the server withholds it,
 * this says why in plain words instead of showing a figure unclassified money would decide. Null
 * when there is nothing honest to say (an older server, or a month with no activity at all).
 */
export function savingsHeadline(report: ReportData): string | null {
  const pct = report.savingsRatePct;
  if (pct != null) {
    const rounded = Math.round(Math.abs(pct));
    if (rounded === 0) return 'You broke even this month.';
    return pct > 0 ? `You kept ${rounded}% of what you earned.` : `You spent ${rounded}% more than you earned.`;
  }
  switch (report.savingsRateGateReason) {
    case 'NO_INCOME':
      return report.expense > 0 ? 'No income was counted this month.' : null;
    case 'UNRESOLVED_EXCEEDS_INCOME':
      return "Money you received hasn't been sorted into income yet, so there is no savings rate.";
    default:
      return null;
  }
}

function SavingsRing({ pct }: { pct: number }) {
  const c = useTheme();
  // A negative rate fills nothing (the figure inside says -N%); only a positive one draws an arc.
  const filled = Math.min(Math.max(pct, 0), 100) / 100;
  return (
    <View testID="hero-ring" accessible accessibilityRole="image" accessibilityLabel={`Savings rate ${Math.round(pct)}%`} style={styles.ring}>
      <Svg width={RING} height={RING}>
        <Circle cx={RING / 2} cy={RING / 2} r={RADIUS} stroke={c.border} strokeWidth={STROKE} fill="none" />
        {filled > 0 ? (
          <Circle
            cx={RING / 2} cy={RING / 2} r={RADIUS} stroke={c.success} strokeWidth={STROKE} fill="none" strokeLinecap="round"
            strokeDasharray={`${filled * CIRCUMFERENCE} ${CIRCUMFERENCE}`} transform={`rotate(-90 ${RING / 2} ${RING / 2})`}
          />
        ) : null}
      </Svg>
      {/* The rate is not capped (a month of little income and a lot of spend reads in the thousands of
          percent), so the figure shrinks to the ring's inside rather than spilling over its edge. */}
      <Text
        style={[typography.numberS, styles.ringText, { color: c.ink }]} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.4}
      >
        {Math.round(pct)}%
      </Text>
    </View>
  );
}

/** Income split into what was spent and what was kept. Only drawn when income covers the spend: a bar
 *  of "spent" wider than the income it came from has no honest shape, and the headline says so. */
function KeptSpentBar({ income, expense }: { income: number; expense: number }) {
  const c = useTheme();
  const spentPct = Math.min((expense / income) * 100, 100);
  return (
    <View testID="hero-bar">
      <View
        style={styles.bar} accessible accessibilityRole="image"
        accessibilityLabel={`Of ${fmtCurrency(income)} earned, ${fmtCurrency(expense)} spent and ${fmtCurrency(income - expense)} kept`}
      >
        {spentPct > 0 ? <View style={{ width: `${spentPct}%`, backgroundColor: c.danger }} /> : null}
        {spentPct < 100 ? <View style={{ flex: 1, backgroundColor: c.success }} /> : null}
      </View>
      <View style={styles.legend}>
        <Text style={[typography.caption, { color: c.mutedInk }]}>● Spent {fmtCurrency(expense)}</Text>
        <Text style={[typography.caption, { color: c.mutedInk }]}>● Kept {fmtCurrency(income - expense)}</Text>
      </View>
    </View>
  );
}

export function ReportHero({ report }: { report: ReportData }) {
  const c = useTheme();
  const net = report.income - report.expense;
  const cmp = report.comparison;
  const priorName = cmp ? comparisonMonthName(cmp.previousMonth, report.month) : null;
  const compare = priorName ? netChangePhrase(cmp, priorName, fmtCurrency) : null;
  const gateNote = priorName && !compare
    ? comparisonGateNote(cmp, monthLabelLong(report.month).split(' ')[0], priorName)
    : null;
  const headline = savingsHeadline(report);
  const netColor = net > 0 ? c.successInk : net < 0 ? c.dangerInk : c.ink;
  // The bar is "kept" as a share of income, so it follows the rate's own gate: when the server
  // withholds the rate because the income itself is in doubt, the bar would show the same doubtful
  // share as a picture. An older server sends no reason, and then the bar is drawn as before.
  const showBar = report.income > 0 && report.expense <= report.income && !report.savingsRateGateReason;

  return (
    <DashboardCard testID="report-hero" style={styles.card}>
      <View style={styles.top}>
        {report.savingsRatePct != null ? <SavingsRing pct={report.savingsRatePct} /> : null}
        <View style={styles.text}>
          <Text style={[typography.eyebrow, { color: c.mutedInk }]}>Net cash flow</Text>
          <Text testID="hero-net" style={[typography.numberL, { color: netColor }]} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.7}>
            {net > 0 ? '+' : ''}{fmtCurrency(net)}
          </Text>
          {headline ? <Text testID="hero-headline" style={[typography.bodyM, { color: c.ink }]}>{headline}</Text> : null}
          {compare ? <Text testID="hero-compare" style={[typography.bodyS, { color: c.mutedInk }]}>{compare}.</Text> : null}
          {gateNote ? <Text testID="hero-compare-note" style={[typography.caption, { color: c.mutedInk }]}>{gateNote}</Text> : null}
        </View>
      </View>
      {showBar ? <KeptSpentBar income={report.income} expense={report.expense} /> : null}
      {/* Said once: when the comparison note already gives the reason this month is only partly in,
          a second line repeating it would just be noise. */}
      {report.complete === false && !gateNote ? (
        <Text testID="hero-partial" style={[typography.caption, { color: c.mutedInk }]}>
          Part of this month isn&apos;t in yet, so these figures may change.
        </Text>
      ) : null}
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  card: { gap: spacing.md },
  top: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: spacing.md },
  text: { flex: 1, minWidth: 180, gap: 2 },
  ring: { width: RING, height: RING, alignItems: 'center', justifyContent: 'center' },
  ringText: { position: 'absolute', maxWidth: RING - STROKE * 2 - 4, textAlign: 'center' },
  bar: { flexDirection: 'row', height: 12, borderRadius: 6, overflow: 'hidden', gap: 2 },
  legend: { flexDirection: 'row', justifyContent: 'space-between', marginTop: 6, gap: spacing.sm, flexWrap: 'wrap' },
});
