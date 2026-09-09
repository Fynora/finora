import { Pressable, StyleSheet, Text, View } from 'react-native';
import Svg, { Path } from 'react-native-svg';
import { AnimatedHealthScoreNumber } from '../AnimatedHealthScoreNumber';
import { healthBarColor, healthColor } from '../../lib/health';
import { HealthSparkline } from './HealthSparkline';
import { radius, spacing, useTheme } from '../../theme';
import type { HealthScorePoint } from '../../types';

const GAUGE_WIDTH = 240;
const GAUGE_HEIGHT = 130;
const GAUGE_CX = GAUGE_WIDTH / 2;
const GAUGE_CY = 118;
const GAUGE_R = 100;
const GAUGE_STROKE = 16;

/** score 0 -> 180deg (left), score 100 -> 0deg (right), sweeping over the top -- a standard
 *  semi-circle gauge. Not reused from lib/chartGeometry.ts's arcPath: that helper is fixed to
 *  the donut's own DONUT_CENTER/DONUT_RADIUS constants, not parameterized by center/radius, and
 *  this is the only place a semi-circle (rather than a full-circle arc) is needed. */
function pointAt(score: number) {
  const angle = (Math.PI * (100 - score)) / 100;
  return { x: GAUGE_CX + GAUGE_R * Math.cos(angle), y: GAUGE_CY - GAUGE_R * Math.sin(angle) };
}

function arcPath(fromScore: number, toScore: number): string {
  const start = pointAt(fromScore);
  const end = pointAt(toScore);
  return `M ${start.x} ${start.y} A ${GAUGE_R} ${GAUGE_R} 0 0 1 ${end.x} ${end.y}`;
}

interface Props {
  available: boolean;
  healthScore: number;
  healthLabel: string;
  healthScoreDeltaVsLastMonth: number | null;
  healthSparkline: HealthScorePoint[];
  healthScoreTransactionCount: number;
  healthScoreMinTransactions: number;
  onImportPress: () => void;
}

/**
 * The screen's hero card -- dark surface, semi-circle gauge, score/label, month-over-month delta
 * pill, 6-month sparkline. Below healthScoreTransactionCount's floor it shows the same onboarding
 * progress the old inline card did (same three text pieces the pinned test in
 * DashboardScreen.test.tsx checks for), plus a new "Continue Setup" CTA into Import.
 *
 * healthColor/healthBarColor (not a fixed red/amber/green triple) drive the score/label text and
 * the progress arc's own color, so this stays consistent with every other health-score display on
 * the screen (the breakdown rows, Categorization Confidence) -- only the gauge's background TRACK
 * is a fixed 3-band red/amber/green scale face, the same way a real gauge's dial doesn't change
 * color, only its needle does.
 */
export function HealthHero({
  available, healthScore, healthLabel, healthScoreDeltaVsLastMonth, healthSparkline,
  healthScoreTransactionCount, healthScoreMinTransactions, onImportPress,
}: Props) {
  const c = useTheme();

  if (!available) {
    const percent = Math.round(Math.min(100, (healthScoreTransactionCount / healthScoreMinTransactions) * 100));
    return (
      <View style={[styles.card, { backgroundColor: c.primaryDark }]}>
        <Text style={[styles.title, { color: c.onPrimary }]}>Financial Health Score</Text>
        <View style={styles.emptyWrap}>
          <Text style={[styles.emptyTitle, { color: c.onPrimary }]}>Getting Started</Text>
          <Text style={[styles.emptyBody, { color: c.primaryLight }]}>
            Import more transactions to unlock your Financial Health Score.
          </Text>
          <View style={styles.emptyProgressLabels}>
            <Text style={[styles.emptyProgressText, { color: c.primaryLight }]}>
              {healthScoreTransactionCount} / {healthScoreMinTransactions} transactions
            </Text>
            <Text style={[styles.emptyProgressText, { color: c.primaryLight }]}>{percent}%</Text>
          </View>
          <View style={[styles.progressTrack, { backgroundColor: c.border }]}>
            <View style={[styles.progressFill, { width: `${percent}%`, backgroundColor: c.onPrimary }]} />
          </View>
          <Pressable
            onPress={onImportPress}
            hitSlop={8}
            style={[styles.continueButton, { backgroundColor: c.onPrimary }]}
            accessibilityRole="button"
          >
            <Text style={[styles.continueButtonText, { color: c.primaryDark }]}>Continue Setup</Text>
          </Pressable>
        </View>
      </View>
    );
  }

  const scoreColor = healthColor(healthLabel, c);
  const progressColor = healthBarColor(healthScore, c);
  const deltaPositive = (healthScoreDeltaVsLastMonth ?? 0) > 0;

  return (
    <View style={[styles.card, { backgroundColor: c.primaryDark }]}>
      <View style={styles.headerRow}>
        <Text style={[styles.title, { color: c.onPrimary }]}>Financial Health Score</Text>
        {healthScoreDeltaVsLastMonth !== null && healthScoreDeltaVsLastMonth !== 0 ? (
          <View style={[styles.deltaPill, { backgroundColor: deltaPositive ? c.successBg : c.dangerBg }]}>
            <Text style={[styles.deltaPillText, { color: deltaPositive ? c.successInk : c.danger }]}>
              {deltaPositive ? '+' : ''}{healthScoreDeltaVsLastMonth} this month
            </Text>
          </View>
        ) : null}
      </View>

      <View style={styles.gaugeWrap}>
        <Svg width={GAUGE_WIDTH} height={GAUGE_HEIGHT}>
          {/* Fixed 3-band scale face -- always the same red/amber/green thirds, independent of
              the actual score, the way a speedometer's dial never changes. */}
          <Path d={arcPath(0, 30)} stroke={c.danger} strokeWidth={GAUGE_STROKE} fill="none" strokeLinecap="round" />
          <Path d={arcPath(30, 60)} stroke={c.warning} strokeWidth={GAUGE_STROKE} fill="none" />
          <Path d={arcPath(60, 100)} stroke={c.success} strokeWidth={GAUGE_STROKE} fill="none" strokeLinecap="round" />
          {/* Progress needle-arc, 0 up to the real score, colored by this app's own healthColor
              cutoffs (not the fixed band color) so it agrees with the score/label text below it. */}
          {healthScore > 0 ? (
            <Path d={arcPath(0, healthScore)} stroke={progressColor} strokeWidth={GAUGE_STROKE + 4} fill="none" strokeLinecap="round" />
          ) : null}
        </Svg>
        <View style={styles.gaugeScoreWrap} pointerEvents="none">
          <AnimatedHealthScoreNumber
            testID="health-score-value"
            value={healthScore}
            style={[styles.scoreValue, { color: scoreColor }]}
          />
          <Text style={[styles.scoreLabel, { color: scoreColor }]}>{healthLabel}</Text>
        </View>
      </View>

      {healthSparkline.length >= 2 ? (
        <View style={styles.sparklineWrap}>
          <Text style={[styles.sparklineLabel, { color: c.primaryLight }]}>6-month trend</Text>
          <HealthSparkline points={healthSparkline} color={c.onPrimary} />
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  card: { borderRadius: radius.xl, padding: spacing.lg, overflow: 'hidden' },
  headerRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: spacing.sm },
  title: { fontSize: 16, fontWeight: '700' },
  deltaPill: { borderRadius: 999, paddingHorizontal: 10, paddingVertical: 4 },
  deltaPillText: { fontSize: 12, fontWeight: '700' },
  gaugeWrap: { alignItems: 'center', marginTop: spacing.sm },
  gaugeScoreWrap: { position: 'absolute', top: 60, alignItems: 'center' },
  scoreValue: { fontSize: 40, fontWeight: '800' },
  scoreLabel: { fontSize: 14, fontWeight: '700', marginTop: 2 },
  sparklineWrap: { marginTop: spacing.md },
  sparklineLabel: { fontSize: 11, fontWeight: '600', marginBottom: 4 },
  emptyWrap: { alignItems: 'center', paddingTop: spacing.md, gap: 6 },
  emptyTitle: { fontSize: 15, fontWeight: '700' },
  emptyBody: { fontSize: 13, textAlign: 'center', maxWidth: 240 },
  emptyProgressLabels: { flexDirection: 'row', justifyContent: 'space-between', width: '100%', marginTop: spacing.sm },
  emptyProgressText: { fontSize: 12 },
  progressTrack: { height: 6, borderRadius: 3, overflow: 'hidden', width: '100%', marginTop: 6 },
  progressFill: { height: 6, borderRadius: 3 },
  continueButton: { marginTop: spacing.md, minHeight: 40, paddingHorizontal: spacing.lg, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  continueButtonText: { fontSize: 13, fontWeight: '700' },
});
