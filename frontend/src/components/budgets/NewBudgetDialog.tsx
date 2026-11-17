import { useRef, useState } from 'react';
import { Button, useDialogA11y } from '../../design-system';
import { CategoryCombobox } from '../CategoryCombobox';
import { limitErrorMessage } from './LimitEditor';

/**
 * New budget: the category comes from the same picker the rest of the app uses, so a typo can no
 * longer create a budget against a category no transaction will ever be filed under.
 */
export function NewBudgetDialog({ onClose, onSave }: {
  onClose: () => void; onSave: (categoryName: string, limit: number) => Promise<void>;
}) {
  const [categoryName, setCategoryName] = useState('');
  const [limitText, setLimitText] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const inFlight = useRef(false);
  const panelRef = useDialogA11y({ onClose, closeDisabled: saving });

  async function save() {
    if (inFlight.current) return;
    if (!categoryName) {
      setError('Pick a category first.');
      return;
    }
    const trimmed = limitText.trim();
    const limit = Number(trimmed);
    if (!trimmed || !Number.isFinite(limit) || !(limit > 0)) {
      setError('Monthly limit must be greater than zero.');
      return;
    }
    inFlight.current = true;
    setSaving(true);
    setError(null);
    try {
      await onSave(categoryName, limit);
    } catch (e) {
      setError(limitErrorMessage(e));
      inFlight.current = false;
      setSaving(false);
    }
  }

  return (
    <>
      <div className="fixed inset-0 z-30 bg-black/40" onClick={saving ? undefined : onClose} />
      <div className="pointer-events-none fixed inset-0 z-40 flex items-center justify-center p-4">
        <div
          ref={panelRef}
          role="dialog"
          aria-modal="true"
          aria-labelledby="new-budget-title"
          tabIndex={-1}
          className="pointer-events-auto w-full max-w-md space-y-4 rounded-xl2 border border-border bg-card p-6 shadow-soft focus:outline-none"
        >
          <h2 id="new-budget-title" className="font-display text-lg font-bold text-ink">New budget</h2>
          <div className="space-y-1.5">
            <label htmlFor="new-budget-category" className="block text-xs font-medium text-muted">Category</label>
            <CategoryCombobox inputId="new-budget-category" value={categoryName} onChange={(name) => { setCategoryName(name); setError(null); }} />
          </div>
          <div className="space-y-1.5">
            <label htmlFor="new-budget-limit" className="block text-xs font-medium text-muted">Monthly limit</label>
            <div className="flex items-center rounded-lg border border-border bg-card px-3 py-2.5 focus-within:border-primary">
              <span aria-hidden="true" className="mr-1 text-sm text-muted">₹</span>
              <input
                id="new-budget-limit"
                inputMode="decimal"
                placeholder="0"
                value={limitText}
                onChange={(e) => { setLimitText(e.target.value); setError(null); }}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void save(); } }}
                className="w-full bg-transparent text-sm text-ink focus:outline-none"
              />
            </div>
          </div>
          {error && <p role="alert" className="text-sm text-danger">{error}</p>}
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={onClose} disabled={saving}>Cancel</Button>
            <Button onClick={() => void save()} loading={saving}>Save budget</Button>
          </div>
        </div>
      </div>
    </>
  );
}
