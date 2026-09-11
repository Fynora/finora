import { useEffect } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Svg, { Circle, Path } from 'react-native-svg';
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
  //
  // Interpolates the arc's actual endpoint (0 -> healthScore), not a strokeDasharray/
  // strokeDashoffset "reveal" trick -- an earlier version used the dash trick and produced a
  // real, visible square notch at the growing tip (confirmed from a full-resolution screenshot,
  // not assumed): stroke-linecap="round" doesn't reliably render as round at a dash/gap
  // boundary the way it does at a path's true endpoint. Recomputing `d` every frame instead
  // means strokeLinecap only ever has to cap a real, true path end, which round-caps cleanly.
  const scoreProgress = useSharedValue(0);
  useEffect(() => {
    scoreProgress.value = withTiming(healthScore, { duration: 700, easing: Easing.out(Easing.cubic) });
  }, [healthScore, scoreProgress]);
  const arcAnimatedProps = useAnimatedProps(() => {
    'worklet';
    const s = scoreProgress.value;
    if (s <= 0) return { d: '' };
    const startAngle = Math.PI; // score 0
    const endAngle = (Math.PI * (100 - s)) / 100;
    const startX = GAUGE_CX + GAUGE_R * Math.cos(startAngle);
    const startY = GAUGE_CY - GAUGE_R * Math.sin(startAngle);
    const endX = GAUGE_CX + GAUGE_R * Math.cos(endAngle);
    const endY = GAUGE_CY - GAUGE_R * Math.sin(endAngle);
    return { d: `M ${startX} ${startY} A ${GAUGE_R} ${GAUGE_R} 0 0 1 ${endX} ${endY}` };
  });

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
              inside it. A plain concentric Circle, not a scaled/transformed arc Path -- an earlier
              version scaled arcPath(0,100) via an SVG transform, which distorted the stroke near
              the flat start/end caps into a visible blocky artifact at the bottom corners
              (confirmed from a real screenshot, not assumed). The Svg's own fixed height (130,
              well under GAUGE_CY + FRAME_R) naturally clips the circle's lower half, leaving only
              the same semicircle-shaped sliver the arc-based version was trying to draw by hand. */}
          <Circle cx={GAUGE_CX} cy={GAUGE_CY} r={FRAME_R} stroke={c.brass} strokeWidth={1} fill="none" opacity={0.5} />
          {/* Dim "remaining" track for the full 0-100 range -- replaces an earlier fixed 3-band
              red/amber/green reference dial. That version always painted 60-100 solid green
              regardless of the actual score, so an 84 and a 93 (both inside that band) looked
              identically "full," with no visible sense of how much of the gauge was still
              unclaimed -- confirmed wrong against a real mockup showing a dim gray remaining arc
              behind the colored progress. Same `c.border` token this component's own onboarding
              progress bar already uses against this same dark `primaryDark` card background. */}
          <Path d={arcPath(0, 100)} stroke={c.border} strokeWidth={GAUGE_STROKE} fill="none" />
          {/* Progress needle-arc, 0 up to the real score, colored by this app's own healthColor
              cutoffs (not the fixed band color) so it agrees with the score/label text below it.
              Kept health-semantic -- resolved decision, brass explicitly does NOT replace this
              color, only the frame/plate/badge around it. Draws in once per mount via
              arcAnimatedProps (see top of component).
              strokeWidth is GAUGE_STROKE, matching the reference bands underneath exactly --
              NOT GAUGE_STROKE + 4. That extra width was the actual bug a full-resolution
              screenshot exposed at 4x zoom: since this arc only ever covers 0..healthScore
              while the reference band underneath spans the full 0..100, a WIDER progress arc
              creates a real radial step in stroke width at the exact point the progress arc
              ends and the narrower band becomes the only thing visible again -- a strokeLinecap
              change (round vs butt) cannot fix that, because the mismatch is in the two arcs'
              radii, not their cap shape (confirmed by trying butt first, which changed nothing).
              Equal widths mean the two arcs are perfectly concentric everywhere, so there is no
              step left to be visible regardless of cap style. */}
          {healthScore > 0 ? (
            <AnimatedPath
              stroke={progressColor}
              strokeWidth={GAUGE_STROKE}
              fill="none"
              strokeLinecap="butt"
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
