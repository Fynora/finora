import { ScrollView, StyleSheet, Text, View } from 'react-native';
import Svg, { Circle } from 'react-native-svg';
import { DashboardCard } from './DashboardCard';
import { fmtCurrency } from '../../lib/format';
import { useLargeFontScale } from '../../lib/useLargeFontScale';
import { cardShadowRoom, spacing, typography, useTheme } from '../../theme';
import type { Goal } from '../../types';

const RING_SIZE = 56;
const RING_STROKE = 5;
const RING_R = (RING_SIZE - RING_STROKE) / 2;
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_R;

/**
 * Passbook redesign: brass progress rings replace the previous linear progress bar, per the
 * resolved design decision -- goal cards should visually belong to the same "seal" family as the
 * Financial Health Score. `rotation={-90}` on the progress circle starts the sweep at 12 o'clock,
 * matching a clock-face reading rather than the default 3-o'clock start `Circle` would otherwise
 * use.
 */
export function GoalsRow({ goals }: { goals: Goal[] }) {
  const c = useTheme();
  const largeText = useLargeFontScale();
  if (goals.length === 0) return null;

  return (
    <ScrollView testID="goals-row" horizontal showsHorizontalScrollIndicator={false} style={styles.scroller} contentContainerStyle={styles.row}>
      {goals.map((g) => {
        // Bug fix: the ring's geometry has to stay capped at 100% -- unlike a linear bar's width,
        // which just overflows a fixed container past 100%, a strokeDashoffset past this ring's
        // own [0, RING_CIRCUMFERENCE] range goes NEGATIVE and renders wrong, not just "too full".
        // But the TEXT label was reusing that same capped value -- so a goal funded past its
        // target (e.g. 120%) displayed a stuck "100%" forever. rawPct is the real, uncapped
        // figure for the label; ringPct stays capped and drives the ring's geometry only.
        const rawPct = g.targetAmount > 0 ? (g.currentAmount / g.targetAmount) * 100 : 0;
        const ringPct = Math.min(100, rawPct);
        return (
          <DashboardCard key={g.id} style={styles.card} padding="compact">
            <View style={styles.ringWrap}>
              <Svg width={RING_SIZE} height={RING_SIZE}>
                <Circle cx={RING_SIZE / 2} cy={RING_SIZE / 2} r={RING_R} stroke={c.border} strokeWidth={RING_STROKE} fill="none" />
                <Circle
                  cx={RING_SIZE / 2}
                  cy={RING_SIZE / 2}
                  r={RING_R}
                  stroke={c.brass}
                  strokeWidth={RING_STROKE}
                  fill="none"
                  strokeLinecap="round"
                  strokeDasharray={`${RING_CIRCUMFERENCE} ${RING_CIRCUMFERENCE}`}
                  strokeDashoffset={RING_CIRCUMFERENCE * (1 - ringPct / 100)}
                  rotation={-90}
                  originX={RING_SIZE / 2}
                  originY={RING_SIZE / 2}
                />
              </Svg>
              <View style={styles.ringCenter} pointerEvents="none">
                <Text style={[typography.labelS, { color: c.ink }]}>{rawPct.toFixed(0)}%</Text>
              </View>
            </View>
            <Text style={[typography.labelM, styles.name, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>
              {g.name}
            </Text>
            <Text style={[typography.caption, styles.meta, { color: c.mutedInk }]}>
              {fmtCurrency(g.currentAmount)} of {fmtCurrency(g.targetAmount)}
            </Text>
          </DashboardCard>
        );
      })}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  // Edge to edge, same reasoning as HealthFactorsRow: cancels DashboardScreen's side padding
  // (spacing.ml) so tiles scroll off under the bezel instead of being cut at the padding line.
  //
  // Vertically the content is padded by the tiles' shadow reach, because a scroll view clips to
  // its bounds, and the scroller's margins take the same amount back: the tiles still sit
  // spacing.xs from whatever is above and below.
  scroller: {
    marginTop: spacing.xs - cardShadowRoom.top,
    marginBottom: spacing.xs - cardShadowRoom.bottom,
    marginHorizontal: -spacing.ml,
  },
  row: {
    gap: spacing.ms,
    paddingTop: cardShadowRoom.top,
    paddingBottom: cardShadowRoom.bottom,
    paddingHorizontal: spacing.ml,
  },
  card: { width: 164, gap: spacing.ms },
  name: { alignSelf: 'stretch' },
  ringWrap: { width: RING_SIZE, height: RING_SIZE },
  ringCenter: { position: 'absolute', width: RING_SIZE, height: RING_SIZE, alignItems: 'center', justifyContent: 'center' },
  meta: { alignSelf: 'stretch' },
});
