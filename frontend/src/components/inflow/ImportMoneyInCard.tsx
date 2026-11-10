import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { inflowApi, type ChoiceScope, type InflowKind, type UnresolvedSender } from '../../api/endpoints';
import { Button } from '../../design-system';
import { useInvalidateMoneyFigures } from '../../lib/invalidateMoneyFigures';
import { UnresolvedSenderRow } from './UnresolvedSenderRow';

const SHOWN = 3;

type ApiErr = { response?: { data?: { message?: string } } };

/**
 * The import Done step's question: the payments this import added that Fynora does not count as
 * income yet (money from people, unexplained card credits, cash, unnamed senders), asked while the
 * user still remembers them. Up to three senders inline, biggest first; "See all" opens Money
 * Review for the same days. Renders nothing when there is nothing to ask or the list cannot be
 * read -- the import itself succeeded either way.
 */
export function ImportMoneyInCard({ statementImportIds }: { statementImportIds: string[] }) {
  const [senders, setSenders] = useState<UnresolvedSender[] | null>(null);
  const [kinds, setKinds] = useState<InflowKind[]>([]);
  const [openId, setOpenId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [undo, setUndo] = useState<{ transactionId: string; scope: ChoiceScope; label: string } | null>(null);
  const [skipped, setSkipped] = useState(false);
  const [kindsFailed, setKindsFailed] = useState(false);
  const invalidateMoneyFigures = useInvalidateMoneyFigures();
  const idsKey = statementImportIds.join(',');

  const load = useCallback(() => {
    const ids = idsKey ? idsKey.split(',') : [];
    Promise.all(ids.map((id) => inflowApi.unresolvedForImport(id)))
      .then((lists) => setSenders(lists.flat().sort((a, b) => b.total - a.total)))
      .catch(() => setSenders([]));
  }, [idsKey]);

  useEffect(() => {
    load();
    inflowApi.kinds().then(setKinds).catch(() => setKindsFailed(true));
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

  if (skipped || senders === null) return null;
  if (senders.length === 0 && !undo) return null;

  const payments = senders.reduce((n, s) => n + s.count, 0);
  const dates = senders.flatMap((s) => s.rows.map((r) => r.date)).sort();

  return (
    <div className="mb-5 rounded-xl2 border border-border bg-bg p-4 space-y-3" data-testid="import-money-in-card">
      {payments > 0 && (
        <div>
          <p className="text-sm font-semibold text-ink">
            {payments === 1
              ? "1 payment you received isn't counted as income"
              : `${payments} payments you received aren't counted as income`}
          </p>
          <p className="text-xs text-muted mt-0.5">What were they? This decides if they count as income.</p>
        </div>
      )}
      {undo && (
        <div className="flex items-center justify-between bg-card border border-border rounded-xl2 px-3 py-2 text-sm" role="status">
          <span className="text-ink">Saved for {undo.label}.</span>
          <Button size="sm" variant="secondary" onClick={undoLast}>Undo</Button>
        </div>
      )}
      {error && <p className="text-danger text-sm">{error}</p>}
      {/* Without the choices a row opens to nothing; point at the page that can retry instead. */}
      {kindsFailed && (
        <p className="text-sm text-muted">Couldn't load the choices. See all on the Money not counted page.</p>
      )}
      {!kindsFailed && senders.slice(0, SHOWN).map((s) => (
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
      {payments > 0 && (
        <div className="flex items-center justify-end gap-4 text-xs">
          {(senders.length > SHOWN || kindsFailed) && dates.length > 0 && (
            <Link to={`/app/money-review?start=${dates[0]}&end=${dates[dates.length - 1]}`} className="text-primary font-medium">
              See all
            </Link>
          )}
          <button type="button" className="text-muted underline underline-offset-2" onClick={() => setSkipped(true)}>
            Skip for now
          </button>
        </div>
      )}
    </div>
  );
}
