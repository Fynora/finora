import { useEffect } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Svg, { Circle, Defs, Path, RadialGradient, Stop } from 'react-native-svg';
import Animated, { Easing, useAnimatedProps, useSharedValue, withTiming } from 'react-native-reanimated';
import { AnimatedHealthScoreNumber } from '../AnimatedHealthScoreNumber';
import { GlassSurface } from '../GlassSurface';
import { GAUGE_SIZE, GAUGE_STROKE, gaugeArcPath } from '../../lib/heroGauge';
import { heroTones } from '../../lib/heroTones';
import { useLargeFontScale } from '../../lib/useLargeFontScale';
import { useReduceTransparency } from '../../lib/useReduceTransparency';
import { HealthSparkline } from './HealthSparkline';
import { ProgressBar } from './ProgressBar';
import { radius, spacing, typography, useTheme } from '../../theme';
import type { HealthScorePoint } from '../../types';

const AnimatedPath = Animated.createAnimatedComponent(Path);
const GLOW = 260;
// The score is display type. 1.3 takes its 56 points to about 73, which the row still has room
// for beside the dial; uncapped, accessibility sizes push it past 100 and into its neighbours.
const SCORE_MAX_FONT_SCALE = 1.3;

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

/** The brass light in the hero's top-right corner. Decorative; clipped by the card's radius. */
function Glow({ color }: { color: string }) {
  return (
    <Svg width={GLOW} height={GLOW} style={styles.glow} pointerEvents="none">
      <Defs>
        <RadialGradient id="heroGlow" cx="50%" cy="50%" r="50%">
          <Stop offset="0" stopColor={color} stopOpacity={0.34} />
          <Stop offset="1" stopColor={color} stopOpacity={0} />
        </RadialGradient>
      </Defs>
      <Circle cx={GLOW / 2} cy={GLOW / 2} r={GLOW / 2} fill="url(#heroGlow)" />
    </Svg>
  );
}

/**
 * The screen's hero card: the score as type, a 270 degree dial with the label inside it, the
 * change against the last recorded score, and the 6-month sparkline. Below
 * healthScoreTransactionCount's floor it shows the onboarding progress instead (the same three
 * text pieces the pinned test in DashboardScreen.test.tsx checks for) and a "Continue Setup" CTA
 * into Import.
 *
 * The surface is glass tinted with the hero's own colour; every colour drawn on it comes from
 * heroTones(), which is measured against that surface on both themes.
 */
export function HealthHero({
  available, healthScore, healthLabel, healthScoreDeltaVsLastMonth, healthSparkline,
  healthScoreTransactionCount, healthScoreMinTransactions, onImportPress,
}: Props) {
  const c = useTheme();
  const t = heroTones(c);
  // Tinted glass. GlassSurface uses a caller's fill as given (it moves it onto its tint layer),
  // so the opaque fallback for Reduce Transparency, or before the setting is known, is chosen
  // here. The edge takes the fill's colour on that path: a solid hero has never had an outline.
  const solid = useReduceTransparency() !== false;
  // The dial is a fixed-size graphic, so a label inside it cannot grow with Dynamic Type without
  // leaving it. At large text sizes the label is written under the score instead.
  const largeText = useLargeFontScale();
  const surface = solid
    ? { backgroundColor: t.solidSurface, borderColor: t.solidSurface }
    : { backgroundColor: t.surface };
  // Draw-in fires once per mount, matching AnimatedHealthScoreNumber's own behaviour (its shared
  // value is constructed at 0 and only ever counts up once), so the two stay in sync. React
  // Navigation's bottom tabs keep this screen mounted after its first focus (AppTabs.tsx, no
  // unmountOnBlur), so in practice that is once per app session.
  //
  // Interpolates the arc's actual endpoint (0 -> healthScore), not a strokeDasharray/
  // strokeDashoffset "reveal": that trick left a visible square notch at the growing tip, because
  // strokeLinecap="round" does not reliably round a dash/gap boundary the way it rounds a path's
  // true end. Rebuilding `d` every frame means the cap only ever has to close a real path end.
  const scoreProgress = useSharedValue(0);
  useEffect(() => {
    scoreProgress.value = withTiming(healthScore, { duration: 700, easing: Easing.out(Easing.cubic) });
  }, [healthScore, scoreProgress]);
  const arcAnimatedProps = useAnimatedProps(() => ({ d: gaugeArcPath(scoreProgress.value) }));

  if (!available) {
    // A zero floor is a real value when the server sends none; dividing by it printed "NaN%".
    const share = healthScoreMinTransactions > 0 ? healthScoreTransactionCount / healthScoreMinTransactions : 0;
    const percent = Math.round(Math.min(100, share * 100));
    return (
      <GlassSurface testID="health-hero" style={[styles.card, surface]}>
        <Glow color={c.brass} />
        <Text style={[typography.eyebrow, { color: t.textSoft }]}>Financial Health Score</Text>
        <View style={styles.lockedText}>
          <Text style={[typography.numberL, { color: t.text }]}>Getting Started</Text>
          <Text style={[typography.bodyM, { color: t.textSoft }]}>
            Import more transactions to unlock your Financial Health Score.
          </Text>
        </View>
        <View style={styles.lockedProgress}>
          <View style={styles.lockedLabels}>
            <Text style={[typography.labelS, { color: t.textSoft }]}>
              {healthScoreTransactionCount} / {healthScoreMinTransactions} transactions
            </Text>
            <Text style={[typography.labelS, { color: t.textSoft }]}>{percent}%</Text>
          </View>
          <ProgressBar percent={percent} color={c.primaryLight} trackColor={t.track} />
        </View>
        <Pressable
          onPress={onImportPress}
          hitSlop={8}
          style={[styles.continueButton, { backgroundColor: t.text }]}
          accessibilityRole="button"
        >
          <Text style={[typography.labelM, { color: t.solidSurface }]}>Continue Setup</Text>
        </Pressable>
      </GlassSurface>
    );
  }

  const deltaPositive = (healthScoreDeltaVsLastMonth ?? 0) > 0;

  return (
    <GlassSurface testID="health-hero" style={[styles.card, surface]}>
      <Glow color={c.brass} />
      <Text style={[typography.eyebrow, { color: t.textSoft }]}>Financial Health Score</Text>

      <View style={styles.scoreRow}>
        <View style={styles.scoreCol}>
          <View style={styles.scoreValueRow}>
            <AnimatedHealthScoreNumber
              testID="health-score-value"
              value={healthScore}
              style={[typography.display, { color: t.text }]}
              maxFontSizeMultiplier={SCORE_MAX_FONT_SCALE}
            />
            <Text style={[typography.bodyM, styles.outOf, { color: t.textSoft }]}>/ 100</Text>
          </View>
          {largeText ? <Text style={[typography.cardTitle, { color: t.text }]}>{healthLabel}</Text> : null}
          {/* Brass, not directional green/red: the sign and the number already carry the
              direction. "vs last score", not "this month": the delta is against the most recent
              PRIOR snapshot, which DashboardService's gap-skipping lookup can resolve further
              back than last month (web: "vs your last recorded score"). */}
          {healthScoreDeltaVsLastMonth !== null && healthScoreDeltaVsLastMonth !== 0 ? (
            <View style={[styles.deltaPill, { backgroundColor: c.brassBg }]}>
              <Text style={[typography.labelS, { color: c.brassInk }]}>
                {deltaPositive ? '+' : ''}{healthScoreDeltaVsLastMonth} vs last score
              </Text>
            </View>
          ) : null}
        </View>

        <View style={styles.gauge}>
          <Svg width={GAUGE_SIZE} height={GAUGE_SIZE}>
            {/* What remains: the whole dial, faint. */}
            <Path d={gaugeArcPath(100)} stroke={t.track} strokeWidth={GAUGE_STROKE} strokeLinecap="round" fill="none" />
            {/* Progress, in the tier's colour. Same stroke width as the track so the two arcs
                are concentric everywhere: a wider progress arc leaves a visible step where it
                ends (found on a 4x screenshot in the previous design). */}
            {healthScore > 0 ? (
              <AnimatedPath
                stroke={t.arc(healthScore)}
                strokeWidth={GAUGE_STROKE}
                strokeLinecap="round"
                fill="none"
                animatedProps={arcAnimatedProps}
              />
            ) : null}
          </Svg>
          {largeText ? null : (
            <View style={styles.gaugeLabel} pointerEvents="none">
              {/* Inside the dial, so it must fit inside the dial: "Needs Attention" on one line ran
                  across the arc and out the other side on a real screen. Two lines within the inner
                  width, shrinking slightly before it may touch the stroke. */}
              <Text
                style={[typography.cardTitle, styles.gaugeLabelText, { color: t.text }]}
                numberOfLines={2}
                adjustsFontSizeToFit
                minimumFontScale={0.8}
              >
                {healthLabel}
              </Text>
            </View>
          )}
        </View>
      </View>

      {healthSparkline.length >= 2 ? (
        <>
          <View style={[styles.divider, { backgroundColor: t.divider }]} />
          <View style={styles.trendRow}>
            <Text style={[typography.eyebrow, { color: t.textSoft }]}>6-month trend</Text>
            <View style={styles.sparkline}>
              <HealthSparkline points={healthSparkline} color={t.text} />
            </View>
          </View>
        </>
      ) : null}
    </GlassSurface>
  );
}

const styles = StyleSheet.create({
  card: { borderRadius: radius.hero, padding: spacing.lg, overflow: 'hidden', gap: spacing.ml },
  glow: { position: 'absolute', top: -GLOW / 2, right: -GLOW / 3 },
  scoreRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.md },
  scoreCol: { flexShrink: 1, gap: spacing.ms },
  scoreValueRow: { flexDirection: 'row', alignItems: 'flex-end', gap: 6 },
  outOf: { marginBottom: 10 },
  deltaPill: { alignSelf: 'flex-start', borderRadius: 999, paddingHorizontal: 10, paddingVertical: 4 },
  gauge: { width: GAUGE_SIZE, height: GAUGE_SIZE },
  gaugeLabel: { position: 'absolute', top: 0, right: 0, bottom: 0, left: 0, alignItems: 'center', justifyContent: 'center' },
  // The dial's inner diameter (size minus the stroke on both sides), less 6 points of air each side.
  gaugeLabelText: { maxWidth: GAUGE_SIZE - GAUGE_STROKE * 2 - 12, textAlign: 'center' },
  divider: { height: StyleSheet.hairlineWidth, alignSelf: 'stretch' },
  trendRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.md },
  sparkline: { flex: 1, maxWidth: 170 },
  lockedText: { gap: spacing.sm },
  lockedProgress: { gap: spacing.sm },
  lockedLabels: { flexDirection: 'row', justifyContent: 'space-between' },
  continueButton: { minHeight: 44, borderRadius: 999, alignItems: 'center', justifyContent: 'center' },
});
