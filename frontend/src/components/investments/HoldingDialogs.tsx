import { useRef, useState, type ReactNode } from 'react';
import { Button, useDialogA11y } from '../../design-system';
import { holdingValueProblem, parseHoldingValue, valueChangeLine } from '../../lib/investmentSummary';
import type { Account } from '../../types';

// The values already stored on holdings; the labels people see are the same words.
const HOLDING_KINDS = ['Mutual Fund', 'Stocks', 'FD', 'PPF/NPS', 'Other'];
const NAME_MAX = 120;

function serverMessage(e: unknown, fallback: string) {
  return (e as { response?: { data?: { message?: string } } })?.response?.data?.message ?? fallback;
}

function valueError(text: string) {
  return holdingValueProblem(text) ?? 'Current value must be greater than zero.';
}

function DialogShell({ titleId, title, saving, onClose, children }: {
  titleId: string; title: string; saving: boolean; onClose: () => void; children: ReactNode;
}) {
  const panelRef = useDialogA11y({ onClose, closeDisabled: saving });
  return (
    <>
      <div className="fixed inset-0 z-30 bg-black/40" onClick={saving ? undefined : onClose} />
      <div className="pointer-events-none fixed inset-0 z-40 flex items-center justify-center p-4">
        <div
          ref={panelRef}
          role="dialog"
          aria-modal="true"
          aria-labelledby={titleId}
          tabIndex={-1}
          className="pointer-events-auto max-h-full w-full max-w-md space-y-4 overflow-y-auto rounded-xl2 border border-border bg-card p-6 shadow-soft focus:outline-none"
        >
          <h2 id={titleId} className="break-words font-display text-lg font-bold text-ink">{title}</h2>
          {children}
        </div>
      </div>
    </>
  );
}

function ValueField({ id, value, onChange, onEnter, autoFocus = false }: {
  id: string; value: string; onChange: (v: string) => void; onEnter: () => void; autoFocus?: boolean;
}) {
  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="block text-xs font-medium text-muted">Current value</label>
      <div className="flex items-center rounded-lg border border-border bg-card px-3 py-2.5 focus-within:border-primary">
        <span aria-hidden="true" className="mr-1 text-sm text-muted">₹</span>
        <input
          id={id}
          autoFocus={autoFocus}
          inputMode="decimal"
          placeholder="0"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); onEnter(); } }}
          className="w-full bg-transparent text-sm text-ink focus:outline-none max-sm:text-base"
        />
      </div>
    </div>
  );
}

/** Change what a holding is worth today. Name, type and deposit terms stay as they are. */
export function UpdateValueDialog({ holding, money, onClose, onSave }: {
  holding: Account; money: (n: number) => string; onClose: () => void; onSave: (value: number) => Promise<void>;
}) {
  const [text, setText] = useState(String(holding.balance));
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  // State updates land after the event; a ref stops a fast second click before `saving` re-renders.
  const inFlight = useRef(false);
  const change = valueChangeLine(holding.balance, parseHoldingValue(text), money);

  async function save() {
    if (inFlight.current) return;
    const value = parseHoldingValue(text);
    if (value === null) {
      setError(valueError(text));
      return;
    }
    inFlight.current = true;
    setSaving(true);
    setError(null);
    try {
      await onSave(value);
    } catch (e) {
      setError(serverMessage(e, 'Could not save this value. Try again.'));
      inFlight.current = false;
      setSaving(false);
    }
  }

  return (
    <DialogShell titleId="update-value-title" title={holding.name} saving={saving} onClose={onClose}>
      <ValueField id="update-value" value={text} autoFocus onChange={(v) => { setText(v); setError(null); }} onEnter={() => void save()} />
      {change && <p className="text-[13px] text-muted">{change}</p>}
      {error && <p role="alert" className="text-sm text-danger">{error}</p>}
      <div className="flex justify-end gap-2">
        <Button variant="secondary" onClick={onClose} disabled={saving}>Cancel</Button>
        <Button onClick={() => void save()} loading={saving}>Save value</Button>
      </div>
    </DialogShell>
  );
}

export function AddHoldingDialog({ onClose, onSave }: {
  onClose: () => void; onSave: (holding: { name: string; balance: number; investmentKind: string }) => Promise<void>;
}) {
  const [name, setName] = useState('');
  const [text, setText] = useState('');
  const [kind, setKind] = useState(HOLDING_KINDS[0]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const inFlight = useRef(false);

  async function save() {
    if (inFlight.current) return;
    if (!name.trim()) {
      setError('Give this holding a name.');
      return;
    }
    const balance = parseHoldingValue(text);
    if (balance === null) {
      setError(valueError(text));
      return;
    }
    inFlight.current = true;
    setSaving(true);
    setError(null);
    try {
      await onSave({ name: name.trim(), balance, investmentKind: kind });
    } catch (e) {
      // The one failure a person can act on is the holdings limit, whose message says what to do.
      setError(serverMessage(e, 'Could not add this holding.'));
      inFlight.current = false;
      setSaving(false);
    }
  }

  return (
    <DialogShell titleId="add-holding-title" title="Add holding" saving={saving} onClose={onClose}>
      <div className="space-y-1.5">
        <label htmlFor="holding-name" className="block text-xs font-medium text-muted">Name</label>
        <input
          id="holding-name"
          autoFocus
          maxLength={NAME_MAX}
          value={name}
          onChange={(e) => { setName(e.target.value); setError(null); }}
          className="w-full rounded-lg border border-border bg-card px-3 py-2.5 text-sm text-ink focus:border-primary focus:outline-none max-sm:text-base"
        />
      </div>
      <ValueField id="holding-value" value={text} onChange={(v) => { setText(v); setError(null); }} onEnter={() => void save()} />
      <div className="space-y-1.5">
        <label htmlFor="holding-type" className="block text-xs font-medium text-muted">Type</label>
        <select
          id="holding-type"
          value={kind}
          onChange={(e) => setKind(e.target.value)}
          className="w-full rounded-lg border border-border bg-card px-3 py-2.5 text-sm text-ink focus:border-primary focus:outline-none max-sm:text-base"
        >
          {HOLDING_KINDS.map((k) => <option key={k}>{k}</option>)}
        </select>
      </div>
      {error && <p role="alert" className="text-sm text-danger">{error}</p>}
      <div className="flex justify-end gap-2">
        <Button variant="secondary" onClick={onClose} disabled={saving}>Cancel</Button>
        <Button onClick={() => void save()} loading={saving}>Add holding</Button>
      </div>
    </DialogShell>
  );
}
