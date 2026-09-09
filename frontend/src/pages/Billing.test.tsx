import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Billing from './Billing';
import { INTENDED_BILLING_CYCLE_KEY } from './landing/plans';
import { billingApi, userApi, entitlementsApi, referralsApi, accountsApi, goalsApi, budgetsApi, analyticsApi, usageApi } from '../api/endpoints';
import { openRazorpayCheckout } from '../lib/razorpayCheckout';
import type { BillingHistoryEntry, MySubscription, UserSettings } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  billingApi: {
    history: vi.fn(), mySubscription: vi.fn(), checkout: vi.fn(), cancel: vi.fn(),
    changePlan: vi.fn(), cancelPendingOrder: vi.fn(), pause: vi.fn(), resume: vi.fn(),
    undoCancellation: vi.fn(), invoicePdf: vi.fn(),
  },
  userApi: { get: vi.fn() },
  entitlementsApi: { mine: vi.fn() },
  referralsApi: { mine: vi.fn() },
  accountsApi: { list: vi.fn() },
  goalsApi: { list: vi.fn() },
  budgetsApi: { list: vi.fn() },
  analyticsApi: { importStatistics: vi.fn() },
  usageApi: { viewCount: vi.fn() },
}));
vi.mock('../lib/razorpayCheckout', () => ({
  openRazorpayCheckout: vi.fn(),
}));
vi.mock('../lib/download', () => ({
  downloadBlob: vi.fn(),
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Billing />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function subscription(overrides: Partial<MySubscription> = {}): MySubscription {
  return {
    planCode: 'FREE', planName: 'Free', billingCycle: null, status: 'ACTIVE',
    renewalDate: null, autoRenew: true, hasBillingSubscription: false, pendingChange: null,
    pendingOrder: null, paymentProvider: null, paymentMethod: null, autoRenewResumable: false,
    ...overrides,
  };
}

function entry(overrides: Partial<BillingHistoryEntry> = {}): BillingHistoryEntry {
  return {
    id: 'payment-1', amount: 499, currency: 'INR', provider: 'RAZORPAY', status: 'SUCCESS',
    createdAt: '2026-08-20T10:00:00Z', ...overrides,
  };
}

function userSettings(overrides: Partial<UserSettings> = {}): UserSettings {
  return {
    email: 'ada@example.com', fullName: 'Ada Lovelace', lowBalanceThreshold: 0, theme: 'system',
    timezone: 'UTC', phoneNumber: '+919876543210', phoneVerified: true, // synthetic-ok
    createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
    onboardingCompleted: true,
    ...overrides,
  };
}

describe('Billing', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.mocked(billingApi.history).mockReset().mockResolvedValue([]);
    vi.mocked(billingApi.mySubscription).mockReset();
    vi.mocked(billingApi.checkout).mockReset();
    vi.mocked(billingApi.cancel).mockReset();
    vi.mocked(billingApi.changePlan).mockReset();
    vi.mocked(billingApi.cancelPendingOrder).mockReset();
    vi.mocked(billingApi.pause).mockReset();
    vi.mocked(billingApi.resume).mockReset();
    vi.mocked(billingApi.undoCancellation).mockReset();
    vi.mocked(billingApi.invoicePdf).mockReset();
    vi.mocked(openRazorpayCheckout).mockReset();
    vi.mocked(userApi.get).mockReset().mockResolvedValue(userSettings());
    vi.mocked(entitlementsApi.mine).mockReset().mockResolvedValue({
      planCode: 'FREE', planName: 'Free',
      features: { BASIC_DASHBOARD: true, ADVANCED_REPORTS: false, EXTENDED_HISTORY: false, UNLIMITED_ACCOUNTS: false, GMAIL_SYNC: false, INVESTMENT_INSIGHTS: false, FINO_AI: false, PRIORITY_SUPPORT: false },
    });
    vi.mocked(referralsApi.mine).mockReset().mockResolvedValue({ code: 'ADA123', referralCount: 0 });
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(goalsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(analyticsApi.importStatistics).mockReset().mockResolvedValue({
      totalStatements: 0, totalTransactionsImported: 0, totalTransactionsSkipped: 0, lastImportedAt: null,
    });
    vi.mocked(usageApi.viewCount).mockReset().mockResolvedValue({ viewCount: 0 });
  });

  it('shows the current Free plan and a disabled auto-renewal toggle', async () => {
    // Account Controls renders unconditionally (design spec §2's "Option 2: disabled controls,
    // not hidden" -- a user should always see what a control does even when it can't act on it),
    // so the switch itself is present here, just disabled since there's no billing subscription.
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    renderPage();

    expect(await screen.findByTestId('current-plan-name')).toHaveTextContent('Free');
    expect(screen.queryByRole('button', { name: /cancel subscription/i })).not.toBeInTheDocument();
    const toggle = screen.getByRole('switch', { name: /auto renewal/i });
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    expect(toggle).toBeDisabled();
  });

  it('shows the renewal date and an enabled, on auto-renewal toggle for a paid plan', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY',
      renewalDate: '2026-11-01', hasBillingSubscription: true,
    }));
    renderPage();

    expect(await screen.findByTestId('current-plan-name')).toHaveTextContent('Plus');
    // formatDate renders a LocalDate like "2026-11-01" as e.g. "1 Nov 2026" (en-IN,
    // locale-dependent exact token order) -- assert on the parts that don't vary.
    expect(screen.getAllByText(/nov/i).length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: /cancel subscription/i })).toBeInTheDocument();
    const toggle = screen.getByRole('switch', { name: /auto renewal/i });
    expect(toggle).toHaveAttribute('aria-checked', 'true');
    expect(toggle).not.toBeDisabled();
  });

  it('shows an ends-on message and an off auto-renewal toggle once already cancelled', async () => {
    // BillingCheckoutService.cancel() only flips autoRenew -- status/renewalDate/
    // hasBillingSubscription are all untouched until the actual webhook lands (design spec
    // §6.3). The Billing Portal must still tell the user their cancellation took effect.
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY',
      renewalDate: '2026-11-01', hasBillingSubscription: true, autoRenew: false,
    }));
    renderPage();

    await screen.findByTestId('current-plan-name');
    expect(screen.getByText(/ends.*won't renew/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /cancel subscription/i })).not.toBeInTheDocument();
    expect(screen.getByRole('switch', { name: /auto renewal/i })).toHaveAttribute('aria-checked', 'false');
  });

  it('resumes auto-renewal directly, with no confirm dialog, when resumable', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(
      subscription({
        planCode: 'PLUS', hasBillingSubscription: true, paymentProvider: 'RAZORPAY',
        autoRenew: false, autoRenewResumable: true,
      })
    );
    vi.mocked(billingApi.undoCancellation).mockResolvedValue({ message: 'Auto-renewal resumed' });
    const user = userEvent.setup();
    renderPage();

    const toggle = await screen.findByRole('switch', { name: /auto renewal/i });
    expect(toggle).not.toBeDisabled();
    await user.click(toggle);

    await waitFor(() => expect(billingApi.undoCancellation).toHaveBeenCalled());
    expect(screen.queryByText(/cancel subscription\?/i)).not.toBeInTheDocument();
  });

  it('disables the toggle with an explanation once the cancellation has been dispatched to Razorpay', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(
      subscription({
        planCode: 'PLUS', hasBillingSubscription: true, paymentProvider: 'RAZORPAY',
        autoRenew: false, autoRenewResumable: false,
      })
    );
    renderPage();

    const toggle = await screen.findByRole('switch', { name: /auto renewal/i });
    expect(toggle).toBeDisabled();
    expect(screen.getByText(/too close to your renewal date to resume/i)).toBeInTheDocument();
  });

  it('shows a pending downgrade banner', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PREMIUM', planName: 'Premium', billingCycle: 'MONTHLY',
      hasBillingSubscription: true,
      pendingChange: { toPlanCode: 'PLUS', toPlanName: 'Plus', effectiveAt: '2026-11-01T00:00:00Z' },
    }));
    renderPage();

    expect(await screen.findByText(/to plus on/i)).toBeInTheDocument();
  });

  it('shows a resume/cancel banner for an abandoned checkout', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      pendingOrder: { planCode: 'PREMIUM', planName: 'Premium', billingCycle: 'YEARLY', razorpaySubscriptionId: 'sub_stuck', keyId: 'rzp_test' },
    }));
    renderPage();

    expect(await screen.findByText(/started upgrading to premium/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /resume checkout/i })).toBeInTheDocument();
  });

  it('resuming a pending order opens Razorpay directly without calling checkout again, prefilled', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      pendingOrder: { planCode: 'PREMIUM', planName: 'Premium', billingCycle: 'YEARLY', razorpaySubscriptionId: 'sub_stuck', keyId: 'rzp_test' },
    }));
    vi.mocked(openRazorpayCheckout).mockResolvedValue({ paymentId: 'pay_1' });
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: /resume checkout/i });

    await user.click(screen.getByRole('button', { name: /resume checkout/i }));

    await waitFor(() => expect(openRazorpayCheckout).toHaveBeenCalledWith(
      expect.objectContaining({
        key: 'rzp_test', subscription_id: 'sub_stuck',
        prefill: { email: 'ada@example.com', contact: '+919876543210', name: 'Ada Lovelace' }, // synthetic-ok
      })
    ));
    expect(billingApi.checkout).not.toHaveBeenCalled();
  });

  it('shows an error if resuming a pending order fails to open Razorpay', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      pendingOrder: { planCode: 'PREMIUM', planName: 'Premium', billingCycle: 'YEARLY', razorpaySubscriptionId: 'sub_stuck', keyId: 'rzp_test' },
    }));
    vi.mocked(openRazorpayCheckout).mockRejectedValue(new Error('Failed to load Razorpay Checkout.'));
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: /resume checkout/i });

    await user.click(screen.getByRole('button', { name: /resume checkout/i }));

    expect(await screen.findByText(/could not resume this checkout/i)).toBeInTheDocument();
  });

  it('disables Resume checkout while a resume is already in flight', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      pendingOrder: { planCode: 'PREMIUM', planName: 'Premium', billingCycle: 'YEARLY', razorpaySubscriptionId: 'sub_stuck', keyId: 'rzp_test' },
    }));
    let resolveCheckout: (v: { paymentId: string } | null) => void;
    vi.mocked(openRazorpayCheckout).mockReturnValue(new Promise((resolve) => { resolveCheckout = resolve; }));
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: /resume checkout/i });

    await user.click(screen.getByRole('button', { name: /resume checkout/i }));
    // Still in flight (the mocked promise hasn't resolved yet) -- a second click must not open a
    // second Razorpay widget.
    await user.click(screen.getByRole('button', { name: /resume checkout/i }));
    resolveCheckout!({ paymentId: 'pay_1' });

    await waitFor(() => expect(openRazorpayCheckout).toHaveBeenCalledTimes(1));
  });

  it('cancelling a pending order calls cancelPendingOrder after confirmation', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      pendingOrder: { planCode: 'PREMIUM', planName: 'Premium', billingCycle: 'YEARLY', razorpaySubscriptionId: 'sub_stuck', keyId: 'rzp_test' },
    }));
    vi.mocked(billingApi.cancelPendingOrder).mockResolvedValue({ message: 'Cancelled' });
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: /resume checkout/i });

    await user.click(screen.getByRole('button', { name: /^cancel$/i }));
    await user.click(screen.getByRole('button', { name: /confirm/i }));

    await waitFor(() => expect(billingApi.cancelPendingOrder).toHaveBeenCalled());
  });

  it('checking out a paid plan from Free opens the Razorpay widget', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    vi.mocked(billingApi.checkout).mockResolvedValue({ razorpaySubscriptionId: 'sub_new', keyId: 'rzp_test' });
    vi.mocked(openRazorpayCheckout).mockResolvedValue({ paymentId: 'pay_1' });
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId('current-plan-name');

    await user.click(screen.getByRole('button', { name: 'Upgrade to Plus' }));

    await waitFor(() => expect(billingApi.checkout).toHaveBeenCalledWith('PLUS', 'MONTHLY'));
    expect(openRazorpayCheckout).toHaveBeenCalledWith(
      expect.objectContaining({ key: 'rzp_test', subscription_id: 'sub_new' })
    );
  });

  // Bug found in review: openRazorpayCheckout was called with no `prefill` at all, so Razorpay's
  // widget always asked for contact details fresh even though Fynora already has the user's
  // verified email and phone number -- checked against the real code, not assumed.
  it('prefills the Razorpay widget with the signed-in user\'s email, phone, and name', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    vi.mocked(billingApi.checkout).mockResolvedValue({ razorpaySubscriptionId: 'sub_new', keyId: 'rzp_test' });
    vi.mocked(openRazorpayCheckout).mockResolvedValue({ paymentId: 'pay_1' });
    vi.mocked(userApi.get).mockResolvedValue(userSettings({
      email: 'grace@example.com', fullName: 'Grace Hopper', phoneNumber: '+911234567890', // synthetic-ok
    }));
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId('current-plan-name');

    await user.click(screen.getByRole('button', { name: 'Upgrade to Plus' }));

    await waitFor(() => expect(openRazorpayCheckout).toHaveBeenCalledWith(
      expect.objectContaining({
        prefill: { email: 'grace@example.com', contact: '+911234567890', name: 'Grace Hopper' }, // synthetic-ok
      })
    ));
  });

  it('omits the phone from prefill when the account has none (e.g. Google sign-in)', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    vi.mocked(billingApi.checkout).mockResolvedValue({ razorpaySubscriptionId: 'sub_new', keyId: 'rzp_test' });
    vi.mocked(openRazorpayCheckout).mockResolvedValue({ paymentId: 'pay_1' });
    vi.mocked(userApi.get).mockResolvedValue(userSettings({ phoneNumber: null }));
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId('current-plan-name');

    await user.click(screen.getByRole('button', { name: 'Upgrade to Plus' }));

    await waitFor(() => expect(openRazorpayCheckout).toHaveBeenCalledWith(
      expect.objectContaining({ prefill: expect.not.objectContaining({ contact: expect.anything() }) })
    ));
  });

  it('shows plain user-facing copy, not the raw API instruction, when checkout hits an in-progress-order 409', async () => {
    // BillingCheckoutService.resumableOrderOrGuard's own message is written for an API caller
    // ("Cancel it (POST /api/v1/billing/pending-order/cancel)...") -- shown verbatim to a real
    // user with no working pendingOrder card on screen (e.g. a stale/failed initial
    // mySubscription fetch), it read as an unactionable raw API error. This must never render as-is.
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    vi.mocked(billingApi.checkout).mockRejectedValue({
      response: {
        status: 409,
        data: {
          message: 'You have a checkout already in progress for a different plan. ' +
            'Cancel it (POST /api/v1/billing/pending-order/cancel) before starting a new one.',
        },
      },
    });
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId('current-plan-name');

    await user.click(screen.getByRole('button', { name: 'Upgrade to Plus' }));

    expect(await screen.findByText(/already have a checkout in progress/i)).toBeInTheDocument();
    expect(screen.queryByText(/POST \/api\/v1\/billing\/pending-order\/cancel/i)).not.toBeInTheDocument();
    // Refetches my-subscription so the actionable Resume/Cancel card gets a fresh chance to
    // render, in case the first load was the one that missed it.
    await waitFor(() => expect(billingApi.mySubscription).toHaveBeenCalledTimes(2));
  });

  it('double-clicking Upgrade only checks out once', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    let resolveCheckout: (v: { razorpaySubscriptionId: string; keyId: string }) => void;
    vi.mocked(billingApi.checkout).mockReturnValue(new Promise((resolve) => { resolveCheckout = resolve; }));
    vi.mocked(openRazorpayCheckout).mockResolvedValue({ paymentId: 'pay_1' });
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId('current-plan-name');

    await user.click(screen.getByRole('button', { name: 'Upgrade to Plus' }));
    // billingApi.checkout hasn't resolved yet -- a second click must not fire a second checkout.
    await user.click(screen.getByRole('button', { name: 'Upgrade to Plus' }));
    resolveCheckout!({ razorpaySubscriptionId: 'sub_new', keyId: 'rzp_test' });

    await waitFor(() => expect(billingApi.checkout).toHaveBeenCalledTimes(1));
  });

  it('lets a subscriber switch billing cycle on their current plan via the plan grid', async () => {
    // Plan cards gate "Current Plan" on plan code alone would make a monthly→yearly switch on the
    // SAME plan unreachable -- the button must stay live whenever the Monthly/Yearly toggle above
    // the grid differs from the subscriber's actual billingCycle.
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY', hasBillingSubscription: true,
    }));
    vi.mocked(billingApi.changePlan).mockResolvedValue(null);
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId('current-plan-name');

    await user.click(screen.getByRole('button', { name: 'Yearly' }));
    await user.click(screen.getByRole('button', { name: 'Switch to Yearly billing' }));

    await waitFor(() => expect(billingApi.changePlan).toHaveBeenCalledWith('PLUS', 'YEARLY'));
  });

  it('updates the displayed plan price when the Monthly/Yearly toggle is switched', async () => {
    // Bug: the toggle used to only change what a checkout charged (subscribeToPlan's own
    // targetCycle argument) without ever changing what the card claimed the price was -- a
    // visitor could toggle to Yearly, read "₹399/month", and be charged ₹3,500 instead.
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId('current-plan-name');

    expect(screen.getByTestId('plan-price-plus')).toHaveTextContent('₹399/month');
    expect(screen.getByTestId('plan-price-premium')).toHaveTextContent('₹799/month');

    await user.click(screen.getByRole('button', { name: 'Yearly' }));

    expect(screen.getByTestId('plan-price-plus')).toHaveTextContent('₹3,500/year');
    expect(screen.getByTestId('plan-price-premium')).toHaveTextContent('₹8,000/year');
    // Free has no secondaryPriceNote -- unaffected by the toggle either way.
    expect(screen.getByTestId('plan-price-free')).toHaveTextContent('₹0/month');

    await user.click(screen.getByRole('button', { name: 'Monthly' }));

    expect(screen.getByTestId('plan-price-plus')).toHaveTextContent('₹399/month');
    expect(screen.getByTestId('plan-price-premium')).toHaveTextContent('₹799/month');
  });

  it("defaults the cycle toggle to whatever the landing page's toggle carried through, then clears it", async () => {
    // See INTENDED_BILLING_CYCLE_KEY's own doc comment (plans.ts): a one-time carry-through from
    // Pricing.tsx's own toggle, read once and cleared so it never reasserts on a later visit.
    localStorage.setItem(INTENDED_BILLING_CYCLE_KEY, 'yearly');
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    renderPage();
    await screen.findByTestId('current-plan-name');

    expect(screen.getByTestId('plan-price-plus')).toHaveTextContent('₹3,500/year');
    await waitFor(() => expect(localStorage.getItem(INTENDED_BILLING_CYCLE_KEY)).toBeNull());
  });

  it('cancelling calls the cancel endpoint after confirmation', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY', hasBillingSubscription: true,
    }));
    vi.mocked(billingApi.cancel).mockResolvedValue({ message: 'Cancelled' });
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId('current-plan-name');

    await user.click(screen.getByRole('button', { name: /cancel subscription/i }));
    await user.click(screen.getByRole('button', { name: /confirm/i }));

    await waitFor(() => expect(billingApi.cancel).toHaveBeenCalled());
  });

  it('shows a Pause action in Account Controls for an active paid plan', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY', hasBillingSubscription: true,
    }));
    renderPage();

    await screen.findByTestId('current-plan-name');
    expect(screen.getByText('Pause Subscription')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Pause' })).toBeInTheDocument();
    expect(screen.queryByText('Resume Subscription')).not.toBeInTheDocument();
  });

  it('pausing calls the pause endpoint after confirmation', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY', hasBillingSubscription: true,
    }));
    vi.mocked(billingApi.pause).mockResolvedValue({ message: 'Paused' });
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId('current-plan-name');

    await user.click(screen.getByRole('button', { name: 'Pause' }));
    // The trigger button and the confirm dialog's own confirm button are both labelled "Pause" --
    // scoped to the dialog so this doesn't ambiguously match the trigger still visible behind it.
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: /^pause$/i }));

    await waitFor(() => expect(billingApi.pause).toHaveBeenCalled());
  });

  it('shows a paused message and a Resume action instead of Pause/Cancel while paused', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY', hasBillingSubscription: true,
      status: 'PAUSED', renewalDate: '2026-11-01',
    }));
    renderPage();

    await screen.findByTestId('current-plan-name');
    expect(screen.getAllByText(/paused/i).length).toBeGreaterThan(0);
    expect(screen.getByText('Resume Subscription')).toBeInTheDocument();
    expect(screen.queryByText('Pause Subscription')).not.toBeInTheDocument();
    expect(screen.queryByText('Cancel Subscription')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /cancel subscription/i })).not.toBeInTheDocument();
    // The stale pre-pause renewalDate must not render as if it were still accurate.
    expect(screen.queryByText(/renews/i)).not.toBeInTheDocument();
  });

  it('shows Paused, not Active, on the top Current Plan KPI badge while paused', async () => {
    // Bug found in review: this badge was hardcoded to isFree ? 'Free' : 'Active' with no PAUSED
    // case, so it kept showing a green "Active" badge while the membership card right below it
    // correctly said "Paused" -- two elements on the same page disagreeing.
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY', hasBillingSubscription: true,
      status: 'PAUSED',
    }));
    renderPage();

    const planNameEl = await screen.findByTestId('current-plan-name');
    const kpiCard = planNameEl.closest('.h-full') as HTMLElement;
    expect(within(kpiCard).getByText('Paused')).toBeInTheDocument();
    expect(within(kpiCard).queryByText('Active')).not.toBeInTheDocument();
  });

  it('resuming calls the resume endpoint directly, with no confirmation dialog', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY', hasBillingSubscription: true,
      status: 'PAUSED',
    }));
    vi.mocked(billingApi.resume).mockResolvedValue({ message: 'Resumed' });
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId('current-plan-name');

    await user.click(screen.getByRole('button', { name: 'Resume' }));

    await waitFor(() => expect(billingApi.resume).toHaveBeenCalled());
  });

  it('disables plan-switch buttons and explains why while paused', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY', hasBillingSubscription: true,
      status: 'PAUSED',
    }));
    renderPage();

    await screen.findByTestId('current-plan-name');
    expect(screen.getByText(/resume your subscription to change plans/i)).toBeInTheDocument();
    // Every plan card's own switch/upgrade button must be disabled while paused, not just hidden --
    // same "always visible, never a dead end" posture the RevenueCat-owned path already uses.
    screen.getAllByRole('button', { name: /current plan|choose|switch to/i }).forEach((btn) => {
      expect(btn).toBeDisabled();
    });
  });

  it('renders payment history', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    vi.mocked(billingApi.history).mockResolvedValue([entry()]);
    renderPage();

    expect(await screen.findByText('₹499')).toBeInTheDocument();
  });

  it('opens the invoice PDF in a new tab when View is clicked on a successful payment', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    vi.mocked(billingApi.history).mockResolvedValue([entry({ status: 'SUCCESS' })]);
    const blob = new Blob(['%PDF-fake'], { type: 'application/pdf' });
    vi.mocked(billingApi.invoicePdf).mockResolvedValue(blob);
    const createObjectURL = vi.fn().mockReturnValue('blob:fake-url');
    vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL: vi.fn() });
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null);
    renderPage();

    await screen.findByText('₹499');
    await userEvent.click(screen.getByRole('button', { name: 'View' }));

    await waitFor(() => expect(billingApi.invoicePdf).toHaveBeenCalledWith('payment-1'));
    expect(createObjectURL).toHaveBeenCalledWith(blob);
    expect(openSpy).toHaveBeenCalledWith('blob:fake-url', '_blank');
    vi.unstubAllGlobals();
  });

  it('downloads the invoice PDF when Download is clicked on a successful payment', async () => {
    const { downloadBlob } = await import('../lib/download');
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    vi.mocked(billingApi.history).mockResolvedValue([entry({ status: 'SUCCESS' })]);
    const blob = new Blob(['%PDF-fake'], { type: 'application/pdf' });
    vi.mocked(billingApi.invoicePdf).mockResolvedValue(blob);
    renderPage();

    await screen.findByText('₹499');
    await userEvent.click(screen.getByRole('button', { name: 'Download' }));

    await waitFor(() => expect(billingApi.invoicePdf).toHaveBeenCalledWith('payment-1'));
    expect(downloadBlob).toHaveBeenCalledWith(blob, 'fynora-invoice-payment-.pdf');
  });

  it('keeps View/Download disabled for a payment that is not yet successful', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    vi.mocked(billingApi.history).mockResolvedValue([entry({ status: 'PENDING' })]);
    renderPage();

    await screen.findByText('₹499');
    expect(screen.getByRole('button', { name: 'View' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Download' })).toBeDisabled();
    expect(billingApi.invoicePdf).not.toHaveBeenCalled();
  });

  it('shows the card on file with an Update Payment Method button for a Razorpay subscriber', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY', hasBillingSubscription: true,
      paymentProvider: 'RAZORPAY',
      paymentMethod: { cardLast4: '4366', cardNetwork: 'Visa', cardType: 'credit', razorpaySubscriptionId: 'sub_existing', keyId: 'rzp_test' },
    }));
    renderPage();

    expect(await screen.findByText(/visa.*4366/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /update payment method/i })).toBeInTheDocument();
  });

  it('hides the Update Payment Method button when no card is on file yet (e.g. a UPI mandate)', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY', hasBillingSubscription: true,
      paymentProvider: 'RAZORPAY',
      paymentMethod: { cardLast4: null, cardNetwork: null, cardType: null, razorpaySubscriptionId: 'sub_existing', keyId: 'rzp_test' },
    }));
    renderPage();

    await screen.findByTestId('current-plan-name');
    expect(screen.queryByRole('button', { name: /update payment method/i })).not.toBeInTheDocument();
    expect(screen.getByText(/managed securely through razorpay checkout/i)).toBeInTheDocument();
  });

  it('clicking Update Payment Method opens Razorpay Checkout against the existing subscription', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY', hasBillingSubscription: true,
      paymentProvider: 'RAZORPAY',
      paymentMethod: { cardLast4: '4366', cardNetwork: 'Visa', cardType: 'credit', razorpaySubscriptionId: 'sub_existing', keyId: 'rzp_test' },
    }));
    vi.mocked(openRazorpayCheckout).mockResolvedValue({ paymentId: 'pay_1' });
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: /update payment method/i });

    await user.click(screen.getByRole('button', { name: /update payment method/i }));

    await waitFor(() => expect(openRazorpayCheckout).toHaveBeenCalledWith(
      expect.objectContaining({ key: 'rzp_test', subscription_id: 'sub_existing' })
    ));
    // Not a new checkout -- billingApi.checkout() must never be called for this flow.
    expect(billingApi.checkout).not.toHaveBeenCalled();
  });

  it('does not refetch the subscription if the Update Payment Method checkout is dismissed', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY', hasBillingSubscription: true,
      paymentProvider: 'RAZORPAY',
      paymentMethod: { cardLast4: '4366', cardNetwork: 'Visa', cardType: 'credit', razorpaySubscriptionId: 'sub_existing', keyId: 'rzp_test' },
    }));
    vi.mocked(openRazorpayCheckout).mockResolvedValue(null); // dismissed, per its own documented contract
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: /update payment method/i });

    await user.click(screen.getByRole('button', { name: /update payment method/i }));

    await waitFor(() => expect(openRazorpayCheckout).toHaveBeenCalled());
    expect(billingApi.mySubscription).toHaveBeenCalledTimes(1); // no refetch triggered
  });

  it('disables Update Payment Method while a plan-change checkout is already in flight', async () => {
    // Matches the existing cross-flow guard between subscribeToPlan and resumePendingOrder (see
    // isSubmitting's own comment) -- Update Payment Method must join the SAME guard, not race it
    // with an independent flag, or two Razorpay widgets could open at once.
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY', hasBillingSubscription: true,
      paymentProvider: 'RAZORPAY',
      paymentMethod: { cardLast4: '4366', cardNetwork: 'Visa', cardType: 'credit', razorpaySubscriptionId: 'sub_existing', keyId: 'rzp_test' },
    }));
    let resolveChangePlan: (v: { razorpaySubscriptionId: string; keyId: string }) => void;
    vi.mocked(billingApi.changePlan).mockReturnValue(new Promise((resolve) => { resolveChangePlan = resolve; }));
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: /update payment method/i });

    // The redesign replaced the "Choose a plan" dropdown + Subscribe button with one button per
    // plan card -- an upgrade from PLUS reads "Choose Premium" (see subscribeToPlan's own button
    // label logic: TIER_RANK[code] > TIER_RANK[current] -> "Choose", else "Switch to").
    await user.click(screen.getByRole('button', { name: /choose premium/i }));

    expect(screen.getByRole('button', { name: /update payment method/i })).toBeDisabled();
    resolveChangePlan!({ razorpaySubscriptionId: 'sub_new', keyId: 'rzp_test' });
  });

  it('disables Update Payment Method while an upgrade is activating', async () => {
    // After an upgrade's checkout succeeds, subscription.paymentMethod still points at the OLD
    // razorpaySubscriptionId until useActivationPoll's refetch lands (RazorpayWebhookDispatcher.
    // handleActivated cancels that old subscription once the new one activates) -- clicking Update
    // Payment Method in that window would re-authenticate a mandate about to be cancelled. Matches
    // Subscribe/Resume's own activatingPlanCode guard.
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY', hasBillingSubscription: true,
      paymentProvider: 'RAZORPAY',
      paymentMethod: { cardLast4: '4366', cardNetwork: 'Visa', cardType: 'credit', razorpaySubscriptionId: 'sub_old', keyId: 'rzp_test' },
    }));
    vi.mocked(billingApi.changePlan).mockResolvedValue({ razorpaySubscriptionId: 'sub_new', keyId: 'rzp_test' });
    vi.mocked(openRazorpayCheckout).mockResolvedValue({ paymentId: 'pay_1' });
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: /update payment method/i });

    await user.click(screen.getByRole('button', { name: /choose premium/i }));

    await waitFor(() => expect(screen.getByText(/activating your premium plan/i)).toBeInTheDocument());
    expect(screen.getByRole('button', { name: /update payment method/i })).toBeDisabled();
  });

  it('does not show any Payment Method card for a RevenueCat-owned subscription', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PREMIUM', planName: 'Premium', billingCycle: 'MONTHLY',
      hasBillingSubscription: true, paymentProvider: 'REVENUECAT', paymentMethod: null,
    }));
    renderPage();

    // The redesign shows this same RevenueCat message in two separate places on the page, so
    // findByText (which requires exactly one match) would throw -- findAllByText tolerates that.
    await waitFor(() => expect(screen.getAllByText(/managed through the App Store\/Play Store/i).length).toBeGreaterThan(0));
    expect(screen.queryByRole('button', { name: /update payment method/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/managed securely through razorpay checkout/i)).not.toBeInTheDocument();
  });

  it('shows disabled plan controls with a store-managed note for a RevenueCat-owned subscription', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PREMIUM', planName: 'Premium', billingCycle: 'MONTHLY',
      renewalDate: '2026-10-06', autoRenew: true, hasBillingSubscription: true,
      paymentProvider: 'REVENUECAT',
    }));
    renderPage();

    expect(await screen.findAllByText(/managed through the App Store\/Play Store/i)).not.toHaveLength(0);
    expect(screen.queryByRole('button', { name: /cancel subscription/i })).not.toBeInTheDocument();
    // Every plan card's own change-plan control is disabled, not hidden -- design spec §2's
    // "Option 2": a user who knows they're paying should always see what they're paying for.
    expect(screen.getByRole('button', { name: 'Current Plan' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Switch to Plus' })).toBeDisabled();
  });

  it('disables the downgrade button (not upgrade) while a cancellation is pending, with an explanation', async () => {
    // Bug found in a second bug-hunt pass, post-merge: changePlan()'s downgrade path can schedule a
    // Razorpay plan-change on a subscription that also has a pending, not-yet-dispatched
    // cancellation -- the backend now refuses this. Mirrored here per this page's own "disabled
    // controls, not hidden" philosophy. Upgrade must stay enabled -- it's unaffected on the backend.
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PREMIUM', planName: 'Premium', billingCycle: 'MONTHLY',
      hasBillingSubscription: true, paymentProvider: 'RAZORPAY', autoRenew: false,
    }));
    renderPage();

    await screen.findByTestId('current-plan-name');
    const downgradeButton = screen.getByRole('button', { name: 'Switch to Plus' });
    expect(downgradeButton).toBeDisabled();
    expect(screen.getByText(/resume auto-renewal first to downgrade instead/i)).toBeInTheDocument();
  });

  it('leaves the upgrade button enabled while a cancellation is pending', async () => {
    // Companion to the downgrade test above -- upgrade must stay clickable in the same state,
    // matching the backend's own unblocked upgrade path.
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY',
      hasBillingSubscription: true, paymentProvider: 'RAZORPAY', autoRenew: false,
    }));
    renderPage();

    await screen.findByTestId('current-plan-name');
    expect(screen.getByRole('button', { name: 'Choose Premium' })).not.toBeDisabled();
    expect(screen.queryByText(/resume auto-renewal first to downgrade instead/i)).not.toBeInTheDocument();
  });

  it('recognizes an admin-granted (complimentary) plan as current and never opens real checkout for it', async () => {
    // Bug found in review: SubscriptionService.changePlan's ADMIN_GRANT path only ever sets planId
    // -- billingCycle stays null and hasBillingSubscription stays false, exactly like a genuine
    // Free/never-subscribed user. Comparing billing cycles to decide "is this my current plan"
    // made a comped Premium subscriber's own plan card render as an enabled "Switch to Monthly
    // billing" button that, if clicked, opened a real Razorpay checkout and charged them for a
    // plan they already had for free.
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PREMIUM', planName: 'Premium', billingCycle: null, hasBillingSubscription: false,
    }));
    renderPage();

    expect(await screen.findByRole('button', { name: 'Current Plan' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: /switch to (monthly|yearly) billing/i })).not.toBeInTheDocument();
    // Every place the page tells the user how they're billed must reflect the same truth: they
    // aren't. Second review pass caught two more spots making the same false "Razorpay" claim the
    // membership card's own Payment method row was first fixed for.
    expect(screen.getByText('Complimentary (no charge)')).toBeInTheDocument();
    expect(screen.getByText('Complimentary')).toBeInTheDocument();
    expect(screen.getByText("No payment method on file — this plan isn't billed.")).toBeInTheDocument();
    expect(screen.queryByText('Managed securely through Razorpay Checkout at each billing cycle.')).not.toBeInTheDocument();
  });

  it('hides the Premium Benefits Summary card for a Free-plan user', async () => {
    // Bug found in review: this card (a static ₹8,450 "value received" claim) rendered
    // unconditionally, including for Free users who hadn't unlocked any of it.
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    renderPage();

    await screen.findByTestId('current-plan-name');
    expect(screen.queryByText('Premium Benefits Summary')).not.toBeInTheDocument();
    expect(screen.getAllByText('Referral Rewards').length).toBeGreaterThan(0);
  });

  it('shows the real Smart Insights view count instead of a hardcoded number', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    vi.mocked(usageApi.viewCount).mockResolvedValue({ viewCount: 12 });
    renderPage();

    await screen.findByTestId('current-plan-name');
    expect(usageApi.viewCount).toHaveBeenCalledWith('insights');
    expect(await screen.findByText('12')).toBeInTheDocument();
    expect(screen.queryByText('142')).not.toBeInTheDocument();
  });

  // Unlike Goals Created/Budgets Managed/Connected Accounts (naturally small, hand-created
  // counts), a view count grows on every visit with no ceiling -- same shape as Transactions
  // Imported, which already gets comma formatting for exactly this reason.
  it('comma-formats the Smart Insights view count once it grows past 999', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    vi.mocked(usageApi.viewCount).mockResolvedValue({ viewCount: 12345 });
    renderPage();

    await screen.findByTestId('current-plan-name');
    expect(await screen.findByText('12,345')).toBeInTheDocument();
  });
});
