import { useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useQueryClient } from '@tanstack/react-query';
import { Card, SectionHeading } from './Card';
import { CategoryPickerModal } from './CategoryPickerModal';
import { ProgressBar } from './dashboard/ProgressBar';
import { transactionsApi } from '../api/endpoints';
import { fmtCurrency, fmtDate } from '../lib/format';
import { hapticError, hapticSuccess } from '../lib/haptics';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import { spacing, useTheme } from '../theme';
import type { QuickSortBatch, QuickSortKind, QuickSortQuestion } from '../types';

const PROMPT: Record<QuickSortKind, string> = {
  PERSON_PAID: 'What was this person paid for?',
  SHOP: 'What kind of shop is this?',
  GUESS: '',
  MONEY_IN: 'What was this money?',
};

/**
 * Quick sort on mobile: a few payee questions, biggest money first, one at a time -- the same
 * questions, answers and wording as the web's QuickSortCard. The server (QuickSortService) decides
 * the batch and the answers offered. Laid out for a phone: the count sits beside the title over a
 * progress bar, the saved answer is named above the next question, and "Skip for now" sits apart
 * from the answer chips. {@code onLoaded} tells the screen how many questions the first batch
 * has, so it can keep its full review lists one tap away while there are questions to answer.
 */
export function QuickSortPanel({ onLoaded }: { onLoaded?: (questions: number) => void }) {
  const c = useTheme();
  const queryClient = useQueryClient();
  const [batch, setBatch] = useState<QuickSortBatch | null>(null);
  const [index, setIndex] = useState(0);
  const [startTotal, setStartTotal] = useState<number | null>(null);
  const [answeredInBatch, setAnsweredInBatch] = useState(0);
  const [skipped, setSkipped] = useState(0);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [allSorted, setAllSorted] = useState(false);
  // The answer just saved, named above the next question: without it the question swaps with
  // nothing on screen saying the last one landed. Cleared by anything that is not a saved answer.
  const [lastSaved, setLastSaved] = useState<{ category: string; payee: string } | null>(null);

  function load(skip: number, first: boolean) {
    transactionsApi.quickSort(skip)
      .then((b) => {
        setBatch(b);
        setIndex(0);
        setAnsweredInBatch(0);
        setStartTotal((prev) => prev ?? b.waitingTotal);
        if (first) onLoaded?.(b.questions.length);
      })
      .catch(() => {
        // The first fetch failing hides the panel: the screen's full lists still work. A later one
        // keeps the panel and says so.
        if (first) {
          setBatch(null);
          onLoaded?.(0);
        } else {
          setError("Couldn't load more questions — please try again.");
        }
      });
  }
  // eslint-disable-next-line react-hooks/exhaustive-deps -- loads once, on mount
  useEffect(() => load(0, true), []);

  // Hidden only when the first batch found nothing waiting; a later empty batch (everything left
  // was skipped) still shows the end of the run.
  if (!batch || !startTotal) return null;

  const current: QuickSortQuestion | undefined = batch.questions[index];
  const sortedPct = startTotal > 0
    ? Math.max(0, Math.min(100, Math.floor(((startTotal - batch.waitingTotal + answeredInBatch) / startTotal) * 100)))
    : 0;

  async function answer(q: QuickSortQuestion, category: string) {
    setSaving(true);
    setError(null);
    setLastSaved(null);
    try {
      await transactionsApi.quickSortAnswer(q.anchorTransactionId, category, q.kind);
      hapticSuccess();
      setAnsweredInBatch((n) => n + q.total);
      setIndex((i) => i + 1);
      setLastSaved({ category, payee: q.payee });
      invalidateFinancialData(queryClient);
    } catch {
      hapticError();
      setError("Couldn't save that answer — please try again.");
    } finally {
      setSaving(false);
    }
  }

  function skip() {
    setSkipped((n) => n + 1);
    setIndex((i) => i + 1);
    setError(null);
    setLastSaved(null);
  }

  async function sortMore() {
    setLastSaved(null);
    try {
      await transactionsApi.quickSortMore();
    } catch {
      // Only a usage counter; the next batch is what matters.
    }
    load(skipped, false);
  }

  function askSkippedAgain() {
    setSkipped(0);
    setError(null);
    setLastSaved(null);
    load(0, false);
  }

  async function stopAsking() {
    if (!batch) return;
    setSaving(true);
    setError(null);
    setLastSaved(null);
    try {
      await transactionsApi.quickSortKeepRest(batch.rest.transactionIds);
      hapticSuccess();
      setAllSorted(true);
      invalidateFinancialData(queryClient);
    } catch {
      hapticError();
      setError("Couldn't stop asking — please try again.");
    } finally {
      setSaving(false);
    }
  }

  const chip = (label: string, onPress: () => void, primary = false) => (
    <Pressable
      key={label}
      onPress={onPress}
      disabled={saving}
      accessibilityRole="button"
      style={[styles.chip, primary
        ? { backgroundColor: c.primary, borderColor: c.primary }
        : { borderColor: c.border }, saving && styles.dimmed]}
    >
      {/* onPrimary, not white: dark mode's primary is near-white, and white on it was unreadable. */}
      <Text style={[styles.chipText, { color: primary ? c.onPrimary : c.ink }]}>{label}</Text>
    </Pressable>
  );

  const pct = allSorted ? 100 : sortedPct;

  return (
    <Card style={styles.card}>
      <SectionHeading
        title="Quick sort"
        action={current && !allSorted ? (
          <Text style={[styles.counter, { color: c.mutedInk }]}>{index + 1} of {batch.questions.length}</Text>
        ) : undefined}
      />
      <ProgressBar percent={pct} color={c.primary} testID="quick-sort-progress" />
      <Text style={[styles.progress, { color: c.mutedInk }]}>
        You've sorted {pct}% of your waiting money
      </Text>

      {lastSaved && !allSorted ? (
        <Text style={[styles.saved, { color: c.successInk }]} accessibilityLiveRegion="polite">
          Saved {lastSaved.category} for {lastSaved.payee}
        </Text>
      ) : null}

      {allSorted ? (
        <Text style={[styles.body, { color: c.ink }]}>All sorted. You can change any category later from the Ledger.</Text>
      ) : current ? (
        <View>
          <Text style={[styles.payee, { color: c.ink }]}>{current.payee}</Text>
          <Text style={[styles.meta, { color: c.mutedInk }]}>
            {current.payments} {current.payments === 1 ? 'payment' : 'payments'} · {fmtCurrency(current.total)} · latest {fmtDate(current.latestDate) ?? current.latestDate}
          </Text>
          {current.largeOneOff ? (
            <Text style={[styles.badge, { color: c.ink, backgroundColor: c.border }]}>Large one-off payment</Text>
          ) : null}
          <Text style={[styles.body, { color: c.ink }]}>
            {current.kind === 'GUESS' ? `Fynora thinks: ${current.currentCategory}` : PROMPT[current.kind]}
          </Text>
          <View style={styles.chips} testID="quick-sort-answers">
            {current.kind === 'GUESS' && current.currentCategory
              ? chip('Correct', () => void answer(current, current.currentCategory!), true)
              : null}
            {current.answers
              .filter((a) => !(current.kind === 'GUESS' && a === current.currentCategory))
              .map((a) => chip(a, () => void answer(current, a)))}
            {chip(current.kind === 'GUESS' ? 'Change' : 'More…', () => setPickerOpen(true))}
          </View>
          {/* Apart from the answers: Skip saves nothing, and as a chip among them it read as one. */}
          <Pressable onPress={skip} disabled={saving} accessibilityRole="button" style={[styles.skip, saving && styles.dimmed]}>
            <Text style={[styles.skipText, { color: c.mutedInk }]}>Skip for now</Text>
          </Pressable>
        </View>
      ) : batch.rest.questions > 0 ? (
        <View>
          <View style={styles.chips}>
            {chip('Sort 10 more', () => void sortMore(), true)}
            {chip('Stop asking about these', () => void stopAsking())}
          </View>
          <Text style={[styles.meta, { color: c.mutedInk }]}>
            {batch.rest.payments} {batch.rest.payments === 1 ? 'payment' : 'payments'} ({fmtCurrency(batch.rest.amount)}) stay as Personal Transfer or Other. You can change any of them later from the Ledger.
          </Text>
        </View>
      ) : skipped > 0 ? (
        <View>
          <Text style={[styles.body, { color: c.ink }]}>
            You skipped {skipped} {skipped === 1 ? "question. It's" : "questions. They're"} still waiting.
          </Text>
          <View style={styles.chips}>{chip('Ask the skipped ones again', askSkippedAgain)}</View>
        </View>
      ) : (
        <Text style={[styles.body, { color: c.ink }]}>All sorted. You can change any category later from the Ledger.</Text>
      )}

      {error ? <Text style={[styles.body, { color: c.dangerInk }]}>{error}</Text> : null}

      <CategoryPickerModal
        visible={pickerOpen}
        title="Choose a category"
        // The pick is the answer; the answer's success or error is its one haptic.
        silent
        selectedName={null}
        onSelect={(category) => {
          setPickerOpen(false);
          if (current) void answer(current, category.name);
        }}
        onClose={() => setPickerOpen(false)}
      />
    </Card>
  );
}

const styles = StyleSheet.create({
  card: { marginBottom: spacing.md },
  progress: { fontSize: 12, marginTop: 6, marginBottom: spacing.sm },
  counter: { fontSize: 12, fontWeight: '600' },
  saved: { fontSize: 12, fontWeight: '600', marginBottom: spacing.sm },
  payee: { fontSize: 16, fontWeight: '700' },
  meta: { fontSize: 12, marginTop: 2 },
  badge: {
    alignSelf: 'flex-start', fontSize: 10, fontWeight: '700', marginTop: 4,
    paddingHorizontal: 6, paddingVertical: 2, borderRadius: 4, overflow: 'hidden',
  },
  body: { fontSize: 14, marginTop: spacing.sm },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: spacing.sm },
  // At least 44pt tall, the smallest comfortable touch target; 8pt of padding alone left them
  // shorter. The padding stays so large text still clears the chip's edge.
  chip: {
    borderWidth: 1, borderRadius: 999, paddingHorizontal: 14, paddingVertical: 8, minHeight: 44, justifyContent: 'center',
  },
  chipText: { fontSize: 14, fontWeight: '600' },
  dimmed: { opacity: 0.5 },
  skip: { alignSelf: 'flex-start', minHeight: 44, justifyContent: 'center', marginTop: spacing.xs },
  skipText: { fontSize: 13, fontWeight: '600' },
});
