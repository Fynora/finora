import { fireEvent, render, screen } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { FynScreen } from './FynScreen';
import { fynChatApi, entitlementsApi, type EntitlementsDto } from '../api/endpoints';
import { ThemeProvider } from '../theme';

jest.mock('../api/endpoints', () => ({
  fynChatApi: { send: jest.fn() },
  entitlementsApi: { mine: jest.fn() },
}));

const mockGoBack = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ goBack: mockGoBack }),
}));

const fynChat = fynChatApi as jest.Mocked<typeof fynChatApi>;
const entitlements = entitlementsApi as jest.Mocked<typeof entitlementsApi>;

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
    fynChat.send.mockResolvedValue({ conversationId: 'conv-1', reply: 'Your balance is ₹50,000.' });

    renderScreen();
    fireEvent.press(await screen.findByLabelText("What's my balance?"));

    expect(await screen.findByText(/50,000/)).toBeTruthy();
    expect(screen.getByText("What's my balance?")).toBeTruthy();
    expect(fynChat.send).toHaveBeenCalledWith("What's my balance?", undefined);
  });

  it('hides the suggestions once a conversation has started', async () => {
    entitlements.mine.mockResolvedValue(granted());
    fynChat.send.mockResolvedValue({ conversationId: 'conv-1', reply: 'Your balance is ₹50,000.' });

    renderScreen();
    fireEvent.press(await screen.findByLabelText("What's my balance?"));
    await screen.findByText(/50,000/);

    expect(screen.queryByLabelText('How are my budgets doing?')).toBeNull();
  });

  it('sends a message and renders the reply', async () => {
    entitlements.mine.mockResolvedValue(granted());
    fynChat.send.mockResolvedValue({ conversationId: 'conv-1', reply: 'Your balance is ₹50,000.' });

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
      .mockResolvedValueOnce({ conversationId: 'conv-1', reply: 'First reply.' })
      .mockResolvedValueOnce({ conversationId: 'conv-1', reply: 'Second reply.' });

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
});
