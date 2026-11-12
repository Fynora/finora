import { useId, useState } from 'react';
import { ConfirmDialog } from './ConfirmDialog';

/**
 * The confirmation for support-assisted account deletion (POST /admin/users/{id}/purge). The
 * deletion is instant and cannot be undone, so the admin types the account's email to enable the
 * button: a click alone, or the wrong account's page, cannot do it.
 */
export function DeleteAccountDialog({
  name, email, busy, onConfirm, onCancel,
}: {
  name: string;
  email: string;
  busy: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const [typed, setTyped] = useState('');
  const id = useId();
  const matches = typed.trim().toLowerCase() === email.trim().toLowerCase();
  return (
    <ConfirmDialog
      title={`Delete ${name}'s account permanently?`}
      message={'This cannot be undone. The account is anonymized at once and its financial data deleted: accounts, '
        + 'transactions, statements, budgets, goals and the rest. A paid subscription is cancelled and Gmail is '
        + 'disconnected. The customer is signed out everywhere.'}
      confirmLabel={busy ? 'Deleting…' : 'Delete permanently'}
      danger
      busy={busy}
      confirmDisabled={!matches}
      onConfirm={() => { if (matches) onConfirm(); }}
      onCancel={onCancel}
    >
      <label htmlFor={`${id}-email`} className="text-xs font-medium text-muted mb-1 mt-3 block">
        Type the account&apos;s email, <span className="font-mono text-ink">{email}</span>, to confirm
      </label>
      <input
        id={`${id}-email`}
        value={typed}
        onChange={(e) => setTyped(e.target.value)}
        autoComplete="off"
        spellCheck={false}
        className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm font-mono"
      />
    </ConfirmDialog>
  );
}
