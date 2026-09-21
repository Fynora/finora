import { act, render, screen, waitFor, fireEvent } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider, onlineManager } from '@tanstack/react-query';
import { BillingHistorySection } from './BillingHistorySection';
import { billingApi } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({
  billingApi: { history: jest.fn(), downloadInvoice: jest.fn() },
}));

const mockedBillingApi = billingApi as jest.Mocked<typeof billingApi>;

function entry(overrides: Record<string, unknown> = {}) {
  return {
    id: 'abcdef12-0000-0000-0000-000000000000', amount: 399, currency: 'INR', provider: 'razorpay',
    status: 'SUCCESS', createdAt: '2026-09-01T10:00:00Z', ...overrides,
  };
}

function renderSection(paymentProvider: string | null = 'RAZORPAY', hideWhenEmpty = false) {
  // gcTime: 0 -- see SubscriptionScreen.test.tsx's own comment on this exact line.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <BillingHistorySection paymentProvider={paymentProvider} hideWhenEmpty={hideWhenEmpty} />
    </QueryClientProvider>
  );
}

describe('BillingHistorySection', () => {
  beforeEach(() => {
    mockedBillingApi.history.mockReset();
    mockedBillingApi.downloadInvoice.mockReset();
  });

  it('renders nothing while the history is loading', () => {
    mockedBillingApi.history.mockReturnValue(new Promise(() => {}));
    renderSection();
    expect(screen.queryByText('Billing history')).toBeNull();
    expect(screen.queryByText(/No billing history/)).toBeNull();
  });

  it('lists each payment with amount, date, reference and a status label', async () => {
    mockedBillingApi.history.mockResolvedValue([
      entry(),
      entry({ id: '11111111-0000-0000-0000-000000000000', amount: 799, status: 'FAILED' }),
      entry({ id: '22222222-0000-0000-0000-000000000000', amount: 100, status: 'REFUNDED' }),
      entry({ id: '33333333-0000-0000-0000-000000000000', amount: 50, status: 'PENDING' }),
    ]);
    renderSection();

    expect(await screen.findByText('₹399')).toBeTruthy();
    expect(screen.getByText('₹799')).toBeTruthy();
    expect(screen.getByText(/ABCDEF12/)).toBeTruthy();
    expect(screen.getByText('Paid')).toBeTruthy();
    expect(screen.getByText('Failed')).toBeTruthy();
    expect(screen.getByText('Refunded')).toBeTruthy();
    expect(screen.getByText('Pending')).toBeTruthy();
  });

  it('shows a dash, not ₹0, for a retry attempt whose amount was never recorded', async () => {
    mockedBillingApi.history.mockResolvedValue([entry({ amount: 0, status: 'PENDING' })]);
    renderSection();

    expect(await screen.findByText('—')).toBeTruthy();
    expect(screen.queryByText('₹0')).toBeNull();
    expect(screen.getByText('Pending')).toBeTruthy();
  });

  it('offers an invoice only for a completed payment', async () => {
    mockedBillingApi.history.mockResolvedValue([
      entry(),
      entry({ id: '11111111-0000-0000-0000-000000000000', status: 'FAILED' }),
      entry({ id: '22222222-0000-0000-0000-000000000000', status: 'REFUNDED' }),
      entry({ id: '33333333-0000-0000-0000-000000000000', status: 'PENDING' }),
    ]);
    renderSection();

    await screen.findByText('Paid');
    expect(screen.getAllByText('Invoice')).toHaveLength(1);
  });

  it('downloads the invoice with a reference-based file name when tapped', async () => {
    mockedBillingApi.history.mockResolvedValue([entry()]);
    mockedBillingApi.downloadInvoice.mockResolvedValue(undefined);
    renderSection();

    fireEvent.press(await screen.findByText('Invoice'));
    await act(async () => {});

    expect(mockedBillingApi.downloadInvoice).toHaveBeenCalledWith(
      'abcdef12-0000-0000-0000-000000000000', 'Fynora-invoice-ABCDEF12.pdf'
    );
    // Back to a tappable Invoice link once the share sheet call settles.
    expect(screen.getByText('Invoice')).toBeTruthy();
  });

  it('shows the failure inline and stays usable when the invoice cannot be fetched', async () => {
    mockedBillingApi.history.mockResolvedValue([entry()]);
    mockedBillingApi.downloadInvoice.mockRejectedValue(new Error('boom'));
    renderSection();

    fireEvent.press(await screen.findByText('Invoice'));
    await act(async () => {});

    expect(screen.getByText(/Could not open this invoice/i)).toBeTruthy();
    expect(screen.getByText('Invoice')).toBeTruthy();
  });

  it('ignores a second tap while an invoice is already opening', async () => {
    mockedBillingApi.history.mockResolvedValue([
      entry(),
      entry({ id: '11111111-0000-0000-0000-000000000000' }),
    ]);
    let release: () => void = () => {};
    mockedBillingApi.downloadInvoice.mockReturnValue(new Promise<void>((resolve) => { release = resolve; }));
    renderSection();

    const links = await screen.findAllByText('Invoice');
    fireEvent.press(links[0]);
    await screen.findByText('Opening…');
    fireEvent.press(screen.getAllByText('Invoice')[0]);

    expect(mockedBillingApi.downloadInvoice).toHaveBeenCalledTimes(1);
    // Inside act, so the state update that clears the busy label is flushed before the assertion
    // rather than left to a polling waitFor -- it timed out on a loaded CI runner.
    await act(async () => { release(); });
    expect(screen.queryByText('Opening…')).toBeNull();
  });

  it('says there is no history yet for a Razorpay-owned subscription with no payments', async () => {
    mockedBillingApi.history.mockResolvedValue([]);
    renderSection('RAZORPAY');
    expect(await screen.findByText(/No billing history yet/)).toBeTruthy();
  });

  it('points a RevenueCat-owned subscription at its store account instead of implying a missing record', async () => {
    mockedBillingApi.history.mockResolvedValue([]);
    renderSection('REVENUECAT');
    expect(await screen.findByText(/App Store or Google Play/)).toBeTruthy();
    expect(screen.queryByText(/No billing history yet/)).toBeNull();
  });

  describe('hideWhenEmpty (the Paywall, for a user with no live subscription)', () => {
    it('renders nothing for someone who never paid', async () => {
      mockedBillingApi.history.mockResolvedValue([]);
      renderSection(null, true);

      await waitFor(() => expect(mockedBillingApi.history).toHaveBeenCalled());
      await act(async () => {});
      expect(screen.queryByText('Billing history')).toBeNull();
      expect(screen.queryByText(/No billing history yet/)).toBeNull();
    });

    it('renders nothing, not a failure note, when the history cannot be loaded', async () => {
      mockedBillingApi.history.mockRejectedValue(new Error('network'));
      renderSection(null, true);

      await waitFor(() => expect(mockedBillingApi.history).toHaveBeenCalled());
      await act(async () => {});
      expect(screen.queryByText(/Couldn't load your billing history/)).toBeNull();
    });

    it('still lists past payments and their invoices for a lapsed payer', async () => {
      mockedBillingApi.history.mockResolvedValue([entry()]);
      renderSection(null, true);

      expect(await screen.findByText('Billing history')).toBeTruthy();
      expect(screen.getByText('₹399')).toBeTruthy();
      expect(screen.getByText('Invoice')).toBeTruthy();
    });
  });

  // Offline, React Query pauses a cold query: not loading, not an error, no data. Reading that as
  // "no payments" would tell someone with real invoices that they have none.
  it('does not claim there is no history while offline with nothing loaded', async () => {
    onlineManager.setOnline(false);
    try {
      mockedBillingApi.history.mockResolvedValue([]);
      renderSection('RAZORPAY');
      await act(async () => {});

      expect(screen.queryByText(/No billing history yet/)).toBeNull();
      expect(screen.getByText(/Couldn't load your billing history/)).toBeTruthy();
    } finally {
      onlineManager.setOnline(true);
    }
  });

  it('renders nothing on the Paywall while offline with nothing loaded', async () => {
    onlineManager.setOnline(false);
    try {
      mockedBillingApi.history.mockResolvedValue([]);
      renderSection(null, true);
      await act(async () => {});

      expect(screen.queryByText('Billing history')).toBeNull();
    } finally {
      onlineManager.setOnline(true);
    }
  });

  // React Query keeps the previous data when a background refetch fails, so isError is true while
  // the invoices are still perfectly good. A failed refetch must not hide them.
  it('keeps listing payments and invoices, with a note, when a refetch fails', async () => {
    mockedBillingApi.history.mockResolvedValueOnce([entry()]).mockRejectedValueOnce(new Error('boom'));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    render(
      <QueryClientProvider client={queryClient}>
        <BillingHistorySection paymentProvider="RAZORPAY" />
      </QueryClientProvider>
    );
    await screen.findByText('₹399');

    await act(async () => { await queryClient.refetchQueries({ queryKey: ['billing-history'] }); });
    // React Query notifies observers a tick after the refetch settles; without this flush the
    // assertions below would run before the error state reaches the screen and pass vacuously.
    await act(async () => { await new Promise((r) => setTimeout(r, 0)); });

    expect(queryClient.getQueryState(['billing-history'])?.status).toBe('error');
    expect(screen.getByText('₹399')).toBeTruthy();
    expect(screen.getByText('Invoice')).toBeTruthy();
    expect(screen.getByText(/Couldn't refresh/)).toBeTruthy();
    expect(screen.queryByText(/Couldn't load your billing history/)).toBeNull();
  });

  it('keeps the Paywall list of past payments when a refetch fails', async () => {
    mockedBillingApi.history.mockResolvedValueOnce([entry()]).mockRejectedValueOnce(new Error('boom'));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    render(
      <QueryClientProvider client={queryClient}>
        <BillingHistorySection paymentProvider={null} hideWhenEmpty />
      </QueryClientProvider>
    );
    await screen.findByText('₹399');

    await act(async () => { await queryClient.refetchQueries({ queryKey: ['billing-history'] }); });
    await act(async () => { await new Promise((r) => setTimeout(r, 0)); });

    expect(queryClient.getQueryState(['billing-history'])?.status).toBe('error');
    expect(screen.getByText('₹399')).toBeTruthy();
    expect(screen.getByText('Invoice')).toBeTruthy();
  });

  it('shows a load failure rather than a false empty state', async () => {
    mockedBillingApi.history.mockRejectedValue(new Error('network'));
    renderSection();
    expect(await screen.findByText(/Couldn't load your billing history/)).toBeTruthy();
    expect(screen.queryByText(/No billing history yet/)).toBeNull();
  });
});
