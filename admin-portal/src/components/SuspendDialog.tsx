import { useState } from 'react';
import { ConfirmDialog } from './ConfirmDialog';
import type { SuspensionReason } from '../api/endpoints';

/** The grounds section 15 of the Terms of Service gives for a suspension, in the backend's
 *  SuspensionReason order. The customer is told the ground, so the admin must choose one. */
export const SUSPENSION_REASONS: { value: SuspensionReason; label: string }[] = [
  { value: 'TERMS_VIOLATION', label: 'Breaks the Terms of Service' },
  { value: 'FRAUD', label: 'Fraud' },
  { value: 'SECURITY_RISK', label: 'Security risk' },
];

const MAX_MESSAGE_LENGTH = 1000;

/**
 * Suspending an account (privacy workstream 10): the admin picks the ground and may add a message.
 * Both reach the customer, on the suspended screen and in the notice email, so the message field
 * says so.
 */
export function SuspendDialog({ name, busy, initialReason, initialMessage, alreadySuspended, onConfirm, onCancel }: {
  name: string;
  busy?: boolean;
  /** The current reason and message, when changing those of an account that is already suspended. */
  initialReason?: SuspensionReason | null;
  initialMessage?: string | null;
  /** Records or changes the reason of a suspended account: the customer is sent the notice again. */
  alreadySuspended?: boolean;
  onConfirm: (reason: SuspensionReason, message: string | undefined) => void;
  onCancel: () => void;
}) {
  const [reason, setReason] = useState<SuspensionReason | ''>(initialReason ?? '');
  const [message, setMessage] = useState(initialMessage ?? '');

  return (
    <ConfirmDialog
      title={alreadySuspended ? `Suspension reason for ${name}` : `Suspend ${name}?`}
      message={alreadySuspended
        ? 'Saving a new reason or message sends the customer the suspension notice again, if their email is verified.'
        : 'They will be signed out and can only see why, appeal, download their data or ask for deletion until reactivated. If their email is verified, they are emailed the reason.'}
      confirmLabel={alreadySuspended ? 'Save and notify' : 'Suspend'}
      danger
      busy={busy}
      confirmDisabled={reason === ''}
      onConfirm={() => {
        if (reason === '') return;
        const trimmed = message.trim();
        onConfirm(reason, trimmed === '' ? undefined : trimmed);
      }}
      onCancel={onCancel}
    >
      <label className="text-xs font-medium text-muted mb-1 mt-3 block" htmlFor="suspend-reason">Reason</label>
      <select
        id="suspend-reason"
        className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
        value={reason}
        onChange={(e) => setReason(e.target.value as SuspensionReason | '')}
      >
        <option value="">Choose a reason</option>
        {SUSPENSION_REASONS.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
      </select>
      <label className="text-xs font-medium text-muted mb-1 mt-3 block" htmlFor="suspend-message">
        Message to the customer (optional)
      </label>
      <textarea
        id="suspend-message"
        className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
        rows={3}
        maxLength={MAX_MESSAGE_LENGTH}
        value={message}
        onChange={(e) => setMessage(e.target.value)}
      />
      <p className="mt-1 text-xs text-muted">The customer sees this. Don't include anything you wouldn't send them.</p>
    </ConfirmDialog>
  );
}
