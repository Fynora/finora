import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { TransactionsSection } from './TransactionsSection';
import { adminTransactionsApi } from '../../api/endpoints';
import { useAdminAuth } from '../../context/AdminAuthContext';
import { mockAdminAuthState } from '../../test/mockAdminAuth';
import type { TransactionDto } from '../../types';

vi.mock('../../api/endpoints', () => ({
  adminTransactionsApi: { list: vi.fn(), delete: vi.fn() },
}));
vi.mock('../../context/AdminAuthContext', () => ({ useAdminAuth: vi.fn() }));

function renderSection() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <TransactionsSection userId="u1" />
    </QueryClientProvider>
  );
}

const TXN = {
  id: 't1', description: 'Grocery store', amount: 250, type: 'EXPENSE', date: '2026-11-01', categoryName: 'Groceries',
} as unknown as TransactionDto;

// Audit 2026-11-10: both failures used to look like success or like an empty ledger.
describe('TransactionsSection', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
      permissions: ['TRANSACTION_DELETE'], hasPermission: (p: string) => p === 'TRANSACTION_DELETE',
    }));
  });

  it('says the transactions could not be loaded instead of "No transactions on file"', async () => {
    vi.mocked(adminTransactionsApi.list).mockRejectedValue(new Error('Network Error'));
    renderSection();

    expect(await screen.findByRole('alert')).toHaveTextContent("Couldn't load this list");
    expect(screen.queryByText('No transactions on file for this user.')).not.toBeInTheDocument();
  });

  it('says why a delete was refused', async () => {
    vi.mocked(adminTransactionsApi.list).mockResolvedValue([TXN]);
    vi.mocked(adminTransactionsApi.delete).mockRejectedValue({ response: { data: { message: 'Transaction is locked' } } });
    renderSection();

    fireEvent.click(await screen.findByTitle('Delete'));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Transaction is locked');
  });
});
