import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { Linking, Platform } from 'react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StoreSubscriptionDeletionWarning } from './StoreSubscriptionDeletionWarning';
import { billingApi, type MySubscription } from '../../api/endpoints';
import { ThemeProvider } from '../../theme';
import { never } from '../../test/loadingStates';

jest.mock('../../api/endpoints', () => ({ billingApi: { mySubscription: jest.fn() } }));

const mySubscription = billingApi.mySubscription as jest.Mock;

function subscription(overrides: Partial<MySubscription>): MySubscription {
  return {
    planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY', status: 'ACTIVE', renewalDate: '2026-11-30',
    autoRenew: true, hasBillingSubscription: true, paymentProvider: 'REVENUECAT', storePlatform: 'IOS',
    ...overrides,
  };
}

function renderWarning() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ThemeProvider><StoreSubscriptionDeletionWarning /></ThemeProvider>
    </QueryClientProvider>
  );
}

const originalOS = Platform.OS;
afterEach(() => {
  Object.defineProperty(Platform, 'OS', { value: originalOS, configurable: true });
  mySubscription.mockReset();
  jest.restoreAllMocks();
});

function onPlatform(os: 'ios' | 'android') {
  Object.defineProperty(Platform, 'OS', { value: os, configurable: true });
}

describe('StoreSubscriptionDeletionWarning', () => {
  it('warns about an App Store subscription that is still renewing, and opens Apple subscriptions on iPhone', async () => {
    onPlatform('ios');
    mySubscription.mockResolvedValue(subscription({ storePlatform: 'IOS' }));
    const openURL = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
    renderWarning();

    expect(await screen.findByText('Cancel your Plus subscription first')).toBeTruthy();
    expect(screen.getByText(/You subscribed through the App Store/)).toBeTruthy();
    expect(screen.getByText(/Apple will keep charging you/)).toBeTruthy();

    fireEvent.press(screen.getByText('Open App Store subscriptions'));
    await waitFor(() => expect(openURL).toHaveBeenCalledWith('itms-apps://apps.apple.com/account/subscriptions'));
  });

  it('names Google Play for an Android subscription and opens Play subscriptions on Android', async () => {
    onPlatform('android');
    mySubscription.mockResolvedValue(subscription({ storePlatform: 'ANDROID' }));
    const openURL = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
    renderWarning();

    expect(await screen.findByText(/You subscribed through Google Play/)).toBeTruthy();
    fireEvent.press(screen.getByText('Open Google Play subscriptions'));
    await waitFor(() => expect(openURL).toHaveBeenCalledWith('https://play.google.com/store/account/subscriptions'));
  });

  /** An iPhone cannot open Google Play's page, so the warning stays but the button does not. */
  it('offers no store button on the other platform', async () => {
    onPlatform('ios');
    mySubscription.mockResolvedValue(subscription({ storePlatform: 'ANDROID' }));
    renderWarning();

    expect(await screen.findByText(/You subscribed through Google Play/)).toBeTruthy();
    expect(screen.queryByText(/Open .* subscriptions/)).toBeNull();
  });

  it('says nothing when the store subscription is already set not to renew', async () => {
    mySubscription.mockResolvedValue(subscription({ autoRenew: false }));
    renderWarning();

    await waitFor(() => expect(mySubscription).toHaveBeenCalled());
    expect(screen.queryByTestId('store-subscription-deletion-warning')).toBeNull();
  });

  /** Account deletion cancels a web (Razorpay) subscription itself, so there is nothing to warn about. */
  it('says nothing for a web subscription or a free account', async () => {
    mySubscription.mockResolvedValue(subscription({ paymentProvider: 'RAZORPAY', storePlatform: null }));
    renderWarning();

    await waitFor(() => expect(mySubscription).toHaveBeenCalled());
    expect(screen.queryByTestId('store-subscription-deletion-warning')).toBeNull();
    expect(screen.queryByText(/App Store or Google Play/)).toBeNull();
  });

  it('shows nothing while the subscription is still loading', () => {
    mySubscription.mockReturnValue(never());
    renderWarning();

    expect(screen.queryByText(/App Store|Google Play/)).toBeNull();
  });

  it('falls back to a general reminder when the subscription cannot be read', async () => {
    mySubscription.mockRejectedValue(new Error('network down'));
    renderWarning();

    expect(await screen.findByText(/If you subscribed to Plus through the App Store or Google Play/)).toBeTruthy();
  });
});
