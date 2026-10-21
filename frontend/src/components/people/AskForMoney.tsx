import { useRef, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { X } from 'lucide-react';
import { peopleApi } from '../../api/endpoints';
import { useAuth } from '../../context/AuthContext';
import { Button, useDialogA11y } from '../../design-system';
import { UPI_ID, rupees, serverMessage, upiRequestUrl } from '../../lib/people';

interface Ask {
  label: string;
  owed: number;
}

/**
 * "Ask <person>" (spec §6 E): a UPI request built from the user's own UPI id, asked for once and
 * saved. The browser's share sheet opens where it has one; elsewhere the request is copied for the
 * user to send. Fynora sends nothing. Returns the call and what the page renders for it.
 */
export function useAskForMoney(): { ask: (who: Ask) => void; prompt: ReactNode } {
  const queryClient = useQueryClient();
  // The payee's name the request carries: the user's own.
  const { fullName } = useAuth();
  const [asking, setAsking] = useState<Ask | null>(null);
  // What the page says once the request is handed over: copied, or (no clipboard either) the
  // request itself to copy by hand. Null after the share sheet, which says it all.
  const [notice, setNotice] = useState<string | null>(null);
  const opening = useRef(false);

  async function hand(ownUpiId: string, who: Ask) {
    const url = upiRequestUrl(ownUpiId, fullName, who.owed);
    if (typeof navigator.share === 'function') {
      try {
        await navigator.share({ text: url });
        return;
      } catch (err) {
        // A closed share sheet is the user's choice. Anything else -- the browser refusing it, as it
        // does once the click is a few awaits behind -- falls through to copying.
        if ((err as { name?: string } | null)?.name === 'AbortError') return;
      }
    }
    try {
      await navigator.clipboard.writeText(url);
      setNotice(`Copied the UPI request. Send it to ${who.label} yourself.`);
    } catch {
      setNotice(`Copy this UPI request and send it to ${who.label} yourself: ${url}`);
    }
  }

  async function ask(who: Ask) {
    if (opening.current) return;
    opening.current = true;
    setNotice(null);
    try {
      const saved = await queryClient.fetchQuery({
        queryKey: ['people', 'preferences'], queryFn: () => peopleApi.preferences(), staleTime: 60_000,
      }).catch(() => null);
      if (saved?.ownUpiId) await hand(saved.ownUpiId, who);
      else setAsking(who);
    } finally {
      opening.current = false;
    }
  }

  return {
    ask: (who) => void ask(who),
    prompt: (
      <>
        {notice && <p role="status" className="text-xs text-success break-all">{notice}</p>}
        {asking && (
          <UpiIdPrompt
            who={asking}
            onClose={() => setAsking(null)}
            onSaved={async (ownUpiId) => {
              queryClient.setQueryData(['people', 'preferences'], { ownUpiId });
              const who = asking;
              setAsking(null);
              await hand(ownUpiId, who);
            }}
          />
        )}
      </>
    ),
  };
}

function UpiIdPrompt({ who, onClose, onSaved }: { who: Ask; onClose: () => void; onSaved: (ownUpiId: string) => Promise<void> }) {
  const [value, setValue] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const panelRef = useDialogA11y({ onClose, closeDisabled: saving });

  async function save() {
    const upi = value.trim();
    if (!UPI_ID.test(upi)) {
      setError("That doesn't look like a UPI id, such as name@bank.");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await peopleApi.setOwnUpiId(upi);
      await onSaved(upi);
    } catch (err) {
      setError(serverMessage(err, "Couldn't save it. Try again."));
      setSaving(false);
    }
  }

  return (
    <>
      <div className="fixed inset-0 bg-black/40 z-30" onClick={saving ? undefined : onClose} />
      <div className="fixed inset-0 z-40 flex items-center justify-center p-4 pointer-events-none">
        <div
          ref={panelRef}
          role="dialog"
          aria-modal="true"
          aria-labelledby="upi-id-title"
          tabIndex={-1}
          className="bg-card border border-border rounded-xl2 shadow-soft w-full max-w-sm p-5 pointer-events-auto space-y-3"
        >
          <div className="flex items-center justify-between">
            <h3 id="upi-id-title" className="font-semibold text-ink text-sm">{`Ask ${who.label} for ${rupees(who.owed)}`}</h3>
            <button type="button" onClick={onClose} disabled={saving} aria-label="Close" className="text-muted hover:text-ink">
              <X size={18} />
            </button>
          </div>
          <p className="text-xs text-muted">
            Your UPI id goes in the request, so the money comes to you. You send it yourself; Fynora sends nothing. Asked once.
          </p>
          <form onSubmit={(e) => { e.preventDefault(); void save(); }} className="space-y-3">
            <input
              type="text"
              value={value}
              onChange={(e) => setValue(e.target.value)}
              placeholder="name@bank"
              aria-label="Your UPI id"
              autoComplete="off"
              autoFocus
              className="w-full border border-border rounded-lg px-3 py-2 text-sm"
            />
            {error && <p role="alert" className="text-danger text-xs">{error}</p>}
            <div className="flex gap-2">
              <Button type="submit" loading={saving}>Save and share</Button>
              <Button type="button" variant="secondary" onClick={onClose} disabled={saving}>Cancel</Button>
            </div>
          </form>
        </div>
      </div>
    </>
  );
}
