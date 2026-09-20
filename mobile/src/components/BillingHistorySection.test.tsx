import { render, screen, waitFor, fireEvent } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
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

function renderSection(paymentProvider: string | null = 'RAZORPAY') {
  // gcTime: 0 -- see SubscriptionScreen.test.tsx's own comment on this exact line.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}><BillingHistorySection paymentProvider={paymentProvider} /></QueryClientProvider>
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

    await waitFor(() =>
      expect(mockedBillingApi.downloadInvoice).toHaveBeenCalledWith(
        'abcdef12-0000-0000-0000-000000000000', 'Fynora-invoice-ABCDEF12.pdf'
      )
    );
    // Back to a tappable Invoice link once the share sheet call settles.
    expect(await screen.findByText('Invoice')).toBeTruthy();
  });

  it('shows the failure inline and stays usable when the invoice cannot be fetched', async () => {
    mockedBillingApi.history.mockResolvedValue([entry()]);
    mockedBillingApi.downloadInvoice.mockRejectedValue(new Error('boom'));
    renderSection();

    fireEvent.press(await screen.findByText('Invoice'));

    expect(await screen.findByText(/Could not open this invoice/i)).toBeTruthy();
    expect(await screen.findByText('Invoice')).toBeTruthy();
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
    release();
    await waitFor(() => expect(screen.queryByText('Opening…')).toBeNull());
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

  it('shows a load failure rather than a false empty state', async () => {
    mockedBillingApi.history.mockRejectedValue(new Error('network'));
    renderSection();
    expect(await screen.findByText(/Couldn't load your billing history/)).toBeTruthy();
    expect(screen.queryByText(/No billing history yet/)).toBeNull();
  });
});
