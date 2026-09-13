import { useRef, useState } from 'react';
import { Send } from 'lucide-react';
import { fynChatApi } from '../api/endpoints';
import { FinoraCard, SectionHeader } from '../design-system';
import { PremiumFeatureGate } from '../components/PremiumFeatureGate';

interface ChatTurn {
  role: 'user' | 'assistant';
  content: string;
}

/** Fyn Phase 4 chat (plan §6). Conversation state lives only in this component -- a reload starts
 *  a fresh conversation, same as most chat widgets' first cut; {@code conversationId} is tracked
 *  so a follow-up message within one page visit continues the same backend thread rather than
 *  starting a new one every turn. */
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

  async function send() {
    const message = input.trim();
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
    <FinoraCard>
      <SectionHeader title="Ask Fyn" />
      <p className="text-xs text-muted mb-4">
        Fyn answers questions about your own balance, spending, and budgets -- it narrates your
        real data, it doesn't give financial advice.
      </p>

      <div className="space-y-3 mb-4 max-h-96 overflow-y-auto" role="log" aria-label="Conversation with Fyn">
        {turns.length === 0 && (
          <p className="text-sm text-muted">Try asking "what's my balance?" or "how's my Dining budget?"</p>
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

      {error && <p className="text-sm text-danger mb-3">{error}</p>}

      <div className="flex gap-2">
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
    </FinoraCard>
  );
}

export default function Fyn() {
  return (
    <div className="space-y-6">
      <PremiumFeatureGate featureKey="FYN_CHAT">
        <FynChat />
      </PremiumFeatureGate>
    </div>
  );
}
