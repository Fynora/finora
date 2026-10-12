import type { RefObject } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { GlassSurface, useGlassSurfaceStyle } from '../GlassSurface';
import { spacing, typography, useTheme } from '../../theme';

export type TypeFilter = 'ALL' | 'INCOME' | 'EXPENSE';
export interface ActiveFilterChip { key: string; label: string; onClear: () => void }

const TYPES: { value: TypeFilter; label: string; spoken: string }[] = [
  { value: 'ALL', label: 'All', spoken: 'all' },
  { value: 'INCOME', label: 'Income', spoken: 'income' },
  { value: 'EXPENSE', label: 'Expense', spoken: 'expense' },
];

/**
 * The Transactions tab's controls: search, the All / Income / Expense switch, the one Filters
 * button that opens the rarely used filters, and a chip for every filter that is on.
 */
export function LedgerControls({
  keyword, onKeywordChange, searchRef, typeFilter, onTypeFilter, filtersOn, onOpenFilters, chips, stacked,
}: {
  keyword: string;
  onKeywordChange: (text: string) => void;
  searchRef: RefObject<TextInput | null>;
  typeFilter: TypeFilter;
  onTypeFilter: (t: TypeFilter) => void;
  filtersOn: number;
  onOpenFilters: () => void;
  chips: ActiveFilterChip[];
  stacked: boolean;
}) {
  const c = useTheme();
  // The Filters button is a Pressable, so it takes the glass fill and edge as a style.
  const glass = useGlassSurfaceStyle();
  const bodyM = typography.bodyM;

  return (
    <View style={styles.wrap}>
      <GlassSurface variant="row" style={styles.search}>
        <Ionicons name="search" size={18} color={c.muted} />
        <TextInput
          ref={searchRef}
          value={keyword}
          onChangeText={onKeywordChange}
          placeholder="Search description, merchant, bank…"
          placeholderTextColor={c.muted}
          autoCapitalize="none"
          autoCorrect={false}
          accessibilityLabel="Search transactions"
          // The type ramp's font and size without its line height: a fixed line height on an
          // iOS text field pushes the text to the bottom of the field, and at large text sizes
          // cut it off.
          style={[
            styles.searchInput,
            { fontFamily: bodyM.fontFamily, fontWeight: bodyM.fontWeight, fontSize: bodyM.fontSize, color: c.ink },
          ]}
        />
      </GlassSurface>

      <View testID="ledger-controls-row" style={stacked ? styles.controlsStacked : styles.controls}>
        <GlassSurface variant="row" style={[styles.track, !stacked && styles.grow]}>
          {TYPES.map((t) => {
            const selected = typeFilter === t.value;
            return (
              <Pressable
                key={t.value}
                onPress={() => onTypeFilter(t.value)}
                // The track is 44 high with 4 of padding, so a segment is 36; this makes it 44.
                hitSlop={{ top: 4, bottom: 4 }}
                accessibilityRole="button"
                accessibilityState={{ selected }}
                accessibilityLabel={`Filter: ${t.spoken}`}
                style={[styles.segment, selected && { backgroundColor: c.primary }]}
              >
                {/* A third of the row each: past 1.5 times "Expense" broke in the middle. */}
                <Text
                  numberOfLines={1}
                  maxFontSizeMultiplier={1.5}
                  style={[typography.labelS, { color: selected ? c.onPrimary : c.mutedInk }]}
                >
                  {t.label}
                </Text>
              </Pressable>
            );
          })}
        </GlassSurface>

        <Pressable
          onPress={onOpenFilters}
          accessibilityRole="button"
          accessibilityLabel={filtersOn > 0 ? `Filters, ${filtersOn} on` : 'Filters'}
          style={[styles.filtersButton, glass]}
        >
          <Ionicons name="options-outline" size={16} color={c.ink} />
          <Text style={[typography.labelS, { color: c.ink }]}>Filters</Text>
          {filtersOn > 0 ? (
            <View style={[styles.count, { backgroundColor: c.primary }]}>
              <Text testID="filters-count" style={[typography.caption, { color: c.onPrimary }]}>{filtersOn}</Text>
            </View>
          ) : null}
        </Pressable>
      </View>

      {chips.length > 0 ? (
        <View testID="ledger-active-filters" style={styles.chips}>
          {chips.map((chip) => (
            <View key={chip.key} style={[styles.chip, { borderColor: c.primary, backgroundColor: c.primaryLight }]}>
              <Text style={[typography.labelS, styles.chipLabel, { color: c.primary }]} numberOfLines={1}>{chip.label}</Text>
              <Pressable
                onPress={chip.onClear}
                // A 28 by 28 box plus 8 on every side is a 44 by 44 target.
                hitSlop={8}
                accessibilityRole="button"
                accessibilityLabel={`Clear filter: ${chip.label}`}
                style={styles.chipClear}
              >
                <Ionicons name="close" size={14} color={c.primary} />
              </Pressable>
            </View>
          ))}
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: spacing.ms },
  search: {
    flexDirection: 'row', alignItems: 'center', gap: spacing.sm,
    minHeight: 48, borderRadius: 999, paddingHorizontal: spacing.md,
  },
  searchInput: { flex: 1, paddingVertical: spacing.ms },
  controls: { flexDirection: 'row', alignItems: 'center', gap: spacing.ms },
  controlsStacked: { flexDirection: 'column', alignItems: 'stretch', gap: spacing.ms },
  grow: { flex: 1 },
  track: { flexDirection: 'row', minHeight: 44, borderRadius: 999, padding: 4 },
  segment: { flex: 1, alignItems: 'center', justifyContent: 'center', borderRadius: 999, paddingHorizontal: spacing.sm },
  filtersButton: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 6,
    minHeight: 44, borderRadius: 999, borderWidth: StyleSheet.hairlineWidth, paddingHorizontal: spacing.md,
  },
  count: { minWidth: 18, height: 18, borderRadius: 9, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 5 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  chip: {
    flexDirection: 'row', alignItems: 'center', gap: spacing.xs, maxWidth: '100%',
    minHeight: 32, borderRadius: 999, borderWidth: 1, paddingLeft: spacing.ms, paddingRight: spacing.xs,
  },
  chipLabel: { flexShrink: 1 },
  chipClear: { width: 28, height: 28, alignItems: 'center', justifyContent: 'center' },
});
