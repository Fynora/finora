import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { FynWidget } from './FynWidget';
import { fynChatApi, entitlementsApi } from '../api/endpoints';
import { AuthProvider } from '../context/AuthContext';
import { safeStorage } from '../lib/safeStorage';

vi.mock('../api/endpoints', () => ({
  fynChatApi: { send: vi.fn(), sendScreenshot: vi.fn(), history: vi.fn(), setFeedback: vi.fn() },
  entitlementsApi: { mine: vi.fn() },
}));

function entitled(featureKey: string) {
  vi.mocked(entitlementsApi.mine).mockResolvedValue(
    { planCode: 'PLUS', planName: 'Plus', features: { [featureKey]: true } });
}

// PremiumFeatureGate reads entitlements via useQuery, so every render needs a real
// QueryClientProvider ancestor -- same pattern the old Fyn.test.tsx's own renderFyn() used.
// AuthProvider added for the discoverability badge's useAuth() call -- same wrapping
// TopBar.test.tsx's own renderTopBar() already uses.
function renderWidget() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <FynWidget />
      </AuthProvider>
    </QueryClientProvider>
  );
}

async function openDrawer() {
  await userEvent.click(screen.getByRole('button', { name: /ask fyn/i }));
}

describe('FynWidget', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // The discoverability badge's "seen" flag persists in real localStorage across tests in this
    // file (no global clear between them) -- without this, whichever test opens the drawer first
    // would mark it seen for every test that runs after it.
    localStorage.clear();
    // Every render now fetches history on mount -- default to "nothing to resume" so the other
    // tests (which don't care about it) see the same blank-start behavior they always did. Tests
    // that DO care override this explicitly.
    vi.mocked(fynChatApi.history).mockResolvedValue({ conversationId: null, turns: [] });
  });

  it('shows the discoverability badge for a user who has never opened Fyn', () => {
    entitled('FYN_CHAT');
    renderWidget();

    expect(screen.getByTestId('fyn-unseen-badge')).toBeInTheDocument();
  });

  it('hides the discoverability badge for good after the first open', async () => {
    entitled('FYN_CHAT');
    renderWidget();
    expect(screen.getByTestId('fyn-unseen-badge')).toBeInTheDocument();

    await openDrawer();

    expect(screen.queryByTestId('fyn-unseen-badge')).not.toBeInTheDocument();
    // Persisted, not just in-memory for this render -- a fresh mount (e.g. the next page load)
    // must not show it again.
    expect(safeStorage.getItem('finora_fyn_seen_anonymous')).toBe('true');
  });

  it('does not show the badge for a returning user who has already opened Fyn before', () => {
    safeStorage.setItem('finora_fyn_seen_anonymous', 'true');
    entitled('FYN_CHAT');

    renderWidget();

    expect(screen.queryByTestId('fyn-unseen-badge')).not.toBeInTheDocument();
  });

  it('renders only the trigger button until opened', () => {
    entitled('FYN_CHAT');
    renderWidget();

    expect(screen.getByRole('button', { name: /ask fyn/i })).toBeInTheDocument();
    expect(screen.queryByPlaceholderText(/ask about your balance/i)).not.toBeInTheDocument();
  });

  it('opens the drawer on click and shows the upgrade prompt when the user lacks FYN_CHAT', async () => {
    vi.mocked(entitlementsApi.mine).mockResolvedValue({ planCode: 'FREE', planName: 'Free', features: {} });
    renderWidget();

    await openDrawer();

    expect(await screen.findByText(/premium feature/i)).toBeInTheDocument();
    expect(screen.queryByPlaceholderText(/ask about your balance/i)).not.toBeInTheDocument();
  });

  it('closes the drawer via the close button', async () => {
    entitled('FYN_CHAT');
    renderWidget();
    await openDrawer();
    await screen.findByPlaceholderText(/ask about your balance/i);

    await userEvent.click(screen.getByRole('button', { name: /^close$/i }));

    expect(screen.queryByPlaceholderText(/ask about your balance/i)).not.toBeInTheDocument();
  });

  it('closes the drawer on Escape', async () => {
    entitled('FYN_CHAT');
    renderWidget();
    await openDrawer();
    await screen.findByPlaceholderText(/ask about your balance/i);

    fireEvent.keyDown(window, { key: 'Escape' });

    expect(screen.queryByPlaceholderText(/ask about your balance/i)).not.toBeInTheDocument();
  });

  it('shows tappable suggested questions before the first message', async () => {
    entitled('FYN_CHAT');
    renderWidget();
    await openDrawer();

    expect(await screen.findByRole('button', { name: "What's my balance?" })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'How are my budgets doing?' })).toBeInTheDocument();
  });

  it('tapping a suggested question sends it immediately, without typing', async () => {
    entitled('FYN_CHAT');
    vi.mocked(fynChatApi.send).mockResolvedValue({ conversationId: 'conv-1', reply: 'Your balance is ₹50,000.', messageId: 'msg-1' });
    renderWidget();
    await openDrawer();

    await userEvent.click(await screen.findByRole('button', { name: "What's my balance?" }));

    expect(await screen.findByText(/50,000/)).toBeInTheDocument();
    expect(screen.getByText("What's my balance?")).toBeInTheDocument();
    expect(fynChatApi.send).toHaveBeenCalledWith("What's my balance?", undefined);
  });

  it('hides the suggestions once a conversation has started', async () => {
    entitled('FYN_CHAT');
    vi.mocked(fynChatApi.send).mockResolvedValue({ conversationId: 'conv-1', reply: 'Your balance is ₹50,000.', messageId: 'msg-1' });
    renderWidget();
    await openDrawer();

    await userEvent.click(await screen.findByRole('button', { name: "What's my balance?" }));
    await screen.findByText(/50,000/);

    expect(screen.queryByRole('button', { name: 'How are my budgets doing?' })).not.toBeInTheDocument();
  });

  it('sends a message and renders the reply', async () => {
    entitled('FYN_CHAT');
    vi.mocked(fynChatApi.send).mockResolvedValue({ conversationId: 'conv-1', reply: 'Your balance is ₹50,000.', messageId: 'msg-1' });
    renderWidget();
    await openDrawer();

    const input = await screen.findByPlaceholderText(/ask about your balance/i);
    await userEvent.type(input, "what's my balance?");
    await userEvent.click(screen.getByRole('button', { name: /send/i }));

    expect(await screen.findByText(/50,000/)).toBeInTheDocument();
    expect(screen.getByText("what's my balance?")).toBeInTheDocument();
    expect(fynChatApi.send).toHaveBeenCalledWith("what's my balance?", undefined);
  });

  it('continues the same conversation on a second message', async () => {
    entitled('FYN_CHAT');
    vi.mocked(fynChatApi.send)
      .mockResolvedValueOnce({ conversationId: 'conv-1', reply: 'First reply.', messageId: 'msg-1' })
      .mockResolvedValueOnce({ conversationId: 'conv-1', reply: 'Second reply.', messageId: 'msg-1' });
    renderWidget();
    await openDrawer();
    const input = await screen.findByPlaceholderText(/ask about your balance/i);

    await userEvent.type(input, 'first question');
    await userEvent.click(screen.getByRole('button', { name: /send/i }));
    await screen.findByText('First reply.');

    await userEvent.type(input, 'follow up');
    await userEvent.click(screen.getByRole('button', { name: /send/i }));
    await screen.findByText('Second reply.');

    expect(fynChatApi.send).toHaveBeenNthCalledWith(2, 'follow up', 'conv-1');
  });

  it('shows an inline error without crashing', async () => {
    entitled('FYN_CHAT');
    vi.mocked(fynChatApi.send).mockRejectedValueOnce({ response: { data: { message: 'Fyn is over budget.' } } });
    renderWidget();
    await openDrawer();

    const input = await screen.findByPlaceholderText(/ask about your balance/i);
    await userEvent.type(input, 'hi');
    await userEvent.click(screen.getByRole('button', { name: /send/i }));

    expect(await screen.findByText('Fyn is over budget.')).toBeInTheDocument();
  });

  it('does not send on the Enter keystroke that confirms an IME composition', async () => {
    entitled('FYN_CHAT');
    renderWidget();
    await openDrawer();

    const input = await screen.findByPlaceholderText(/ask about your balance/i);
    await userEvent.type(input, '日本語');
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true });

    expect(fynChatApi.send).not.toHaveBeenCalled();
  });

  it('does not send a blank message', async () => {
    entitled('FYN_CHAT');
    renderWidget();
    await openDrawer();
    await screen.findByPlaceholderText(/ask about your balance/i);

    await userEvent.click(screen.getByRole('button', { name: /send/i }));

    expect(fynChatApi.send).not.toHaveBeenCalled();
  });

  describe('screenshot attachment', () => {
    function pngFile(name = 'screenshot.png') {
      return new File(['fake-png-bytes'], name, { type: 'image/png' });
    }

    it('shows a preview chip after attaching a screenshot, and can remove it', async () => {
      entitled('FYN_CHAT');
      renderWidget();
      await openDrawer();

      await userEvent.upload(screen.getByTestId('fyn-screenshot-input'), pngFile());

      expect(screen.getByText('screenshot.png')).toBeInTheDocument();

      await userEvent.click(screen.getByRole('button', { name: /remove attachment/i }));

      expect(screen.queryByText('screenshot.png')).not.toBeInTheDocument();
    });

    it('sends the attached screenshot with the typed question via sendScreenshot, not send', async () => {
      entitled('FYN_CHAT');
      vi.mocked(fynChatApi.sendScreenshot).mockResolvedValue(
        { conversationId: 'conv-1', reply: 'That looks like a Swiggy order for ₹499.', messageId: 'msg-1' });
      renderWidget();
      await openDrawer();

      const file = pngFile();
      await userEvent.upload(screen.getByTestId('fyn-screenshot-input'), file);
      const input = await screen.findByPlaceholderText(/add a question about this screenshot/i);
      await userEvent.type(input, 'what is this charge?');
      await userEvent.click(screen.getByRole('button', { name: /send/i }));

      expect(await screen.findByText(/swiggy order/i)).toBeInTheDocument();
      expect(fynChatApi.sendScreenshot).toHaveBeenCalledWith(file, 'what is this charge?', undefined);
      expect(fynChatApi.send).not.toHaveBeenCalled();
      // The chip clears once the turn is sent, ready for a fresh attachment on the next message.
      expect(screen.queryByRole('button', { name: /remove attachment/i })).not.toBeInTheDocument();
    });

    it('sends an attachment with no typed text, using the default question', async () => {
      entitled('FYN_CHAT');
      vi.mocked(fynChatApi.sendScreenshot).mockResolvedValue({ conversationId: 'conv-1', reply: 'reply', messageId: 'msg-1' });
      renderWidget();
      await openDrawer();

      await userEvent.upload(screen.getByTestId('fyn-screenshot-input'), pngFile());
      await userEvent.click(screen.getByRole('button', { name: /send/i }));

      expect(await screen.findByText('What can you tell me about this screenshot?')).toBeInTheDocument();
      expect(fynChatApi.sendScreenshot).toHaveBeenCalledWith(expect.any(File), '', undefined);
    });

    it('rejects an unsupported file type client-side without calling the API', async () => {
      entitled('FYN_CHAT');
      renderWidget();
      await openDrawer();

      // fireEvent, not userEvent.upload -- upload() respects the input's own accept attribute and
      // silently refuses a mismatched file, same gotcha Import.test.tsx's own comment documents;
      // fireEvent bypasses that so the component's own validation branch is actually exercised.
      const input = screen.getByTestId('fyn-screenshot-input');
      const badFile = new File(['not an image'], 'notes.txt', { type: 'text/plain' });
      fireEvent.change(input, { target: { files: [badFile] } });

      expect(await screen.findByText(/PNG, JPEG, or WebP/i)).toBeInTheDocument();
      expect(screen.queryByText('notes.txt')).not.toBeInTheDocument();
      await userEvent.click(screen.getByRole('button', { name: /send/i }));
      expect(fynChatApi.sendScreenshot).not.toHaveBeenCalled();
    });

    it('rejects an oversized file client-side without calling the API', async () => {
      entitled('FYN_CHAT');
      renderWidget();
      await openDrawer();

      const tooBig = new File([new Uint8Array(9 * 1024 * 1024)], 'huge.png', { type: 'image/png' });
      fireEvent.change(screen.getByTestId('fyn-screenshot-input'), { target: { files: [tooBig] } });

      expect(await screen.findByText(/too large/i)).toBeInTheDocument();
      expect(screen.queryByText('huge.png')).not.toBeInTheDocument();
    });
  });

  describe('resuming a past conversation', () => {
    it('resumes a past conversation on mount, not the suggested questions', async () => {
      entitled('FYN_CHAT');
      vi.mocked(fynChatApi.history).mockResolvedValue({
        conversationId: 'conv-1',
        turns: [
          { id: 'u1', role: 'user', content: "what's my balance?", feedback: null },
          { id: 'a1', role: 'assistant', content: 'Your balance is ₹50,000.', feedback: null },
        ],
      });
      renderWidget();
      await openDrawer();

      expect(await screen.findByText("what's my balance?")).toBeInTheDocument();
      expect(screen.getByText('Your balance is ₹50,000.')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'How are my budgets doing?' })).not.toBeInTheDocument();
    });

    it('continues the resumed conversation using its conversationId, not a new one', async () => {
      entitled('FYN_CHAT');
      vi.mocked(fynChatApi.history).mockResolvedValue({
        conversationId: 'conv-1',
        turns: [
          { id: 'u1', role: 'user', content: 'first question', feedback: null },
          { id: 'a1', role: 'assistant', content: 'first reply', feedback: null },
        ],
      });
      vi.mocked(fynChatApi.send).mockResolvedValue({ conversationId: 'conv-1', reply: 'second reply', messageId: 'msg-1' });
      renderWidget();
      await openDrawer();
      const input = await screen.findByPlaceholderText(/ask about your balance/i);

      await userEvent.type(input, 'follow up');
      await userEvent.click(screen.getByRole('button', { name: /send/i }));
      await screen.findByText('second reply');

      expect(fynChatApi.send).toHaveBeenCalledWith('follow up', 'conv-1');
    });

    it('shows suggested questions as normal for a user who has never chatted', async () => {
      entitled('FYN_CHAT');
      vi.mocked(fynChatApi.history).mockResolvedValue({ conversationId: null, turns: [] });
      renderWidget();
      await openDrawer();

      expect(await screen.findByRole('button', { name: "What's my balance?" })).toBeInTheDocument();
    });

    it('falls back to a blank conversation if the history fetch fails, without crashing', async () => {
      entitled('FYN_CHAT');
      vi.mocked(fynChatApi.history).mockRejectedValue(new Error('network error'));
      renderWidget();
      await openDrawer();

      expect(await screen.findByRole('button', { name: "What's my balance?" })).toBeInTheDocument();
    });

    // Found in review: send() reads conversationId.current, which the history effect's own
    // .then() can still overwrite AFTER a message was sent if the history fetch is slow enough --
    // clobbering the just-created (or resumed) conversation reference back to whatever it was
    // before. The fix is disabling send entirely until history has settled, proven here by
    // holding the history fetch open and confirming send is genuinely refused, not just visually
    // discouraged.
    it('refuses to send while the history fetch is still in flight, closing the conversationId race', async () => {
      entitled('FYN_CHAT');
      let resolveHistory: (() => void) | undefined;
      vi.mocked(fynChatApi.history).mockReturnValue(
        new Promise((resolve) => { resolveHistory = () => resolve({ conversationId: null, turns: [] }); }));
      renderWidget();
      await openDrawer();
      const input = await screen.findByPlaceholderText(/ask about your balance/i);

      expect(input).toBeDisabled();
      await userEvent.type(input, 'hi'); // typing into a disabled input is a no-op, same as a real browser
      await userEvent.click(screen.getByRole('button', { name: /send/i }));

      expect(fynChatApi.send).not.toHaveBeenCalled();

      resolveHistory?.();
      await waitFor(() => expect(input).not.toBeDisabled());
    });
  });

  describe('rating a reply', () => {
    it('shows thumbs up/down under an assistant reply, not under the user\'s own message', async () => {
      entitled('FYN_CHAT');
      vi.mocked(fynChatApi.send).mockResolvedValue({ conversationId: 'conv-1', reply: 'reply', messageId: 'msg-1' });
      renderWidget();
      await openDrawer();
      const input = await screen.findByPlaceholderText(/ask about your balance/i);
      await userEvent.type(input, 'hi');
      await userEvent.click(screen.getByRole('button', { name: /send/i }));
      await screen.findByText('reply');

      expect(screen.getAllByLabelText('Helpful')).toHaveLength(1);
      expect(screen.getAllByLabelText('Not helpful')).toHaveLength(1);
    });

    it('tapping helpful calls setFeedback with HELPFUL and marks it pressed', async () => {
      entitled('FYN_CHAT');
      vi.mocked(fynChatApi.send).mockResolvedValue({ conversationId: 'conv-1', reply: 'reply', messageId: 'msg-1' });
      vi.mocked(fynChatApi.setFeedback).mockResolvedValue(undefined);
      renderWidget();
      await openDrawer();
      const input = await screen.findByPlaceholderText(/ask about your balance/i);
      await userEvent.type(input, 'hi');
      await userEvent.click(screen.getByRole('button', { name: /send/i }));
      await screen.findByText('reply');

      await userEvent.click(screen.getByLabelText('Helpful'));

      expect(fynChatApi.setFeedback).toHaveBeenCalledWith('msg-1', 'HELPFUL');
      await waitFor(() => expect(screen.getByLabelText('Helpful')).toHaveAttribute('aria-pressed', 'true'));
    });

    it('tapping the same thumb again clears the rating (sends null)', async () => {
      entitled('FYN_CHAT');
      vi.mocked(fynChatApi.history).mockResolvedValue({
        conversationId: 'conv-1',
        turns: [{ id: 'a1', role: 'assistant', content: 'reply', feedback: 'HELPFUL' }],
      });
      vi.mocked(fynChatApi.setFeedback).mockResolvedValue(undefined);
      renderWidget();
      await openDrawer();
      await screen.findByText('reply');
      expect(screen.getByLabelText('Helpful')).toHaveAttribute('aria-pressed', 'true');

      await userEvent.click(screen.getByLabelText('Helpful'));

      expect(fynChatApi.setFeedback).toHaveBeenCalledWith('a1', null);
      await waitFor(() => expect(screen.getByLabelText('Helpful')).toHaveAttribute('aria-pressed', 'false'));
    });

    it('disables both thumbs while a rating request is in flight, so a rapid tap on the other one is a no-op', async () => {
      entitled('FYN_CHAT');
      vi.mocked(fynChatApi.send).mockResolvedValue({ conversationId: 'conv-1', reply: 'reply', messageId: 'msg-1' });
      let resolveFeedback: (() => void) | undefined;
      vi.mocked(fynChatApi.setFeedback).mockReturnValue(new Promise((resolve) => { resolveFeedback = () => resolve(undefined); }));
      renderWidget();
      await openDrawer();
      const input = await screen.findByPlaceholderText(/ask about your balance/i);
      await userEvent.type(input, 'hi');
      await userEvent.click(screen.getByRole('button', { name: /send/i }));
      await screen.findByText('reply');

      await userEvent.click(screen.getByLabelText('Helpful'));
      expect(screen.getByLabelText('Helpful')).toBeDisabled();
      expect(screen.getByLabelText('Not helpful')).toBeDisabled();

      // Same request the disabled state is meant to prevent -- a rapid tap on the other thumb
      // while the first is still in flight -- attempted anyway, to prove the guard actually
      // blocks it rather than just visually discouraging it.
      await userEvent.click(screen.getByLabelText('Not helpful'));
      expect(fynChatApi.setFeedback).toHaveBeenCalledTimes(1);
      expect(fynChatApi.setFeedback).toHaveBeenCalledWith('msg-1', 'HELPFUL');

      resolveFeedback?.();
      await waitFor(() => expect(screen.getByLabelText('Helpful')).not.toBeDisabled());
    });

    it('reverts the optimistic update if saving feedback fails', async () => {
      entitled('FYN_CHAT');
      vi.mocked(fynChatApi.send).mockResolvedValue({ conversationId: 'conv-1', reply: 'reply', messageId: 'msg-1' });
      vi.mocked(fynChatApi.setFeedback).mockRejectedValue(new Error('network error'));
      renderWidget();
      await openDrawer();
      const input = await screen.findByPlaceholderText(/ask about your balance/i);
      await userEvent.type(input, 'hi');
      await userEvent.click(screen.getByRole('button', { name: /send/i }));
      await screen.findByText('reply');

      await userEvent.click(screen.getByLabelText('Helpful'));

      await waitFor(() => expect(screen.getByLabelText('Helpful')).toHaveAttribute('aria-pressed', 'false'));
    });
  });
});
