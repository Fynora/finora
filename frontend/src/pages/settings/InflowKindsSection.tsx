import { useEffect, useState } from 'react';
import { inflowApi, type InflowKind, type SenderRule } from '../../api/endpoints';
import { Button } from '../../design-system/Button';
import { FinoraCard } from '../../design-system/FinoraCard';
import { SectionHeader } from '../../design-system/SectionHeader';
import { InflowKindPicker } from '../../components/inflow/InflowKindPicker';

type ApiErr = { response?: { data?: { message?: string; details?: { rows?: number; senders?: number } } } };

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

/** Settings → Categorization: the user's money kinds and remembered senders (Plan 2). A kind can
 *  only be deleted once nothing uses it, so a delete never silently changes a total. */
export function InflowKindsSection() {
  const [kinds, setKinds] = useState<InflowKind[] | null>(null);
  const [rules, setRules] = useState<SenderRule[] | null>(null);
  const [renaming, setRenaming] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [message, setMessage] = useState<string | null>(null);

  function load() {
    inflowApi.kinds().then(setKinds).catch(() => setMessage("Couldn't load your kinds."));
    inflowApi.senderRules().then(setRules).catch(() => setMessage("Couldn't load remembered senders."));
  }
  useEffect(load, []);

  async function attempt(action: () => Promise<unknown>, fallback: string) {
    setMessage(null);
    try {
      await action();
      load();
      return true;
    } catch (e: unknown) {
      setMessage((e as ApiErr).response?.data?.message ?? fallback);
      return false;
    }
  }

  async function remove(k: InflowKind) {
    setMessage(null);
    try {
      await inflowApi.deleteKind(k.id);
      load();
    } catch (e: unknown) {
      const d = (e as ApiErr).response?.data;
      const rows = d?.details?.rows, senders = d?.details?.senders;
      const usage = rows == null || senders == null ? ''
        : ` Used by ${plural(rows, 'payment', 'payments')} and ${plural(senders, 'sender', 'senders')}.`;
      setMessage(`${d?.message ?? 'Could not delete this kind.'}${usage}`);
    }
  }

  async function saveName(k: InflowKind) {
    if (await attempt(() => inflowApi.updateKind(k.id, { name: draft }), 'Could not rename this kind.')) setRenaming(null);
  }

  return (
    <>
      <FinoraCard>
        <SectionHeader title="Money kinds" size="sm" />
        <p className="text-xs text-muted mb-3">
          What money coming in can be. Kinds that count as income add to your income; the others are left out of it.
        </p>
        {message && <p className="text-danger text-xs mb-2" role="alert">{message}</p>}
        <ul className="divide-y divide-border">
          {kinds?.map((k) => (
            <li key={k.id} className="flex items-center justify-between gap-2 py-2">
              {renaming === k.id ? (
                <input
                  aria-label={`New name for ${k.name}`}
                  value={draft}
                  maxLength={60}
                  onChange={(e) => setDraft(e.target.value)}
                  className="flex-1 border border-border rounded-lg px-2 py-1 text-sm bg-card text-ink"
                />
              ) : (
                <span className="text-sm text-ink">{k.name}</span>
              )}
              <div className="flex items-center gap-2">
                {k.builtIn ? (
                  <span className="text-xs text-muted">{k.countsAsIncome ? 'Income' : 'Not income'}</span>
                ) : (
                  <label className="text-xs text-muted flex items-center gap-1">
                    <input
                      type="checkbox"
                      aria-label={`${k.name} counts as income`}
                      checked={k.countsAsIncome}
                      onChange={(e) => attempt(() => inflowApi.updateKind(k.id, { countsAsIncome: e.target.checked }),
                        'Could not change this kind.')}
                    />
                    Income
                  </label>
                )}
                {renaming === k.id ? (
                  <Button size="sm" onClick={() => saveName(k)} disabled={!draft.trim()}>Save</Button>
                ) : (
                  <Button size="sm" variant="secondary" aria-label={`Rename ${k.name}`}
                    onClick={() => { setRenaming(k.id); setDraft(k.name); }}>
                    Rename
                  </Button>
                )}
                {!k.builtIn && (
                  <Button size="sm" variant="danger" aria-label={`Delete ${k.name}`} onClick={() => remove(k)}>Delete</Button>
                )}
              </div>
            </li>
          ))}
        </ul>
        {kinds && (
          <div className="mt-3">
            <InflowKindPicker
              kinds={[]}
              selectedId={null}
              onPick={() => undefined}
              onCreate={async (name, countsAsIncome) => {
                await inflowApi.createKind(name, countsAsIncome);
                load();
              }}
            />
          </div>
        )}
      </FinoraCard>
      <FinoraCard>
        <SectionHeader title="Remembered senders" size="sm" />
        {rules && rules.length === 0 && <p className="text-xs text-muted">No senders remembered yet.</p>}
        <ul className="divide-y divide-border">
          {rules?.map((r) => (
            <li key={r.id} className="flex items-center justify-between gap-2 py-2">
              <div className="min-w-0">
                <p className="text-sm text-ink truncate">{r.label}</p>
                <p className="text-xs text-muted">{r.kind.name} · {plural(r.rowCount, 'payment', 'payments')}</p>
              </div>
              <Button size="sm" variant="secondary" aria-label={`Forget ${r.label}`}
                onClick={() => attempt(() => inflowApi.forgetSender(r.id), 'Could not forget this sender.')}>
                Forget
              </Button>
            </li>
          ))}
        </ul>
      </FinoraCard>
    </>
  );
}
