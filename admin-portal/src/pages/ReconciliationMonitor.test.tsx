import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ReconciliationMonitor from './ReconciliationMonitor';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminReconciliationApi } from '../api/endpoints';

// AdminLayout now renders ThemeToggle (dark-mode support), which calls useTheme() --
// same reason adminSearchApi is stubbed below for GlobalSearch: a real ThemeProvider isn't
// mounted in these tests, so without this mock every AdminLayout-wrapped page throws before
// any assertion runs.
vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminReconciliationApi: { platformStats: vi.fn() },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ReconciliationMonitor />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

// See LearningEngine.test.tsx's mockAuth comment -- AdminLayout always renders Sidebar, which
// reads `permissions` off this same hook, so every mock here must supply it.
function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Support Admin',
    logout: vi.fn(),
  }));
}

describe('ReconciliationMonitor', () => {
  beforeEach(() => {
    vi.mocked(useAdminAuth).mockReset();
    vi.mocked(adminReconciliationApi.platformStats).mockReset();
  });

  it('shows an access-denied message when the account lacks RECONCILIATION_VIEW', () => {
    mockAuth([]);
    vi.mocked(adminReconciliationApi.platformStats).mockResolvedValue({
      okCount: 0, duplicateCount: 0, transferCount: 0, refundCount: 0, reversalCount: 0,
      investmentTransferCount: 0, supersededCount: 0, recurringCount: 0, totalTransactions: 0,
    });

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
  });

  it('renders the platform reconciliation breakdown for an account with RECONCILIATION_VIEW', async () => {
    mockAuth(['RECONCILIATION_VIEW']);
    // totalTransactions (985) is every status's own count summed, including the three the UI
    // used to have no card for at all -- REVERSAL/INVESTMENT_TRANSFER/SUPERSEDED (CodeQL
    // java/missing-case-in-switch, 2026-09-04). Deliberately not 954 (900+12+34+8): that was the
    // exact silent-undercount this fixes.
    vi.mocked(adminReconciliationApi.platformStats).mockResolvedValue({
      okCount: 900, duplicateCount: 12, transferCount: 34, refundCount: 8, reversalCount: 5,
      investmentTransferCount: 20, supersededCount: 6, recurringCount: 56, totalTransactions: 985,
    });

    renderPage();

    await waitFor(() => expect(screen.getByText('985')).toBeInTheDocument());
    expect(screen.getByText('900')).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();
    expect(screen.getByText('34')).toBeInTheDocument();
    expect(screen.getByText('8')).toBeInTheDocument();
    expect(screen.getByText('56')).toBeInTheDocument();
    expect(screen.getByText('5')).toBeInTheDocument();
    expect(screen.getByText('20')).toBeInTheDocument();
    expect(screen.getByText('6')).toBeInTheDocument();
  });

  // Audit 2026-11-10: Recurring is a separate flag on transactions of any status, so as a tenth
  // card in the "broken down by status" grid the cards added up to more than the total.
  it('keeps Recurring out of the status breakdown, which adds up to the total', async () => {
    mockAuth(['RECONCILIATION_VIEW']);
    vi.mocked(adminReconciliationApi.platformStats).mockResolvedValue({
      okCount: 900, duplicateCount: 12, transferCount: 34, refundCount: 8, reversalCount: 5,
      investmentTransferCount: 20, supersededCount: 6, recurringCount: 56, totalTransactions: 985,
    });

    renderPage();

    const note = await screen.findByText(/of these are\s+also marked recurring/);
    expect(note.closest('p')).toHaveTextContent('56 of these are also marked recurring');
    const breakdown = screen.getByRole('list', { name: 'Transactions by reconciliation status' });
    expect(within(breakdown).queryByText(/recurring/i)).not.toBeInTheDocument();
  });

  it('says the numbers could not be loaded instead of showing zeros', async () => {
    mockAuth(['RECONCILIATION_VIEW']);
    vi.mocked(adminReconciliationApi.platformStats).mockRejectedValue(new Error('Network Error'));

    renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent("Couldn't load");
    expect(screen.queryByText('Total transactions')).not.toBeInTheDocument();
  });
});
