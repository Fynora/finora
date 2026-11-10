import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { inflowApi, type ChoiceScope, type InflowKind, type UnresolvedSender } from '../api/endpoints';
import { Button, FinoraCard } from '../design-system';
import { UnresolvedSenderRow } from '../components/inflow/UnresolvedSenderRow';
import { useInvalidateMoneyFigures } from '../lib/invalidateMoneyFigures';

/** First and last day of the current calendar month, as YYYY-MM-DD -- used when the page is opened
 *  without a period (a bookmark, or the Reports link of an older build). */
function monthBounds(today = new Date()): [string, string] {
  const y = today.getFullYear(), m = today.getMonth();
  const pad = (n: number) => String(n).padStart(2, '0');
  return [`${y}-${pad(m + 1)}-01`, `${y}-${pad(m + 1)}-${pad(new Date(y, m + 1, 0).getDate())}`];
}

type ApiErr = { response?: { data?: { message?: string } } };

/** "Money not counted yet" (Plan 2): the credits Fynora left out of income, one row per sender,
 *  each resolvable in one tap for every payment from that sender. */
export default function MoneyReview() {
  const [params] = useSearchParams();
  const [defaultStart, defaultEnd] = monthBounds();
  const start = params.get('start') ?? defaultStart;
  const end = params.get('end') ?? defaultEnd;
  const [senders, setSenders] = useState<UnresolvedSender[] | null>(null);
  const [kinds, setKinds] = useState<InflowKind[]>([]);
  const [openId, setOpenId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [undo, setUndo] = useState<{ transactionId: string; scope: ChoiceScope; label: string } | null>(null);
  const invalidateMoneyFigures = useInvalidateMoneyFigures();

  const load = useCallback(() => {
    inflowApi.unresolved(start, end).then(setSenders).catch(() => setError("Couldn't load this list — please try again."));
  }, [start, end]);

  useEffect(() => {
    load();
    inflowApi.kinds().then(setKinds).catch(() => setError("Couldn't load your kinds."));
  }, [load]);

  async function apply(transactionId: string, kindId: string, scope: ChoiceScope, label: string) {
    setBusy(true);
    setError(null);
    try {
      await inflowApi.setChoice(transactionId, kindId, scope);
      setUndo({ transactionId, scope, label });
      setOpenId(null);
      load();
      invalidateMoneyFigures();
    } catch (e: unknown) {
      setError((e as ApiErr)?.response?.data?.message ?? 'Could not save this choice.');
    } finally {
      setBusy(false);
    }
  }

  async function undoLast() {
    if (!undo) return;
    try {
      await inflowApi.clearChoice(undo.transactionId, undo.scope);
      setUndo(null);
      load();
      invalidateMoneyFigures();
    } catch {
      setError('Could not undo that choice.');
    }
  }

  return (
    <div className="max-w-3xl mx-auto p-4 space-y-4">
      <div>
        <h1 className="text-lg font-semibold text-ink">Money not counted yet</h1>
        <p className="text-sm text-muted">
          {start} – {end}. Tell Fynora what each payment was; it remembers the sender.
        </p>
      </div>
      {undo && (
        <div className="flex items-center justify-between bg-card border border-border rounded-xl2 px-4 py-2 text-sm" role="status">
          <span className="text-ink">Saved for {undo.label}.</span>
          <Button size="sm" variant="secondary" onClick={undoLast}>Undo</Button>
        </div>
      )}
      {error && <p className="text-danger text-sm">{error}</p>}
      {senders && senders.length === 0 && (
        <FinoraCard><p className="text-ink text-sm text-center py-6">Everything's sorted</p></FinoraCard>
      )}
      {senders?.map((s) => (
        <UnresolvedSenderRow
          key={s.sampleTransactionId}
          sender={s}
          kinds={kinds}
          open={openId === s.sampleTransactionId}
          onToggle={() => setOpenId(openId === s.sampleTransactionId ? null : s.sampleTransactionId)}
          busy={busy}
          onApply={apply}
          onCreateKind={async (name, countsAsIncome) => {
            const created = await inflowApi.createKind(name, countsAsIncome);
            setKinds((k) => [...k, created]);
            return created;
          }}
        />
      ))}
    </div>
  );
}
