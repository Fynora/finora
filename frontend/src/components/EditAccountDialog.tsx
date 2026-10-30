import { useEffect, useRef, useState } from 'react';
import { X } from 'lucide-react';
import { accountsApi } from '../api/endpoints';
import type { Account } from '../types';
import { useDialogA11y } from '../design-system';
import { BankPicker } from './BankPicker';

/**
 * Edit an account's name and bank (spec 2026-10-22 §4). Name and bank are the only fields in scope;
 * the type never changes once an account exists (AccountService.update refuses it).
 *
 * The bank is read-only for an account synced through Account Aggregator. Bank Sync sets that bank
 * from the account's own IFSC when the link is made, and matching a later link or statement to
 * this account compares against the bank stored on it (ProductIdentityResolver.storedIdentityOf),
 * so an edited bank would stop the same account being recognised and create a second one.
 */
export function EditAccountDialog({
  account,
  onClose,
  onSaved,
  answeringWhichBank = false,
}: {
  account: Account;
  onClose: () => void;
  onSaved: (updated: Account) => void;
  /** Opened from "Which bank is this?": saving with nothing changed is still an answer ("Other bank",
   *  confirmed), so Save stays enabled and reports back without a request. */
  answeringWhichBank?: boolean;
}) {
  const bankLocked = account.primarySource === 'ACCOUNT_AGGREGATOR';
  const [name, setName] = useState(account.name);
  const [bankId, setBankId] = useState(account.bank?.id ?? 'OTHER');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const panelRef = useDialogA11y({ onClose, closeDisabled: saving });
  const nameRef = useRef<HTMLInputElement>(null);
  // After useDialogA11y's own focus effect, for the reason AddAmountOwedDialog gives.
  useEffect(() => { nameRef.current?.focus(); }, []);

  const trimmed = name.trim();
  const unchanged = trimmed === account.name && bankId === (account.bank?.id ?? 'OTHER');
  const canSave = !!trimmed && !saving && (!unchanged || answeringWhichBank);

  async function save() {
    if (!canSave) return;
    if (unchanged) {
      onSaved(account);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const updated = await accountsApi.update(account.id, {
        name: trimmed,
        // Sent unchanged: the server refuses a different type and needs this one to save.
        accountType: account.accountType,
        ...(bankLocked ? {} : { bankId }),
      });
      onSaved(updated);
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not save this account. Try again.');
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
        aria-labelledby="edit-account-title"
        tabIndex={-1}
        className="bg-card rounded-xl2 shadow-card p-6 w-[440px] max-w-[90vw]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3 mb-4">
          <h2 id="edit-account-title" className="text-lg font-semibold text-ink">
            {answeringWhichBank ? 'Which bank is this?' : 'Edit account'}
          </h2>
          <button type="button" onClick={onClose} disabled={saving} className="text-muted hover:text-ink" aria-label="Close">
            <X size={18} />
          </button>
        </div>
        <form onSubmit={(e) => { e.preventDefault(); void save(); }} className="space-y-3">
          <div>
            <label htmlFor="edit-account-name" className="block text-xs uppercase text-muted mb-1">Account name</label>
            <input
              id="edit-account-name"
              ref={nameRef}
              value={name}
              maxLength={120}
              onChange={(e) => { setName(e.target.value); setError(null); }}
              className="bg-card text-ink w-full border border-border rounded-lg px-3 py-2 text-sm"
            />
          </div>
          <div>
            <label htmlFor="edit-account-bank" className="block text-xs uppercase text-muted mb-1">Bank</label>
            <BankPicker
              id="edit-account-bank"
              value={bankId}
              onChange={(id) => { setBankId(id); setError(null); }}
              includeBanks={account.bank && account.bank.id !== 'OTHER' ? [account.bank] : []}
              disabled={bankLocked}
              describedBy={bankLocked ? 'edit-account-bank-note' : undefined}
            />
            {bankLocked && (
              <p id="edit-account-bank-note" className="text-xs text-muted mt-1">
                Set by Bank Sync from this account's own details, so it can't be changed here.
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
              disabled={!canSave}
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
