import { Pressable, StyleSheet, Text, View } from 'react-native';
import type { DailySpend } from '../../api/endpoints';
import { fmtCurrency } from '../../lib/format';
import { shortDate } from '../../lib/reportSections/trend';
import { buildSpendCalendar, type SpendLevel } from '../../lib/reportSections/spendCalendar';
import { spacing, typography, useTheme } from '../../theme';
import { DashboardCard } from '../dashboard/DashboardCard';
import { SectionHeading } from '../Card';

/** '#RRGGBB' -> 'rgba(r,g,b,a)'. The theme's colours are hex; the shades are the primary at a strength. */
function withAlpha(hex: string, alpha: number): string {
  const h = hex.replace('#', '');
  const n = parseInt(h.length === 3 ? h.split('').map((x) => x + x).join('') : h, 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

const STRENGTH: Record<SpendLevel, number> = { 0: 0, 1: 0.15, 2: 0.3, 3: 0.65, 4: 1 };
const WEEK = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];

/**
 * The month as a calendar, each day shaded by how much was spent that day relative to the month's
 * biggest day, with that day ringed and named. A day with spend opens that day's transactions. The
 * data is the report's own per-day spend (same rows and refund netting as the category list). Drawn
 * only when something was spent. A day with no shade means no spending was recorded that day, and for
 * a month only partly in the data the note says days past the data show nothing.
 */
export function SpendingCalendar({ month, days, complete, onOpenDay }: {
  month: string; days: DailySpend[] | undefined; complete: boolean; onOpenDay: (date: string) => void;
}) {
  const c = useTheme();
  if (!days || days.length === 0) return null;
  const { leadingBlanks, cells, biggest } = buildSpendCalendar(month, days);
  if (!biggest) return null;
  const items: ({ blank: true; key: string } | { blank: false; cell: (typeof cells)[number] })[] = [
    ...Array.from({ length: leadingBlanks }, (_, i) => ({ blank: true as const, key: `blank-${i}` })),
    ...cells.map((cell) => ({ blank: false as const, cell })),
  ];
  return (
    <DashboardCard testID="spend-calendar-card">
      <SectionHeading title="Daily spending" />
      <Text testID="calendar-biggest" style={[typography.caption, { color: c.mutedInk }]}>
        Biggest day: {shortDate(biggest.date)}, {fmtCurrency(biggest.amount)}
      </Text>
      <View style={styles.week} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
        {WEEK.map((d, i) => <Text key={i} style={[typography.caption, styles.weekDay, { color: c.mutedInk }]}>{d}</Text>)}
      </View>
      <View style={styles.grid} testID="spend-calendar">
        {items.map((item) => {
          if (item.blank) return <View key={item.key} style={styles.slot} />;
          const { cell } = item;
          const strong = cell.level >= 3;
          const ring = cell.date === biggest.date ? { borderWidth: 2, borderColor: c.ink } : null;
          const inner = (
            <View
              style={[
                styles.cell,
                cell.level === 0
                  ? { borderWidth: 1, borderColor: c.border }
                  : { backgroundColor: withAlpha(c.primary, STRENGTH[cell.level]) },
                ring,
              ]}
            >
              <Text style={[typography.caption, { color: cell.level === 0 ? c.mutedInk : strong ? c.onPrimary : c.ink }]}>{cell.day}</Text>
            </View>
          );
          return (
            <View key={cell.date} style={styles.slot}>
              {cell.amount > 0 ? (
                <Pressable
                  onPress={() => onOpenDay(cell.date)} accessibilityRole="button"
                  accessibilityLabel={`${shortDate(cell.date)}: ${fmtCurrency(cell.amount)} spent`} accessibilityHint="Opens that day's transactions"
                >
                  {inner}
                </Pressable>
              ) : inner}
            </View>
          );
        })}
      </View>
      <Text style={[typography.caption, { color: c.mutedInk }]}>
        A stronger shade means more spent that day.
        {complete ? '' : ' Days past your latest data show nothing because they are not in yet.'}
      </Text>
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  week: { flexDirection: 'row', marginTop: spacing.sm },
  weekDay: { width: `${100 / 7}%`, textAlign: 'center' },
  grid: { flexDirection: 'row', flexWrap: 'wrap', marginVertical: spacing.xs },
  slot: { width: `${100 / 7}%`, padding: 2 },
  cell: { aspectRatio: 1, borderRadius: 6, padding: 4, alignItems: 'flex-start', justifyContent: 'flex-start' },
});
