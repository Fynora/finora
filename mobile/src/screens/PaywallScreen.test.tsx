import { render, screen, waitFor, fireEvent } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { PaywallScreen } from './PaywallScreen';
import { billingApi } from '../api/endpoints';
import { purchasePlan } from '../lib/revenueCat';

// Premium is hidden in the app (lib/premiumVisibility.ts). These tests default it to visible so the
// Premium paths that still exist stay tested; individual tests turn it off.
const mockPremium = { visible: true };
jest.mock('../lib/premiumVisibility', () => ({
  get PREMIUM_PLAN_VISIBLE() {
    return mockPremium.visible;
  },
}));

jest.mock('../api/endpoints', () => ({
  billingApi: { mySubscription: jest.fn(), history: jest.fn(), downloadInvoice: jest.fn() },
}));
jest.mock('../lib/revenueCat', () => ({ purchasePlan: jest.fn() }));

const mockedBillingApi = billingApi as jest.Mocked<typeof billingApi>;
const mockedPurchasePlan = purchasePlan as jest.MockedFunction<typeof purchasePlan>;

function renderScreen() {
  // gcTime: 0 -- see SubscriptionScreen.test.tsx's own comment on this exact line: without it, a
  // successful query here schedules a real 5-minute GC timer nothing ever cancels.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(<QueryClientProvider client={queryClient}><PaywallScreen /></QueryClientProvider>);
}

describe('PaywallScreen', () => {
  beforeEach(() => {
    mockedBillingApi.mySubscription.mockResolvedValue({
      planCode: 'FREE', hasBillingSubscription: false,
    } as any);
    // Someone who never paid -- the Paywall must look exactly as it did before the history section.
    mockedBillingApi.history.mockReset().mockResolvedValue([]);
  });

  it('shows past payments and invoices to a lapsed payer, below the plans', async () => {
    mockedBillingApi.history.mockResolvedValue([
      { id: 'abcdef12-0000-0000-0000-000000000000', amount: 399, currency: 'INR', provider: 'RAZORPAY',
        status: 'SUCCESS', createdAt: '2026-08-01T10:00:00Z' },
    ]);
    renderScreen();

    expect(await screen.findByText('Billing history')).toBeTruthy();
    expect(screen.getByText('₹399')).toBeTruthy();
    expect(screen.getByText('Invoice')).toBeTruthy();
    expect(screen.getAllByText('Subscribe')).toHaveLength(2);
  });

  it('offers only Plus while Premium is hidden, and subscribing buys Plus', async () => {
    mockPremium.visible = false;
    try {
      mockedPurchasePlan.mockRejectedValue(new Error('stop after the call'));
      renderScreen();

      expect(await screen.findByText('Plus')).toBeTruthy();
      expect(screen.queryByText('Premium')).toBeNull();
      expect(screen.getAllByText('Subscribe')).toHaveLength(1);

      fireEvent.press(screen.getByText('Subscribe'));
      await waitFor(() => expect(mockedPurchasePlan).toHaveBeenCalled());
      expect(mockedPurchasePlan.mock.calls[0][0]).toBe('PLUS');
    } finally {
      mockPremium.visible = true;
    }
  });

  it('adds no billing history to the Paywall of someone who never paid', async () => {
    renderScreen();

    await screen.findAllByText('Subscribe');
    await waitFor(() => expect(mockedBillingApi.history).toHaveBeenCalled());
    expect(screen.queryByText('Billing history')).toBeNull();
    expect(screen.queryByText(/No billing history/)).toBeNull();
  });

  it('shows both plans and purchases the tapped one', async () => {
    mockedPurchasePlan.mockResolvedValue(undefined);
    renderScreen();

    // Two "Subscribe" buttons render (one per plan) -- findByText is ambiguous here; the first
    // one is Plus, matching the PLANS array order in PaywallScreen.tsx.
    fireEvent.press((await screen.findAllByText('Subscribe'))[0]);

    await waitFor(() => expect(mockedPurchasePlan).toHaveBeenCalledWith('PLUS', 'MONTHLY'));
  });

  it('shows an error if the purchase fails', async () => {
    mockedPurchasePlan.mockRejectedValue(new Error('Purchase cancelled'));
    renderScreen();

    fireEvent.press((await screen.findAllByText('Subscribe'))[0]);

    expect(await screen.findByText('Purchase cancelled')).toBeTruthy();
  });

  // Design spec §6.1 step 5: activation is only ever trusted from the backend's verified
  // RevenueCat webhook, never the client-side purchase call resolving -- so a real race exists
  // between purchasePlan() resolving and the webhook actually landing. Mirrors web's own
  // useActivationPoll (Billing.tsx) rather than a single invalidateQueries, which could otherwise
  // leave the Paywall showing the OLD (still-Free) plan with no explanation for as long as the
  // query's staleTime keeps that stale read looking "fresh".
  it('shows an activating message and polls until the purchase actually lands', async () => {
    mockedBillingApi.mySubscription
      .mockResolvedValueOnce({ planCode: 'FREE', hasBillingSubscription: false } as any) // initial load
      .mockResolvedValueOnce({ planCode: 'FREE', hasBillingSubscription: false } as any) // first poll: webhook hasn't landed yet
      .mockResolvedValue({ planCode: 'PLUS', hasBillingSubscription: true } as any); // second poll onward: activated
    mockedPurchasePlan.mockResolvedValue(undefined);
    renderScreen();

    fireEvent.press((await screen.findAllByText('Subscribe'))[0]);

    expect(await screen.findByText(/Activating your Plus plan/i)).toBeTruthy();
    // Real (not faked) timers here -- pollForActivation's setTimeout(2000) is production code, and
    // jest.useFakeTimers() would also fake the timers waitFor's own polling relies on and hang it
    // (see AppLockGate.test.tsx's identical note). Needs 2 real poll cycles (FREE, FREE, then PLUS),
    // nominally 4000ms.
    //
    // not.toBeOnTheScreen(), deliberately not toBeNull(): every waitFor attempt that fails builds
    // the assertion message eagerly, and toBeNull()'s message pretty-prints the whole React fiber
    // graph behind the element (the "Received: {"_fiber": ...}" dump). Measured at 1.3-3s of
    // synchronous work per failed attempt on an idle-ish machine and 4.3-4.8s under CPU load --
    // time in which the real setTimeout(2000) above cannot fire, so the timer was measured landing
    // 3.8-13.9s late and the test ran out of budget no matter how large the timeout was made.
    // not.toBeOnTheScreen()'s message formats only the one host element (measured ~1ms).
    await waitFor(() => expect(screen.queryByText(/Activating/i)).not.toBeOnTheScreen(), { timeout: 20000 });
  }, 25000);
});
