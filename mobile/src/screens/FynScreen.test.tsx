import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { FynScreen } from './FynScreen';
import { fynChatApi, entitlementsApi, type EntitlementsDto } from '../api/endpoints';
import { ScreenshotTooLargeError, pickFynScreenshot } from '../lib/fynScreenshot';
import * as StoreReview from 'expo-store-review';
import { ThemeProvider } from '../theme';

jest.mock('../api/endpoints', () => ({
  fynChatApi: { send: jest.fn(), history: jest.fn(), sendScreenshot: jest.fn(), setFeedback: jest.fn() },
  entitlementsApi: { mine: jest.fn() },
}));
jest.mock('../lib/fynScreenshot', () => {
  class ScreenshotTooLargeError extends Error {}
  return { ScreenshotTooLargeError, pickFynScreenshot: jest.fn() };
});
jest.mock('expo-store-review', () => ({ hasAction: jest.fn(), requestReview: jest.fn() }));

const mockGoBack = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ goBack: mockGoBack }),
}));

const fynChat = fynChatApi as jest.Mocked<typeof fynChatApi>;
const entitlements = entitlementsApi as jest.Mocked<typeof entitlementsApi>;
const picker = pickFynScreenshot as jest.MockedFunction<typeof pickFynScreenshot>;
const storeReview = StoreReview as jest.Mocked<typeof StoreReview>;

async function settle() {
  await act(async () => {});
}

function granted(overrides: Partial<EntitlementsDto> = {}): EntitlementsDto {
  return { planCode: 'PLUS', planName: 'Plus', features: { FYN_CHAT: true }, ...overrides };
}

// PremiumFeatureGate reads entitlements via useQuery, same as every other gated screen's own
// renderScreen() helper (see AdvancedReportsScreen.test.tsx).
function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <FynScreen />
      </ThemeProvider>
    </QueryClientProvider>
  );
}

describe('FynScreen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    // Every render now fetches history on mount -- default to "nothing to resume" so the other
    // tests (which don't care about it) see the same blank-start behavior they always did. Tests
    // that DO care override this explicitly.
    fynChat.history.mockResolvedValue({ conversationId: null, turns: [] });
    storeReview.hasAction.mockResolvedValue(false);
  });

  it('shows the upgrade prompt, not the chat, when the user lacks FYN_CHAT', async () => {
    entitlements.mine.mockResolvedValue(granted({ features: {} }));

    renderScreen();

    expect(await screen.findByText(/Ask Fyn is a premium feature/i)).toBeTruthy();
    expect(screen.queryByPlaceholderText(/ask about your balance/i)).toBeNull();
    expect(fynChat.send).not.toHaveBeenCalled();
  });

  it('shows tappable suggested questions before the first message', async () => {
    entitlements.mine.mockResolvedValue(granted());

    renderScreen();

    expect(await screen.findByText("What's my balance?")).toBeTruthy();
    expect(screen.getByText('How are my budgets doing?')).toBeTruthy();
  });

  it('tapping a suggested question sends it immediately, without typing', async () => {
    entitlements.mine.mockResolvedValue(granted());
    fynChat.send.mockResolvedValue({ conversationId: 'conv-1', reply: 'Your balance is ₹50,000.', messageId: 'msg-1' });

    renderScreen();
    fireEvent.press(await screen.findByLabelText("What's my balance?"));

    expect(await screen.findByText(/50,000/)).toBeTruthy();
    expect(screen.getByText("What's my balance?")).toBeTruthy();
    expect(fynChat.send).toHaveBeenCalledWith("What's my balance?", undefined);
  });

  it('hides the suggestions once a conversation has started', async () => {
    entitlements.mine.mockResolvedValue(granted());
    fynChat.send.mockResolvedValue({ conversationId: 'conv-1', reply: 'Your balance is ₹50,000.', messageId: 'msg-1' });

    renderScreen();
    fireEvent.press(await screen.findByLabelText("What's my balance?"));
    await screen.findByText(/50,000/);

    expect(screen.queryByLabelText('How are my budgets doing?')).toBeNull();
  });

  it('sends a message and renders the reply', async () => {
    entitlements.mine.mockResolvedValue(granted());
    fynChat.send.mockResolvedValue({ conversationId: 'conv-1', reply: 'Your balance is ₹50,000.', messageId: 'msg-1' });

    renderScreen();
    const input = await screen.findByPlaceholderText(/ask about your balance/i);
    fireEvent.changeText(input, "what's my balance?");
    fireEvent.press(screen.getByLabelText('Send'));

    expect(await screen.findByText(/50,000/)).toBeTruthy();
    expect(screen.getByText("what's my balance?")).toBeTruthy();
    expect(fynChat.send).toHaveBeenCalledWith("what's my balance?", undefined);
  });

  it('continues the same conversation on a second message', async () => {
    entitlements.mine.mockResolvedValue(granted());
    fynChat.send
      .mockResolvedValueOnce({ conversationId: 'conv-1', reply: 'First reply.', messageId: 'msg-1' })
      .mockResolvedValueOnce({ conversationId: 'conv-1', reply: 'Second reply.', messageId: 'msg-1' });

    renderScreen();
    const input = await screen.findByPlaceholderText(/ask about your balance/i);

    fireEvent.changeText(input, 'first question');
    fireEvent.press(screen.getByLabelText('Send'));
    await screen.findByText('First reply.');

    fireEvent.changeText(input, 'follow up');
    fireEvent.press(screen.getByLabelText('Send'));
    await screen.findByText('Second reply.');

    expect(fynChat.send).toHaveBeenNthCalledWith(2, 'follow up', 'conv-1');
  });

  it('shows an inline error, using the server message, without crashing', async () => {
    entitlements.mine.mockResolvedValue(granted());
    fynChat.send.mockRejectedValueOnce({
      isAxiosError: true,
      response: { status: 503, data: { message: 'Fyn is over budget.' } },
    });

    renderScreen();
    const input = await screen.findByPlaceholderText(/ask about your balance/i);
    fireEvent.changeText(input, 'hi');
    fireEvent.press(screen.getByLabelText('Send'));

    expect(await screen.findByText('Fyn is over budget.')).toBeTruthy();
  });

  it('does not send a blank message', async () => {
    entitlements.mine.mockResolvedValue(granted());

    renderScreen();
    await screen.findByPlaceholderText(/ask about your balance/i);
    fireEvent.press(screen.getByLabelText('Send'));

    expect(fynChat.send).not.toHaveBeenCalled();
  });

  it('goes back via the header button', async () => {
    entitlements.mine.mockResolvedValue(granted());
    renderScreen();

    // The Back button renders immediately (it's outside PremiumFeatureGate), independently of the
    // entitlements query -- but that query is still in flight underneath it. Waiting for the
    // gated chat input too, not just Back, lets that query settle inside this test's own act()
    // scope instead of resolving after the test has already finished and unmounted.
    await screen.findByPlaceholderText(/ask about your balance/i);
    fireEvent.press(screen.getByLabelText('Back'));

    expect(mockGoBack).toHaveBeenCalled();
  });

  describe('resuming a past conversation', () => {
    it('resumes a past conversation on mount, not the suggested questions', async () => {
      entitlements.mine.mockResolvedValue(granted());
      fynChat.history.mockResolvedValue({
        conversationId: 'conv-1',
        turns: [
          { id: 'u1', role: 'user', content: "what's my balance?", feedback: null },
          { id: 'a1', role: 'assistant', content: 'Your balance is ₹50,000.', feedback: null },
        ],
      });

      renderScreen();

      expect(await screen.findByText("what's my balance?")).toBeTruthy();
      expect(screen.getByText('Your balance is ₹50,000.')).toBeTruthy();
      expect(screen.queryByLabelText('How are my budgets doing?')).toBeNull();
    });

    it('continues the resumed conversation using its conversationId, not a new one', async () => {
      entitlements.mine.mockResolvedValue(granted());
      fynChat.history.mockResolvedValue({
        conversationId: 'conv-1',
        turns: [
          { id: 'u1', role: 'user', content: 'first question', feedback: null },
          { id: 'a1', role: 'assistant', content: 'first reply', feedback: null },
        ],
      });
      fynChat.send.mockResolvedValue({ conversationId: 'conv-1', reply: 'second reply', messageId: 'msg-2' });

      renderScreen();
      const input = await screen.findByPlaceholderText(/ask about your balance/i);
      fireEvent.changeText(input, 'follow up');
      fireEvent.press(screen.getByLabelText('Send'));
      await screen.findByText('second reply');

      expect(fynChat.send).toHaveBeenCalledWith('follow up', 'conv-1');
    });

    // Found in review: send() reads conversationId.current, which the history effect's own
    // .then() can still overwrite AFTER a message was sent if the history fetch is slow enough --
    // clobbering the just-created (or resumed) conversation reference back to whatever it was
    // before. The fix is disabling send entirely until history has settled, proven here by
    // holding the history fetch open and confirming send is genuinely refused.
    it('refuses to send while the history fetch is still in flight, closing the conversationId race', async () => {
      entitlements.mine.mockResolvedValue(granted());
      let resolveHistory: (() => void) | undefined;
      fynChat.history.mockReturnValue(
        new Promise((resolve) => { resolveHistory = () => resolve({ conversationId: null, turns: [] }); }));
      renderScreen();
      const input = await screen.findByPlaceholderText(/ask about your balance/i);

      fireEvent.changeText(input, 'hi');
      fireEvent.press(screen.getByLabelText('Send'));
      await settle();

      expect(fynChat.send).not.toHaveBeenCalled();

      resolveHistory?.();
      await settle();
    });
  });

  describe('attaching a screenshot', () => {
    it('shows a preview chip after picking a screenshot, and can remove it', async () => {
      entitlements.mine.mockResolvedValue(granted());
      picker.mockResolvedValue({ uri: 'file:///cache/screenshot.png', name: 'screenshot.png', type: 'image/png' });
      renderScreen();
      await screen.findByPlaceholderText(/ask about your balance/i);

      fireEvent.press(screen.getByLabelText('Attach a screenshot'));
      await settle();

      expect(screen.getByText('screenshot.png')).toBeTruthy();

      fireEvent.press(screen.getByLabelText('Remove attachment'));

      expect(screen.queryByText('screenshot.png')).toBeNull();
    });

    it('sends the attached screenshot via sendScreenshot, not send', async () => {
      entitlements.mine.mockResolvedValue(granted());
      picker.mockResolvedValue({ uri: 'file:///cache/screenshot.png', name: 'screenshot.png', type: 'image/png' });
      fynChat.sendScreenshot.mockResolvedValue({
        conversationId: 'conv-1', reply: 'That looks like a Swiggy order.', messageId: 'msg-1',
      });
      renderScreen();
      await screen.findByPlaceholderText(/ask about your balance/i);

      fireEvent.press(screen.getByLabelText('Attach a screenshot'));
      await settle();
      const input = screen.getByPlaceholderText(/add a question about this screenshot/i);
      fireEvent.changeText(input, 'what is this?');
      fireEvent.press(screen.getByLabelText('Send'));
      await settle();

      expect(await screen.findByText('That looks like a Swiggy order.')).toBeTruthy();
      expect(fynChat.sendScreenshot).toHaveBeenCalledWith(
        { uri: 'file:///cache/screenshot.png', name: 'screenshot.png', type: 'image/png' },
        'what is this?', undefined);
      expect(fynChat.send).not.toHaveBeenCalled();
    });

    it('shows a size error from the picker and does not attach', async () => {
      entitlements.mine.mockResolvedValue(granted());
      picker.mockRejectedValue(new ScreenshotTooLargeError('Screenshots are limited to 8 MB.'));
      renderScreen();
      await screen.findByPlaceholderText(/ask about your balance/i);

      fireEvent.press(screen.getByLabelText('Attach a screenshot'));
      await settle();

      expect(await screen.findByText('Screenshots are limited to 8 MB.')).toBeTruthy();
      expect(screen.queryByLabelText('Remove attachment')).toBeNull();
    });
  });

  describe('rating a reply', () => {
    it('shows thumbs under an assistant reply, not the user\'s own message', async () => {
      entitlements.mine.mockResolvedValue(granted());
      fynChat.send.mockResolvedValue({ conversationId: 'conv-1', reply: 'reply', messageId: 'msg-1' });
      renderScreen();
      const input = await screen.findByPlaceholderText(/ask about your balance/i);
      fireEvent.changeText(input, 'hi');
      fireEvent.press(screen.getByLabelText('Send'));
      await screen.findByText('reply');

      expect(screen.getAllByLabelText('Helpful')).toHaveLength(1);
      expect(screen.getAllByLabelText('Not helpful')).toHaveLength(1);
    });

    it('tapping helpful calls setFeedback with HELPFUL and offers a store review', async () => {
      entitlements.mine.mockResolvedValue(granted());
      fynChat.send.mockResolvedValue({ conversationId: 'conv-1', reply: 'reply', messageId: 'msg-1' });
      fynChat.setFeedback.mockResolvedValue(undefined);
      storeReview.hasAction.mockResolvedValue(true);
      renderScreen();
      const input = await screen.findByPlaceholderText(/ask about your balance/i);
      fireEvent.changeText(input, 'hi');
      fireEvent.press(screen.getByLabelText('Send'));
      await screen.findByText('reply');

      fireEvent.press(screen.getByLabelText('Helpful'));
      await settle();

      expect(fynChat.setFeedback).toHaveBeenCalledWith('msg-1', 'HELPFUL');
      expect(storeReview.requestReview).toHaveBeenCalled();
    });

    it('does not offer a store review for a not-helpful rating', async () => {
      entitlements.mine.mockResolvedValue(granted());
      fynChat.send.mockResolvedValue({ conversationId: 'conv-1', reply: 'reply', messageId: 'msg-1' });
      fynChat.setFeedback.mockResolvedValue(undefined);
      storeReview.hasAction.mockResolvedValue(true);
      renderScreen();
      const input = await screen.findByPlaceholderText(/ask about your balance/i);
      fireEvent.changeText(input, 'hi');
      fireEvent.press(screen.getByLabelText('Send'));
      await screen.findByText('reply');

      fireEvent.press(screen.getByLabelText('Not helpful'));
      await settle();

      expect(fynChat.setFeedback).toHaveBeenCalledWith('msg-1', 'NOT_HELPFUL');
      expect(storeReview.requestReview).not.toHaveBeenCalled();
    });

    // Found in review: maybeAskToRateFynora() is fired with `void` from inside rate()'s own try
    // block, so a rejection from it is NOT caught by rate()'s catch -- it would be a genuine
    // unhandled promise rejection (which this app's Sentry integration auto-captures as a real
    // error) for a feature that's supposed to be entirely best-effort. A failing StoreReview call
    // must not surface as an error, and the rating itself must still have saved successfully.
    it('does not throw when the native store-review call itself fails', async () => {
      entitlements.mine.mockResolvedValue(granted());
      fynChat.send.mockResolvedValue({ conversationId: 'conv-1', reply: 'reply', messageId: 'msg-1' });
      fynChat.setFeedback.mockResolvedValue(undefined);
      storeReview.hasAction.mockRejectedValue(new Error('native module unavailable'));
      renderScreen();
      const input = await screen.findByPlaceholderText(/ask about your balance/i);
      fireEvent.changeText(input, 'hi');
      fireEvent.press(screen.getByLabelText('Send'));
      await screen.findByText('reply');

      fireEvent.press(screen.getByLabelText('Helpful'));
      await settle();

      expect(fynChat.setFeedback).toHaveBeenCalledWith('msg-1', 'HELPFUL');
      await waitFor(() => expect(screen.getByLabelText('Helpful')).not.toBeDisabled());
    });

    it('tapping the same thumb again clears the rating', async () => {
      entitlements.mine.mockResolvedValue(granted());
      fynChat.history.mockResolvedValue({
        conversationId: 'conv-1',
        turns: [{ id: 'a1', role: 'assistant', content: 'reply', feedback: 'HELPFUL' }],
      });
      fynChat.setFeedback.mockResolvedValue(undefined);
      renderScreen();
      await screen.findByText('reply');

      fireEvent.press(screen.getByLabelText('Helpful'));
      await settle();

      expect(fynChat.setFeedback).toHaveBeenCalledWith('a1', null);
    });

    it('disables both thumbs while a rating request is in flight, so a rapid tap on the other one is a no-op', async () => {
      entitlements.mine.mockResolvedValue(granted());
      fynChat.send.mockResolvedValue({ conversationId: 'conv-1', reply: 'reply', messageId: 'msg-1' });
      let resolveFeedback: (() => void) | undefined;
      fynChat.setFeedback.mockReturnValue(new Promise((resolve) => { resolveFeedback = () => resolve(undefined); }));
      renderScreen();
      const input = await screen.findByPlaceholderText(/ask about your balance/i);
      fireEvent.changeText(input, 'hi');
      fireEvent.press(screen.getByLabelText('Send'));
      await screen.findByText('reply');

      fireEvent.press(screen.getByLabelText('Helpful'));
      await settle();

      // Same request the disabled state is meant to prevent -- a rapid tap on the other thumb
      // while the first is still in flight -- attempted anyway, to prove the guard actually
      // blocks it rather than just visually discouraging it.
      fireEvent.press(screen.getByLabelText('Not helpful'));
      await settle();
      expect(fynChat.setFeedback).toHaveBeenCalledTimes(1);
      expect(fynChat.setFeedback).toHaveBeenCalledWith('msg-1', 'HELPFUL');

      resolveFeedback?.();
      await settle();
    });
  });
});
