import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import PlatformAnalytics from './PlatformAnalytics';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminPlatformAnalyticsApi } from '../api/endpoints';
import { periodChoices } from '../lib/platformPeriod';
import type { PlatformAnalyticsDto } from '../types';

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
  adminPlatformAnalyticsApi: { get: vi.fn(), spendingTracking: vi.fn() },
}));

function month(over: Partial<PlatformAnalyticsDto> = {}): PlatformAnalyticsDto {
  return {
    from: '2026-10-01',
    to: '2026-10-31',
    previousFrom: '2026-09-01',
    previousTo: '2026-09-30',
    minCustomers: 3,
    summary: { customersWithSpend: 41, totalSpend: 1000000, medianSpendPerCustomer: 38400, toSortSpend: 140000, transactionCount: 3912 },
    previousSummary: { customersWithSpend: 35, totalSpend: 900000, medianSpendPerCustomer: 30000, toSortSpend: 99000, transactionCount: 3000 },
    topCategories: [
      { name: 'Food and dining', customers: 37, totalSpend: 210000, medianSpendPerCustomer: 4800.4, transactionCount: 900, previousMedianSpendPerCustomer: 4400 },
      { name: 'Rent', customers: 3, totalSpend: 90000, medianSpendPerCustomer: 30000, transactionCount: 3, previousMedianSpendPerCustomer: null },
    ],
    topMerchants: [
      { name: 'Swiggy', customers: 22, totalSpend: 71000, medianSpendPerCustomer: 2600, transactionCount: 300, previousMedianSpendPerCustomer: 2860 },
    ],
    ...over,
  };
}

const ALL_TIME = month({
  from: null, to: null, previousFrom: null, previousTo: null, previousSummary: null,
  topCategories: [{ name: 'Groceries', customers: 50, totalSpend: 5000000, medianSpendPerCustomer: 60000, transactionCount: 9000, previousMedianSpendPerCustomer: null }],
  topMerchants: [],
});

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <PlatformAnalytics />
      </MemoryRouter>
    </QueryClientProvider>
  );
  return queryClient;
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

function rowOf(name: string): string[] {
  const row = screen.getByText(name).closest('tr') as HTMLTableRowElement;
  return within(row).getAllByRole('cell').map((c) => c.textContent ?? '');
}

describe('PlatformAnalytics', () => {
  beforeEach(() => {
    vi.mocked(useAdminAuth).mockReset();
    vi.mocked(adminPlatformAnalyticsApi.get).mockReset().mockImplementation(async (period) => (period ? month() : ALL_TIME));
  });

  it('shows an access-denied message when the account lacks PLATFORM_ANALYTICS_VIEW', () => {
    mockAuth([]);
    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
    expect(adminPlatformAnalyticsApi.get).not.toHaveBeenCalled();
  });

  it('asks for the last full month first and compares it with the month before', async () => {
    mockAuth(['PLATFORM_ANALYTICS_VIEW']);
    renderPage();

    await screen.findByText('Food and dining');
    expect(adminPlatformAnalyticsApi.get).toHaveBeenCalledWith(periodChoices(new Date())[0].period);
    expect(screen.getByText('October 2026, compared with September 2026.')).toBeInTheDocument();
  });

  it('shows customers, spend per customer, the share still to sort and transactions, with changes', async () => {
    mockAuth(['PLATFORM_ANALYTICS_VIEW']);
    renderPage();

    await screen.findByText('41');
    expect(screen.getByText('+6 vs September 2026')).toBeInTheDocument();
    expect(screen.getByText('₹38,400')).toBeInTheDocument();
    // 140,000 of 10,00,000 is 14%; September's 99,000 of 9,00,000 is 11%.
    expect(screen.getByText('14%')).toBeInTheDocument();
    expect(screen.getByText('+3 pts vs September 2026')).toBeInTheDocument();
    expect(screen.getByText('3,912')).toBeInTheDocument();
  });

  it('lists categories and merchants with customers, whole-rupee spend and the change per customer', async () => {
    mockAuth(['PLATFORM_ANALYTICS_VIEW']);
    renderPage();

    await screen.findByText('Food and dining');
    expect(rowOf('Food and dining')).toEqual(['Food and dining', '37', '₹2,10,000', '₹4,800', '900', '+9%']);
    // No comparable figure for September: a dash, not a made-up change.
    expect(rowOf('Rent')).toEqual(['Rent', '3', '₹90,000', '₹30,000', '3', '—']);
    expect(rowOf('Swiggy')).toEqual(['Swiggy', '22', '₹71,000', '₹2,600', '300', '−9%']);
    expect(screen.getAllByRole('columnheader', { name: 'Per customer vs September 2026' })).toHaveLength(2);
  });

  it('switches to all time: no comparison column and no changes', async () => {
    mockAuth(['PLATFORM_ANALYTICS_VIEW']);
    renderPage();
    await screen.findByText('Food and dining');

    fireEvent.change(screen.getByLabelText('Period'), { target: { value: 'all' } });

    await screen.findByText('Groceries');
    expect(adminPlatformAnalyticsApi.get).toHaveBeenLastCalledWith(undefined);
    expect(screen.getByText('All time has nothing to compare with.')).toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: /Per customer vs/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/vs September 2026/)).not.toBeInTheDocument();
  });

  it('asks for three months when Last 3 full months is chosen', async () => {
    mockAuth(['PLATFORM_ANALYTICS_VIEW']);
    renderPage();
    await screen.findByText('Food and dining');

    fireEvent.change(screen.getByLabelText('Period'), { target: { value: 'last3' } });

    await waitFor(() => expect(adminPlatformAnalyticsApi.get).toHaveBeenLastCalledWith(
      periodChoices(new Date()).find((c) => c.key === 'last3')?.period));
  });

  it('hides the money figures and says why when fewer than three customers spent', async () => {
    mockAuth(['PLATFORM_ANALYTICS_VIEW']);
    vi.mocked(adminPlatformAnalyticsApi.get).mockResolvedValue(month({
      summary: { customersWithSpend: 2, totalSpend: null, medianSpendPerCustomer: null, toSortSpend: null, transactionCount: 5 },
      previousSummary: { customersWithSpend: 1, totalSpend: null, medianSpendPerCustomer: null, toSortSpend: null, transactionCount: 1 },
      topCategories: [],
      topMerchants: [],
    }));
    renderPage();

    await screen.findByText(/Money figures are hidden because fewer than 3 customers spent in this period/);
    expect(screen.getByText('2')).toBeInTheDocument();
    expect(screen.queryByText(/pts vs/)).not.toBeInTheDocument();
    expect(screen.getByText('No category had spend from 3 or more customers in this period.')).toBeInTheDocument();
    expect(screen.getByText('No merchant had spend from 3 or more customers in this period.')).toBeInTheDocument();
  });

  it('keeps the figures on screen when a later refresh fails', async () => {
    mockAuth(['PLATFORM_ANALYTICS_VIEW']);
    const queryClient = renderPage();
    await screen.findByText('Food and dining');

    vi.mocked(adminPlatformAnalyticsApi.get).mockRejectedValue(new Error('offline'));
    await queryClient.refetchQueries();

    await waitFor(() => expect(queryClient.getQueryState(['admin-platform-analytics', periodChoices(new Date())[0].key])?.status).toBe('error'));
    expect(screen.getByText('Food and dining')).toBeInTheDocument();
    expect(screen.getByText('Swiggy')).toBeInTheDocument();
  });

  it('says a first load failed instead of showing an empty page', async () => {
    mockAuth(['PLATFORM_ANALYTICS_VIEW']);
    vi.mocked(adminPlatformAnalyticsApi.get).mockRejectedValue(new Error('offline'));
    renderPage();

    expect(await screen.findAllByRole('alert')).toHaveLength(2);
    expect(screen.queryByText('No category had spend from 3 or more customers in this period.')).not.toBeInTheDocument();
  });

  it('points to Activation for the setup answers, which no longer load here', async () => {
    mockAuth(['PLATFORM_ANALYTICS_VIEW']);
    renderPage();

    await screen.findByText('Food and dining');
    // The sidebar has its own Activation link; this is the one in the page's note.
    const note = screen.getByText(/How users tracked spending before Fynora is now on/);
    expect(within(note).getByRole('link', { name: 'Activation' })).toHaveAttribute('href', '/analytics/activation');
    expect(adminPlatformAnalyticsApi.spendingTracking).not.toHaveBeenCalled();
  });
});
