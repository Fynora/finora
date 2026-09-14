import { ScrollView, StyleSheet, Text, View } from 'react-native';
import Svg, { Circle } from 'react-native-svg';
import { DashboardCard } from './DashboardCard';
import { fmtCurrency } from '../../lib/format';
import { useLargeFontScale } from '../../lib/useLargeFontScale';
import { fonts, spacing, useTheme } from '../../theme';
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
    <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.row}>
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
          <DashboardCard key={g.id} style={styles.card}>
            <Text style={[styles.name, { color: c.ink, fontFamily: fonts.bodySemibold }]} numberOfLines={largeText ? 2 : 1}>
              {g.name}
            </Text>
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
                <Text style={[styles.pct, { color: c.ink, fontFamily: fonts.bodyBold }]}>{rawPct.toFixed(0)}%</Text>
              </View>
            </View>
            <Text style={[styles.meta, { color: c.mutedInk, fontFamily: fonts.body }]}>
              {fmtCurrency(g.currentAmount)} of {fmtCurrency(g.targetAmount)}
            </Text>
          </DashboardCard>
        );
      })}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  row: { gap: spacing.sm, paddingVertical: spacing.xs },
  card: { width: 180, alignItems: 'center' },
  name: { fontSize: 13, alignSelf: 'flex-start' },
  ringWrap: { marginTop: spacing.sm, width: RING_SIZE, height: RING_SIZE },
  ringCenter: { position: 'absolute', width: RING_SIZE, height: RING_SIZE, alignItems: 'center', justifyContent: 'center' },
  pct: { fontSize: 13 },
  meta: { fontSize: 11, marginTop: spacing.sm, alignSelf: 'flex-start' },
});
