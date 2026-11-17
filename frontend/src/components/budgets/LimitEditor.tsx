import { useId, useRef, useState } from 'react';
import { Button } from '../../design-system';

const CHIPS = [500, 1000];

export function limitErrorMessage(e: unknown) {
  return (e as { response?: { data?: { message?: string } } })?.response?.data?.message ?? 'Could not save this budget. Try again.';
}

/**
 * In-place limit editor for one budget row. Enter saves, Escape cancels, chips add to the typed
 * amount. A text field with a decimal keypad rather than type="number", so whatever is typed is
 * what gets validated (a number input silently turns "abc" into "").
 */
export function LimitEditor({
  categoryName, initial, onSave, onCancel,
}: {
  categoryName: string; initial: number; onSave: (limit: number) => Promise<void>; onCancel: () => void;
}) {
  const [value, setValue] = useState(String(initial));
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  // State updates land after the event; a ref stops a fast second click before `saving` re-renders.
  const inFlight = useRef(false);
  // useId, not the category name: two names that differ only in punctuation would collide.
  const inputId = `limit-${useId()}`;

  async function save() {
    if (inFlight.current) return;
    const trimmed = value.trim();
    const limit = Number(trimmed);
    if (!trimmed || !Number.isFinite(limit) || !(limit > 0)) {
      setError('Monthly limit must be greater than zero.');
      return;
    }
    inFlight.current = true;
    setSaving(true);
    setError(null);
    try {
      await onSave(limit);
    } catch (e) {
      setError(limitErrorMessage(e));
      inFlight.current = false;
      setSaving(false);
    }
  }

  return (
    <div className="flex min-w-0 flex-1 flex-col gap-1.5">
      <div className="flex flex-wrap items-center gap-2">
        <label htmlFor={inputId} className="sr-only">{`Monthly limit for ${categoryName}`}</label>
        <div className="flex items-center rounded-lg border-2 border-primary bg-card px-3 py-1.5">
          <span aria-hidden="true" className="mr-1 text-sm font-semibold text-ink">₹</span>
          <input
            id={inputId}
            autoFocus
            inputMode="decimal"
            value={value}
            onChange={(e) => { setValue(e.target.value); setError(null); }}
            onKeyDown={(e) => {
              if (e.key === 'Enter') { e.preventDefault(); void save(); }
              if (e.key === 'Escape') { e.preventDefault(); onCancel(); }
            }}
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? `${inputId}-error` : undefined}
            className="w-24 bg-transparent text-sm font-semibold text-ink focus:outline-none"
          />
        </div>
        {CHIPS.map((n) => (
          <button
            key={n}
            type="button"
            onClick={() => setValue(String((Number(value) || 0) + n))}
            className="rounded-full bg-surface px-2.5 py-1 text-xs font-medium text-ink hover:bg-border"
          >
            {`+${n.toLocaleString('en-IN')}`}
          </button>
        ))}
        <span className="flex-1" />
        <Button variant="secondary" size="sm" onClick={onCancel} disabled={saving}>Cancel</Button>
        <Button size="sm" onClick={() => void save()} loading={saving}>Save</Button>
      </div>
      {error && <p id={`${inputId}-error`} role="alert" className="text-xs text-danger">{error}</p>}
    </div>
  );
}
