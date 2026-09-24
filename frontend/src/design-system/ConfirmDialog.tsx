import { useDialogA11y } from './useDialogA11y';

/**
 * Replaces the browser's own native `confirm()` for destructive/discard actions across the app --
 * that dialog renders as unstyled OS/browser chrome (literally titled with the page's own origin,
 * e.g. "app.fynora.net says"), which reads as broken rather than as part of the product.
 *
 * Not a global singleton or a hook-based imperative API: the caller owns the "what am I
 * confirming" state (typically `useState<T | null>`) and conditionally renders this component,
 * same convention every other modal on these pages already uses (see StatementHistory.tsx's own
 * ReimportPasswordModal/StatementDetailModal). Escape cancels, same as a native confirm()'s own
 * Esc-dismisses-the-dialog behavior -- this replaces that dialog, so it should keep that much of it.
 */
export function ConfirmDialog({
  title, message, confirmLabel = 'Confirm', cancelLabel = 'Cancel', danger, busy, onConfirm, onCancel,
}: {
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  /** Red confirm button -- for a delete/discard action, as opposed to a neutral confirmation. */
  danger?: boolean;
  /** Disables both buttons and swaps the confirm label to a busy state while the action is in flight. */
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  // Focus moves in on open (onto Cancel, the first control -- WAI-ARIA's guidance for a destructive
  // confirmation is to focus the least destructive action, so a reflexive Enter dismisses rather
  // than deletes), returns on close; Escape cancels like the native confirm() this replaces, and is
  // inert while busy; Tab is trapped. See useDialogA11y for the incident the trap exists for.
  const panelRef = useDialogA11y({ onClose: onCancel, closeDisabled: busy });

  return (
    <>
      <div className="fixed inset-0 bg-black/40 z-30" onClick={busy ? undefined : onCancel} />
      <div className="fixed inset-0 z-40 flex items-center justify-center p-4 pointer-events-none">
        <div
          ref={panelRef}
          role="alertdialog"
          aria-modal="true"
          aria-labelledby="confirm-dialog-title"
          // Focusable as a last resort only (never a Tab stop of its own): while `busy` disables both
          // buttons, this is the one thing left inside for the trap to park focus on.
          tabIndex={-1}
          className="bg-card border border-border rounded-xl2 shadow-soft w-full max-w-sm p-5 pointer-events-auto space-y-4 focus:outline-none"
        >
          <h3 id="confirm-dialog-title" className="font-semibold text-ink text-sm">{title}</h3>
          <p className="text-sm text-muted">{message}</p>
          <div className="flex justify-end gap-2">
            <button
              type="button"
              onClick={onCancel}
              disabled={busy}
              className="text-xs font-medium text-muted hover:text-ink px-3 py-2 rounded-lg disabled:opacity-40"
            >
              {cancelLabel}
            </button>
            <button
              type="button"
              onClick={onConfirm}
              disabled={busy}
              className={`text-xs font-semibold text-white rounded-lg px-3.5 py-2 disabled:opacity-40 ${danger ? 'bg-danger' : 'bg-primary'}`}
            >
              {busy ? 'Working…' : confirmLabel}
            </button>
          </div>
        </div>
      </div>
    </>
  );
}
