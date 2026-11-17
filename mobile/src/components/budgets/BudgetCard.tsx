import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Card } from '../Card';
import { spacing, typography, useTheme } from '../../theme';
import type { IoniconName } from '../../lib/categoryIcons';
import { useLargeFontScale } from '../../lib/useLargeFontScale';
import { describePace, paceA11yLabel, type BudgetPace, type MonthClock } from '../../lib/budgetPace';
import type { Budget } from '../../types';
import { PaceBar, groupInk } from './PaceBar';

type Money = (n: number) => string;

/**
 * One budget. The body is a single accessible button (its spoken label carries every fact, so a
 * screen reader never separates a spend from its limit) that opens this category's transactions;
 * the three-dot button beside it is its own control, so pressing it never also opens them.
 */
export function BudgetCard({ budget, pace, clock, iconName, color, money, onOpen, onMore }: {
  budget: Budget;
  pace: BudgetPace;
  clock: MonthClock;
  iconName: IoniconName;
  color: string;
  money: Money;
  onOpen: () => void;
  onMore: () => void;
}) {
  const c = useTheme();
  const largeText = useLargeFontScale();
  const copy = describePace(pace, clock, money);

  return (
    <Card testID={`budget-card-${budget.id}`} style={styles.card}>
      <View style={styles.row}>
        <Pressable
          style={styles.body}
          accessibilityRole="button"
          accessibilityLabel={paceA11yLabel(budget.categoryName, budget.spentThisMonth, budget.monthlyLimit, pace, clock, money)}
          accessibilityHint="Opens these transactions"
          android_ripple={{ color: c.border }}
          onPress={onOpen}
        >
          <View style={[styles.top, largeText && styles.topStacked]}>
            <View style={styles.nameRow}>
              <View style={[styles.icon, { backgroundColor: `${color}26` }]}>
                <Ionicons name={iconName} size={15} color={color} />
              </View>
              <Text style={[typography.labelM, styles.name, { color: c.ink }]} numberOfLines={largeText ? undefined : 2}>
                {budget.categoryName}
              </Text>
            </View>
            <Text style={[typography.labelM, { color: pace.group === 'over' ? c.dangerInk : c.ink }]}>{copy.main}</Text>
          </View>
          <PaceBar spentFrac={pace.spentFrac} monthFrac={clock.monthFrac} group={pace.group} />
          <View style={[styles.foot, largeText && styles.footStacked]}>
            <Text style={[typography.bodyS, { color: c.mutedInk }]}>{`${money(budget.spentThisMonth)} of ${money(budget.monthlyLimit)}`}</Text>
            {copy.sub ? (
              <Text style={[typography.bodyS, { color: pace.group === 'heading-over' ? groupInk(c, pace.group) : c.mutedInk }]}>{copy.sub}</Text>
            ) : null}
          </View>
        </Pressable>
        <Pressable
          onPress={onMore}
          hitSlop={8}
          style={styles.more}
          accessibilityRole="button"
          accessibilityLabel={`More actions for ${budget.categoryName}`}
        >
          <Ionicons name="ellipsis-vertical" size={18} color={c.mutedInk} />
        </Pressable>
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  card: { paddingVertical: 14, paddingLeft: spacing.md, paddingRight: spacing.xs },
  row: { flexDirection: 'row', alignItems: 'flex-start' },
  body: { flex: 1, gap: 10 },
  top: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm },
  topStacked: { flexDirection: 'column', alignItems: 'flex-start' },
  nameRow: { flexDirection: 'row', alignItems: 'center', gap: 10, flexShrink: 1 },
  icon: { width: 28, height: 28, borderRadius: 14, alignItems: 'center', justifyContent: 'center' },
  name: { flexShrink: 1 },
  foot: { flexDirection: 'row', justifyContent: 'space-between', gap: spacing.sm, flexWrap: 'wrap' },
  footStacked: { flexDirection: 'column', gap: 2 },
  more: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center', marginTop: -10 },
});
