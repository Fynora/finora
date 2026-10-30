import { useEffect, useRef, useState, type RefObject } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { GlassSurface, useGlassSurfaceStyle } from '../GlassSurface';
import { hapticSelectionIfChanged } from '../../lib/haptics';
import { spacing, typography, useTheme } from '../../theme';

export type TypeFilter = 'ALL' | 'INCOME' | 'EXPENSE';
export interface ActiveFilterChip { key: string; label: string; onClear: () => void }

const TYPES: { value: TypeFilter; label: string; spoken: string }[] = [
  { value: 'ALL', label: 'All', spoken: 'all' },
  { value: 'INCOME', label: 'Income', spoken: 'income' },
  { value: 'EXPENSE', label: 'Expense', spoken: 'expense' },
];

/** What the search field needs from the screen. */
interface SearchProps {
  /** Read once, when the field mounts. To put a different search in the box (one arriving from
   *  another screen, or Clear filters) the screen remounts the controls with a new `key`. */
  initialKeyword: string;
  /** Every keystroke, as it happens. */
  onKeywordChange: (text: string) => void;
  /** The text to search for, once typing has paused for `settleMs`. */
  onKeywordSettled: (text: string) => void;
  settleMs: number;
  searchRef: RefObject<TextInput | null>;
}

/**
 * The search field. It holds what is typed itself, so a keystroke renders this field and nothing
 * else: held by the screen, every keystroke rendered the whole Ledger, its list and its sheets, and
 * typing stalled on a slow phone. It also waits for typing to pause before handing the screen the
 * search to run, so a request is not fired per keystroke. Unmounting drops a search still waiting.
 */
function SearchField({ initialKeyword, onKeywordChange, onKeywordSettled, settleMs, searchRef }: SearchProps) {
  const c = useTheme();
  const bodyM = typography.bodyM;
  const [keyword, setKeyword] = useState(initialKeyword);
  const settleTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => {
    if (settleTimer.current) clearTimeout(settleTimer.current);
  }, []);
  function change(text: string) {
    setKeyword(text);
    onKeywordChange(text);
    if (settleTimer.current) clearTimeout(settleTimer.current);
    settleTimer.current = setTimeout(() => {
      settleTimer.current = null;
      onKeywordSettled(text);
    }, settleMs);
  }

  return (
    <GlassSurface variant="row" style={styles.search}>
      <Ionicons name="search" size={18} color={c.muted} />
      <TextInput
        ref={searchRef}
        value={keyword}
        onChangeText={change}
        placeholder="Search description, merchant, bank…"
        placeholderTextColor={c.muted}
        autoCapitalize="none"
        autoCorrect={false}
        accessibilityLabel="Search transactions"
        returnKeyType="search"
        // The type ramp's font and size without its line height: a fixed line height on an
        // iOS text field pushes the text to the bottom of the field, and at large text sizes
        // cut it off.
        style={[
          styles.searchInput,
          { fontFamily: bodyM.fontFamily, fontWeight: bodyM.fontWeight, fontSize: bodyM.fontSize, color: c.ink },
        ]}
      />
      {/* Clearing a long search otherwise meant holding backspace. Its own button, on both
          platforms: iOS's built-in clear button has no Android counterpart. */}
      {keyword ? (
        <Pressable
          onPress={() => change('')}
          // A 28 by 28 box plus 8 on every side is a 44 by 44 target.
          hitSlop={8}
          accessibilityRole="button"
          accessibilityLabel="Clear search"
          style={styles.searchClear}
        >
          <Ionicons name="close-circle" size={18} color={c.muted} />
        </Pressable>
      ) : null}
    </GlassSurface>
  );
}

/**
 * The Transactions tab's controls: search, the All / Income / Expense switch, the one Filters
 * button that opens the rarely used filters, and a chip for every filter that is on.
 */
export function LedgerControls({
  initialKeyword, onKeywordChange, onKeywordSettled, settleMs, searchRef, typeFilter, onTypeFilter, filtersOn,
  onOpenFilters, chips, stacked,
}: SearchProps & {
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

  return (
    <View style={styles.wrap}>
      <SearchField
        initialKeyword={initialKeyword}
        onKeywordChange={onKeywordChange}
        onKeywordSettled={onKeywordSettled}
        settleMs={settleMs}
        searchRef={searchRef}
      />

      <View testID="ledger-controls-row" style={stacked ? styles.controlsStacked : styles.controls}>
        <GlassSurface variant="row" style={[styles.track, !stacked && styles.grow]}>
          {TYPES.map((t) => {
            const selected = typeFilter === t.value;
            return (
              <Pressable
                key={t.value}
                onPress={() => { hapticSelectionIfChanged(typeFilter, t.value); onTypeFilter(t.value); }}
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
  searchClear: { width: 28, height: 28, alignItems: 'center', justifyContent: 'center' },
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
