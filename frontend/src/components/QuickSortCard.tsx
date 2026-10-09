import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Sparkles } from 'lucide-react';
import { transactionsApi } from '../api/endpoints';
import { CategoryCombobox } from './CategoryCombobox';
import { CategoryCreateEditPanel } from './CategoryCreateEditPanel';
import type { QuickSortBatch, QuickSortKind, QuickSortQuestion } from '../types';

function fmt(n: number) {
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

const PROMPT: Record<QuickSortKind, string> = {
  PERSON_PAID: 'What was this person paid for?',
  SHOP: 'What kind of shop is this?',
  GUESS: '',
  MONEY_IN: 'What was this money?',
};

/** Query keys every review card refreshes after a category change. */
const AFFECTED_QUERIES = ['transactions', 'recent-transactions', 'dashboard-summary', 'insights', 'budgets'];

/**
 * Quick sort: a few payee questions, biggest money first, in place of studying every waiting row.
 * The server (QuickSortService) decides the batch -- 80% of the waiting money or 10 questions --
 * and the answers offered; this card asks one question at a time. Progress is measured against
 * the waiting money when the card first loaded, so it survives "Sort 10 more".
 */
export function QuickSortCard() {
  const queryClient = useQueryClient();
  const [batch, setBatch] = useState<QuickSortBatch | null>(null);
  const [index, setIndex] = useState(0);
  const [startTotal, setStartTotal] = useState<number | null>(null);
  const [answeredInBatch, setAnsweredInBatch] = useState(0);
  const [skipped, setSkipped] = useState(0);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showMore, setShowMore] = useState(false);
  const [creating, setCreating] = useState<string | null>(null);
  const [allSorted, setAllSorted] = useState(false);

  function load(skip: number, first = false) {
    transactionsApi.quickSort(skip)
      .then((b) => {
        setBatch(b);
        setIndex(0);
        setAnsweredInBatch(0);
        setStartTotal((prev) => prev ?? b.waitingTotal);
      })
      .catch(() => {
        // The first fetch failing leaves the card hidden: the full review lists still work. A
        // later one keeps the card and says so.
        if (first) setBatch(null);
        else setError("Couldn't load more questions — please try again.");
      });
  }
  useEffect(() => load(0, true), []);

  // Hidden only when the first batch found nothing waiting; a later empty batch (everything left
  // was skipped) still shows the end of the run.
  if (!batch || !startTotal) return null;

  const current: QuickSortQuestion | undefined = batch.questions[index];
  const sortedPct = startTotal > 0
    ? Math.max(0, Math.min(100, Math.floor(((startTotal - batch.waitingTotal + answeredInBatch) / startTotal) * 100)))
    : 0;

  function refreshViews() {
    for (const key of AFFECTED_QUERIES) void queryClient.invalidateQueries({ queryKey: [key] });
  }

  async function answer(q: QuickSortQuestion, category: string) {
    setSaving(true);
    setError(null);
    try {
      await transactionsApi.quickSortAnswer(q.anchorTransactionId, category, q.kind);
      setAnsweredInBatch((n) => n + q.total);
      setIndex((i) => i + 1);
      setShowMore(false);
      setCreating(null);
      refreshViews();
    } catch {
      setError("Couldn't save that answer — please try again.");
    } finally {
      setSaving(false);
    }
  }

  function skip() {
    setSkipped((n) => n + 1);
    setIndex((i) => i + 1);
    setShowMore(false);
    setError(null);
  }

  async function sortMore() {
    setError(null);
    try {
      await transactionsApi.quickSortMore();
    } catch {
      // Only a usage counter; the next batch is what matters.
    }
    load(skipped);
  }

  function askSkippedAgain() {
    setSkipped(0);
    setError(null);
    load(0);
  }

  async function stopAsking() {
    if (!batch) return;
    setSaving(true);
    setError(null);
    try {
      await transactionsApi.quickSortKeepRest(batch.rest.transactionIds);
      setAllSorted(true);
      refreshViews();
    } catch {
      setError("Couldn't stop asking — please try again.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="rounded-xl border border-border bg-card p-4 space-y-3" aria-label="Quick sort">
      <div className="flex items-center justify-between gap-2">
        <h2 className="flex items-center gap-2 text-sm font-semibold text-ink">
          <Sparkles size={16} className="text-primary" /> Quick sort
          {!allSorted && current && (
            <span className="text-xs font-normal text-muted">
              Question {index + 1} of {batch.questions.length}
            </span>
          )}
        </h2>
        <p className="text-xs text-muted">You've sorted {allSorted ? 100 : sortedPct}% of your waiting money</p>
      </div>

      {allSorted && <p className="text-sm text-ink">All sorted. You can change any category later from the Ledger.</p>}

      {!allSorted && current && (
        <div className="space-y-3">
          <div>
            <p className="text-base font-semibold text-ink">{current.payee}</p>
            <p className="text-xs text-muted">
              {current.payments} {current.payments === 1 ? 'payment' : 'payments'} · {fmt(current.total)} · latest {current.latestDate}
            </p>
            {current.largeOneOff && (
              <p className="mt-1 inline-block rounded bg-accent-blue-bg px-2 py-0.5 text-xs text-accent-blue">Large one-off payment</p>
            )}
          </div>

          <p className="text-sm text-ink">
            {current.kind === 'GUESS' ? `Fynora thinks: ${current.currentCategory}` : PROMPT[current.kind]}
          </p>

          <div className="flex flex-wrap gap-2">
            {current.kind === 'GUESS' && current.currentCategory && (
              <button type="button" disabled={saving}
                className="rounded-full bg-primary px-3 py-1.5 text-sm font-medium text-on-primary disabled:opacity-60"
                onClick={() => void answer(current, current.currentCategory!)}>
                Correct
              </button>
            )}
            {current.answers
              .filter((a) => !(current.kind === 'GUESS' && a === current.currentCategory))
              .map((a) => (
                <button key={a} type="button" disabled={saving}
                  className="rounded-full border border-border px-3 py-1.5 text-sm text-ink hover:bg-black/5 disabled:opacity-60"
                  onClick={() => void answer(current, a)}>
                  {a}
                </button>
              ))}
            <button type="button" disabled={saving}
              className="rounded-full border border-dashed border-border px-3 py-1.5 text-sm text-muted"
              onClick={() => setShowMore((v) => !v)}>
              {current.kind === 'GUESS' ? 'Change' : 'More…'}
            </button>
            <button type="button" disabled={saving} className="px-3 py-1.5 text-sm text-muted underline" onClick={skip}>
              Skip
            </button>
          </div>

          {showMore && (creating !== null ? (
            <CategoryCreateEditPanel
              mode="create"
              initialName={creating}
              onSaved={(c) => void answer(current, c.name)}
              onCancel={() => setCreating(null)}
            />
          ) : (
            <CategoryCombobox
              value=""
              onChange={(name) => { if (name) void answer(current, name); }}
              onCreateNew={(text) => setCreating(text)}
            />
          ))}

          {current.samples.length > 0 && (
            <details className="text-xs text-muted">
              <summary className="cursor-pointer">Show payments</summary>
              <ul className="mt-1 space-y-0.5">
                {current.samples.map((s) => (
                  <li key={s.id}>{s.date} · {fmt(s.amount)} · {s.description}</li>
                ))}
              </ul>
            </details>
          )}
        </div>
      )}

      {!allSorted && !current && (
        batch.rest.questions > 0 ? (
          <div className="space-y-2">
            <div className="flex flex-wrap gap-2">
              <button type="button" disabled={saving}
                className="rounded-lg bg-primary px-3 py-2 text-sm font-medium text-on-primary disabled:opacity-60"
                onClick={() => void sortMore()}>
                Sort 10 more
              </button>
              <button type="button" disabled={saving}
                className="rounded-lg border border-border px-3 py-2 text-sm text-ink disabled:opacity-60"
                onClick={() => void stopAsking()}>
                Stop asking about these
              </button>
            </div>
            <p className="text-xs text-muted">
              {batch.rest.payments} {batch.rest.payments === 1 ? 'payment' : 'payments'} ({fmt(batch.rest.amount)}) stay as Personal Transfer or Other. You can change any of them later from the Ledger.
            </p>
          </div>
        ) : skipped > 0 ? (
          <div className="space-y-2">
            <p className="text-sm text-ink">
              You skipped {skipped} {skipped === 1 ? "question. It's" : "questions. They're"} still waiting.
            </p>
            <button type="button"
              className="rounded-lg border border-border px-3 py-2 text-sm text-ink"
              onClick={askSkippedAgain}>
              Ask the skipped ones again
            </button>
          </div>
        ) : (
          <p className="text-sm text-ink">All sorted. You can change any category later from the Ledger.</p>
        )
      )}

      {error && <p className="text-sm text-danger">{error}</p>}
    </section>
  );
}
