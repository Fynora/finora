import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { AppSwitch } from '../AppSwitch';
import { hapticSelection, hapticSelectionIfChanged } from '../../lib/haptics';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { AppModal } from '../AppModal';
import { DateField } from '../DateField';
import { GlassSurface } from '../GlassSurface';
import { reconciliationBadge } from '../../lib/reconciliationBadge';
import { radius, spacing, typography, useTheme } from '../../theme';
import type { ReconciliationStatus } from '../../types';

export type StatusFilter = 'ALL' | ReconciliationStatus;

// One entry per match status, in the order they are offered. 'OK' is offered too, worded on its
// own: reconciliationBadge returns nothing for it, but "only the ordinary rows" is a real filter
// for someone working through flagged ones.
export const STATUS_FILTERS: ReconciliationStatus[] = [
  'DUPLICATE', 'TRANSFER', 'REFUND', 'REVERSAL', 'INVESTMENT_TRANSFER', 'SUPERSEDED',
];

export function statusFilterLabel(s: StatusFilter): string {
  return s === 'ALL' ? 'Any status' : (reconciliationBadge(s)?.label ?? 'OK');
}

/**
 * The rarely used filters: status, date range, international. Each applies as it is changed, as
 * the chips it replaces did; the button at the bottom only closes the sheet.
 */
export function LedgerFiltersSheet({
  status, onStatus, dateFrom, dateTo, onDateFrom, onDateTo, internationalOnly, onInternational, onClearAll,
  resultCount, onClose,
}: {
  status: StatusFilter;
  onStatus: (s: StatusFilter) => void;
  dateFrom: string | null;
  dateTo: string | null;
  onDateFrom: (v: string | null) => void;
  onDateTo: (v: string | null) => void;
  internationalOnly: boolean;
  onInternational: (on: boolean) => void;
  onClearAll: () => void;
  /** How many transactions match now; null while that is loading or the request failed. */
  resultCount: number | null;
  onClose: () => void;
}) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const done = resultCount === null
    ? 'Done'
    : `Show ${resultCount.toLocaleString('en-IN')} ${resultCount === 1 ? 'transaction' : 'transactions'}`;

  return (
    <AppModal visible transparent animationType="slide" onRequestClose={onClose}>
      <View style={styles.flex}>
        <Pressable style={styles.backdrop} onPress={onClose} accessibilityLabel="Close filters" />
        <GlassSurface style={[styles.sheet, { paddingBottom: insets.bottom + spacing.ml }]}>
          <View style={styles.head}>
            <Text accessibilityRole="header" style={[typography.cardTitle, { color: c.ink }]}>Filters</Text>
            <Pressable onPress={onClearAll} accessibilityRole="button" accessibilityLabel="Clear all filters" style={styles.clearAll}>
              <Text style={[typography.labelM, { color: c.primary }]}>Clear all</Text>
            </Pressable>
          </View>

          {/* Scrolls: the date picker opens in place on iOS and is taller than what is left. */}
          <ScrollView style={styles.scroll} contentContainerStyle={styles.body} keyboardShouldPersistTaps="handled">
            <Text style={[typography.eyebrow, { color: c.mutedInk }]}>Status</Text>
            <View style={styles.statuses}>
              {(['ALL', 'OK', ...STATUS_FILTERS] as StatusFilter[]).map((s) => {
                const selected = status === s;
                const label = statusFilterLabel(s);
                return (
                  <Pressable
                    key={s}
                    onPress={() => { hapticSelectionIfChanged(status, s); onStatus(s); }}
                    // The chip is 36 high; this makes the target 44.
                    hitSlop={{ top: 4, bottom: 4 }}
                    accessibilityRole="button"
                    accessibilityState={{ selected }}
                    accessibilityLabel={`Filter by status: ${label}`}
                    style={[styles.status, { borderColor: selected ? c.primary : c.border }, selected && { backgroundColor: c.primary }]}
                  >
                    <Text style={[typography.labelS, { color: selected ? c.onPrimary : c.mutedInk }]}>{label}</Text>
                  </Pressable>
                );
              })}
            </View>

            <Text style={[typography.eyebrow, styles.section, { color: c.mutedInk }]}>Date range</Text>
            {/* One under the other, not side by side: the iOS calendar opens inside its field. */}
            <DateField label="From" value={dateFrom} onChange={onDateFrom} />
            <DateField label="To" value={dateTo} onChange={onDateTo} />

            <Pressable
              // The switch below has no handler of its own, so the row gives AppSwitch's tick: a
              // press always flips the value.
              onPress={() => { onInternational(!internationalOnly); hapticSelection(); }}
              accessibilityRole="switch"
              accessibilityState={{ checked: internationalOnly }}
              accessibilityLabel="Show only international transactions"
              style={styles.switchRow}
            >
              <Text style={[typography.labelM, styles.switchLabel, { color: c.ink }]}>International only</Text>
              {/* The row is the control; the switch is its picture. */}
              <View pointerEvents="none" accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
                <AppSwitch value={internationalOnly} trackColor={{ true: c.primary, false: c.border }} />
              </View>
            </Pressable>
          </ScrollView>

          <Pressable testID="filters-done" onPress={onClose} accessibilityRole="button" style={[styles.done, { backgroundColor: c.primary }]}>
            <Text style={[typography.labelM, { color: c.onPrimary }]}>{done}</Text>
          </Pressable>
        </GlassSurface>
      </View>
    </AppModal>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)' },
  sheet: {
    maxHeight: '90%', borderTopLeftRadius: radius.hero, borderTopRightRadius: radius.hero,
    paddingHorizontal: spacing.ml, paddingTop: spacing.ml,
  },
  head: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: spacing.ms },
  clearAll: { minHeight: 44, justifyContent: 'center' },
  scroll: { flexGrow: 0 },
  body: { paddingBottom: spacing.md },
  statuses: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm, marginTop: spacing.sm },
  status: { minHeight: 36, justifyContent: 'center', borderRadius: 999, borderWidth: 1, paddingHorizontal: 14 },
  section: { marginTop: spacing.ml, marginBottom: spacing.sm },
  switchRow: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.ms,
    minHeight: 48, marginTop: spacing.sm,
  },
  switchLabel: { flexShrink: 1 },
  done: { minHeight: 50, borderRadius: 999, alignItems: 'center', justifyContent: 'center', marginTop: spacing.sm },
});
