import { useEffect, useRef, useState } from 'react';
import { X } from 'lucide-react';
import { accountsApi } from '../api/endpoints';
import type { Account } from '../types';
import { useDialogA11y } from '../design-system';
import { readAmountOwed } from '../lib/amountOwed';

/**
 * Shown in place of a card's balance when the server says its amount owed is not known: the
 * balance was summed from zero off statements with no closing balance, so no figure is shown.
 * docs/superpowers/specs/2026-10-26-card-amount-owed-prompt-design.md.
 */
export function AmountOwedNotKnown({ onAdd, className = '' }: { onAdd: () => void; className?: string }) {
  return (
    <span className={`inline-flex items-center gap-2 text-sm ${className}`}>
      <span className="text-muted">Amount owed not known</span>
      <button type="button" onClick={onAdd} className="text-primary font-medium hover:underline">
        Add it
      </button>
    </span>
  );
}

/**
 * "How much do you owe on this card today?" for a card already on the Accounts page. Saves through
 * the ordinary account update, which records the figure as typed by the user: rows already on the
 * card, and statements for earlier periods, stop moving it.
 */
export function AddAmountOwedDialog({
  account,
  onClose,
  onSaved,
}: {
  account: Account;
  onClose: () => void;
  onSaved: (updated: Account) => void;
}) {
  const [value, setValue] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const answer = readAmountOwed(value);
  const panelRef = useDialogA11y({ onClose, closeDisabled: saving });
  const inputRef = useRef<HTMLInputElement>(null);
  // Declared after useDialogA11y so it runs after that hook's focus effect. autoFocus alone lost
  // to it under StrictMode: the hook's effect, its cleanup (back to the opener) and the effect
  // again landed on the first control, Close, so typing went nowhere.
  useEffect(() => { inputRef.current?.focus(); }, []);

  async function save() {
    if (answer.state !== 'valid' || saving) return;
    setSaving(true);
    setError(null);
    try {
      const updated = await accountsApi.update(account.id, {
        name: account.name,
        accountType: account.accountType,
        balance: answer.value,
      });
      onSaved(updated);
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not save the amount. Try again.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-30" onClick={saving ? undefined : onClose}>
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="add-amount-owed-title"
        tabIndex={-1}
        className="bg-card rounded-xl2 shadow-card p-6 w-[420px] max-w-[90vw]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3 mb-2">
          <h2 id="add-amount-owed-title" className="text-lg font-semibold text-ink">
            How much do you owe on {account.name} today?
          </h2>
          <button type="button" onClick={onClose} disabled={saving} className="text-muted hover:text-ink" aria-label="Close">
            <X size={18} />
          </button>
        </div>
        <p className="text-sm text-muted mb-4">
          Your statements don't show this card's balance. Your card app or latest bill has it.
        </p>
        <form onSubmit={(e) => { e.preventDefault(); void save(); }} className="space-y-3">
          <div>
            <label htmlFor="add-amount-owed" className="block text-xs uppercase text-muted mb-1">Amount owed today (₹)</label>
            <input
              id="add-amount-owed"
              ref={inputRef}
              inputMode="decimal"
              value={value}
              onChange={(e) => { setValue(e.target.value); setError(null); }}
              aria-invalid={answer.state === 'invalid'}
              aria-describedby={answer.state === 'invalid' ? 'add-amount-owed-error' : undefined}
              className="bg-card text-ink w-full border border-border rounded-lg px-3 py-2 text-sm"
            />
            {answer.state === 'invalid' && (
              <p id="add-amount-owed-error" role="alert" className="text-xs text-danger mt-1">
                Enter the amount in rupees, like 12345.67
              </p>
            )}
          </div>
          {error && <p className="text-sm text-danger bg-danger-bg rounded-lg px-3 py-2">{error}</p>}
          <div className="flex justify-end gap-2">
            <button type="button" onClick={onClose} disabled={saving} className="text-sm text-muted hover:text-ink px-3 py-2">
              Cancel
            </button>
            <button
              type="submit"
              disabled={answer.state !== 'valid' || saving}
              className="bg-primary text-on-primary hover:bg-primary-dark rounded-lg px-4 py-2 text-sm font-medium disabled:opacity-50"
            >
              Save
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
