import { useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useQueryClient } from '@tanstack/react-query';
import { aiSortApi, type AiSortChoice, type AiSortOffer, type AiSortRun } from '../api/endpoints';
import { Button } from './Button';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import { spacing, typography, useTheme } from '../theme';

/** Draft wording for counsel (spec docs/superpowers/specs/2026-11-14-ai-sort-after-import-design.md). */
export const AI_QUESTION = 'Fynora can use AI to suggest categories for these payments. It sends the payment text, '
  + 'with names and account numbers removed, to Anthropic. You can turn this off any time in Settings.';

/** How often a running run is read. */
export const POLL_MS = 2000;

function rupees(n: number) {
  return '₹' + Math.round(n).toLocaleString('en-IN');
}

/**
 * The import Done step's AI sorting card: "AI can sort 12 shop payments", a tap to sort them, then
 * what it sorted. A customer who said no to AI sees the one-more question here instead. The server
 * decides whether to show it (AiSortService); this renders nothing when not offered or when the offer
 * cannot be read -- the import itself succeeded either way. Mirrors frontend/src/components/AiSortCard.tsx.
 */
export function AiSortCard({ statementImportIds, onOpenQuickSort }: {
  statementImportIds: string[];
  onOpenQuickSort: () => void;
}) {
  const c = useTheme();
  const queryClient = useQueryClient();
  const [offer, setOffer] = useState<AiSortOffer | null>(null);
  const [run, setRun] = useState<AiSortRun | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [gone, setGone] = useState(false);
  const idsKey = statementImportIds.join(',');

  useEffect(() => {
    if (!idsKey) return;
    let live = true;
    aiSortApi.offer(idsKey.split(','))
      .then((o) => { if (live) setOffer(o); })
      .catch(() => { if (live) setOffer(null); });
    return () => { live = false; };
  }, [idsKey]);

  const running = run?.status === 'RUNNING';
  useEffect(() => {
    if (!run || run.status !== 'RUNNING') return;
    const timer = setTimeout(() => {
      // A failed read keeps the progress shown and tries again on the next tick.
      aiSortApi.run(run.id).then(setRun).catch(() => setRun((r) => (r ? { ...r } : r)));
    }, POLL_MS);
    return () => clearTimeout(timer);
  }, [run]);

  // The rows it filed move every financial figure and Quick sort's questions.
  const finished = !!run && !running;
  useEffect(() => {
    if (finished) invalidateFinancialData(queryClient);
  }, [finished, queryClient]);

  async function choose(choice: AiSortChoice) {
    setBusy(true);
    setError(null);
    try {
      const started = await aiSortApi.start(idsKey.split(','), choice);
      if (choice === 'NO_THANKS' || !started) setGone(true);
      else setRun(started);
    } catch {
      setError("Couldn't start sorting. Try again.");
    } finally {
      setBusy(false);
    }
  }

  if (gone || !offer?.show || !offer.mode) return null;

  const line = offer.payments === 1
    ? `AI can sort 1 shop payment (${rupees(offer.amount)})`
    : `AI can sort ${offer.payments} shop payments (${rupees(offer.amount)})`;

  return (
    <View style={styles.wrap} testID="ai-sort-card">
      <Text style={[typography.labelM, { color: c.ink }]}>{line}</Text>
      {run ? (
        <View accessibilityLiveRegion="polite" style={styles.result}>
          {running ? (
            <Text style={[typography.bodyS, { color: c.ink }]}>Sorting… {run.paymentsDone} of {run.paymentsTotal}</Text>
          ) : (
            <RunResult run={run} onOpenQuickSort={onOpenQuickSort} />
          )}
        </View>
      ) : (
        <>
          {offer.mode === 'ASK' ? (
            <Text style={[typography.caption, { color: c.mutedInk }]}>{AI_QUESTION}</Text>
          ) : null}
          {error ? <Text style={[typography.caption, { color: c.dangerInk }]}>{error}</Text> : null}
          {offer.mode === 'SORT' ? (
            <Button label="Sort with AI" onPress={() => void choose('SORT')} disabled={busy} />
          ) : (
            <View style={styles.buttons}>
              <Button label="Turn on AI and sort" onPress={() => void choose('TURN_ON_AND_SORT')} disabled={busy} />
              <Button label="No thanks" variant="secondary" onPress={() => void choose('NO_THANKS')} disabled={busy} />
            </View>
          )}
        </>
      )}
    </View>
  );
}

/** How a finished run ended, in the spec's words. */
function RunResult({ run, onOpenQuickSort }: { run: AiSortRun; onOpenQuickSort: () => void }) {
  const c = useTheme();
  const sorted = run.paymentsSorted;
  const text = { color: c.ink };
  if (run.status === 'STOPPED_LIMIT') {
    return <Text style={[typography.bodyS, text]}>Sorted {sorted}; AI has reached today&apos;s limit — the rest stay in Quick sort</Text>;
  }
  if (run.status === 'STOPPED_AI_OFF') {
    return <Text style={[typography.bodyS, text]}>AI was turned off — the rest stay in Quick sort</Text>;
  }
  if (run.status === 'STOPPED') {
    return <Text style={[typography.bodyS, text]}>Sorted {sorted} — the rest stay in Quick sort</Text>;
  }
  if (sorted === 0) {
    return <Text style={[typography.bodyS, text]}>AI couldn&apos;t sort these — they stay in Quick sort</Text>;
  }
  return (
    <View style={styles.result}>
      <Text style={[typography.bodyS, text]}>Sorted {sorted} — check them in Quick sort</Text>
      {run.paymentsLeft > 0 ? (
        <Text style={[typography.caption, { color: c.mutedInk }]}>{run.paymentsLeft} left for you</Text>
      ) : null}
      <Pressable accessibilityRole="link" accessibilityLabel="Check them in Quick sort" onPress={onOpenQuickSort}
        style={styles.link}>
        <Text style={[typography.labelS, { color: c.primary }]}>Open Quick sort</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: spacing.sm },
  result: { gap: spacing.xs },
  buttons: { gap: spacing.sm },
  // 44pt tall, a comfortable touch target like the panel's other controls.
  link: { minHeight: 44, justifyContent: 'center', alignSelf: 'flex-start' },
});
