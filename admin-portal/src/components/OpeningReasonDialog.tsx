import { useState } from 'react';
import { ConfirmDialog } from './ConfirmDialog';

const MIN_REASON = 15;
const MAX_REASON = 500;

/** What every opening of a customer's statement content triggers, said before it happens. */
const NOTICE = 'Only Admins can do this. Super Admins will be emailed and the reason is recorded. '
  + 'The customer will be told the date and that it was to fix a reading problem.';

/** The server's own words, except a busy server, which is said plainly. */
function errorText(err: unknown, fallback: string): string {
  const response = (err as { response?: { status?: number; data?: { message?: string } } })?.response;
  if (response?.status === 503) return 'The server is busy. Try again in a minute.';
  return response?.data?.message ?? fallback;
}

/**
 * The reason step in front of anything that shows staff a customer's statement content: the
 * original pages and a trust hold's staged rows. 15 to 500 characters after trimming, as the server
 * requires; the trimmed reason is what {@code onOpen} receives. A failure keeps the dialog open with
 * the reason still typed and the server's message under it.
 */
export default function OpeningReasonDialog({ title, confirmLabel, failureText, onOpen, onClose }: {
  title: string;
  confirmLabel: string;
  /** Shown when the request fails without a message from the server. */
  failureText: string;
  onOpen: (reason: string) => Promise<void>;
  onClose: () => void;
}) {
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const trimmed = reason.trim();
  const reasonOk = trimmed.length >= MIN_REASON && trimmed.length <= MAX_REASON;

  async function open() {
    setBusy(true);
    setError(null);
    try {
      await onOpen(trimmed);
    } catch (err) {
      setError(errorText(err, failureText));
    } finally {
      setBusy(false);
    }
  }

  return (
    <ConfirmDialog
      title={title}
      message={NOTICE}
      confirmLabel={confirmLabel}
      confirmDisabled={!reasonOk}
      busy={busy}
      onConfirm={() => void open()}
      onCancel={onClose}
    >
      <div className="space-y-1">
        <label htmlFor="opening-reason" className="text-xs font-medium text-ink">Reason</label>
        <textarea
          id="opening-reason"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          maxLength={MAX_REASON + 50}
          rows={3}
          placeholder="e.g. Rows on page 2 are missing from the preview"
          className="w-full rounded-lg border border-border bg-bg px-3 py-2 text-sm text-ink"
        />
        <p className={`text-xs ${trimmed.length > MAX_REASON ? 'text-danger' : 'text-muted'}`}>
          {trimmed.length} / {MAX_REASON}
        </p>
        {error && <p className="text-xs text-danger">{error}</p>}
      </div>
    </ConfirmDialog>
  );
}
