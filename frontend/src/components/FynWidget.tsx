import { useEffect, useRef, useState } from 'react';
import type { ChangeEvent } from 'react';
import { MessageCircle, Paperclip, Send, ThumbsDown, ThumbsUp, X } from 'lucide-react';
import { fynChatApi } from '../api/endpoints';
import type { FynFeedback } from '../api/endpoints';
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
  // '' for a just-sent user bubble that hasn't round-tripped yet -- the backend never hands the
  // client a user message's own id (only the assistant reply's, which is the only row feedback is
  // ever recorded against), so there's nothing real to put here for that role anyway.
  id: string;
  role: 'user' | 'assistant';
  content: string;
  feedback: FynFeedback | null;
  // Display-only -- FynScreenshotOcrService.describeForChat folds the attachment into `content`
  // server-side, this just lets the user's own bubble show what they attached.
  attachmentName?: string;
}

// Mirrors FynScreenshotOcrService's own ALLOWED_CONTENT_TYPES/MAX_IMAGE_BYTES exactly -- client
// validation is a fast UX nicety only, the backend re-validates the same limits regardless.
const ALLOWED_IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp'];
const MAX_IMAGE_BYTES = 8 * 1024 * 1024;

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
 * away to a dedicated page. FynChat resumes the caller's most recent conversation on mount (via
 * GET /fyn/chat/history) rather than starting blank every time the drawer reopens -- it was
 * component-local-only state until users pointed out that closing the drawer (or navigating away
 * on mobile) silently discarded a conversation that was, in fact, already persisted server-side
 * all along.
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
        // accent-purple, not the neutral bg-card/text-muted every other TopBar icon uses --
        // reuses the app's own existing decorative accent family (Dashboard/Insights' identical
        // bg-accent-purple-bg/text-accent-purple icon chips), not an invented color, so Fyn reads
        // as its own distinct thing among four neutral utility icons rather than blending in.
        className="relative w-10 h-10 rounded-full bg-accent-purple-bg border border-accent-purple text-accent-purple shadow-card flex items-center justify-center transition-opacity hover:opacity-80"
      >
        <MessageCircle size={17} />
        {!hasBeenOpened && (
          <span className="absolute -top-0.5 -right-0.5 flex h-3 w-3" aria-hidden="true" data-testid="fyn-unseen-badge">
            <span className="animate-ping motion-reduce:animate-none absolute inline-flex h-full w-full rounded-full bg-accent-purple opacity-75" />
            <span className="relative inline-flex h-3 w-3 rounded-full bg-accent-purple" />
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
  const [attachedImage, setAttachedImage] = useState<File | null>(null);
  // Starts true so the empty state (suggested questions) doesn't flash before a real, resumed
  // conversation has had a chance to load in -- see the effect below.
  const [loadingHistory, setLoadingHistory] = useState(true);
  const conversationId = useRef<string | undefined>(undefined);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let cancelled = false;
    fynChatApi.history()
      .then((result) => {
        if (cancelled) return;
        if (result.conversationId) conversationId.current = result.conversationId;
        if (result.turns.length > 0) setTurns(result.turns);
      })
      // Silent: a failed history fetch just means this mount starts blank, exactly the behavior
      // every mount had before this existed -- not worth an error banner over.
      .catch(() => {})
      .finally(() => { if (!cancelled) setLoadingHistory(false); });
    return () => { cancelled = true; };
  }, []);

  function onErrorMessage(err: unknown): string {
    const response = (err as { response?: { data?: { message?: string } } })?.response;
    return response?.data?.message ?? 'Fyn could not answer that right now.';
  }

  function onFileSelected(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = ''; // lets the same file be re-selected later (e.g. after removing it)
    if (!file) return;
    if (!ALLOWED_IMAGE_TYPES.includes(file.type)) {
      setError('Please attach a PNG, JPEG, or WebP screenshot.');
      return;
    }
    if (file.size > MAX_IMAGE_BYTES) {
      setError('Screenshot is too large -- please attach one under 8MB.');
      return;
    }
    setError(null);
    setAttachedImage(file);
  }

  // overrideText only ever comes from the suggested-question chips, which never have an
  // attachment -- a plain typed message can still carry one.
  async function send(overrideText?: string) {
    const message = (overrideText ?? input).trim();
    if (sending || (!message && !attachedImage)) return;
    const image = attachedImage;
    setInput('');
    setAttachedImage(null);
    setError(null);
    setTurns((t) => [...t, {
      id: '',
      role: 'user',
      content: message || 'What can you tell me about this screenshot?',
      feedback: null,
      attachmentName: image?.name,
    }]);
    setSending(true);
    try {
      const result = image
        ? await fynChatApi.sendScreenshot(image, message, conversationId.current)
        : await fynChatApi.send(message, conversationId.current);
      conversationId.current = result.conversationId;
      setTurns((t) => [...t, { id: result.messageId, role: 'assistant', content: result.reply, feedback: null }]);
    } catch (err) {
      setError(onErrorMessage(err));
    } finally {
      setSending(false);
    }
  }

  // Tapping the thumb already showing toggles it off (sends null) rather than staying stuck rated
  // -- see FynChatOrchestrationService.setMessageFeedback's own doc comment for why. Optimistic:
  // the icon updates immediately, and reverts if the request fails, same as this component
  // doesn't otherwise block the UI on network round-trips. No store-review prompt here -- that's
  // mobile-only (FynScreen.tsx); the web app has no app store equivalent to ask for a rating on.
  async function rate(turn: ChatTurn, value: FynFeedback) {
    if (!turn.id) return;
    const next = turn.feedback === value ? null : value;
    const previous = turn.feedback;
    setTurns((ts) => ts.map((t) => (t.id === turn.id ? { ...t, feedback: next } : t)));
    try {
      await fynChatApi.setFeedback(turn.id, next);
    } catch {
      setTurns((ts) => ts.map((t) => (t.id === turn.id ? { ...t, feedback: previous } : t)));
    }
  }

  return (
    <div className="flex flex-col h-full">
      <p className="text-xs text-muted mb-4 flex-shrink-0">
        Fyn answers questions about your own balance, spending, and budgets -- it narrates your
        real data, it doesn't give financial advice.
      </p>

      {/* bg-surface, not the drawer's own bg-card: gives the conversation its own contained
          "well," distinct from both the header/input chrome above/below it and from the
          assistant bubbles inside it (bg-bg), which would otherwise blend into a plain bg-bg
          container. */}
      <div className="flex-1 min-h-0 space-y-3 mb-4 overflow-y-auto bg-surface rounded-xl2 p-3" role="log" aria-label="Conversation with Fyn">
        {loadingHistory && <p className="text-sm text-muted">Loading conversation…</p>}
        {!loadingHistory && turns.length === 0 && (
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
              {turn.attachmentName && (
                <span className={
                  'flex items-center gap-1 text-xs opacity-75 mb-1 ' +
                  (turn.role === 'user' ? 'justify-end' : '')
                }>
                  <Paperclip size={11} /> {turn.attachmentName}
                </span>
              )}
              {turn.content}
            </p>
            {turn.role === 'assistant' && turn.id && (
              <div className="flex gap-1 mt-1">
                <button
                  type="button"
                  onClick={() => void rate(turn, 'HELPFUL')}
                  aria-label="Helpful"
                  aria-pressed={turn.feedback === 'HELPFUL'}
                  className={'p-1 rounded hover:bg-primary-light ' + (turn.feedback === 'HELPFUL' ? 'text-primary' : 'text-muted')}
                >
                  <ThumbsUp size={13} />
                </button>
                <button
                  type="button"
                  onClick={() => void rate(turn, 'NOT_HELPFUL')}
                  aria-label="Not helpful"
                  aria-pressed={turn.feedback === 'NOT_HELPFUL'}
                  className={'p-1 rounded hover:bg-primary-light ' + (turn.feedback === 'NOT_HELPFUL' ? 'text-primary' : 'text-muted')}
                >
                  <ThumbsDown size={13} />
                </button>
              </div>
            )}
          </div>
        ))}
        {sending && <p className="text-sm text-muted">Fyn is thinking…</p>}
      </div>

      {error && <p className="text-sm text-danger mb-3 flex-shrink-0">{error}</p>}

      <div className="flex flex-col gap-2 flex-shrink-0">
        {attachedImage && (
          <div className="flex items-center gap-2 rounded-lg border border-border bg-bg px-3 py-1.5 text-xs text-ink">
            <Paperclip size={14} className="text-muted flex-shrink-0" />
            <span className="truncate flex-1">{attachedImage.name}</span>
            <button
              type="button"
              onClick={() => setAttachedImage(null)}
              aria-label="Remove attachment"
              className="text-muted hover:text-ink flex-shrink-0"
            >
              <X size={14} />
            </button>
          </div>
        )}
        <div className="flex gap-2">
          <input
            type="file"
            ref={fileInputRef}
            onChange={onFileSelected}
            accept="image/png,image/jpeg,image/webp"
            data-testid="fyn-screenshot-input"
            className="hidden"
          />
          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            disabled={sending}
            title="Attach a screenshot"
            aria-label="Attach a screenshot"
            className="rounded-lg border border-border bg-bg px-3 py-2 text-muted hover:text-ink disabled:opacity-50"
          >
            <Paperclip size={16} />
          </button>
          <input
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter' && !e.nativeEvent.isComposing) void send(); }}
            placeholder={attachedImage ? 'Add a question about this screenshot (optional)…' : 'Ask about your balance, spending, or budgets…'}
            disabled={sending}
            className="flex-1 rounded-lg border border-border bg-bg px-3 py-2 text-sm text-ink"
          />
          <button
            type="button"
            onClick={() => void send()}
            disabled={sending || (!input.trim() && !attachedImage)}
            className="rounded-lg bg-primary px-3 py-2 text-white disabled:opacity-50"
            aria-label="Send"
          >
            <Send size={16} />
          </button>
        </div>
      </div>
    </div>
  );
}
