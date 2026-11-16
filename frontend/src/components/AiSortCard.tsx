import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { aiSortApi, type AiSortChoice, type AiSortOffer, type AiSortRun } from '../api/endpoints';
import { Button } from '../design-system';
import { useInvalidateMoneyFigures } from '../lib/invalidateMoneyFigures';
import { trackNavigation } from '../lib/trackNavigation';

/** Draft wording for counsel (spec docs/superpowers/specs/2026-11-14-ai-sort-after-import-design.md). */
export const AI_QUESTION = 'Let AI sort these for you? Fynora sends the payment text from your statement to Anthropic\'s AI. '
  + 'We hide the names and account numbers we can spot. You can turn AI off any time in Settings.';

/** How often a running run is read. */
export const POLL_MS = 2000;

function rupees(n: number) {
  return '₹' + Math.round(n).toLocaleString('en-IN');
}

/**
 * The import Done step's AI sorting card: "AI can sort 12 shop payments", a tap to sort them, then
 * what it sorted. A customer who said no to AI sees the one-more question here instead. The server
 * decides whether to show it at all (AiSortService); this renders nothing when not offered or when the
 * offer cannot be read -- the import itself succeeded either way.
 */
export function AiSortCard({ statementImportIds, onSorted }: {
  statementImportIds: string[];
  /** Told how many of the import's payments the AI filed, once a run that filed some finishes. */
  onSorted?: (sorted: number) => void;
}) {
  const [offer, setOffer] = useState<AiSortOffer | null>(null);
  const [run, setRun] = useState<AiSortRun | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [gone, setGone] = useState(false);
  const invalidateMoneyFigures = useInvalidateMoneyFigures();
  const queryClient = useQueryClient();
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

  // The rows it filed move the Ledger, the dashboard and Quick sort's questions.
  const finished = !!run && !running;
  const sortedCount = run?.paymentsSorted ?? 0;
  useEffect(() => {
    if (!finished) return;
    invalidateMoneyFigures();
    if (sortedCount > 0) onSorted?.(sortedCount);
  }, [finished, sortedCount, invalidateMoneyFigures, onSorted]);

  async function choose(choice: AiSortChoice) {
    setBusy(true);
    setError(null);
    try {
      const started = await aiSortApi.start(idsKey.split(','), choice);
      // AI is on now: the Settings switch and the consent gate read it from this cache
      // (consentQueryKey, ['consent', email]); left alone, Settings showed AI off until a reload.
      if (choice === 'TURN_ON_AND_SORT') void queryClient.invalidateQueries({ queryKey: ['consent'] });
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
    <div className="mb-5 rounded-xl2 border border-border bg-bg p-4 space-y-3" data-testid="ai-sort-card">
      <p className="text-sm font-semibold text-ink">{line}</p>
      {run ? (
        <div role="status" aria-live="polite" className="text-sm text-ink space-y-1">
          {running ? <p>Sorting… {run.paymentsDone} of {run.paymentsTotal}</p> : <RunResult run={run} />}
          {run.status === 'STOPPED' && (
            <>
              {error && <p className="text-danger text-sm">{error}</p>}
              <Button variant="secondary" onClick={() => void choose('SORT')} disabled={busy}>Try again</Button>
            </>
          )}
        </div>
      ) : (
        <>
          {offer.mode === 'ASK' && <p className="text-xs text-muted">{AI_QUESTION}</p>}
          {error && <p className="text-danger text-sm">{error}</p>}
          <div className="flex flex-wrap gap-3">
            {offer.mode === 'SORT' ? (
              <Button onClick={() => void choose('SORT')} disabled={busy}>Sort with AI</Button>
            ) : (
              <>
                <Button onClick={() => void choose('TURN_ON_AND_SORT')} disabled={busy}>Turn on AI and sort</Button>
                <Button variant="secondary" onClick={() => void choose('NO_THANKS')} disabled={busy}>No thanks</Button>
              </>
            )}
          </div>
        </>
      )}
    </div>
  );
}

function QuickSortLink() {
  return (
    <Link to="/app/transactions" onClick={() => trackNavigation('transactions', 'contextual')}
      className="text-primary font-medium underline underline-offset-2">
      check them in Quick sort
    </Link>
  );
}

/** How a finished run ended, in the spec's words. */
function RunResult({ run }: { run: AiSortRun }) {
  const sorted = run.paymentsSorted;
  let result;
  if (run.status === 'STOPPED_LIMIT') {
    result = <>Sorted {sorted}; AI has reached today&apos;s limit — the rest stay in Quick sort</>;
  } else if (run.status === 'STOPPED_AI_OFF') {
    result = <>AI was turned off — the rest stay in Quick sort</>;
  } else if (run.status === 'STOPPED') {
    // The AI could not be reached, or the run was cut off: it can be tried again.
    result = sorted > 0
      ? <>Sorted {sorted}, then sorting stopped — the rest stay in Quick sort</>
      : <>Sorting stopped before AI could sort these — they stay in Quick sort</>;
  } else if (sorted === 0) {
    result = <>AI couldn&apos;t sort these — they stay in Quick sort</>;
  } else {
    result = <>Sorted {sorted} — <QuickSortLink /></>;
  }
  return (
    <>
      <p data-testid="ai-sort-result">{result}</p>
      {run.status === 'DONE' && sorted > 0 && run.paymentsLeft > 0 && (
        <p className="text-xs text-muted">{run.paymentsLeft} left for you</p>
      )}
    </>
  );
}
