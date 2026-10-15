import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Svg, { Circle, G } from 'react-native-svg';
import Ionicons from '@expo/vector-icons/Ionicons';
import type { CategoryOption, InsightsData, ReportData } from '../../api/endpoints';
import { colorHexFor, iconNameFor } from '../../lib/categoryIcons';
import { fmtCurrency, monthLabelLong } from '../../lib/format';
import { categoryRows, changeVsAverage, donutSlices, type CategoryRow } from '../../lib/reportSections/categoryShare';
import { radius, spacing, typography, useTheme } from '../../theme';
import { Card, EmptyState, SectionHeading } from '../Card';
import { DashboardCard } from '../dashboard/DashboardCard';

const VISIBLE_ROWS = 8;
const SIZE = 120;
const STROKE = 16;
const RADIUS = (SIZE - STROKE) / 2;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;
/** A hairline between slices, in ring length, so neighbouring categories sharing a colour stay apart. */
const SLICE_GAP = 1.5;

type Mover = NonNullable<InsightsData['movers']>[number];

function Donut({ rows, total, byName }: { rows: CategoryRow[]; total: number; byName: Map<string, CategoryOption> }) {
  const c = useTheme();
  const slices = donutSlices(rows);
  const colorOf = (name: string | null) => {
    const token = name ? byName.get(name.trim().toLowerCase())?.color : undefined;
    return token ? colorHexFor(token) : c.muted;
  };
  // Each slice starts where the ones before it end, in ring length.
  const lengths = slices.map((s) => (s.share / 100) * CIRCUMFERENCE);
  const starts = lengths.map((_, i) => lengths.slice(0, i).reduce((sum, l) => sum + l, 0));
  const summary = slices.slice(0, 4).map((s) => `${s.category ?? 'Other'} ${Math.round(s.share)}%`).join(', ');
  return (
    <View
      testID="category-donut" style={styles.donut} accessible accessibilityRole="image"
      accessibilityLabel={`Spending by category, ${fmtCurrency(total)} in total. Largest: ${summary}.`}
    >
      <Svg width={SIZE} height={SIZE}>
        <G rotation={-90} origin={`${SIZE / 2}, ${SIZE / 2}`}>
          {slices.map((s, i) => {
            const drawn = Math.max(lengths[i] - SLICE_GAP, 0.01);
            return (
              <Circle
                key={s.category ?? '__other'} cx={SIZE / 2} cy={SIZE / 2} r={RADIUS} fill="none" stroke={colorOf(s.category)}
                strokeWidth={STROKE} strokeDasharray={`${drawn} ${CIRCUMFERENCE - drawn}`} strokeDashoffset={-starts[i]}
              />
            );
          })}
        </G>
      </Svg>
      <View style={styles.donutCenter} pointerEvents="none">
        <Text style={[typography.numberS, { color: c.ink }]} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.6}>{fmtCurrency(total)}</Text>
        <Text style={[typography.caption, { color: c.mutedInk }]}>total spend</Text>
      </View>
    </View>
  );
}

function Row({ row, largest, category, mover, monthName, onPress }: {
  row: CategoryRow; largest: number; category: CategoryOption | undefined; mover: Mover | undefined; monthName: string;
  onPress: (() => void) | null;
}) {
  const c = useTheme();
  const color = category ? colorHexFor(category.color) : c.muted;
  const change = changeVsAverage(mover?.pctChange);
  const changeColors = change?.tone === 'up'
    ? { bg: c.dangerBg, fg: c.dangerInk } : change?.tone === 'down'
      ? { bg: c.successBg, fg: c.successInk } : { bg: 'transparent', fg: c.mutedInk };
  const body = (
    <>
      <View style={[styles.icon, { backgroundColor: `${color}22` }]}>
        <Ionicons name={iconNameFor(category?.icon ?? 'tag')} size={15} color={color} />
      </View>
      <View style={styles.rowMain}>
        <View style={styles.nameLine}>
          <Text style={[typography.bodyM, styles.name, { color: c.ink }]} numberOfLines={1}>{row.category}</Text>
          {change ? (
            <View testID="category-change" style={[styles.chip, { backgroundColor: changeColors.bg }]}>
              <Text style={[typography.labelS, { color: changeColors.fg }]}>{change.text}</Text>
            </View>
          ) : null}
        </View>
        <View style={[styles.track, { backgroundColor: c.border }]}>
          <View style={{ width: `${(row.amount / largest) * 100}%`, height: '100%', borderRadius: 3, backgroundColor: color }} />
        </View>
      </View>
      <View style={styles.figures}>
        <Text style={[typography.bodyM, { color: c.ink }]}>{fmtCurrency(row.amount)}</Text>
        <Text style={[typography.caption, { color: c.mutedInk }]}>{Math.round(row.share)}%</Text>
      </View>
    </>
  );
  return onPress ? (
    <Pressable
      onPress={onPress} accessibilityRole="button" style={styles.row}
      // Names the month on screen: a hardcoded "this month's" read the wrong month aloud once a past
      // month was picked.
      accessibilityLabel={`${row.category}: ${fmtCurrency(row.amount)}, ${Math.round(row.share)} percent of ${monthName}'s spending`}
      accessibilityHint="Opens these transactions"
    >
      {body}
    </Pressable>
  ) : (
    <View style={styles.row}>{body}</View>
  );
}

/**
 * Where the month's spending went: a donut and a ranked list that add up to the same total (the
 * rows' own sum, never the report's Expense, which can be smaller). A row opens the transactions for
 * that category and month only when a real category with that name exists: the report's catch-all
 * "Uncategorized" bucket also holds rows in deleted categories, which the Ledger cannot filter to.
 * The change chip is the server's figure against the user's recent average; a category with no
 * history gets none.
 */
export function CategoryBreakdown({ report, categories, movers, onOpenCategory }: {
  report: ReportData;
  categories: CategoryOption[];
  movers: InsightsData['movers'] | null;
  onOpenCategory: (category: string) => void;
}) {
  const c = useTheme();
  const [showAll, setShowAll] = useState(false);
  const { rows, total } = categoryRows(report.categories);
  const byName = new Map(categories.map((cat) => [cat.name.trim().toLowerCase(), cat]));
  const moverByName = new Map((movers ?? []).map((m) => [m.category.trim().toLowerCase(), m]));
  const shown = showAll ? rows : rows.slice(0, VISIBLE_ROWS);
  const largest = rows[0]?.amount ?? 1;
  const hasChips = rows.some((r) => changeVsAverage(moverByName.get(r.category.trim().toLowerCase())?.pctChange));

  if (rows.length === 0) {
    return (
      <Card>
        <SectionHeading title="Where your money went" />
        <EmptyState message={`No expenses recorded in ${monthLabelLong(report.month)}.`} />
      </Card>
    );
  }
  return (
    <DashboardCard>
      <SectionHeading title="Where your money went" />
      <View style={styles.top}>
        <Donut rows={rows} total={total} byName={byName} />
        <Text style={[typography.caption, styles.note, { color: c.mutedInk }]}>
          {hasChips ? '▲ ▼ compare each category with your recent average. ' : ''}Shares are of the total spend shown here.
        </Text>
      </View>
      <View testID="category-rows">
        {shown.map((row) => {
          const key = row.category.trim().toLowerCase();
          const category = byName.get(key);
          return (
            <Row
              key={row.category} row={row} largest={largest} category={category} mover={moverByName.get(key)}
              monthName={monthLabelLong(report.month)}
              onPress={category ? () => onOpenCategory(row.category) : null}
            />
          );
        })}
      </View>
      {rows.length > VISIBLE_ROWS ? (
        <Pressable
          onPress={() => setShowAll((v) => !v)} accessibilityRole="button" accessibilityState={{ expanded: showAll }}
          style={styles.showAll} hitSlop={6}
        >
          <Text style={[typography.labelM, { color: c.brassInk }]}>
            {showAll ? 'Show fewer' : `Show all ${rows.length} categories`}
          </Text>
        </Pressable>
      ) : null}
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  top: { flexDirection: 'row', alignItems: 'center', gap: spacing.md, marginBottom: spacing.sm },
  note: { flex: 1 },
  donut: { width: SIZE, height: SIZE, alignItems: 'center', justifyContent: 'center' },
  donutCenter: { position: 'absolute', alignItems: 'center', width: SIZE - STROKE * 2 - 8 },
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, minHeight: 52, paddingVertical: 6 },
  icon: { width: 30, height: 30, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  rowMain: { flex: 1, minWidth: 0 },
  nameLine: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs },
  name: { flexShrink: 1 },
  chip: { borderRadius: 6, paddingHorizontal: 6, paddingVertical: 1 },
  track: { height: 6, borderRadius: 3, overflow: 'hidden', marginTop: 4 },
  figures: { alignItems: 'flex-end', minWidth: 72 },
  showAll: { alignSelf: 'flex-start', minHeight: 44, justifyContent: 'center' },
});
