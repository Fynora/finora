import { useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { Button } from './Button';
import { toUserMessage } from '../lib/apiError';
import type { InflowKind } from '../types';
import { radius, spacing, useTheme } from '../theme';

/** The kinds a credit can be given, plus "+ New kind…" (Plan 2). Used by the review screen, the
 *  "Counts as" section and Settings. Mirrors frontend/src/components/inflow/InflowKindPicker.tsx. */
export function InflowKindPicker({ kinds, selectedId, onPick, onCreate, busy }: {
  kinds: InflowKind[];
  selectedId: string | null;
  onPick: (kind: InflowKind) => void;
  onCreate: (name: string, countsAsIncome: boolean) => Promise<void>;
  busy?: boolean;
}) {
  const c = useTheme();
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [countsAsIncome, setCountsAsIncome] = useState<boolean | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function submit() {
    if (!name.trim() || countsAsIncome === null) return;
    setSaving(true);
    setError(null);
    try {
      await onCreate(name.trim(), countsAsIncome);
      setCreating(false);
      setName('');
      setCountsAsIncome(null);
    } catch (e) {
      setError(toUserMessage(e, 'Could not create this kind.'));
    } finally {
      setSaving(false);
    }
  }

  return (
    <View style={styles.wrap}>
      <View style={styles.chips}>
        {kinds.map((k) => (
          <Pressable
            key={k.id}
            disabled={busy}
            accessibilityRole="button"
            accessibilityState={{ selected: selectedId === k.id }}
            onPress={() => onPick(k)}
            style={[styles.chip, {
              borderColor: selectedId === k.id ? c.primary : c.border,
              backgroundColor: selectedId === k.id ? c.primaryLight : c.card,
            }]}
          >
            <Text style={[styles.chipName, { color: c.ink }]}>{k.name}</Text>
            <Text style={[styles.chipNote, { color: c.muted }]}>{k.countsAsIncome ? 'income' : 'not income'}</Text>
          </Pressable>
        ))}
        {!creating ? (
          <Pressable accessibilityRole="button" onPress={() => setCreating(true)}
            style={[styles.chip, styles.dashed, { borderColor: c.border }]}>
            <Text style={[styles.chipName, { color: c.muted }]}>+ New kind…</Text>
          </Pressable>
        ) : null}
      </View>
      {creating ? (
        <View style={[styles.form, { borderColor: c.border }]}>
          <TextInput
            accessibilityLabel="Name"
            placeholder="Name"
            placeholderTextColor={c.muted}
            value={name}
            maxLength={60}
            onChangeText={setName}
            style={[styles.input, { borderColor: c.border, color: c.ink, backgroundColor: c.inputBg }]}
          />
          <Text style={[styles.question, { color: c.muted }]}>Count this as income?</Text>
          {[true, false].map((v) => (
            <Pressable key={String(v)} accessibilityRole="radio" accessibilityState={{ checked: countsAsIncome === v }}
              onPress={() => setCountsAsIncome(v)} style={styles.radio}>
              <Text style={[styles.chipName, { color: c.ink }]}>
                {countsAsIncome === v ? '● ' : '○ '}{v ? 'Yes, count it' : "No, don't count it"}
              </Text>
            </Pressable>
          ))}
          {error ? <Text style={[styles.question, { color: c.dangerInk }]}>{error}</Text> : null}
          <Button label="Create" onPress={submit} loading={saving} disabled={!name.trim() || countsAsIncome === null} />
          <Button label="Cancel" variant="link" onPress={() => { setCreating(false); setError(null); }} />
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: spacing.sm },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
  chip: { borderWidth: 1, borderRadius: radius.lg, paddingHorizontal: spacing.sm, paddingVertical: spacing.xs },
  chipName: { fontSize: 13 },
  chipNote: { fontSize: 11 },
  dashed: { borderStyle: 'dashed' },
  form: { borderWidth: 1, borderRadius: radius.lg, padding: spacing.sm, gap: spacing.xs },
  input: { borderWidth: 1, borderRadius: radius.md, paddingHorizontal: spacing.sm, paddingVertical: spacing.xs, fontSize: 14 },
  question: { fontSize: 12 },
  radio: { paddingVertical: spacing.xs },
});
