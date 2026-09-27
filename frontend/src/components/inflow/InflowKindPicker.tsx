import { useState } from 'react';
import { Button } from '../../design-system/Button';
import type { InflowKind } from '../../api/endpoints';

/** The kinds a credit can be given, plus "+ New kind…" (Plan 2). Used by the review page, the
 *  "Counts as" section and Settings, so all three offer exactly the same choices. */
export function InflowKindPicker({ kinds, selectedId, onPick, onCreate, busy }: {
  kinds: InflowKind[];
  selectedId: string | null;
  onPick: (kind: InflowKind) => void;
  onCreate: (name: string, countsAsIncome: boolean) => Promise<void>;
  busy?: boolean;
}) {
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [countsAsIncome, setCountsAsIncome] = useState<boolean | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function submit() {
    if (!name.trim() || countsAsIncome === null) return;
    setSaving(true);
    setError(null);
    try {
      await onCreate(name.trim(), countsAsIncome);
      setCreating(false);
      setName('');
      setCountsAsIncome(null);
    } catch (e: unknown) {
      const message = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
      setError(message ?? 'Could not create this kind.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-2">
        {kinds.map((k) => (
          <button
            key={k.id}
            type="button"
            disabled={busy}
            aria-pressed={selectedId === k.id}
            onClick={() => onPick(k)}
            className={`px-3 py-1.5 rounded-full border text-xs ${selectedId === k.id ? 'border-primary bg-primary-light text-ink' : 'border-border text-ink hover:bg-bg'}`}
          >
            {k.name}
            <span className="text-muted">{k.countsAsIncome ? ' · income' : ' · not income'}</span>
          </button>
        ))}
        {!creating && (
          <button
            type="button"
            onClick={() => setCreating(true)}
            className="px-3 py-1.5 rounded-full border border-dashed border-border text-xs text-muted"
          >
            + New kind…
          </button>
        )}
      </div>
      {creating && (
        <div className="border border-border rounded-xl2 p-3 space-y-2">
          <label className="block text-xs text-muted">
            Name
            <input
              value={name}
              maxLength={60}
              onChange={(e) => setName(e.target.value)}
              className="mt-1 w-full border border-border rounded-lg px-2 py-1.5 text-sm text-ink bg-card"
            />
          </label>
          <fieldset className="text-xs text-ink space-y-1">
            <legend className="text-muted">Count this as income?</legend>
            <label className="flex items-center gap-2">
              <input type="radio" name="counts-as-income" checked={countsAsIncome === true} onChange={() => setCountsAsIncome(true)} />
              Yes, count it
            </label>
            <label className="flex items-center gap-2">
              <input type="radio" name="counts-as-income" checked={countsAsIncome === false} onChange={() => setCountsAsIncome(false)} />
              No, don't count it
            </label>
          </fieldset>
          {error && <p className="text-danger text-xs">{error}</p>}
          <div className="flex gap-2">
            <Button size="sm" onClick={submit} loading={saving} disabled={!name.trim() || countsAsIncome === null}>Create</Button>
            <Button size="sm" variant="secondary" onClick={() => { setCreating(false); setError(null); }}>Cancel</Button>
          </div>
        </div>
      )}
    </div>
  );
}
