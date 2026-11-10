import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { inflowApi } from '../../api/endpoints';
import { Button } from '../Button';
import { Card } from '../Card';
import { UnresolvedSenderRow } from './UnresolvedSenderRow';
import { toUserMessage } from '../../lib/apiError';
import { hapticError, hapticSuccess } from '../../lib/haptics';
import { invalidateFinancialData } from '../../lib/invalidateFinancialData';
import type { ChoiceScope, InflowKind } from '../../types';
import { spacing, typography, useTheme } from '../../theme';

const SHOWN = 3;

/**
 * The import Done step's question: the payments this import added that Fynora does not count as
 * income yet, asked while the user still remembers them. Up to three senders, biggest first; "See
 * all" opens Money Review for the same days. Renders nothing when there is nothing to ask or the
 * list cannot be read -- the import itself succeeded either way. Mirrors
 * frontend/src/components/inflow/ImportMoneyInCard.tsx.
 */
export function ImportMoneyInCard({ statementImportIds, onSeeAll }: {
  statementImportIds: string[];
  onSeeAll: (start: string, end: string) => void;
}) {
  const c = useTheme();
  const queryClient = useQueryClient();
  const [openId, setOpenId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [undo, setUndo] = useState<{ transactionId: string; scope: ChoiceScope; label: string } | null>(null);
  const [skipped, setSkipped] = useState(false);
  // One query per import, each caching the response as the server sent it; merged below.
  const importQs = useQueries({
    queries: statementImportIds.map((id) => ({
      queryKey: ['unresolved-inflows-import', id],
      queryFn: () => inflowApi.unresolvedForImport(id),
    })),
  });
  const kindsQ = useQuery({ queryKey: ['inflow-kinds'], queryFn: () => inflowApi.kinds(), enabled: statementImportIds.length > 0 });

  function refresh() {
    importQs.forEach((q) => void q.refetch());
    invalidateFinancialData(queryClient);
  }

  async function apply(transactionId: string, kindId: string, scope: ChoiceScope, label: string) {
    setBusy(true);
    setError(null);
    try {
      await inflowApi.setChoice(transactionId, kindId, scope);
      hapticSuccess();
      setUndo({ transactionId, scope, label });
      setOpenId(null);
      refresh();
    } catch (e) {
      hapticError();
      setError(toUserMessage(e, 'Could not save this choice.'));
    } finally {
      setBusy(false);
    }
  }

  async function undoLast() {
    if (!undo) return;
    try {
      await inflowApi.clearChoice(undo.transactionId, undo.scope);
      hapticSuccess();
      setUndo(null);
      refresh();
    } catch (e) {
      hapticError();
      setError(toUserMessage(e, 'Could not undo that choice.'));
    }
  }

  if (skipped || importQs.length === 0 || importQs.some((q) => q.isError || !q.data)) return null;
  const senders = importQs.flatMap((q) => q.data ?? []).sort((a, b) => b.total - a.total);
  if (senders.length === 0 && !undo) return null;

  const payments = senders.reduce((n, s) => n + s.count, 0);
  const dates = senders.flatMap((s) => s.rows.map((r) => r.date)).sort();

  return (
    <View style={styles.wrap} testID="import-money-in-card">
      {payments > 0 ? (
        <View style={styles.head}>
          <Text style={[typography.labelM, { color: c.ink }]}>
            {payments === 1
              ? "1 payment you received isn't counted as income"
              : `${payments} payments you received aren't counted as income`}
          </Text>
          <Text style={[typography.caption, { color: c.mutedInk }]}>What were they? This decides if they count as income.</Text>
        </View>
      ) : null}
      {undo ? (
        <Card style={styles.undo}>
          <Text style={[typography.bodyS, { color: c.ink }]}>Saved for {undo.label}.</Text>
          <Button label="Undo" variant="link" onPress={undoLast} />
        </Card>
      ) : null}
      {error ? <Text style={[typography.caption, { color: c.dangerInk }]}>{error}</Text> : null}
      {senders.slice(0, SHOWN).map((s) => (
        <UnresolvedSenderRow
          key={s.sampleTransactionId}
          sender={s}
          kinds={kindsQ.data}
          open={openId === s.sampleTransactionId}
          onToggle={() => setOpenId(openId === s.sampleTransactionId ? null : s.sampleTransactionId)}
          busy={busy}
          onApply={apply}
          onCreateKind={async (name, countsAsIncome) => {
            const created = await inflowApi.createKind(name, countsAsIncome);
            queryClient.setQueryData<InflowKind[]>(['inflow-kinds'], [...(kindsQ.data ?? []), created]);
            return created;
          }}
        />
      ))}
      {payments > 0 ? (
        <View style={styles.links}>
          {senders.length > SHOWN && dates.length > 0 ? (
            <Pressable accessibilityRole="link" hitSlop={8} onPress={() => onSeeAll(dates[0], dates[dates.length - 1])}>
              <Text style={[typography.labelS, { color: c.primary }]}>See all</Text>
            </Pressable>
          ) : null}
          <Pressable accessibilityRole="button" hitSlop={8} onPress={() => setSkipped(true)}>
            <Text style={[typography.labelS, { color: c.mutedInk }]}>Skip for now</Text>
          </Pressable>
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: spacing.sm },
  head: { gap: 2 },
  undo: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  links: { flexDirection: 'row', justifyContent: 'flex-end', gap: spacing.md, flexWrap: 'wrap' },
});
