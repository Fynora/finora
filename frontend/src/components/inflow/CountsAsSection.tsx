import { useEffect, useState } from 'react';
import { inflowApi, type ChoiceScope, type CountsAs, type InflowKind } from '../../api/endpoints';
import { Button } from '../../design-system/Button';
import { InflowKindPicker } from './InflowKindPicker';
import { useInvalidateMoneyFigures } from '../../lib/invalidateMoneyFigures';

type ApiErr = { response?: { data?: { message?: string } } };

/** "Counts as" on a transaction's detail panel: what this credit counts as, and the user's way to
 *  change or clear it (Plan 2). The caller hides it for debits. */
export function CountsAsSection({ transactionId, onChanged }: { transactionId: string; onChanged?: () => void }) {
  const [countsAs, setCountsAs] = useState<CountsAs | null>(null);
  const [kinds, setKinds] = useState<InflowKind[] | null>(null);
  const [editing, setEditing] = useState(false);
  const [picked, setPicked] = useState<InflowKind | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const invalidateMoneyFigures = useInvalidateMoneyFigures();

  useEffect(() => {
    let cancelled = false;
    inflowApi.countsAs(transactionId)
      .then((r) => { if (!cancelled) setCountsAs(r); })
      .catch(() => { if (!cancelled) setError("Couldn't load what this payment counts as."); });
    return () => { cancelled = true; };
  }, [transactionId]);

  async function openEditor() {
    setEditing(true);
    setPicked(null);
    setError(null);
    if (!kinds) {
      try {
        setKinds(await inflowApi.kinds());
      } catch {
        setError("Couldn't load your kinds.");
      }
    }
  }

  async function run(action: () => Promise<CountsAs>) {
    setBusy(true);
    setError(null);
    try {
      setCountsAs(await action());
      setEditing(false);
      invalidateMoneyFigures();
      onChanged?.();
    } catch (e: unknown) {
      setError((e as ApiErr)?.response?.data?.message ?? 'Could not save this choice.');
    } finally {
      setBusy(false);
    }
  }

  if (error && !countsAs) return <p className="text-danger text-xs">{error}</p>;
  if (!countsAs) return null;
  const appliedBy: ChoiceScope | null = countsAs.appliedBy;

  return (
    <div className="space-y-2" data-testid="counts-as-section">
      <p className="text-xs text-muted">Counts as</p>
      <p className="text-ink text-sm">{countsAs.summary}</p>
      {!countsAs.choosable && countsAs.notChoosableReason && (
        <p className="text-xs text-muted">{countsAs.notChoosableReason}</p>
      )}
      {countsAs.choosable && !editing && (
        <div className="flex gap-2">
          <Button size="sm" variant="secondary" onClick={openEditor}>Change</Button>
          {appliedBy && (
            <Button size="sm" variant="secondary" loading={busy}
              onClick={() => run(() => inflowApi.clearChoice(transactionId, appliedBy))}>
              {appliedBy === 'SENDER'
                ? `Clear for every payment from ${countsAs.senderLabel} (${countsAs.senderRowCount})`
                : 'Clear my choice'}
            </Button>
          )}
        </div>
      )}
      {editing && kinds && (
        <div className="space-y-2">
          <InflowKindPicker
            kinds={kinds}
            selectedId={picked?.id ?? null}
            onPick={setPicked}
            busy={busy}
            onCreate={async (name, countsAsIncome) => {
              const created = await inflowApi.createKind(name, countsAsIncome);
              setKinds([...kinds, created]);
              setPicked(created);
            }}
          />
          {picked && (
            <div className="flex flex-wrap gap-2">
              {countsAs.senderAvailable && (
                <Button size="sm" loading={busy} onClick={() => run(() => inflowApi.setChoice(transactionId, picked.id, 'SENDER'))}>
                  {`Every payment from ${countsAs.senderLabel} (${countsAs.senderRowCount})`}
                </Button>
              )}
              <Button size="sm" variant={countsAs.senderAvailable ? 'secondary' : 'primary'} loading={busy}
                onClick={() => run(() => inflowApi.setChoice(transactionId, picked.id, 'ROW'))}>
                Just this one
              </Button>
            </div>
          )}
          <Button size="sm" variant="secondary" onClick={() => setEditing(false)}>Cancel</Button>
        </div>
      )}
      {error && <p className="text-danger text-xs">{error}</p>}
    </div>
  );
}
