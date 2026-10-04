import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import PlatformAnalytics from './PlatformAnalytics';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminPlatformAnalyticsApi } from '../api/endpoints';

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

const NO_ANSWERS = {
  answered: 0,
  notAnswered: 0,
  methods: ['NOT_TRACKED', 'IN_MY_HEAD', 'PAPER', 'SPREADSHEET', 'EXPENSE_APP', 'BANK_APP', 'OTHER']
    .map((method) => ({ method, count: 0 })),
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <PlatformAnalytics />
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

describe('PlatformAnalytics', () => {
  beforeEach(() => {
    vi.mocked(useAdminAuth).mockReset();
    vi.mocked(adminPlatformAnalyticsApi.get).mockReset();
    vi.mocked(adminPlatformAnalyticsApi.spendingTracking).mockReset().mockResolvedValue(NO_ANSWERS);
  });

  it('shows how users tracked spending before Fynora, each answer as a share of all answers', async () => {
    mockAuth(['PLATFORM_ANALYTICS_VIEW']);
    vi.mocked(adminPlatformAnalyticsApi.get).mockResolvedValue({ topCategories: [], topMerchants: [] });
    vi.mocked(adminPlatformAnalyticsApi.spendingTracking).mockResolvedValue({
      answered: 8,
      notAnswered: 5,
      methods: NO_ANSWERS.methods.map((m) =>
        m.method === 'SPREADSHEET' ? { ...m, count: 2 } : m.method === 'IN_MY_HEAD' ? { ...m, count: 6 } : m),
    });

    renderPage();

    await waitFor(() => expect(screen.getByText('Spreadsheet (Excel, Google Sheets)')).toBeInTheDocument());
    expect(screen.getByText('25.0%')).toBeInTheDocument();
    expect(screen.getByText('75.0%')).toBeInTheDocument();
    expect(screen.getByText(/8 answered, 5 not yet asked or not yet answered/)).toBeInTheDocument();
  });

  it('shows a dash, not a percentage, before anyone has answered', async () => {
    mockAuth(['PLATFORM_ANALYTICS_VIEW']);
    vi.mocked(adminPlatformAnalyticsApi.get).mockResolvedValue({ topCategories: [], topMerchants: [] });

    renderPage();

    await waitFor(() => expect(screen.getByText("Don't really track it")).toBeInTheDocument());
    expect(screen.getAllByText('—')).toHaveLength(7);
  });

  it('shows an access-denied message when the account lacks PLATFORM_ANALYTICS_VIEW', () => {
    mockAuth([]);
    vi.mocked(adminPlatformAnalyticsApi.get).mockResolvedValue({ topCategories: [], topMerchants: [] });

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
  });

  it('renders top categories and top merchants for an account with PLATFORM_ANALYTICS_VIEW', async () => {
    mockAuth(['PLATFORM_ANALYTICS_VIEW']);
    vi.mocked(adminPlatformAnalyticsApi.get).mockResolvedValue({
      topCategories: [{ categoryName: 'Groceries', totalSpend: 250, transactionCount: 12 }],
      topMerchants: [{ merchantName: 'Amazon', totalSpend: 75.5, transactionCount: 3 }],
    });

    renderPage();

    await waitFor(() => expect(screen.getByText('Groceries')).toBeInTheDocument());
    // formatCurrency always renders exactly two decimal places, so a whole-number spend still
    // shows "250.00", not "250" -- and now carries the rupee symbol and an explicitly pinned
    // 'en-IN' locale, like every other money formatter in both apps. It used to omit the symbol
    // and pass undefined as the locale, so an unlabelled figure sat next to an unlabelled
    // transaction count and grouped according to the visiting admin's own OS locale.
    expect(screen.getByText('₹250.00')).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();
    expect(screen.getByText('Amazon')).toBeInTheDocument();
    expect(screen.getByText('₹75.50')).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
  });

  it('shows the empty messages when the platform has no spend recorded yet', async () => {
    mockAuth(['PLATFORM_ANALYTICS_VIEW']);
    vi.mocked(adminPlatformAnalyticsApi.get).mockResolvedValue({ topCategories: [], topMerchants: [] });

    renderPage();

    await waitFor(() => expect(screen.getByText('No categorized spend recorded on the platform yet.')).toBeInTheDocument());
    expect(screen.getByText('No merchant spend recorded on the platform yet.')).toBeInTheDocument();
  });
});
