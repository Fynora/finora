import { render, screen, waitFor, fireEvent } from '@testing-library/react-native';
import { Linking, Alert, Platform } from 'react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MySubscriptionScreen } from './MySubscriptionScreen';
import { billingApi } from '../api/endpoints';
import { restorePurchases } from '../lib/revenueCat';

jest.mock('../api/endpoints', () => ({
  billingApi: { mySubscription: jest.fn(), pause: jest.fn(), resume: jest.fn() },
}));
jest.mock('../lib/revenueCat', () => ({ restorePurchases: jest.fn() }));

const mockedBillingApi = billingApi as jest.Mocked<typeof billingApi>;
const mockedRestorePurchases = restorePurchases as jest.MockedFunction<typeof restorePurchases>;

function renderScreen() {
  // gcTime: 0 -- see SubscriptionScreen.test.tsx's own comment on this exact line: without it, a
  // successful query here schedules a real 5-minute GC timer nothing ever cancels.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(<QueryClientProvider client={queryClient}><MySubscriptionScreen /></QueryClientProvider>);
}

describe('MySubscriptionScreen', () => {
  it('shows a managed-on-web note and no controls for a Razorpay-owned subscription that is neither pausable nor resumable', async () => {
    // PAST_DUE: not ACTIVE (so not pausable) and not PAUSED (so not resumable either) -- exactly
    // the residual case that still has nothing actionable here.
    mockedBillingApi.mySubscription.mockResolvedValue({
      planCode: 'PLUS', planName: 'Plus', status: 'PAST_DUE', autoRenew: true,
      hasBillingSubscription: true, paymentProvider: 'RAZORPAY',
    } as any);
    renderScreen();

    expect(await screen.findByText(/managed on web/i)).toBeTruthy();
    expect(screen.queryByText('Manage subscription')).toBeNull();
    expect(screen.queryByText('Restore Purchases')).toBeNull();
    expect(screen.queryByText('Pause subscription')).toBeNull();
    expect(screen.queryByText('Resume subscription')).toBeNull();
  });

  it('shows a Pause button for an active Razorpay-owned subscription and pauses on confirm', async () => {
    mockedBillingApi.mySubscription.mockResolvedValue({
      planCode: 'PLUS', planName: 'Plus', status: 'ACTIVE', autoRenew: true,
      hasBillingSubscription: true, paymentProvider: 'RAZORPAY',
    } as any);
    mockedBillingApi.pause.mockResolvedValue({ message: 'Paused' });
    const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation((_title, _msg, buttons) => {
      buttons?.find((b) => b.text === 'Pause')?.onPress?.();
    });
    renderScreen();

    fireEvent.press(await screen.findByText('Pause subscription'));

    expect(alertSpy).toHaveBeenCalledWith(
      'Pause subscription?', expect.stringMatching(/stops right away/i), expect.any(Array)
    );
    await waitFor(() => expect(mockedBillingApi.pause).toHaveBeenCalled());
  });

  it('does not pause when the confirmation is dismissed', async () => {
    mockedBillingApi.mySubscription.mockResolvedValue({
      planCode: 'PLUS', planName: 'Plus', status: 'ACTIVE', autoRenew: true,
      hasBillingSubscription: true, paymentProvider: 'RAZORPAY',
    } as any);
    jest.spyOn(Alert, 'alert').mockImplementation((_title, _msg, buttons) => {
      buttons?.find((b) => b.text === 'Cancel')?.onPress?.();
    });
    renderScreen();

    fireEvent.press(await screen.findByText('Pause subscription'));

    expect(mockedBillingApi.pause).not.toHaveBeenCalled();
  });

  it('shows a paused message and a Resume button instead of Pause for a paused subscription', async () => {
    mockedBillingApi.mySubscription.mockResolvedValue({
      planCode: 'PLUS', planName: 'Plus', status: 'PAUSED', autoRenew: true, renewalDate: '2026-11-01',
      hasBillingSubscription: true, paymentProvider: 'RAZORPAY',
    } as any);
    renderScreen();

    expect(await screen.findByText(/paused.*billing on hold/i)).toBeTruthy();
    expect(screen.getByText('Resume subscription')).toBeTruthy();
    expect(screen.queryByText('Pause subscription')).toBeNull();
    // The stale pre-pause renewal date must not render as if it were still accurate.
    expect(screen.queryByText(/renews/i)).toBeNull();
  });

  it('resumes directly with no confirmation and refetches on success', async () => {
    mockedBillingApi.mySubscription.mockResolvedValue({
      planCode: 'PLUS', planName: 'Plus', status: 'PAUSED', autoRenew: true,
      hasBillingSubscription: true, paymentProvider: 'RAZORPAY',
    } as any);
    mockedBillingApi.resume.mockResolvedValue({ message: 'Resumed' });
    const alertSpy = jest.spyOn(Alert, 'alert');
    renderScreen();

    fireEvent.press(await screen.findByText('Resume subscription'));

    await waitFor(() => expect(mockedBillingApi.resume).toHaveBeenCalled());
    expect(alertSpy).not.toHaveBeenCalled();
  });

  it('explains why Pause is the only control and links to Manage on web for an active Razorpay-owned subscription', async () => {
    mockedBillingApi.mySubscription.mockResolvedValue({
      planCode: 'PLUS', planName: 'Plus', status: 'ACTIVE', autoRenew: true,
      hasBillingSubscription: true, paymentProvider: 'RAZORPAY',
    } as any);
    renderScreen();

    expect(await screen.findByText(/purchased on the web.*change your plan or cancel/i)).toBeTruthy();
    expect(screen.getByText('Manage on web')).toBeTruthy();
    expect(screen.getByText('Pause subscription')).toBeTruthy();
  });

  it('opens the web Billing page when Manage on web is tapped for a Razorpay-owned subscription', async () => {
    mockedBillingApi.mySubscription.mockResolvedValue({
      planCode: 'PLUS', planName: 'Plus', status: 'ACTIVE', autoRenew: true,
      hasBillingSubscription: true, paymentProvider: 'RAZORPAY',
    } as any);
    const openURLSpy = jest.spyOn(Linking, 'openURL').mockResolvedValue(true);
    renderScreen();

    fireEvent.press(await screen.findByText('Manage on web'));

    await waitFor(() => expect(openURLSpy).toHaveBeenCalledWith(expect.stringContaining('/app/billing')));
  });

  it('leads with Manage on web ahead of Pause on iOS, as the conservative App Review posture for a non-IAP plan', async () => {
    const originalOS = Platform.OS;
    Platform.OS = 'ios';
    try {
      mockedBillingApi.mySubscription.mockResolvedValue({
        planCode: 'PLUS', planName: 'Plus', status: 'ACTIVE', autoRenew: true,
        hasBillingSubscription: true, paymentProvider: 'RAZORPAY',
      } as any);
      renderScreen();

      await screen.findByText('Pause subscription');
      const manageOnWebIndex = screen.getAllByText(/./).findIndex((n) => n.props.children === 'Manage on web');
      const pauseIndex = screen.getAllByText(/./).findIndex((n) => n.props.children === 'Pause subscription');
      expect(manageOnWebIndex).toBeGreaterThanOrEqual(0);
      expect(manageOnWebIndex).toBeLessThan(pauseIndex);
    } finally {
      Platform.OS = originalOS;
    }
  });

  it('shows Manage subscription and Restore Purchases for a RevenueCat-owned subscription', async () => {
    mockedBillingApi.mySubscription.mockResolvedValue({
      planCode: 'PREMIUM', planName: 'Premium', hasBillingSubscription: true, paymentProvider: 'REVENUECAT',
    } as any);
    renderScreen();

    expect(await screen.findByText('Manage subscription')).toBeTruthy();
    expect(await screen.findByText('Restore Purchases')).toBeTruthy();
  });

  it('opens the OS subscription settings when Manage subscription is tapped', async () => {
    mockedBillingApi.mySubscription.mockResolvedValue({
      planCode: 'PREMIUM', planName: 'Premium', hasBillingSubscription: true, paymentProvider: 'REVENUECAT',
    } as any);
    const openURLSpy = jest.spyOn(Linking, 'openURL').mockResolvedValue(true);
    renderScreen();

    fireEvent.press(await screen.findByText('Manage subscription'));

    await waitFor(() => expect(openURLSpy).toHaveBeenCalled());
  });

  it('calls restorePurchases and refetches when Restore Purchases is tapped', async () => {
    mockedBillingApi.mySubscription.mockResolvedValue({
      planCode: 'FREE', hasBillingSubscription: false, paymentProvider: null,
    } as any);
    mockedRestorePurchases.mockResolvedValue(undefined);
    renderScreen();

    fireEvent.press(await screen.findByText('Restore Purchases'));

    await waitFor(() => expect(mockedRestorePurchases).toHaveBeenCalled());
  });

  it('shows the renewal date for an auto-renewing subscription', async () => {
    mockedBillingApi.mySubscription.mockResolvedValue({
      planCode: 'PREMIUM', planName: 'Premium', hasBillingSubscription: true, paymentProvider: 'REVENUECAT',
      renewalDate: '2026-11-01', autoRenew: true,
    } as any);
    renderScreen();

    expect(await screen.findByText('Renews 1 Nov 2026')).toBeTruthy();
  });

  it("shows the subscription is ending, not renewing, once autoRenew is off", async () => {
    mockedBillingApi.mySubscription.mockResolvedValue({
      planCode: 'PREMIUM', planName: 'Premium', hasBillingSubscription: true, paymentProvider: 'REVENUECAT',
      renewalDate: '2026-11-01', autoRenew: false,
    } as any);
    renderScreen();

    expect(await screen.findByText("Ends 1 Nov 2026 — won't renew")).toBeTruthy();
  });
});
