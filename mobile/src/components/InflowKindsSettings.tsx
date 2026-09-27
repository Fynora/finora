import { useState } from 'react';
import { StyleSheet, Switch, Text, TextInput, View } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { inflowApi } from '../api/endpoints';
import { Button } from './Button';
import { Card, SectionHeading } from './Card';
import { InflowKindPicker } from './InflowKindPicker';
import { apiErrorDetails, toUserMessage } from '../lib/apiError';
import { AppAlert } from '../lib/appAlert';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import type { InflowKind, SenderRule } from '../types';
import { radius, spacing, useTheme } from '../theme';

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

/** Settings → Categorization: the user's money kinds and remembered senders (Plan 2). A kind can
 *  only be deleted once nothing uses it, so a delete never silently changes a total. Mirrors
 *  frontend/src/pages/settings/InflowKindsSection.tsx. */
export function InflowKindsSettings() {
  const c = useTheme();
  const queryClient = useQueryClient();
  const kindsQ = useQuery({ queryKey: ['inflow-kinds'], queryFn: () => inflowApi.kinds() });
  const rulesQ = useQuery({ queryKey: ['sender-inflow-rules'], queryFn: () => inflowApi.senderRules() });
  const [renaming, setRenaming] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [message, setMessage] = useState<string | null>(null);

  async function attempt(action: () => Promise<unknown>, fallback: string): Promise<boolean> {
    setMessage(null);
    try {
      await action();
      invalidateFinancialData(queryClient);
      void kindsQ.refetch();
      void rulesQ.refetch();
      return true;
    } catch (e) {
      const d = apiErrorDetails<{ rows?: number; senders?: number }>(e);
      const usage = d?.rows == null || d?.senders == null ? ''
        : ` Used by ${plural(d.rows, 'payment', 'payments')} and ${plural(d.senders, 'sender', 'senders')}.`;
      setMessage(`${toUserMessage(e, fallback)}${usage}`);
      return false;
    }
  }

  function confirmDelete(k: InflowKind) {
    AppAlert.alert(`Delete "${k.name}"?`, 'Payments and senders using it must be moved to another kind first.', [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Delete', style: 'destructive', onPress: () => { void attempt(() => inflowApi.deleteKind(k.id), 'Could not delete this kind.'); } },
    ]);
  }

  function confirmForget(r: SenderRule) {
    AppAlert.alert(`Forget ${r.label}?`, 'Their payments go back to Fynora\'s own reading.', [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Forget', style: 'destructive', onPress: () => { void attempt(() => inflowApi.forgetSender(r.id), 'Could not forget this sender.'); } },
    ]);
  }

  async function saveName(k: InflowKind) {
    if (await attempt(() => inflowApi.updateKind(k.id, { name: draft }), 'Could not rename this kind.')) setRenaming(null);
  }

  return (
    <>
      <Card style={styles.card}>
        <SectionHeading title="Money kinds" />
        <Text style={[styles.small, { color: c.muted }]}>
          What money coming in can be. Kinds that count as income add to your income; the others are left out of it.
        </Text>
        {message ? <Text style={[styles.small, { color: c.danger }]}>{message}</Text> : null}
        {kindsQ.isError ? <Text style={[styles.small, { color: c.danger }]}>Couldn't load your kinds.</Text> : null}
        {kindsQ.data?.map((k) => (
          <View key={k.id} style={[styles.row, { borderBottomColor: c.border }]}>
            {renaming === k.id ? (
              <TextInput
                accessibilityLabel={`New name for ${k.name}`}
                value={draft}
                maxLength={60}
                onChangeText={setDraft}
                style={[styles.input, { borderColor: c.border, color: c.ink, backgroundColor: c.inputBg }]}
              />
            ) : (
              <Text style={[styles.name, { color: c.ink }]}>{k.name}</Text>
            )}
            <View style={styles.actions}>
              {k.builtIn ? (
                <Text style={[styles.small, { color: c.muted }]}>{k.countsAsIncome ? 'Income' : 'Not income'}</Text>
              ) : (
                <Switch
                  accessibilityLabel={`${k.name} counts as income`}
                  value={k.countsAsIncome}
                  onValueChange={(v) => { void attempt(() => inflowApi.updateKind(k.id, { countsAsIncome: v }), 'Could not change this kind.'); }}
                />
              )}
              {renaming === k.id ? (
                <Button label="Save" variant="link" disabled={!draft.trim()} onPress={() => { void saveName(k); }} />
              ) : (
                <Button label="Rename" variant="link" onPress={() => { setRenaming(k.id); setDraft(k.name); }} />
              )}
              {!k.builtIn ? (
                <Text accessibilityRole="button" accessibilityLabel={`Delete ${k.name}`} onPress={() => confirmDelete(k)}
                  style={[styles.small, { color: c.danger }]}>
                  Delete
                </Text>
              ) : null}
            </View>
          </View>
        ))}
        {kindsQ.data ? (
          <InflowKindPicker kinds={[]} selectedId={null} onPick={() => undefined}
            onCreate={async (name, countsAsIncome) => {
              await inflowApi.createKind(name, countsAsIncome);
              void kindsQ.refetch();
            }} />
        ) : null}
      </Card>
      <Card style={styles.card}>
        <SectionHeading title="Remembered senders" />
        {rulesQ.data && rulesQ.data.length === 0 ? (
          <Text style={[styles.small, { color: c.muted }]}>No senders remembered yet.</Text>
        ) : null}
        {rulesQ.data?.map((r) => (
          <View key={r.id} style={[styles.row, { borderBottomColor: c.border }]}>
            <View style={styles.sender}>
              <Text style={[styles.name, { color: c.ink }]} numberOfLines={1}>{r.label}</Text>
              <Text style={[styles.small, { color: c.muted }]}>{r.kind.name} · {plural(r.rowCount, 'payment', 'payments')}</Text>
            </View>
            <Text accessibilityRole="button" accessibilityLabel={`Forget ${r.label}`} onPress={() => confirmForget(r)}
              style={[styles.small, { color: c.primary }]}>
              Forget
            </Text>
          </View>
        ))}
      </Card>
    </>
  );
}

const styles = StyleSheet.create({
  card: { gap: spacing.sm, marginTop: spacing.md },
  row: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm,
    paddingVertical: spacing.sm, borderBottomWidth: StyleSheet.hairlineWidth,
  },
  actions: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  sender: { flex: 1, minWidth: 0 },
  name: { fontSize: 14, flexShrink: 1 },
  small: { fontSize: 12 },
  input: { flex: 1, borderWidth: 1, borderRadius: radius.md, paddingHorizontal: spacing.sm, paddingVertical: spacing.xs, fontSize: 14 },
});
