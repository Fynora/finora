import { useEffect } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Svg, { Path } from 'react-native-svg';
import Animated, { Easing, useAnimatedProps, useSharedValue, withTiming } from 'react-native-reanimated';
import { AnimatedHealthScoreNumber } from '../AnimatedHealthScoreNumber';
import { healthBarColor, healthColor } from '../../lib/health';
import { HealthSparkline } from './HealthSparkline';
import { fonts, radius, spacing, useTheme } from '../../theme';
import type { HealthScorePoint } from '../../types';

const AnimatedPath = Animated.createAnimatedComponent(Path);

const GAUGE_WIDTH = 240;
const GAUGE_HEIGHT = 130;
const GAUGE_CX = GAUGE_WIDTH / 2;
const GAUGE_CY = 118;
const GAUGE_R = 100;
const GAUGE_STROKE = 16;
// The frame ring sits just outside the gauge's own stroke -- large enough not to overlap the
// 3-band scale face.
const FRAME_R = GAUGE_R + GAUGE_STROKE + 6;
// Draw-in technique: a strokeDasharray longer than the path itself (max real arc length is the
// semicircle's own circumference, pi*GAUGE_R =~ 314, at healthScore=100) with strokeDashoffset
// animated from this length down to 0 reveals the line from its start to its real end, whatever
// that end happens to be for the current score -- no need to compute the arc's exact length.
const ARC_DRAW_LENGTH = 400;

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
  // Draw-in fires once per mount, matching AnimatedHealthScoreNumber's own existing behavior
  // (its shared value is constructed at 0 and only ever counts up once) -- both stay in sync.
  // React Navigation's default bottom-tab config (see AppTabs.tsx, no unmountOnBlur) keeps this
  // screen mounted after its first focus, so in practice that means once per app session, same
  // as the score number already does today; a fresh app launch (or logout/login) remounts the
  // whole tab navigator and gives both a fresh draw-in naturally, with no AsyncStorage needed.
  const arcOffset = useSharedValue(ARC_DRAW_LENGTH);
  useEffect(() => {
    arcOffset.value = withTiming(0, { duration: 700, easing: Easing.out(Easing.cubic) });
  }, [arcOffset]);
  const arcAnimatedProps = useAnimatedProps(() => ({ strokeDashoffset: arcOffset.value }));

  if (!available) {
    const percent = Math.round(Math.min(100, (healthScoreTransactionCount / healthScoreMinTransactions) * 100));
    return (
      <View style={[styles.card, { backgroundColor: c.primaryDark }]}>
        <Text style={[styles.title, { color: c.onPrimary, fontFamily: fonts.displaySemibold }]}>Financial Health Score</Text>
        <View style={styles.emptyWrap}>
          <Text style={[styles.emptyTitle, { color: c.onPrimary, fontFamily: fonts.displaySemibold }]}>Getting Started</Text>
          <Text style={[styles.emptyBody, { color: c.primaryLight, fontFamily: fonts.body }]}>
            Import more transactions to unlock your Financial Health Score.
          </Text>
          <View style={styles.emptyProgressLabels}>
            <Text style={[styles.emptyProgressText, { color: c.primaryLight, fontFamily: fonts.body }]}>
              {healthScoreTransactionCount} / {healthScoreMinTransactions} transactions
            </Text>
            <Text style={[styles.emptyProgressText, { color: c.primaryLight, fontFamily: fonts.body }]}>{percent}%</Text>
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
            <Text style={[styles.continueButtonText, { color: c.primaryDark, fontFamily: fonts.bodyBold }]}>Continue Setup</Text>
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
        <Text style={[styles.title, { color: c.onPrimary, fontFamily: fonts.displaySemibold }]}>Financial Health Score</Text>
        {healthScoreDeltaVsLastMonth !== null && healthScoreDeltaVsLastMonth !== 0 ? (
          // Brass, not directional green/red, per the resolved design decision -- distinct from
          // the arc/score below, which stay health-semantic. The +/- sign and number already
          // carry the direction as text even without color here.
          <View style={[styles.deltaPill, { backgroundColor: c.brassBg }]}>
            <Text style={[styles.deltaPillText, { color: c.brassInk, fontFamily: fonts.bodyBold }]}>
              {deltaPositive ? '+' : ''}{healthScoreDeltaVsLastMonth} this month
            </Text>
          </View>
        ) : null}
      </View>

      <View style={styles.gaugeWrap}>
        <Svg width={GAUGE_WIDTH} height={GAUGE_HEIGHT}>
          {/* Brass frame ring -- the seal's signature edge, purely decorative, drawn behind the
              gauge's own 3-band scale face so it never competes with the health-semantic colors
              inside it. */}
          <Path
            d={arcPath(0, 100)}
            stroke={c.brass}
            strokeWidth={1}
            fill="none"
            opacity={0.5}
            // FRAME_R via a scaled-up arcPath call would need its own GAUGE_R param -- simplest
            // correct way to offset the same path outward is a second Svg-space transform.
            transform={`translate(${GAUGE_CX}, ${GAUGE_CY}) scale(${FRAME_R / GAUGE_R}) translate(${-GAUGE_CX}, ${-GAUGE_CY})`}
          />
          {/* Fixed 3-band scale face -- always the same red/amber/green thirds, independent of
              the actual score, the way a speedometer's dial never changes. All three deliberately
              share the default "butt" cap (not "round"): a round cap on red's end or green's
              start would bulge visibly past the 30/60 boundary into the amber band next to it --
              confirmed by rendering this exact path data in a browser before this comment was
              written, not assumed. Flush "butt" seams at both internal boundaries, matching how
              real speedometer dial segments meet. */}
          <Path d={arcPath(0, 30)} stroke={c.danger} strokeWidth={GAUGE_STROKE} fill="none" />
          <Path d={arcPath(30, 60)} stroke={c.warning} strokeWidth={GAUGE_STROKE} fill="none" />
          <Path d={arcPath(60, 100)} stroke={c.success} strokeWidth={GAUGE_STROKE} fill="none" />
          {/* Progress needle-arc, 0 up to the real score, colored by this app's own healthColor
              cutoffs (not the fixed band color) so it agrees with the score/label text below it.
              Kept health-semantic -- resolved decision, brass explicitly does NOT replace this
              color, only the frame/plate/badge around it. Draws in once per mount via
              arcAnimatedProps (see top of component). */}
          {healthScore > 0 ? (
            <AnimatedPath
              d={arcPath(0, healthScore)}
              stroke={progressColor}
              strokeWidth={GAUGE_STROKE + 4}
              fill="none"
              strokeLinecap="round"
              strokeDasharray={[ARC_DRAW_LENGTH, ARC_DRAW_LENGTH]}
              animatedProps={arcAnimatedProps}
            />
          ) : null}
        </Svg>
        <View style={[styles.gaugeScoreWrap, styles.scorePlate, { backgroundColor: c.brassBg }]} pointerEvents="none">
          <AnimatedHealthScoreNumber
            testID="health-score-value"
            value={healthScore}
            style={[styles.scoreValue, { color: scoreColor, fontFamily: fonts.display }]}
          />
          <Text style={[styles.scoreLabel, { color: scoreColor, fontFamily: fonts.bodyBold }]}>{healthLabel}</Text>
        </View>
      </View>

      {healthSparkline.length >= 2 ? (
        <View style={styles.sparklineWrap}>
          <Text style={[styles.sparklineLabel, { color: c.primaryLight, fontFamily: fonts.bodySemibold }]}>6-month trend</Text>
          <HealthSparkline points={healthSparkline} color={c.onPrimary} />
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  card: { borderRadius: radius.xl, padding: spacing.lg, overflow: 'hidden' },
  headerRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: spacing.sm },
  title: { fontSize: 16 },
  deltaPill: { borderRadius: 999, paddingHorizontal: 10, paddingVertical: 4 },
  deltaPillText: { fontSize: 12 },
  gaugeWrap: { alignItems: 'center', marginTop: spacing.sm },
  gaugeScoreWrap: { position: 'absolute', top: 60, alignItems: 'center' },
  // The brass "seal" plate the score/label sit inside -- see the resolved decision: brass frames
  // the score, health-semantic color stays on the score text and the arc itself.
  scorePlate: { borderRadius: radius.lg, paddingHorizontal: spacing.md, paddingVertical: spacing.sm },
  scoreValue: { fontSize: 40 },
  scoreLabel: { fontSize: 14, marginTop: 2 },
  sparklineWrap: { marginTop: spacing.md },
  sparklineLabel: { fontSize: 11, marginBottom: 4 },
  emptyWrap: { alignItems: 'center', paddingTop: spacing.md, gap: 6 },
  emptyTitle: { fontSize: 15 },
  emptyBody: { fontSize: 13, textAlign: 'center', maxWidth: 240 },
  emptyProgressLabels: { flexDirection: 'row', justifyContent: 'space-between', width: '100%', marginTop: spacing.sm },
  emptyProgressText: { fontSize: 12 },
  progressTrack: { height: 6, borderRadius: 3, overflow: 'hidden', width: '100%', marginTop: 6 },
  progressFill: { height: 6, borderRadius: 3 },
  continueButton: { marginTop: spacing.md, minHeight: 40, paddingHorizontal: spacing.lg, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  continueButtonText: { fontSize: 13 },
});
