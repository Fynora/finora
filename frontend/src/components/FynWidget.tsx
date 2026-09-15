import { useEffect, useRef, useState } from 'react';
import { MessageCircle, Send, X } from 'lucide-react';
import { fynChatApi } from '../api/endpoints';
import { PremiumFeatureGate } from './PremiumFeatureGate';
import { useAuth } from '../context/AuthContext';
import { safeStorage } from '../lib/safeStorage';

// Scoped per-account, same reasoning as TopBar.tsx's own readStorageKey for notifications: on a
// shared/family computer, a global key would mark User A's still-undiscovered Fyn as "seen" the
// moment User B (who already knows about it) logs in on the same browser.
function fynSeenStorageKey(email: string | null): string {
  return `finora_fyn_seen_${email ?? 'anonymous'}`;
}

interface ChatTurn {
  role: 'user' | 'assistant';
  content: string;
}

// One example per real chat tool (GET_BALANCE, GET_RECENT_TRANSACTIONS_SUMMARY,
// GET_SPEND_BY_CATEGORY, GET_BUDGET_STATUS -- see FynChatOrchestrationService's tool list) so a
// tap always has a genuine capability behind it, not an invented example. "Dining" is a guess at
// a category name someone might actually have, not a guarantee -- if it doesn't match, the tool's
// own no-match response now lists the user's real category names so Fyn can recover in the same
// turn (see FynGetSpendByCategoryTool's own noMatchMessage).
const SUGGESTED_QUESTIONS = [
  "What's my balance?",
  'What did I spend this month?',
  'How much did I spend on Dining?',
  'How are my budgets doing?',
];

/**
 * Fyn, promoted from its own sidebar page (/app/fyn) to a header icon + slide-in drawer, same
 * pattern as Notifications/Help in TopBar.tsx -- TopBar renders once per page inside AppShell
 * (see App.tsx), so this is now reachable from every screen instead of only after navigating
 * away to a dedicated page. Conversation state still lives only in this component -- closing the
 * drawer or reloading starts a fresh conversation, same as the page version did; conversationId
 * threads a follow-up message within one open session rather than starting a new backend thread
 * every turn.
 *
 * <p>Discoverability: a new AI chat feature living only as an icon among four other icons is easy
 * to never notice. A small pulsing dot (Tailwind's built-in animate-ping, no custom keyframes)
 * marks the button until the user actually opens the drawer once -- then it's gone for good,
 * persisted per-account via safeStorage, so it helps someone find Fyn for the first time without
 * nagging everyone who already knows it's there on every subsequent visit.
 */
export function FynWidget() {
  const { email } = useAuth();
  const [open, setOpen] = useState(false);
  const [hasBeenOpened, setHasBeenOpened] = useState(
    () => safeStorage.getItem(fynSeenStorageKey(email)) === 'true'
  );

  useEffect(() => {
    if (!open) return;
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false);
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [open]);

  function openDrawer() {
    setOpen(true);
    if (!hasBeenOpened) {
      setHasBeenOpened(true);
      safeStorage.setItem(fynSeenStorageKey(email), 'true');
    }
  }

  return (
    <>
      <button
        type="button"
        onClick={openDrawer}
        title="Ask Fyn"
        aria-label="Ask Fyn"
        className="relative w-10 h-10 rounded-full bg-card border border-border shadow-card flex items-center justify-center text-muted hover:text-ink"
      >
        <MessageCircle size={17} />
        {!hasBeenOpened && (
          <span className="absolute -top-0.5 -right-0.5 flex h-3 w-3" aria-hidden="true" data-testid="fyn-unseen-badge">
            <span className="animate-ping motion-reduce:animate-none absolute inline-flex h-full w-full rounded-full bg-primary opacity-75" />
            <span className="relative inline-flex h-3 w-3 rounded-full bg-primary" />
          </span>
        )}
      </button>

      {open && (
        <>
          <div className="fixed inset-0 bg-black/40 z-30" onClick={() => setOpen(false)} />
          <div className="fixed right-0 top-0 z-40 h-full w-full max-w-md bg-card border-l border-border shadow-soft flex flex-col">
            <div className="flex items-center justify-between px-4 py-3.5 border-b border-border flex-shrink-0">
              <h3 className="font-semibold text-ink">Ask Fyn</h3>
              <button type="button" onClick={() => setOpen(false)} aria-label="Close" className="text-muted hover:text-ink">
                <X size={18} />
              </button>
            </div>
            <div className="flex-1 min-h-0 p-4">
              <PremiumFeatureGate featureKey="FYN_CHAT">
                <FynChat />
              </PremiumFeatureGate>
            </div>
          </div>
        </>
      )}
    </>
  );
}

function FynChat() {
  const [turns, setTurns] = useState<ChatTurn[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const conversationId = useRef<string | undefined>(undefined);

  function onErrorMessage(err: unknown): string {
    const response = (err as { response?: { data?: { message?: string } } })?.response;
    return response?.data?.message ?? 'Fyn could not answer that right now.';
  }

  async function send(overrideText?: string) {
    const message = (overrideText ?? input).trim();
    if (!message || sending) return;
    setInput('');
    setError(null);
    setTurns((t) => [...t, { role: 'user', content: message }]);
    setSending(true);
    try {
      const result = await fynChatApi.send(message, conversationId.current);
      conversationId.current = result.conversationId;
      setTurns((t) => [...t, { role: 'assistant', content: result.reply }]);
    } catch (err) {
      setError(onErrorMessage(err));
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="flex flex-col h-full">
      <p className="text-xs text-muted mb-4 flex-shrink-0">
        Fyn answers questions about your own balance, spending, and budgets -- it narrates your
        real data, it doesn't give financial advice.
      </p>

      <div className="flex-1 min-h-0 space-y-3 mb-4 overflow-y-auto" role="log" aria-label="Conversation with Fyn">
        {turns.length === 0 && (
          <div className="space-y-2">
            <p className="text-sm text-muted mb-1">Try asking:</p>
            {SUGGESTED_QUESTIONS.map((question) => (
              <button
                key={question}
                type="button"
                onClick={() => void send(question)}
                disabled={sending}
                className="block w-full text-left rounded-lg border border-border bg-bg px-3 py-2 text-sm text-ink hover:bg-primary-light hover:border-primary disabled:opacity-50"
              >
                {question}
              </button>
            ))}
          </div>
        )}
        {turns.map((turn, i) => (
          <div key={i} className={turn.role === 'user' ? 'text-right' : 'text-left'}>
            <p className={
              'inline-block rounded-xl2 px-3 py-2 text-sm max-w-[80%] whitespace-pre-wrap ' +
              (turn.role === 'user' ? 'bg-primary text-white' : 'bg-bg border border-border text-ink')
            }>
              {turn.content}
            </p>
          </div>
        ))}
        {sending && <p className="text-sm text-muted">Fyn is thinking…</p>}
      </div>

      {error && <p className="text-sm text-danger mb-3 flex-shrink-0">{error}</p>}

      <div className="flex gap-2 flex-shrink-0">
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.nativeEvent.isComposing) void send(); }}
          placeholder="Ask about your balance, spending, or budgets…"
          disabled={sending}
          className="flex-1 rounded-lg border border-border bg-bg px-3 py-2 text-sm text-ink"
        />
        <button
          type="button"
          onClick={() => void send()}
          disabled={sending || !input.trim()}
          className="rounded-lg bg-primary px-3 py-2 text-white disabled:opacity-50"
          aria-label="Send"
        >
          <Send size={16} />
        </button>
      </div>
    </div>
  );
}
