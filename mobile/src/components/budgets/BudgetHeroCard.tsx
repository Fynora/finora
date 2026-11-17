import { StyleSheet, Text, View } from 'react-native';
import { Card } from '../Card';
import { AnimatedNumber } from '../AnimatedNumber';
import { radius, spacing, typography, useTheme } from '../../theme';
import type { BudgetPace, MonthClock } from '../../lib/budgetPace';
import { BudgetRing } from './BudgetRing';

type Money = (n: number) => string;

/** The month at a glance, read by a screen reader as one sentence. */
export function BudgetHeroCard({ totalSpent, totalLimit, overall, clock, money }: {
  totalSpent: number; totalLimit: number; overall: BudgetPace; clock: MonthClock; money: Money;
}) {
  const c = useTheme();
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
          <AnimatedNumber value={totalSpent} style={[typography.numberL, styles.centreText, { color: c.ink }]} />
          <Text style={[typography.bodyS, { color: c.mutedInk }]}>{`of ${money(totalLimit)}`}</Text>
        </BudgetRing>
        <View style={styles.tiles}>
          <View style={[styles.tile, { backgroundColor: c.bg }]}>
            {lastDay && !isOver ? (
              <Text style={[typography.numberS, { color: c.ink }]}>{`Last day · ${money(overall.remaining)} left`}</Text>
            ) : (
              <View style={styles.figureRow}>
                <Text style={[typography.numberM, { color: c.ink }]}>{money(overall.safePerDay)}</Text>
                <Text style={[typography.bodyS, { color: c.mutedInk }]}> / day</Text>
              </View>
            )}
            <Text style={[typography.bodyS, { color: c.mutedInk }]}>Safe to spend</Text>
          </View>
          <View style={[styles.tile, { backgroundColor: c.bg }]}>
            <View style={styles.figureRow}>
              <Text style={[typography.numberM, { color: c.ink }]}>{String(clock.daysLeft)}</Text>
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
  tile: { flex: 1, borderRadius: radius.md, paddingHorizontal: 12, paddingVertical: 10, gap: 2 },
  figureRow: { flexDirection: 'row', alignItems: 'baseline', flexWrap: 'wrap' },
  forecast: { alignSelf: 'stretch', borderRadius: radius.md, paddingHorizontal: 12, paddingVertical: 10 },
});
