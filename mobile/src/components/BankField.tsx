import { useMemo, useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { banksApi } from '../api/endpoints';
import { bankOptionsFrom, filterBankOptions, matchingBanks, type BankOption } from '../lib/bankChoice';
import { hapticSelectionIfChanged } from '../lib/haptics';
import { radius, spacing, useTheme } from '../theme';

/** How many real banks the list shows at once. Typing narrows it; "Other bank" is always added. */
const VISIBLE_BANKS = 8;

interface Props {
  /** The chosen bank, or null when none is chosen yet. */
  value: BankOption | null;
  onChange: (bank: BankOption) => void;
  /** A short line under the field: where a pre-filled bank came from, or why a choice is needed. */
  hint?: string | null;
  /** Show the hint in the warning tone: the import is waiting on this answer. */
  hintIsWarning?: boolean;
  /** Open the search list on mount even though a bank is set (the "Which bank is this?" prompt).
   *  With no bank set the list always starts open. */
  startOpen?: boolean;
  /** Prefix for testIDs, so two fields on one screen never collide. */
  testID?: string;
}

/**
 * The Bank field: the chosen bank, and a search over every bank the backend knows (GET /banks),
 * with "Other bank" always last (spec 2026-10-22-bank-from-profile-and-required-bank-design.md,
 * sections 3 and 4).
 *
 * Inline rather than a picker sheet on purpose. AccountFormSheet is itself a native Modal, and a
 * Modal opened inside another one, then closed with it in the same commit, leaves an invisible layer
 * that swallows every touch on iOS until the app is killed (seen 2026-10-16, fixed for the category
 * picker in #2226). An inline list has no second Modal to get wrong, and it works the same inside
 * the import review's list header. No FlatList either: this sits inside a scrolling parent, so the
 * list is kept short and searching narrows it.
 *
 * A failed bank list never blocks: "Other bank" is still offered, so nobody is stopped from
 * finishing an import because their bank is missing or the list did not load.
 */
export function BankField({ value, onChange, hint, hintIsWarning, startOpen, testID = 'bank-field' }: Props) {
  const c = useTheme();
  const [open, setOpen] = useState(value === null || !!startOpen);
  const [query, setQuery] = useState('');

  const banksQ = useQuery({
    queryKey: ['banks'],
    queryFn: () => banksApi.list(),
    // The registry changes only when an admin adds a bank.
    staleTime: 60 * 60 * 1000,
  });
  const options = useMemo(() => bankOptionsFrom(banksQ.data ?? []), [banksQ.data]);
  const shown = filterBankOptions(options, query, VISIBLE_BANKS);
  const matchCount = matchingBanks(options, query).length;
  const searching = query.trim().length > 0;

  function choose(bank: BankOption) {
    hapticSelectionIfChanged(value?.id ?? null, bank.id);
    onChange(bank);
    setOpen(false);
    setQuery('');
  }

  return (
    <View style={styles.wrap}>
      <Text style={[styles.label, { color: c.muted }]}>Bank</Text>
      <View style={[styles.valueRow, { borderColor: c.border, backgroundColor: c.inputBg }]}>
        <Text
          style={[styles.value, { color: value ? c.ink : c.muted }]}
          numberOfLines={2}
          testID={`${testID}-value`}
        >
          {value ? value.name : 'Not chosen yet'}
        </Text>
        {!open ? (
          <Pressable
            onPress={() => setOpen(true)}
            hitSlop={8}
            accessibilityRole="button"
            accessibilityLabel="Change bank"
            testID={`${testID}-change`}
          >
            <Text style={[styles.change, { color: c.primary }]}>Change</Text>
          </Pressable>
        ) : null}
      </View>
      {hint ? (
        <Text
          style={[styles.hint, { color: hintIsWarning ? c.warningInk : c.muted }]}
          testID={`${testID}-hint`}
        >
          {hint}
        </Text>
      ) : null}

      {open ? (
        <View style={styles.picker}>
          <TextInput
            value={query}
            onChangeText={setQuery}
            placeholder="Search banks"
            placeholderTextColor={c.muted}
            autoCorrect={false}
            autoCapitalize="none"
            accessibilityLabel="Search banks"
            testID={`${testID}-search`}
            style={[styles.input, { color: c.ink, borderColor: c.border, backgroundColor: c.inputBg }]}
          />
          {banksQ.isLoading ? (
            <Text style={[styles.hint, { color: c.muted }]}>Loading banks…</Text>
          ) : banksQ.isError && !banksQ.data ? (
            <View style={styles.loadFailed}>
              <Text style={[styles.hint, { color: c.dangerInk }]}>
                Couldn&apos;t load the bank list. You can still choose Other bank.
              </Text>
              <Pressable onPress={() => void banksQ.refetch()} hitSlop={8} accessibilityRole="button">
                <Text style={[styles.change, { color: c.primary }]}>Retry</Text>
              </Pressable>
            </View>
          ) : searching && matchCount === 0 ? (
            <Text style={[styles.hint, { color: c.muted }]}>
              No bank matches. Choose Other bank if yours isn&apos;t listed.
            </Text>
          ) : null}
          {shown.map((bank) => {
            const selected = bank.id === value?.id;
            return (
              <Pressable
                key={bank.id}
                onPress={() => choose(bank)}
                accessibilityRole="button"
                accessibilityState={{ selected }}
                accessibilityLabel={bank.name}
                testID={`${testID}-option-${bank.id}`}
                style={[
                  styles.option,
                  { borderColor: selected ? c.primary : c.border, backgroundColor: selected ? c.primaryLight : 'transparent' },
                ]}
              >
                <Text style={[styles.optionText, { color: selected ? c.primary : c.ink }]} numberOfLines={2}>
                  {bank.name}
                </Text>
              </Pressable>
            );
          })}
          {matchCount > VISIBLE_BANKS ? (
            <Text style={[styles.hint, { color: c.muted }]}>
              {searching
                ? `${matchCount} banks match. Keep typing to narrow the list.`
                : `Showing ${VISIBLE_BANKS} of ${matchCount}. Search to find yours.`}
            </Text>
          ) : null}
          {value ? (
            <Pressable
              onPress={() => { setOpen(false); setQuery(''); }}
              hitSlop={8}
              accessibilityRole="button"
              testID={`${testID}-keep`}
            >
              <Text style={[styles.change, styles.keep, { color: c.primary }]}>Keep {value.name}</Text>
            </Pressable>
          ) : null}
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { marginTop: spacing.sm },
  label: { fontSize: 11, textTransform: 'uppercase', marginBottom: 4 },
  valueRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.sm,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.sm,
    minHeight: 44,
  },
  value: { flex: 1, fontSize: 15 },
  change: { fontSize: 13, fontWeight: '600' },
  keep: { marginTop: spacing.xs },
  hint: { fontSize: 12, marginTop: 4 },
  picker: { marginTop: spacing.xs, gap: 6 },
  input: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.sm,
    minHeight: 44,
    fontSize: 15,
  },
  loadFailed: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, flexWrap: 'wrap' },
  option: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.sm,
    minHeight: 44,
    justifyContent: 'center',
  },
  optionText: { fontSize: 14 },
});
