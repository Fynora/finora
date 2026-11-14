import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Sparkles } from 'lucide-react';
import { transactionsApi } from '../api/endpoints';
import { CategoryCombobox } from './CategoryCombobox';
import { CategoryCreateEditPanel } from './CategoryCreateEditPanel';
import { Link } from 'react-router-dom';
import type { QuickSortBatch, QuickSortKind, QuickSortQuestion } from '../types';
import { formatDate } from '../utils/date';
import { trackNavigation } from '../lib/trackNavigation';
import { patternLine } from '../lib/payeePattern';
import { QuickSortPattern } from './QuickSortPattern';
import { safeStorage } from '../lib/safeStorage';
import {
  DASHBOARD_QUESTION_LIMIT, DASHBOARD_QUICK_SORT_KEY, isHiddenToday, notNowValue,
} from '../lib/dashboardQuickSort';

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
 * and the answers offered; this card asks one question at a time. Progress is the money sorted
 * out of everything that waited, earlier visits included (the batch's sortedTotal), so it carries
 * across "Sort 10 more" and across visits.
 *
 * variant "dashboard" (spec docs/superpowers/specs/2026-11-13-dashboard-quick-sort-design.md): the
 * same questions, at most DASHBOARD_QUESTION_LIMIT per visit, then how many payees are still waiting
 * and a way to the full Quick sort. No progress line, no "Sort 10 more", and never "Stop asking
 * about these": nothing leaves the queue from the Dashboard unless it is answered. "Not now" hides it
 * until tomorrow for accountEmail on this device. Loading, empty and failed all render nothing.
 */
export function QuickSortCard({ variant = 'full', accountEmail = null }: {
  variant?: 'full' | 'dashboard';
  /** The signed-in account, for the dashboard variant's "Not now". */
  accountEmail?: string | null;
} = {}) {
  const dashboard = variant === 'dashboard';
  const [hidden, setHidden] = useState(() =>
    dashboard && isHiddenToday(safeStorage.getItem(DASHBOARD_QUICK_SORT_KEY), accountEmail, new Date()));
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
  // "Stop asking about these" taken for this batch's rest: that money left the queue unsorted, and
  // the rest is no longer offered. Questions skipped earlier are still waiting.
  const [restStopped, setRestStopped] = useState(false);

  function load(skip: number, first = false) {
    (dashboard ? transactionsApi.quickSort(skip, false, 'DASHBOARD') : transactionsApi.quickSort(skip))
      .then((b) => {
        setBatch(b);
        setIndex(0);
        setAnsweredInBatch(0);
        setRestStopped(false);
        setStartTotal((prev) => prev ?? b.waitingTotal);
      })
      .catch(() => {
        // The first fetch failing leaves the card hidden: the full review lists still work. A
        // later one keeps the card and says so.
        if (first) setBatch(null);
        else setError("Couldn't load more questions — please try again.");
      });
  }
  // One fetch per mount; "Not now" earlier today skips it. Mount-only on purpose: "Not now" pressed
  // later sets hidden, which must not refetch, and load is a fresh closure every render.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { if (!hidden) load(0, true); }, []);

  // Hidden only when the first batch found nothing waiting; a later empty batch (everything left
  // was skipped) still shows the end of the run.
  if (hidden || !batch || !startTotal) return null;

  // The dashboard asks the batch's first few, in the server's order.
  const asked = dashboard ? Math.min(batch.questions.length, DASHBOARD_QUESTION_LIMIT) : batch.questions.length;
  const current: QuickSortQuestion | undefined = index < asked ? batch.questions[index] : undefined;
  const restOffered = batch.rest.questions > 0 && !restStopped;
  // Nothing left to ask, offer or come back to.
  const allSorted = !current && !restOffered && skipped === 0;
  // Out of everything that waited, earlier visits included: the server's sorted money, this
  // batch's answers moved across from waiting, and a stopped rest out of the count altogether.
  // A server without sortedTotal counts this batch only. Exactly 100 once all is sorted: amounts
  // carry paise, and summed in another order than the server's total they can land a hair under.
  const sortedBefore = batch.sortedTotal ?? 0;
  const everWaiting = sortedBefore + batch.waitingTotal - (restStopped ? batch.rest.amount : 0);
  const sortedPct = allSorted ? 100 : everWaiting > 0
    ? Math.max(0, Math.min(100, Math.floor(((sortedBefore + answeredInBatch) / everWaiting) * 100)))
    : 0;

  function refreshViews() {
    for (const key of AFFECTED_QUERIES) void queryClient.invalidateQueries({ queryKey: [key] });
  }

  async function answer(q: QuickSortQuestion, category: string) {
    setSaving(true);
    setError(null);
    try {
      await (dashboard
        ? transactionsApi.quickSortAnswer(q.anchorTransactionId, category, q.kind, 'DASHBOARD')
        : transactionsApi.quickSortAnswer(q.anchorTransactionId, category, q.kind));
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
      setRestStopped(true);
      refreshViews();
    } catch {
      setError("Couldn't stop asking — please try again.");
    } finally {
      setSaving(false);
    }
  }

  function notNow() {
    safeStorage.setItem(DASHBOARD_QUICK_SORT_KEY, notNowValue(accountEmail, new Date()));
    setHidden(true);
  }

  if (dashboard) {
    // Still waiting once this visit's few are done: the batch's unasked questions, the ones skipped
    // here (skipping files nothing), and every payee after the batch.
    const stillWaiting = batch.questions.length - asked + skipped + batch.rest.questions;
    if (!current && stillWaiting === 0) return null;
    return (
      <section className="rounded-xl border border-border bg-card p-4 space-y-3" aria-label="Sort a few payments">
        <div className="flex items-center justify-between gap-2">
          <h2 className="flex items-center gap-2 text-sm font-semibold text-ink">
            <Sparkles size={16} className="text-primary" /> Sort a few payments
          </h2>
          {current && <span className="text-xs text-muted">{index + 1} of {asked}</span>}
        </div>

        {current ? (
          <div className="space-y-3">
            <div>
              <p className="text-base font-semibold text-ink">{current.payee}</p>
              {patternLine(current) ? <QuickSortPattern key={current.id} question={current} /> : (
                <p className="text-xs text-muted">
                  {current.payments} {current.payments === 1 ? 'payment' : 'payments'} · {fmt(current.total)}
                </p>
              )}
            </div>
            <p className="text-sm text-ink">
              {current.kind === 'GUESS' ? `Fynora thinks: ${current.currentCategory}` : PROMPT[current.kind]}
            </p>
            <div className="flex flex-wrap gap-2">
              {current.kind === 'GUESS' && current.currentCategory && (
                <button type="button" disabled={saving}
                  className="rounded-full border border-border px-3 py-1.5 text-sm text-ink hover:bg-black/5 disabled:opacity-60"
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
            <div className="flex justify-end gap-3">
              <button type="button" disabled={saving} className="text-sm text-muted underline" onClick={skip}>
                Skip
              </button>
              <button type="button" disabled={saving} className="text-sm text-muted underline" onClick={notNow}>
                Not now
              </button>
            </div>
          </div>
        ) : (
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="text-sm text-ink">
              {stillWaiting} {stillWaiting === 1 ? 'payee' : 'payees'} still waiting
            </p>
            <Link to="/app/transactions" onClick={() => trackNavigation('transactions', 'contextual')}
              className="text-sm font-medium text-primary underline">
              Open Quick sort
            </Link>
          </div>
        )}

        {error && <p className="text-sm text-danger">{error}</p>}
      </section>
    );
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
        <p className="text-xs text-muted">You've sorted {sortedPct}% of your waiting money</p>
      </div>

      {allSorted && <p className="text-sm text-ink">All sorted. You can change any category later from the Ledger.</p>}

      {!allSorted && current && (
        <div className="space-y-3">
          <div>
            <p className="text-base font-semibold text-ink">{current.payee}</p>
            {patternLine(current) ? <QuickSortPattern key={current.id} question={current} /> : (
              <p className="text-xs text-muted">
                {current.payments} {current.payments === 1 ? 'payment' : 'payments'} · {fmt(current.total)} · latest {formatDate(current.latestDate) || current.latestDate}
              </p>
            )}
            {current.largeOneOff && (
              <p className="mt-1 inline-block rounded bg-accent-blue-bg px-2 py-0.5 text-xs text-ink">Large one-off payment</p>
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

          {/* Only without a pattern (an older server, or a one-row question): the pattern line opens the
              payee's payments itself. */}
          {!patternLine(current) && current.samples.length > 0 && (
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
        restOffered ? (
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
        ) : (
          // Not all sorted, nothing to ask or offer: only skipped questions are left.
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
        )
      )}

      {error && <p className="text-sm text-danger">{error}</p>}
    </section>
  );
}
