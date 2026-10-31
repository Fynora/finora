import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StoreSubscriptionDeletionWarning } from './StoreSubscriptionDeletionWarning';
import { billingApi, type MySubscription } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({ billingApi: { mySubscription: vi.fn() } }));

function subscription(overrides: Partial<MySubscription>): MySubscription {
  return {
    planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY', status: 'ACTIVE', renewalDate: '2026-11-30',
    autoRenew: true, hasBillingSubscription: true, pendingChange: null, pendingOrder: null,
    paymentProvider: 'REVENUECAT', paymentMethod: null, autoRenewResumable: false, storePlatform: 'IOS',
    ...overrides,
  };
}

function renderWarning() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StoreSubscriptionDeletionWarning />
    </QueryClientProvider>
  );
}

describe('StoreSubscriptionDeletionWarning', () => {
  // Braces, not an expression body: mockReset() returns the mock, and a function returned from
  // beforeEach is run by vitest as that test's cleanup -- calling mySubscription once more after
  // the test, which in the rejecting case below fails the test with that rejection.
  beforeEach(() => {
    vi.mocked(billingApi.mySubscription).mockReset();
  });

  it('warns about an App Store subscription that is still renewing, with a link to Apple', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({ storePlatform: 'IOS' }));
    renderWarning();

    expect(await screen.findByText('Cancel your Plus subscription first')).toBeInTheDocument();
    expect(screen.getByText(/You subscribed through the App Store/)).toBeInTheDocument();
    expect(screen.getByText(/Apple will keep charging you/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Manage App Store subscriptions/ }))
      .toHaveAttribute('href', 'https://apps.apple.com/account/subscriptions');
  });

  it('names Google Play for an Android subscription, with a link to Google', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({ storePlatform: 'ANDROID' }));
    renderWarning();

    expect(await screen.findByText(/You subscribed through Google Play/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Manage Google Play subscriptions/ }))
      .toHaveAttribute('href', 'https://play.google.com/store/account/subscriptions');
  });

  it('says nothing when the store subscription is already set not to renew', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({ autoRenew: false }));
    const { container } = renderWarning();

    await waitFor(() => expect(billingApi.mySubscription).toHaveBeenCalled());
    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });

  /** Account deletion cancels a web (Razorpay) subscription itself, so there is nothing to warn about. */
  it('says nothing for a web subscription', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({ paymentProvider: 'RAZORPAY', storePlatform: null }));
    const { container } = renderWarning();

    await waitFor(() => expect(billingApi.mySubscription).toHaveBeenCalled());
    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });

  it('falls back to a general reminder when the subscription cannot be read', async () => {
    vi.mocked(billingApi.mySubscription).mockRejectedValue(new Error('network down'));
    renderWarning();

    expect(await screen.findByText(/If you subscribed to Plus through the App Store or Google Play/)).toBeInTheDocument();
  });
});
