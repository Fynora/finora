import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { RouteProp } from '@react-navigation/native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { inflowApi } from '../api/endpoints';
import { Button } from '../components/Button';
import { Card, EmptyState, SectionHeading } from '../components/Card';
import { InflowKindPicker } from '../components/InflowKindPicker';
import { toUserMessage } from '../lib/apiError';
import { fmtCurrency } from '../lib/format';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import type { MoreStackParamList } from '../navigation/types';
import type { ChoiceScope, InflowKind, UnresolvedSender } from '../types';
import { spacing, useTheme } from '../theme';

/** First and last day of the current calendar month, as YYYY-MM-DD. */
function monthBounds(today = new Date()): { start: string; end: string } {
  const y = today.getFullYear(), m = today.getMonth();
  const pad = (n: number) => String(n).padStart(2, '0');
  return { start: `${y}-${pad(m + 1)}-01`, end: `${y}-${pad(m + 1)}-${pad(new Date(y, m + 1, 0).getDate())}` };
}

/** "Money not counted yet" (Plan 2): the credits Fynora left out of income, one row per sender,
 *  each resolvable for every payment from that sender. Mirrors frontend/src/pages/MoneyReview.tsx. */
export function MoneyReviewScreen({ route }: { route: RouteProp<MoreStackParamList, 'MoneyReview'> }) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const queryClient = useQueryClient();
  const { start, end } = route.params ?? monthBounds();
  const [openId, setOpenId] = useState<string | null>(null);
  const [picked, setPicked] = useState<InflowKind | null>(null);
  const [oneRow, setOneRow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [undo, setUndo] = useState<{ transactionId: string; scope: ChoiceScope; label: string } | null>(null);
  const sendersQ = useQuery({ queryKey: ['unresolved-inflows', start, end], queryFn: () => inflowApi.unresolved(start, end) });
  const kindsQ = useQuery({ queryKey: ['inflow-kinds'], queryFn: () => inflowApi.kinds() });

  function refresh() {
    void sendersQ.refetch();
    invalidateFinancialData(queryClient);
  }

  function toggle(s: UnresolvedSender) {
    setOpenId(openId === s.sampleTransactionId ? null : s.sampleTransactionId);
    setPicked(null);
    setOneRow(false);
  }

  async function apply(transactionId: string, scope: ChoiceScope, label: string) {
    if (!picked) return;
    setBusy(true);
    setError(null);
    try {
      await inflowApi.setChoice(transactionId, picked.id, scope);
      setUndo({ transactionId, scope, label });
      setOpenId(null);
      setPicked(null);
      setOneRow(false);
      refresh();
    } catch (e) {
      setError(toUserMessage(e, 'Could not save this choice.'));
    } finally {
      setBusy(false);
    }
  }

  async function undoLast() {
    if (!undo) return;
    try {
      await inflowApi.clearChoice(undo.transactionId, undo.scope);
      setUndo(null);
      refresh();
    } catch (e) {
      setError(toUserMessage(e, 'Could not undo that choice.'));
    }
  }

  return (
    <ScrollView style={{ backgroundColor: c.bg }}
      contentContainerStyle={[styles.content, { paddingTop: insets.top + spacing.md, paddingBottom: insets.bottom + spacing.lg }]}>
      <SectionHeading title="Money not counted yet" />
      <Text style={[styles.small, { color: c.muted }]}>
        {start} – {end}. Tell Fynora what each payment was; it remembers the sender.
      </Text>
      {undo ? (
        <Card style={styles.undo}>
          <Text style={[styles.body, { color: c.ink }]}>Saved for {undo.label}.</Text>
          <Button label="Undo" variant="link" onPress={undoLast} />
        </Card>
      ) : null}
      {error ? <Text style={[styles.small, { color: c.danger }]}>{error}</Text> : null}
      {sendersQ.isError ? <EmptyState message={toUserMessage(sendersQ.error, "Couldn't load this list.")} /> : null}
      {sendersQ.data && sendersQ.data.length === 0 ? <EmptyState message="Everything's sorted" /> : null}
      {sendersQ.data?.map((s) => (
        <Card key={s.sampleTransactionId}>
          <Pressable accessibilityRole="button" accessibilityState={{ expanded: openId === s.sampleTransactionId }}
            onPress={() => toggle(s)}>
            <View style={styles.row}>
              <Text style={[styles.name, { color: c.ink }]} numberOfLines={1}>{s.label}</Text>
              <Text style={[styles.body, { color: c.ink }]}>{fmtCurrency(s.total)}</Text>
            </View>
            <Text style={[styles.small, { color: c.muted }]}>
              {s.count} {s.count === 1 ? 'payment' : 'payments'} · latest {s.latestDate}{s.accountName ? ` · ${s.accountName}` : ''}
            </Text>
          </Pressable>
          {openId === s.sampleTransactionId && kindsQ.data ? (
            <View style={styles.editor}>
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
              {picked && !oneRow ? (
                <View style={styles.editor}>
                  {s.senderKnown ? (
                    <Button label={`Every payment from ${s.label} (${s.senderPaymentCount})`} loading={busy}
                      onPress={() => apply(s.sampleTransactionId, 'SENDER', s.label)} />
                  ) : null}
                  <Button label="Just this one" variant={s.senderKnown ? 'link' : 'primary'} disabled={busy}
                    onPress={() => (s.rows.length === 1 ? apply(s.rows[0].id, 'ROW', s.label) : setOneRow(true))} />
                </View>
              ) : null}
              {picked && oneRow ? s.rows.map((r) => (
                <Pressable key={r.id} accessibilityRole="button" disabled={busy} onPress={() => apply(r.id, 'ROW', s.label)}
                  style={styles.row}>
                  <Text style={[styles.body, { color: c.ink }]}>{r.date}</Text>
                  <Text style={[styles.body, { color: c.ink }]}>{fmtCurrency(r.amount)}</Text>
                </Pressable>
              )) : null}
            </View>
          ) : null}
        </Card>
      ))}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: { paddingHorizontal: spacing.md, gap: spacing.sm },
  row: { flexDirection: 'row', justifyContent: 'space-between', gap: spacing.sm, paddingVertical: spacing.xs },
  name: { fontSize: 15, fontWeight: '600', flexShrink: 1 },
  body: { fontSize: 14 },
  small: { fontSize: 12 },
  undo: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  editor: { gap: spacing.sm, marginTop: spacing.sm },
});
