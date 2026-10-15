import { useEffect, useRef, useState } from 'react';
import { X } from 'lucide-react';
import { ConfirmDialog } from './ConfirmDialog';
import { adminOriginalViewApi } from '../api/endpoints';
import type { OriginalViewOpened } from '../types';

const MIN_REASON = 15;
const MAX_REASON = 500;

const ENDED = 'This view has ended. Open the statement again.';
const BUSY = 'The server is busy. Try again in a minute.';

/** The server's words where they help: a 404 is always "the view has ended" (the server never says
 *  whose view an id was), a 503 is "busy", anything else is what the server said. */
function errorText(err: unknown, fallback: string): string {
  const response = (err as { response?: { status?: number; data?: { message?: string } } })?.response;
  if (response?.status === 404) return ENDED;
  if (response?.status === 503) return BUSY;
  return response?.data?.message ?? fallback;
}

/** The server's view lifetime (StatementOriginalView.LIFETIME). */
const LIFETIME_MS = 30 * 60_000;

/** How long until the view ends, from the server's expiresAt. The view has only just opened, so
 *  anything outside 0..30 minutes is this computer's clock being off, not the view: the lifetime
 *  is used instead, rather than taking the pages down at once or leaving them up for hours. */
function msUntilEnd(expiresAt: string): number {
  const ms = new Date(expiresAt).getTime() - Date.now();
  return Number.isFinite(ms) && ms > 0 && ms <= LIFETIME_MS ? ms : LIFETIME_MS;
}

function untilTime(expiresAt: string): string {
  const date = new Date(expiresAt);
  return Number.isNaN(date.getTime())
    ? expiresAt
    : date.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
}

/**
 * The real statement behind a held item, shown in the browser and never downloaded. The reason step
 * comes first and says who is told; opening is audited with the reason, emails the Super Admins and
 * tells the customer the date and that it was to fix a reading problem. Pages are fetched one after
 * another as images under an authenticated request, shown with dragging and the context menu turned
 * off, and their object URLs freed when the viewer closes. A screenshot is still possible: this makes
 * access visible, not impossible.
 */
export default function OriginalStatementViewer({ queue, itemId, onClose }: {
  queue: 'IMPORT' | 'TRUST';
  itemId: string;
  onClose: () => void;
}) {
  const [reason, setReason] = useState('');
  const [opening, setOpening] = useState(false);
  const [openError, setOpenError] = useState<string | null>(null);
  const [view, setView] = useState<OriginalViewOpened | null>(null);
  const [pages, setPages] = useState<string[]>([]);
  const [lines, setLines] = useState<string[] | null>(null);
  const [viewError, setViewError] = useState<string | null>(null);
  const [ended, setEnded] = useState(false);
  // Every object URL made, freed on unmount -- a ref, so the cleanup sees the latest list.
  const urls = useRef<string[]>([]);
  const endedRef = useRef(false);
  const trimmed = reason.trim();
  const reasonOk = trimmed.length >= MIN_REASON && trimmed.length <= MAX_REASON;

  useEffect(() => () => {
    urls.current.forEach((url) => URL.revokeObjectURL(url));
    urls.current = [];
  }, []);

  useEffect(() => {
    if (!view) return;
    let stopped = false;
    async function load(opened: OriginalViewOpened) {
      try {
        if (opened.format === 'CSV') {
          const csv = await adminOriginalViewApi.csv(opened.viewId);
          if (!stopped && !endedRef.current) setLines(csv);
          return;
        }
        for (let n = 1; n <= opened.pageCount; n++) {
          const blob = await adminOriginalViewApi.pageBlob(opened.viewId, n);
          if (stopped || endedRef.current) return;
          const url = URL.createObjectURL(blob);
          urls.current.push(url);
          setPages((current) => [...current, url]);
        }
      } catch (err) {
        if (!stopped) setViewError(errorText(err, 'This page could not be shown.'));
      }
    }
    void load(view);
    return () => { stopped = true; };
  }, [view]);

  // The server stops serving pages when the view ends; the ones already fetched leave the screen
  // then too, rather than staying up for as long as the dialog is open.
  useEffect(() => {
    if (!view) return;
    const timer = setTimeout(() => {
      urls.current.forEach((url) => URL.revokeObjectURL(url));
      urls.current = [];
      setPages([]);
      setLines(null);
      endedRef.current = true;
      setEnded(true);
      setViewError(ENDED);
    }, msUntilEnd(view.expiresAt));
    return () => clearTimeout(timer);
  }, [view]);

  useEffect(() => {
    if (!view) return;
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [view, onClose]);

  async function open() {
    setOpening(true);
    setOpenError(null);
    try {
      const opened = queue === 'IMPORT'
        ? await adminOriginalViewApi.openImport(itemId, trimmed)
        : await adminOriginalViewApi.openTrust(itemId, trimmed);
      setView(opened);
    } catch (err) {
      setOpenError(errorText(err, 'The statement could not be opened.'));
    } finally {
      setOpening(false);
    }
  }

  if (!view) {
    return (
      <ConfirmDialog
        title="Open the real statement"
        message="Only Admins can do this. Super Admins will be emailed and the reason is recorded. The customer will be told the date and that it was to fix a reading problem."
        confirmLabel="Open statement"
        confirmDisabled={!reasonOk}
        busy={opening}
        onConfirm={() => void open()}
        onCancel={onClose}
      >
        <div className="space-y-1">
          <label htmlFor="original-view-reason" className="text-xs font-medium text-ink">Reason</label>
          <textarea
            id="original-view-reason"
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
          {openError && <p className="text-xs text-danger">{openError}</p>}
        </div>
      </ConfirmDialog>
    );
  }

  return (
    <div className="fixed inset-0 z-50 flex items-stretch justify-center sm:p-6" role="dialog" aria-modal="true"
      aria-labelledby="original-view-title">
      <div className="absolute inset-0 bg-black/40" onClick={onClose} />
      <div className="relative flex w-full max-w-4xl flex-col bg-card border border-border sm:rounded-xl2 shadow-soft">
        <div className="flex items-start justify-between gap-3 border-b border-border px-4 py-3 sm:px-5">
          <div className="min-w-0">
            <h3 id="original-view-title" className="font-semibold text-ink text-sm">Original statement</h3>
            <p className="text-xs text-muted mt-0.5">
              Open until {untilTime(view.expiresAt)}. Nothing here can be downloaded.
            </p>
          </div>
          <button type="button" onClick={onClose} aria-label="Close" className="text-muted hover:text-ink p-1">
            <X className="h-4 w-4" />
          </button>
        </div>
        <div
          className="flex-1 overflow-y-auto overflow-x-hidden bg-bg p-3 sm:p-5 select-none"
          onContextMenu={(e) => e.preventDefault()}
        >
          {view.format === 'CSV' ? (
            lines && (
              <pre data-testid="original-statement-csv"
                className="whitespace-pre-wrap break-words text-xs text-ink font-mono">{lines.join('\n')}</pre>
            )
          ) : (
            <div className="space-y-3">
              {pages.map((src, i) => (
                <img
                  key={src}
                  src={src}
                  alt={`Page ${i + 1} of ${view.pageCount}`}
                  draggable={false}
                  className="mx-auto block w-full max-w-[900px] h-auto border border-border bg-white"
                />
              ))}
              {pages.length < view.pageCount && !viewError && !ended && (
                <p className="text-xs text-muted text-center">Loading page {pages.length + 1} of {view.pageCount}…</p>
              )}
            </div>
          )}
          {viewError && <p className="mt-3 text-sm text-danger">{viewError}</p>}
        </div>
      </div>
    </div>
  );
}
