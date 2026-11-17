import { StyleSheet, Text, View } from 'react-native';
import { Card } from '../Card';
import { AnimatedNumber } from '../AnimatedNumber';
import { radius, spacing, typography, useTheme } from '../../theme';
import type { BudgetPace, MonthClock } from '../../lib/budgetPace';
import { useLargeFontScale } from '../../lib/useLargeFontScale';
import { BudgetRing } from './BudgetRing';

type Money = (n: number) => string;

// The ring is a fixed-size graphic, so its centre text grows only as far as the hole has room for
// (same rule as DonutChart's centre). The amount gets less headroom than the caption: a lakh-sized
// total ("₹1,25,000") at numberM is ~105px wide against a ~124px hole, and 1.15x keeps it inside.
// Uncapped, "₹22,700" was cut to "₹2" at the largest text size on an iPhone Air simulator.
const RING_AMOUNT_MAX_FONT_SCALE = 1.15;
const RING_CAPTION_MAX_FONT_SCALE = 1.3;

/** The month at a glance, read by a screen reader as one sentence. */
export function BudgetHeroCard({ totalSpent, totalLimit, overall, clock, money }: {
  totalSpent: number; totalLimit: number; overall: BudgetPace; clock: MonthClock; money: Money;
}) {
  const c = useTheme();
  const largeText = useLargeFontScale();
  const lastDay = clock.daysLeft === 0;
  const isOver = overall.group === 'over';
  const forecastOver = overall.forecastOverBy > 0;

  const daily = isOver
    ? `${money(overall.overBy)} over your budgets.`
    : lastDay
      ? `Last day, ${money(overall.remaining)} left.`
      : `${money(overall.safePerDay)} a day safe for ${clock.daysLeft} days.`;
  const summary = `${money(totalSpent)} spent of ${money(totalLimit)}. ${daily} At this pace ${money(overall.forecast)} by ${clock.monthEndLabel}.`;
  const forecastLine = forecastOver
    ? `At this pace you reach ${money(overall.forecast)} by ${clock.monthEndLabel}, ${money(overall.forecastOverBy)} over your budgets.`
    : `At this pace you reach ${money(overall.forecast)} by ${clock.monthEndLabel}, ${money(totalLimit - overall.forecast)} under your budgets.`;

  return (
    <Card style={styles.card}>
      <View accessible accessibilityRole="summary" accessibilityLabel={summary} style={styles.inner}>
        <BudgetRing spentFrac={overall.spentFrac} monthFrac={clock.monthFrac} group={overall.group}>
          <AnimatedNumber
            value={totalSpent}
            style={[typography.numberM, styles.centreText, { color: c.ink }]}
            maxFontSizeMultiplier={RING_AMOUNT_MAX_FONT_SCALE}
          />
          <Text style={[typography.bodyS, { color: c.mutedInk }]} numberOfLines={1} maxFontSizeMultiplier={RING_CAPTION_MAX_FONT_SCALE}>
            {`of ${money(totalLimit)}`}
          </Text>
        </BudgetRing>
        {/* Side by side at large text, "₹816" broke into "₹81" / "6"; stacked, each tile has the
            full width and a figure stays on one line. */}
        <View testID="hero-tiles" style={[styles.tiles, largeText && styles.tilesStacked]}>
          <View style={[styles.tile, { backgroundColor: c.bg /* glass-exempt: figure tile inside the glass hero card; opaque bg is its contrast */ }]}>
            {lastDay && !isOver ? (
              <Text style={[typography.numberS, { color: c.ink }]}>{`Last day · ${money(overall.remaining)} left`}</Text>
            ) : (
              <View style={styles.figureRow}>
                <Text style={[typography.numberM, { color: c.ink }]} numberOfLines={1}>{money(overall.safePerDay)}</Text>
                <Text style={[typography.bodyS, { color: c.mutedInk }]}> / day</Text>
              </View>
            )}
            <Text style={[typography.bodyS, { color: c.mutedInk }]}>Safe to spend</Text>
          </View>
          <View style={[styles.tile, { backgroundColor: c.bg /* glass-exempt: figure tile inside the glass hero card; opaque bg is its contrast */ }]}>
            <View style={styles.figureRow}>
              <Text style={[typography.numberM, { color: c.ink }]} numberOfLines={1}>{String(clock.daysLeft)}</Text>
              <Text style={[typography.bodyS, { color: c.mutedInk }]}> days</Text>
            </View>
            <Text style={[typography.bodyS, { color: c.mutedInk }]}>{`Left · day ${clock.dayOfMonth} of ${clock.daysInMonth}`}</Text>
          </View>
        </View>
        <View style={[styles.forecast, { backgroundColor: forecastOver ? c.dangerBg : c.bg }]}>
          <Text style={[typography.labelS, { color: forecastOver ? c.dangerInk : c.mutedInk }]}>{forecastLine}</Text>
        </View>
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  card: { paddingVertical: spacing.lg },
  inner: { alignItems: 'center', gap: spacing.md },
  centreText: { textAlign: 'center', padding: 0 },
  tiles: { flexDirection: 'row', gap: spacing.sm, alignSelf: 'stretch' },
  tilesStacked: { flexDirection: 'column' },
  tile: { flex: 1, borderRadius: radius.md, paddingHorizontal: 12, paddingVertical: 10, gap: 2 },
  figureRow: { flexDirection: 'row', alignItems: 'baseline', flexWrap: 'wrap' },
  forecast: { alignSelf: 'stretch', borderRadius: radius.md, paddingHorizontal: 12, paddingVertical: 10 },
});
