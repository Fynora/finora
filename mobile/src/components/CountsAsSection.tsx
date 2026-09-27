import { useState } from 'react';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { inflowApi } from '../api/endpoints';
import { Button } from './Button';
import { InflowKindPicker } from './InflowKindPicker';
import { toUserMessage } from '../lib/apiError';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import type { CountsAs, InflowKind } from '../types';
import { spacing, useTheme } from '../theme';

/** "Counts as" in a transaction's explanation: what this credit counts as, and the user's way to
 *  change or clear it (Plan 2). The caller hides it for debits. Mirrors
 *  frontend/src/components/inflow/CountsAsSection.tsx. */
export function CountsAsSection({ transactionId }: { transactionId: string }) {
  const c = useTheme();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [picked, setPicked] = useState<InflowKind | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const countsAsQ = useQuery({ queryKey: ['counts-as', transactionId], queryFn: () => inflowApi.countsAs(transactionId) });
  const kindsQ = useQuery({ queryKey: ['inflow-kinds'], queryFn: () => inflowApi.kinds(), enabled: editing });

  async function run(action: () => Promise<CountsAs>) {
    setBusy(true);
    setError(null);
    try {
      const next = await action();
      queryClient.setQueryData(['counts-as', transactionId], next);
      setEditing(false);
      setPicked(null);
      invalidateFinancialData(queryClient);
    } catch (e) {
      setError(toUserMessage(e, 'Could not save this choice.'));
    } finally {
      setBusy(false);
    }
  }

  if (countsAsQ.isLoading) return <ActivityIndicator />;
  if (countsAsQ.isError || !countsAsQ.data) {
    return <Text style={[styles.small, { color: c.danger }]}>Couldn't load what this payment counts as.</Text>;
  }
  const countsAs = countsAsQ.data;
  const appliedBy = countsAs.appliedBy;

  return (
    <View style={styles.wrap} testID="counts-as-section">
      <Text style={[styles.small, { color: c.muted }]}>Counts as</Text>
      <Text style={[styles.summary, { color: c.ink }]}>{countsAs.summary}</Text>
      {!countsAs.choosable && countsAs.notChoosableReason ? (
        <Text style={[styles.small, { color: c.muted }]}>{countsAs.notChoosableReason}</Text>
      ) : null}
      {countsAs.choosable && !editing ? (
        <View style={styles.row}>
          <Button label="Change" variant="link" onPress={() => { setEditing(true); setPicked(null); }} />
          {appliedBy ? (
            <Button
              label={appliedBy === 'SENDER'
                ? `Clear for every payment from ${countsAs.senderLabel} (${countsAs.senderRowCount})`
                : 'Clear my choice'}
              variant="link"
              loading={busy}
              onPress={() => run(() => inflowApi.clearChoice(transactionId, appliedBy))} />
          ) : null}
        </View>
      ) : null}
      {editing && kindsQ.data ? (
        <View style={styles.wrap}>
          <InflowKindPicker
            kinds={kindsQ.data}
            selectedId={picked?.id ?? null}
            onPick={setPicked}
            busy={busy}
            onCreate={async (name, countsAsIncome) => {
              const created = await inflowApi.createKind(name, countsAsIncome);
              queryClient.setQueryData(['inflow-kinds'], [...(kindsQ.data ?? []), created]);
              setPicked(created);
            }}
          />
          {picked ? (
            <View style={styles.wrap}>
              {countsAs.senderAvailable ? (
                <Button label={`Every payment from ${countsAs.senderLabel} (${countsAs.senderRowCount})`} loading={busy}
                  onPress={() => run(() => inflowApi.setChoice(transactionId, picked.id, 'SENDER'))} />
              ) : null}
              <Button label="Just this one" variant={countsAs.senderAvailable ? 'link' : 'primary'} loading={busy}
                onPress={() => run(() => inflowApi.setChoice(transactionId, picked.id, 'ROW'))} />
            </View>
          ) : null}
          <Button label="Cancel" variant="link" onPress={() => setEditing(false)} />
        </View>
      ) : null}
      {editing && kindsQ.isError ? <Text style={[styles.small, { color: c.danger }]}>Couldn't load your kinds.</Text> : null}
      {error ? <Text style={[styles.small, { color: c.danger }]}>{error}</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: spacing.sm },
  row: { flexDirection: 'row', gap: spacing.md },
  small: { fontSize: 12 },
  summary: { fontSize: 14 },
});
