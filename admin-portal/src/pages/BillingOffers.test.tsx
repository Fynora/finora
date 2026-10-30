import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import BillingOffers from './BillingOffers';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminBillingOffersApi } from '../api/endpoints';
import type { BillingOfferAdmin, BillingOfferPlanOption } from '../types';

vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminBillingOffersApi: {
    list: vi.fn(),
    plans: vi.fn(),
    create: vi.fn(),
    endNow: vi.fn(),
  },
}));

const api = vi.mocked(adminBillingOffersApi);

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <BillingOffers />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
  }));
}

const PLANS: BillingOfferPlanOption[] = [
  { planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY', regularPrice: 249, checkoutReady: true },
  { planCode: 'PLUS', planName: 'Plus', billingCycle: 'YEARLY', regularPrice: 1999, checkoutReady: true },
  { planCode: 'PREMIUM', planName: 'Premium', billingCycle: 'MONTHLY', regularPrice: 799, checkoutReady: false },
];

// Diwali 2026: from 00:00 IST on 30 Oct to 00:00 IST on 8 Nov (last day 7 Nov).
const LIVE: BillingOfferAdmin = {
  id: 'o1', code: 'DIWALI2026', planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY',
  firstPeriodPrice: 125, regularPrice: 249, label: 'Diwali offer',
  startsAt: '2026-10-29T18:30:00Z', endsAt: '2026-11-07T18:30:00Z', state: 'LIVE', appliedAtCheckout: true,
  createdAt: '2026-10-30T10:00:00Z',
};
const ENDED: BillingOfferAdmin = {
  ...LIVE, id: 'o2', code: 'FLASH2026', label: 'Flash sale', state: 'ENDED', appliedAtCheckout: false,
  startsAt: '2026-09-01T04:30:00Z', endsAt: '2026-09-02T09:47:00Z',
};

describe('BillingOffers', () => {
  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockReset());
    api.list.mockResolvedValue([LIVE, ENDED]);
    api.plans.mockResolvedValue(PLANS);
  });

  it('is closed to an account without SUBSCRIPTION_MANAGEMENT_VIEW', () => {
    mockAuth([]);
    renderPage();
    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
    expect(api.list).not.toHaveBeenCalled();
  });

  it('lists past and live offers in India time, with the last day of an end on midnight', async () => {
    mockAuth(['SUBSCRIPTION_MANAGEMENT_VIEW']);
    renderPage();
    const live = (await screen.findByText('Diwali offer')).closest('tr')!;
    expect(within(live).getByText('DIWALI2026')).toBeInTheDocument();
    expect(within(live).getByText('Live')).toBeInTheDocument();
    expect(within(live).getByText('Rs 125 for the first month')).toBeInTheDocument();
    expect(within(live).getByText('regular Rs 249')).toBeInTheDocument();
    expect(within(live).getByText('30 Oct 2026, 00:00 IST')).toBeInTheDocument();
    expect(within(live).getByText('08 Nov 2026, 00:00 IST (last day 07 Nov 2026)')).toBeInTheDocument();
    expect(within(live).getByRole('button', { name: 'End now' })).toBeInTheDocument();

    const ended = screen.getByText('Flash sale').closest('tr')!;
    expect(within(ended).getByText('Ended')).toBeInTheDocument();
    // Ended early at 15:17 IST: shown exactly, no "last day".
    expect(within(ended).getByText('02 Sep 2026, 15:17 IST')).toBeInTheDocument();
    expect(within(ended).queryByRole('button', { name: 'End now' })).not.toBeInTheDocument();
  });

  it('flags a live offer that checkout skips', async () => {
    mockAuth(['SUBSCRIPTION_MANAGEMENT_VIEW']);
    api.list.mockResolvedValue([{ ...LIVE, appliedAtCheckout: false, regularPrice: 100 }]);
    renderPage();
    expect(await screen.findByText('Not charged: its price is not below the regular price.')).toBeInTheDocument();
  });

  it('creates an offer with the start in IST and the end at midnight after the last day', async () => {
    const user = userEvent.setup();
    mockAuth(['SUBSCRIPTION_MANAGEMENT_VIEW', 'SUBSCRIPTION_MANAGEMENT_MANAGE']);
    api.create.mockResolvedValue({ ...LIVE, id: 'o3', code: 'HOLI2027', label: 'Holi offer', state: 'UPCOMING' });
    renderPage();

    await user.click(await screen.findByRole('button', { name: /New offer/ }));
    const plan = screen.getByLabelText('Plan and billing cycle');
    expect(within(plan).getByRole('option', { name: /Premium monthly/ })).toBeDisabled();
    await user.selectOptions(plan, 'PLUS/YEARLY');
    await user.type(screen.getByLabelText(/^Code/), 'holi2027');
    await user.type(screen.getByLabelText(/^Name customers see/), 'Holi offer');
    await user.type(screen.getByLabelText(/^Price for the first year/), '999');
    await user.type(screen.getByLabelText('Starts (IST)'), '2027-03-20T00:00');
    await user.type(screen.getByLabelText('Last day (IST)'), '2027-03-22');
    expect(screen.getByTestId('ends-at-preview')).toHaveTextContent('Ends at 23 Mar 2027, 00:00 IST.');
    await user.click(screen.getByRole('button', { name: 'Create offer' }));

    await waitFor(() => expect(api.create).toHaveBeenCalledTimes(1));
    expect(api.create.mock.calls[0][0]).toEqual({
      code: 'HOLI2027',
      planCode: 'PLUS',
      billingCycle: 'YEARLY',
      firstPeriodPrice: 999,
      label: 'Holi offer',
      startsAt: '2027-03-19T18:30:00.000Z',
      endsAt: '2027-03-22T18:30:00.000Z',
    });
    expect(await screen.findByText('Created “Holi offer” (HOLI2027).')).toBeInTheDocument();
  });

  it('refuses a price at or above the regular price before calling the server', async () => {
    const user = userEvent.setup();
    mockAuth(['SUBSCRIPTION_MANAGEMENT_VIEW']);
    renderPage();
    await user.click(await screen.findByRole('button', { name: /New offer/ }));
    await user.type(screen.getByLabelText(/^Code/), 'HOLI2027');
    await user.type(screen.getByLabelText(/^Name customers see/), 'Holi offer');
    await user.type(screen.getByLabelText(/^Price for the first month/), '249');
    await user.type(screen.getByLabelText('Starts (IST)'), '2027-03-20T00:00');
    await user.type(screen.getByLabelText('Last day (IST)'), '2027-03-22');
    await user.click(screen.getByRole('button', { name: 'Create offer' }));

    expect(await screen.findByText('The offer price must be below the regular price of Rs 249.')).toBeInTheDocument();
    expect(api.create).not.toHaveBeenCalled();
  });

  it('refuses a last day before the start day', async () => {
    const user = userEvent.setup();
    mockAuth(['SUBSCRIPTION_MANAGEMENT_VIEW']);
    renderPage();
    await user.click(await screen.findByRole('button', { name: /New offer/ }));
    await user.type(screen.getByLabelText(/^Code/), 'HOLI2027');
    await user.type(screen.getByLabelText(/^Name customers see/), 'Holi offer');
    await user.type(screen.getByLabelText(/^Price for the first month/), '99');
    await user.type(screen.getByLabelText('Starts (IST)'), '2027-03-20T00:00');
    await user.type(screen.getByLabelText('Last day (IST)'), '2027-03-19');
    await user.click(screen.getByRole('button', { name: 'Create offer' }));

    expect(await screen.findByText('The last day must be on or after the start day.')).toBeInTheDocument();
    expect(api.create).not.toHaveBeenCalled();
  });

  it("shows the server's message when it refuses the offer", async () => {
    const user = userEvent.setup();
    mockAuth(['SUBSCRIPTION_MANAGEMENT_VIEW']);
    api.create.mockRejectedValue({ response: { data: { message: 'This overlaps "Diwali offer" (DIWALI2026, ...).' } } });
    renderPage();
    await user.click(await screen.findByRole('button', { name: /New offer/ }));
    await user.type(screen.getByLabelText(/^Code/), 'HOLI2027');
    await user.type(screen.getByLabelText(/^Name customers see/), 'Holi offer');
    await user.type(screen.getByLabelText(/^Price for the first month/), '99');
    await user.click(screen.getByRole('button', { name: 'Start now' }));
    await user.type(screen.getByLabelText('Last day (IST)'), '2027-03-22');
    await user.click(screen.getByRole('button', { name: 'Create offer' }));

    expect(await screen.findByText('This overlaps "Diwali offer" (DIWALI2026, ...).')).toBeInTheDocument();
    expect(api.create).toHaveBeenCalledTimes(1);
  });

  it('ends a live offer after confirming, then reloads the list', async () => {
    const user = userEvent.setup();
    mockAuth(['SUBSCRIPTION_MANAGEMENT_VIEW', 'SUBSCRIPTION_MANAGEMENT_MANAGE']);
    api.endNow.mockResolvedValue({ ...LIVE, state: 'ENDED' });
    renderPage();
    const live = (await screen.findByText('Diwali offer')).closest('tr')!;
    await user.click(within(live).getByRole('button', { name: 'End now' }));

    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('Checkouts started from now on pay the regular price');
    await user.click(within(dialog).getByRole('button', { name: 'End now' }));

    await waitFor(() => expect(api.endNow).toHaveBeenCalledWith('o1'));
    expect(await screen.findByText('Ended “Diwali offer” (DIWALI2026). New checkouts get the regular price.')).toBeInTheDocument();
    await waitFor(() => expect(api.list).toHaveBeenCalledTimes(2));
  });

  it("shows the server's message when ending is refused", async () => {
    const user = userEvent.setup();
    mockAuth(['SUBSCRIPTION_MANAGEMENT_VIEW']);
    api.endNow.mockRejectedValue({ response: { data: { message: 'Access denied' } } });
    renderPage();
    const live = (await screen.findByText('Diwali offer')).closest('tr')!;
    await user.click(within(live).getByRole('button', { name: 'End now' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'End now' }));
    expect(await screen.findByText('Access denied')).toBeInTheDocument();
  });

  it('says so when the offers cannot be loaded', async () => {
    mockAuth(['SUBSCRIPTION_MANAGEMENT_VIEW']);
    api.list.mockRejectedValue({ response: { data: { message: 'Server unavailable' } } });
    renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent('Server unavailable');
    expect(screen.getByText('The offers could not be loaded.')).toBeInTheDocument();
  });
});
