import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { FynWidget } from './FynWidget';
import { fynChatApi, entitlementsApi } from '../api/endpoints';
import { AuthProvider } from '../context/AuthContext';
import { safeStorage } from '../lib/safeStorage';

vi.mock('../api/endpoints', () => ({
  fynChatApi: { send: vi.fn() },
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
    vi.mocked(fynChatApi.send).mockResolvedValue({ conversationId: 'conv-1', reply: 'Your balance is ₹50,000.' });
    renderWidget();
    await openDrawer();

    await userEvent.click(await screen.findByRole('button', { name: "What's my balance?" }));

    expect(await screen.findByText(/50,000/)).toBeInTheDocument();
    expect(screen.getByText("What's my balance?")).toBeInTheDocument();
    expect(fynChatApi.send).toHaveBeenCalledWith("What's my balance?", undefined);
  });

  it('hides the suggestions once a conversation has started', async () => {
    entitled('FYN_CHAT');
    vi.mocked(fynChatApi.send).mockResolvedValue({ conversationId: 'conv-1', reply: 'Your balance is ₹50,000.' });
    renderWidget();
    await openDrawer();

    await userEvent.click(await screen.findByRole('button', { name: "What's my balance?" }));
    await screen.findByText(/50,000/);

    expect(screen.queryByRole('button', { name: 'How are my budgets doing?' })).not.toBeInTheDocument();
  });

  it('sends a message and renders the reply', async () => {
    entitled('FYN_CHAT');
    vi.mocked(fynChatApi.send).mockResolvedValue({ conversationId: 'conv-1', reply: 'Your balance is ₹50,000.' });
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
      .mockResolvedValueOnce({ conversationId: 'conv-1', reply: 'First reply.' })
      .mockResolvedValueOnce({ conversationId: 'conv-1', reply: 'Second reply.' });
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
});
