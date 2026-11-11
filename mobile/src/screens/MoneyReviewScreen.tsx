import { useState } from 'react';
import { ScrollView, StyleSheet, Text } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { RouteProp } from '@react-navigation/native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { inflowApi } from '../api/endpoints';
import { Button } from '../components/Button';
import { Card, EmptyState, SectionHeading } from '../components/Card';
import { UnresolvedSenderRow } from '../components/inflow/UnresolvedSenderRow';
import { toUserMessage } from '../lib/apiError';
import { hapticError, hapticSuccess } from '../lib/haptics';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import type { MoreStackParamList } from '../navigation/types';
import type { ChoiceScope } from '../types';
import { spacing, useTheme } from '../theme';
import { GlassScreen } from '../components/GlassScreen';
import { padForTabBar, useTabBarSpace } from '../navigation/useTabBarSpace';

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
  const tabBar = useTabBarSpace();
  const insets = useSafeAreaInsets();
  const queryClient = useQueryClient();
  const { start, end } = route.params ?? monthBounds();
  const [openId, setOpenId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [undo, setUndo] = useState<{ transactionId: string; scope: ChoiceScope; label: string } | null>(null);
  const sendersQ = useQuery({ queryKey: ['unresolved-inflows', start, end], queryFn: () => inflowApi.unresolved(start, end) });
  const kindsQ = useQuery({ queryKey: ['inflow-kinds'], queryFn: () => inflowApi.kinds() });

  function refresh() {
    void sendersQ.refetch();
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

  return (
    <GlassScreen style={styles.glassRoot}>
    <ScrollView
      contentContainerStyle={padForTabBar([styles.content, { paddingTop: insets.top + spacing.md, paddingBottom: spacing.lg }], tabBar)} scrollIndicatorInsets={{ bottom: tabBar }}>
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
      {error ? <Text style={[styles.small, { color: c.dangerInk }]}>{error}</Text> : null}
      {sendersQ.isError ? <EmptyState message={toUserMessage(sendersQ.error, "Couldn't load this list.")} /> : null}
      {sendersQ.data && sendersQ.data.length === 0 ? <EmptyState message="Everything's sorted" /> : null}
      {sendersQ.data?.map((s) => (
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
            queryClient.setQueryData(['inflow-kinds'], [...(kindsQ.data ?? []), created]);
            return created;
          }}
        />
      ))}
    </ScrollView>
    </GlassScreen>
  );
}

const styles = StyleSheet.create({
  glassRoot: { flex: 1 },
  content: { paddingHorizontal: spacing.md, gap: spacing.sm },
  body: { fontSize: 14 },
  small: { fontSize: 12 },
  undo: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
});
