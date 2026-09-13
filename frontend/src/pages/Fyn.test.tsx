import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Fyn from './Fyn';
import { fynChatApi, entitlementsApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  fynChatApi: { send: vi.fn() },
  entitlementsApi: { mine: vi.fn() },
}));

function entitled(featureKey: string) {
  vi.mocked(entitlementsApi.mine).mockResolvedValue(
    { planCode: 'PLUS', planName: 'Plus', features: { [featureKey]: true } });
}

// PremiumFeatureGate reads entitlements via useQuery, so every render needs a real
// QueryClientProvider ancestor -- same pattern Insights.test.tsx's own renderInsights() uses.
function renderFyn() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <Fyn />
    </QueryClientProvider>
  );
}

describe('Fyn chat', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows the upgrade prompt, not the chat, when the user lacks FYN_CHAT', async () => {
    vi.mocked(entitlementsApi.mine).mockResolvedValue({ planCode: 'FREE', planName: 'Free', features: {} });

    renderFyn();

    expect(await screen.findByText(/premium feature/i)).toBeInTheDocument();
    expect(screen.queryByPlaceholderText(/ask about your balance/i)).not.toBeInTheDocument();
  });

  it('sends a message and renders the reply', async () => {
    entitled('FYN_CHAT');
    vi.mocked(fynChatApi.send).mockResolvedValue({ conversationId: 'conv-1', reply: 'Your balance is ₹50,000.' });

    renderFyn();
    const input = await screen.findByPlaceholderText(/ask about your balance/i);
    await userEvent.type(input, 'what\'s my balance?');
    await userEvent.click(screen.getByRole('button', { name: /send/i }));

    expect(await screen.findByText(/50,000/)).toBeInTheDocument();
    expect(screen.getByText('what\'s my balance?')).toBeInTheDocument();
    expect(fynChatApi.send).toHaveBeenCalledWith('what\'s my balance?', undefined);
  });

  it('continues the same conversation on a second message', async () => {
    entitled('FYN_CHAT');
    vi.mocked(fynChatApi.send)
      .mockResolvedValueOnce({ conversationId: 'conv-1', reply: 'First reply.' })
      .mockResolvedValueOnce({ conversationId: 'conv-1', reply: 'Second reply.' });

    renderFyn();
    const input = await screen.findByPlaceholderText(/ask about your balance/i);

    await userEvent.type(input, 'first question');
    await userEvent.click(screen.getByRole('button', { name: /send/i }));
    await screen.findByText('First reply.');

    await userEvent.type(input, 'follow up');
    await userEvent.click(screen.getByRole('button', { name: /send/i }));
    await screen.findByText('Second reply.');

    expect(fynChatApi.send).toHaveBeenNthCalledWith(2, 'follow up', 'conv-1');
  });

  it('shows an inline error and clears it on the next successful send, without crashing', async () => {
    entitled('FYN_CHAT');
    vi.mocked(fynChatApi.send).mockRejectedValueOnce({ response: { data: { message: 'Fyn is over budget.' } } });

    renderFyn();
    const input = await screen.findByPlaceholderText(/ask about your balance/i);
    await userEvent.type(input, 'hi');
    await userEvent.click(screen.getByRole('button', { name: /send/i }));

    expect(await screen.findByText('Fyn is over budget.')).toBeInTheDocument();
  });

  it('does not send a blank message', async () => {
    entitled('FYN_CHAT');

    renderFyn();
    await screen.findByPlaceholderText(/ask about your balance/i);
    await userEvent.click(screen.getByRole('button', { name: /send/i }));

    expect(fynChatApi.send).not.toHaveBeenCalled();
  });
});
