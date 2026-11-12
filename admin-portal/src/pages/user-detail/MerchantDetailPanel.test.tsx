import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MerchantDetailPanel } from './MerchantDetailPanel';
import { MerchantsSection } from './MerchantsSection';
import { adminUserCategoriesApi, adminUserMerchantsApi } from '../../api/endpoints';
import type { MerchantDto, TransactionDto } from '../../types';

vi.mock('../../api/endpoints', () => ({
  adminUserMerchantsApi: {
    list: vi.fn(), merge: vi.fn(), update: vi.fn(), undo: vi.fn(), resetLearning: vi.fn(),
    get: vi.fn(), audit: vi.fn(), transactions: vi.fn(), confirmCategory: vi.fn(),
  },
  adminUserCategoriesApi: { list: vi.fn(), aiCreated: vi.fn() },
}));
vi.mock('../../context/AdminAuthContext', () => ({
  useAdminAuth: () => ({ hasPermission: () => true }),
}));
const notify = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }));
vi.mock('../../context/NotificationContext', () => ({ useNotify: () => notify }));

const SWIGGY: MerchantDto = {
  id: 'm1', canonicalName: 'Swiggy', logoUrl: null, website: null, topCategory: 'Food', topCategoryConfidence: 80,
  distribution: [{ category: 'Food', confirmationCount: 4, confidence: 80 }, { category: 'Shopping', confirmationCount: 1, confidence: 20 }],
};

function txn(i: number, overrides: Partial<TransactionDto> = {}): TransactionDto {
  return {
    id: `t${i}`, accountId: 'a1', categoryId: 'c-shop', categoryName: 'Shopping', date: `2026-10-${String((i % 28) + 1).padStart(2, '0')}`,
    description: `SWIGGY ORDER ${i}`, merchant: 'Swiggy', paymentMethod: null, amount: 250 + i, type: 'EXPENSE', tags: [],
    notes: null, reconciliationStatus: 'OK', recurring: false, needsCategoryReview: false, categoryManuallySet: false,
    ...overrides,
  } as TransactionDto;
}

function renderPanel(canManage = true) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MerchantDetailPanel userId="u1" merchantId="m1" merchantName="Swiggy" canManage={canManage} />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(adminUserMerchantsApi.get).mockResolvedValue(SWIGGY);
  vi.mocked(adminUserMerchantsApi.audit).mockResolvedValue([
    { action: 'CORRECTED', previousCategory: 'Shopping', newCategory: 'Food', createdAt: '2026-10-01T06:30:00Z' },
  ]);
  vi.mocked(adminUserMerchantsApi.transactions).mockResolvedValue([txn(1, { needsCategoryReview: true })]);
  vi.mocked(adminUserCategoriesApi.list).mockResolvedValue([
    { id: 'c-food', name: 'Food', isSystem: true, icon: 'utensils', color: 'orange', aiCreationReason: null },
    { id: 'c-shop', name: 'Shopping', isSystem: true, icon: 'bag', color: 'blue', aiCreationReason: null },
  ]);
});

describe('MerchantDetailPanel', () => {
  it('opens from the merchant row and shows what was learned, the history, and the transactions', async () => {
    vi.mocked(adminUserMerchantsApi.list).mockResolvedValue([SWIGGY]);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={queryClient}><MerchantsSection userId="u1" /></QueryClientProvider>);

    await userEvent.click(await screen.findByRole('button', { name: 'Show details for Swiggy' }));
    const panel = await screen.findByTestId('merchant-detail-m1');

    expect(await within(panel).findByText('Shopping → Food')).toBeInTheDocument();
    expect(within(panel).getByText('80%')).toBeInTheDocument();
    expect(within(panel).getByText('01 Oct 2026, 12:00 IST')).toBeInTheDocument();
    expect(await within(panel).findByText('SWIGGY ORDER 1')).toBeInTheDocument();
    expect(within(panel).getByText(/needs review/)).toBeInTheDocument();
    expect(within(panel).getByText('−₹251.00')).toBeInTheDocument();
  });

  it('sets a transaction\'s category only after confirming, and says what it changes', async () => {
    vi.mocked(adminUserMerchantsApi.confirmCategory).mockResolvedValue(SWIGGY);
    const user = userEvent.setup();
    renderPanel();

    const select = await screen.findByRole('combobox', { name: 'Category for SWIGGY ORDER 1' });
    // The transaction's current category is not offered as a change.
    expect(within(select).queryByRole('option', { name: 'Shopping' })).not.toBeInTheDocument();
    await user.selectOptions(select, 'c-food');
    await user.click(screen.getByRole('button', { name: 'Apply' }));

    expect(adminUserMerchantsApi.confirmCategory).not.toHaveBeenCalled();
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('Set this transaction to Food?');
    expect(dialog).toHaveTextContent("changes in the customer's account");
    expect(dialog).toHaveTextContent('Swiggy learns Food');
    await user.click(within(dialog).getByRole('button', { name: 'Set category' }));

    await waitFor(() => expect(adminUserMerchantsApi.confirmCategory)
      .toHaveBeenCalledWith('u1', 'm1', { categoryId: 'c-food', applyToTransactionId: 't1' }));
    expect(notify.success).toHaveBeenCalledWith('Category set.');
  });

  it('says why when setting the category is refused', async () => {
    vi.mocked(adminUserMerchantsApi.confirmCategory).mockRejectedValue({
      response: { data: { message: "This transaction isn't resolved to the given merchant" } },
    });
    const user = userEvent.setup();
    renderPanel();

    await user.selectOptions(await screen.findByRole('combobox', { name: 'Category for SWIGGY ORDER 1' }), 'c-food');
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Set category' }));

    await waitFor(() => expect(notify.error).toHaveBeenCalledWith("This transaction isn't resolved to the given merchant"));
  });

  it('offers no category controls, and fetches no categories, without permission to manage', async () => {
    renderPanel(false);

    expect(await screen.findByText('SWIGGY ORDER 1')).toBeInTheDocument();
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    expect(adminUserCategoriesApi.list).not.toHaveBeenCalled();
  });

  it('shows the first 20 transactions, and all of them on request', async () => {
    vi.mocked(adminUserMerchantsApi.transactions).mockResolvedValue(Array.from({ length: 23 }, (_, i) => txn(i)));
    const user = userEvent.setup();
    renderPanel(false);

    expect(await screen.findByText('Transactions (23)')).toBeInTheDocument();
    expect(screen.getAllByText(/^SWIGGY ORDER/)).toHaveLength(20);
    await user.click(screen.getByRole('button', { name: 'Show all 23' }));
    expect(screen.getAllByText(/^SWIGGY ORDER/)).toHaveLength(23);
  });

  it('says a section could not be loaded instead of showing it empty', async () => {
    vi.mocked(adminUserMerchantsApi.transactions).mockRejectedValue(new Error('Network Error'));
    renderPanel(false);

    expect(await screen.findByText("Couldn't load this merchant's transactions. Please try again.")).toBeInTheDocument();
    expect(screen.queryByText('No transactions are attributed to this merchant.')).not.toBeInTheDocument();
  });
});
