import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { billingApi, type BillingOffer, type MySubscription } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { resetBillingOffersCache } from '../pages/landing/offers';
import { OFFER_POPUP_DISMISSED_PREFIX, OfferPopup } from './OfferPopup';

vi.mock('../api/endpoints', () => ({
  billingApi: { offers: vi.fn(), mySubscription: vi.fn() },
}));
vi.mock('../context/AuthContext', () => ({ useAuth: vi.fn() }));

// Synthetic offers, shaped like GET /billing/offers.
const monthly: BillingOffer = {
  planCode: 'PLUS', billingCycle: 'MONTHLY', code: 'DIWALI2026', label: 'Diwali offer',
  firstPeriodPrice: 125, regularPrice: 249, endsAt: '2099-11-07T18:30:00Z',
};
const yearly: BillingOffer = { ...monthly, billingCycle: 'YEARLY', firstPeriodPrice: 999, regularPrice: 1999 };

function subscription(overrides: Partial<MySubscription> = {}): MySubscription {
  return {
    planCode: 'FREE', planName: 'Free', billingCycle: null, status: 'ACTIVE', renewalDate: null,
    autoRenew: true, hasBillingSubscription: false, pendingChange: null, pendingOrder: null,
    paymentProvider: null, paymentMethod: null, autoRenewResumable: false, ...overrides,
  };
}

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="*" element={<OfferPopup />} />
        </Routes>
        <Routes>
          {/* The real Billing route (App.tsx). */}
          <Route path="/app/billing" element={<p>Billing page</p>} />
          <Route path="*" element={null} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('OfferPopup', () => {
  beforeEach(() => {
    localStorage.clear();
    resetBillingOffersCache();
    vi.mocked(billingApi.offers).mockReset().mockResolvedValue([monthly, yearly]);
    vi.mocked(billingApi.mySubscription).mockReset().mockResolvedValue(subscription());
    vi.mocked(useAuth).mockReturnValue({ onboardingCompleted: true } as ReturnType<typeof useAuth>);
  });
  afterEach(() => resetBillingOffersCache());

  it('shows a Free user the offer, with both prices and the last day', async () => {
    renderAt('/app');

    const dialog = await screen.findByRole('dialog', { name: 'Diwali offer' });
    expect(dialog).toHaveTextContent(
      'Plus at ₹125 for your first month (then ₹249/month), or ₹999 for your first year. Ends 7 Nov.');
  });

  it('closes on the cross and stays closed for this offer', async () => {
    const user = userEvent.setup();
    const first = renderAt('/app');
    await screen.findByRole('dialog');

    await user.click(screen.getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(localStorage.getItem(OFFER_POPUP_DISMISSED_PREFIX + 'DIWALI2026')).not.toBeNull();

    first.unmount();
    renderAt('/app');
    await waitFor(() => expect(billingApi.mySubscription).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('closes on Escape', async () => {
    const user = userEvent.setup();
    renderAt('/app');
    await screen.findByRole('dialog');

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('shows again for a different, later offer', async () => {
    localStorage.setItem(OFFER_POPUP_DISMISSED_PREFIX + 'DIWALI2026', '2026-11-01T00:00:00Z');
    vi.mocked(billingApi.offers).mockResolvedValue([{ ...monthly, code: 'HOLI2027', label: 'Holi offer' }]);
    renderAt('/app');

    expect(await screen.findByRole('dialog', { name: 'Holi offer' })).toBeInTheDocument();
  });

  it('Get Plus goes to Billing and closes it', async () => {
    const user = userEvent.setup();
    renderAt('/app');
    await screen.findByRole('dialog');

    await user.click(screen.getByRole('button', { name: 'Get Plus' }));
    expect(await screen.findByText('Billing page')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it.each([
    ['a Razorpay subscriber', subscription({ planCode: 'PLUS', hasBillingSubscription: true })],
    ['a complimentary Plus grant', subscription({ planCode: 'PLUS', hasBillingSubscription: false })],
  ])('is never shown to %s', async (_who, sub) => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(sub);
    renderAt('/app');

    await waitFor(() => expect(billingApi.mySubscription).toHaveBeenCalled());
    await waitFor(() => expect(billingApi.offers).toHaveBeenCalled());
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it.each(['/app/billing', '/app/import', '/app/transactions'])(
    'is not shown off the dashboard, mid-task (%s)', async (path) => {
      renderAt(path);

      await waitFor(() => expect(billingApi.offers).toHaveBeenCalled());
      await waitFor(() => expect(billingApi.mySubscription).toHaveBeenCalled());
      expect(screen.queryByRole('dialog')).toBeNull();
    });

  it('waits until onboarding is finished', async () => {
    vi.mocked(useAuth).mockReturnValue({ onboardingCompleted: false } as ReturnType<typeof useAuth>);
    renderAt('/app');

    await waitFor(() => expect(billingApi.offers).toHaveBeenCalled());
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('shows nothing when no offer is live', async () => {
    vi.mocked(billingApi.offers).mockResolvedValue([]);
    renderAt('/app');

    await waitFor(() => expect(billingApi.mySubscription).toHaveBeenCalled());
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
