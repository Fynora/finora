import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MerchantsSection } from './MerchantsSection';
import { adminUserMerchantsApi } from '../../api/endpoints';
import type { MerchantDto } from '../../types';

vi.mock('../../api/endpoints', () => ({
  adminUserMerchantsApi: { list: vi.fn(), merge: vi.fn(), update: vi.fn(), undo: vi.fn(), resetLearning: vi.fn() },
}));
vi.mock('../../context/AdminAuthContext', () => ({
  useAdminAuth: () => ({ hasPermission: () => true }),
}));
vi.mock('../../context/NotificationContext', () => ({
  useNotify: () => ({ success: vi.fn(), error: vi.fn() }),
}));

function merchant(i: number): MerchantDto {
  return { id: `m${i}`, canonicalName: `Merchant ${i}`, logoUrl: null, website: null,
    topCategory: null, topCategoryConfidence: null, distribution: [] };
}

function renderSection() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MerchantsSection userId="u1" />
    </QueryClientProvider>
  );
}

describe('MerchantsSection', () => {
  // Production, 2026-10-23: a user with 378 merchants rendered one merge picker per row, each listing
  // every other merchant -- 142,884 <option>s -- and the admin user page froze ("Page Unresponsive").
  it('builds no merge picker until a row asks for one, however many merchants there are', async () => {
    vi.mocked(adminUserMerchantsApi.list).mockResolvedValue(Array.from({ length: 400 }, (_, i) => merchant(i)));
    const { container } = renderSection();

    expect(await screen.findByText('Merchant 24')).toBeInTheDocument();
    expect(container.querySelectorAll('option')).toHaveLength(0);

    await userEvent.click(screen.getAllByRole('button', { name: 'Merge…' })[0]);

    // One picker, for that row only, listing every other merchant -- not only this page's.
    expect(screen.getAllByRole('combobox', { name: 'Merge from' })).toHaveLength(1);
    expect(container.querySelectorAll('option')).toHaveLength(400);
  });

  it('shows 25 merchants a page, and the next page shows the next 25', async () => {
    vi.mocked(adminUserMerchantsApi.list).mockResolvedValue(Array.from({ length: 60 }, (_, i) => merchant(i)));
    const user = userEvent.setup();
    renderSection();

    expect(await screen.findByText('Merchant 0')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Merge…' })).toHaveLength(25);
    expect(screen.queryByText('Merchant 25')).not.toBeInTheDocument();
    expect(screen.getByText('Showing 1–25 of 60')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Next page' }));
    expect(screen.getByText('Merchant 25')).toBeInTheDocument();
    expect(screen.queryByText('Merchant 24')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next page' }));
    expect(screen.getByText('Showing 51–60 of 60')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Merge…' })).toHaveLength(10);
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled();
  });

  it('still merges the chosen merchant into the row, and Cancel closes the picker', async () => {
    vi.mocked(adminUserMerchantsApi.list).mockResolvedValue([merchant(1), merchant(2), merchant(3)]);
    vi.mocked(adminUserMerchantsApi.merge).mockResolvedValue({} as never);
    const user = userEvent.setup();
    renderSection();

    await user.click((await screen.findAllByRole('button', { name: 'Merge…' }))[1]);
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('combobox', { name: 'Merge from' })).not.toBeInTheDocument();

    await user.click(screen.getAllByRole('button', { name: 'Merge…' })[0]);
    const picker = screen.getByRole('combobox', { name: 'Merge from' });
    expect(within(picker).getAllByRole('option').map((o) => o.textContent))
      .toEqual(['Merge from…', 'Merchant 2', 'Merchant 3']);
    await user.selectOptions(picker, 'm3');
    await user.click(screen.getByRole('button', { name: 'Merge' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Merge' }));

    expect(adminUserMerchantsApi.merge).toHaveBeenCalledWith('u1', 'm1', { mergeFromMerchantId: 'm3' });
  });

  it('offers no merge for a user with a single merchant', async () => {
    vi.mocked(adminUserMerchantsApi.list).mockResolvedValue([merchant(1)]);
    renderSection();

    expect(await screen.findByText('Merchant 1')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Merge…' })).not.toBeInTheDocument();
  });
});
