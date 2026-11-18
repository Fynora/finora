import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import LearningEngine from './LearningEngine';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminLearningApi } from '../api/endpoints';
import { categoryChange, correctionRate, formatPercent, rateDelta } from './LearningEngine';
import type { LearningEventRow, LearningPlatformStatsDto, PagedResponse } from '../types';

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
  adminLearningApi: { platformStats: vi.fn(), events: vi.fn() },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <LearningEngine />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

// AdminLayout always renders Sidebar regardless of this page's own permission gate, and Sidebar
// reads `permissions` off the same useAdminAuth() hook -- every mock below must supply it (see
// the bug fix on MerchantIntelligence.test.tsx, which used to omit it and crash on render).
function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Support Admin',
    logout: vi.fn(),
  }));
}

// Every value below is made up.
function stats(overrides: Partial<LearningPlatformStatsDto> = {}): LearningPlatformStatsDto {
  return {
    learnedMerchantPairs: 1204,
    totalConfirmations: 2211,
    correctedCount: 29,
    resetCount: 3,
    undoneCount: 7,
    usersWithLearning: 41,
    medianPairsPerUser: 6.5,
    last30Days: { firstTime: 400, kept: 98, corrected: 2 },
    previous30Days: { firstTime: 300, kept: 92, corrected: 8 },
    trend: [
      { month: '2026-09', firstTime: 200, kept: 276, corrected: 24, undone: 1, reset: 2 },
      { month: '2026-10', firstTime: 90, kept: 1898, corrected: 5, undone: 6, reset: 1 },
      { month: '2026-11', firstTime: 3, kept: 8, corrected: 0, undone: 0, reset: 0 },
      { month: '2026-12', firstTime: 12, kept: 0, corrected: 0, undone: 0, reset: 0 },
    ],
    categorySources: [
      { source: 'LEARNED_PATTERN', transactions: 380 },
      { source: 'GLOBAL_RULE', transactions: 220 },
      { source: 'MANUAL', transactions: 90 },
      { source: 'SOMETHING_NEW', transactions: 310 },
    ],
    topCorrectedMerchants: [
      { merchant: 'Merchant Alpha', corrections: 6, users: 3, usualFrom: 'Food and dining', usualTo: 'Groceries' },
      { merchant: 'Merchant Beta', corrections: 1, users: 1, usualFrom: null, usualTo: null },
    ],
    topCategoryChanges: [{ fromCategory: 'Shopping', toCategory: 'Bills and utilities', count: 5 }],
    ...overrides,
  };
}

function eventsPage(content: LearningEventRow[], totalElements = content.length): PagedResponse<LearningEventRow> {
  return { content, page: 0, size: 25, totalElements, totalPages: Math.max(1, Math.ceil(totalElements / 25)) };
}

const corrected: LearningEventRow = {
  id: 'e1', createdAt: '2026-11-17T10:00:00Z', userId: 'u-1', userEmail: 'customer-one@example.com',
  action: 'CORRECTED', merchant: 'Merchant Alpha', fromCategory: 'Food and dining', toCategory: 'Groceries',
};
const reset: LearningEventRow = {
  id: 'e2', createdAt: '2026-11-16T10:00:00Z', userId: 'u-2', userEmail: 'customer-two@example.com',
  action: 'RESET', merchant: 'Merchant Beta', fromCategory: 'Travel', toCategory: null,
};

describe('correctionRate, formatPercent and rateDelta', () => {
  const w = (kept: number, corrected: number) => ({ firstTime: 500, kept, corrected });

  it('is corrections out of the confirmations where the engine already had a category', () => {
    expect(correctionRate({ kept: 3, corrected: 1 })).toBe(0.25);
    expect(correctionRate({ kept: 0, corrected: 2 })).toBe(1);
  });

  it('has no value when every confirmation was a merchant new to the engine', () => {
    expect(correctionRate(w(0, 0))).toBeNull();
  });

  it('is not moved by how many merchants were new', () => {
    const few = { ...w(9, 1), firstTime: 0 };
    const many = { ...w(9, 1), firstTime: 100000 };
    expect(correctionRate(few)).toBe(correctionRate(many));
  });

  it('shows one decimal, and never rounds a real rate to 0.0% or 100.0%', () => {
    expect(formatPercent(0.25)).toBe('25.0%');
    expect(formatPercent(0)).toBe('0.0%');
    expect(formatPercent(1)).toBe('100.0%');
    expect(formatPercent(5 / 1903)).toBe('0.3%');
    expect(formatPercent(1 / 5000)).toBe('<0.1%');
    expect(formatPercent(0.0005)).toBe('0.1%');
    expect(formatPercent(4999 / 5000)).toBe('>99.9%');
    expect(formatPercent(null)).toBe('—');
  });

  it('says which way the rate moved from what, in words', () => {
    expect(rateDelta(w(98, 2), w(92, 8))).toEqual({
      direction: 'down', label: 'Down from 8.0% in the 30 days before', isGood: true,
    });
    expect(rateDelta(w(92, 8), w(98, 2))).toEqual({
      direction: 'up', label: 'Up from 2.0% in the 30 days before', isGood: false,
    });
  });

  it('calls two rates that read the same the same, and compares nothing without a rate', () => {
    expect(rateDelta(w(9999, 1000), w(9998, 1000))?.direction).toBe('flat');
    expect(rateDelta(w(0, 0), w(9, 1))).toBeUndefined();
    expect(rateDelta(w(9, 1), w(0, 0))).toBeUndefined();
  });
});

describe('categoryChange', () => {
  const base = { ...corrected };
  it('names only the confirmed category for a learned event, even when it was already the top one', () => {
    expect(categoryChange({ ...base, action: 'LEARNED', fromCategory: 'Groceries', toCategory: 'Groceries' })).toBe('Groceries');
    expect(categoryChange({ ...base, action: 'LEARNED', fromCategory: null, toCategory: 'Fuel' })).toBe('Fuel');
  });
  it('shows a correction as a change, with a dash for a category deleted since', () => {
    expect(categoryChange(base)).toBe('Food and dining → Groceries');
    expect(categoryChange({ ...base, fromCategory: null })).toBe('— → Groceries');
  });
  it('names the category an undo or reset took away', () => {
    expect(categoryChange({ ...base, action: 'RESET', toCategory: null })).toBe('Cleared Food and dining');
    expect(categoryChange({ ...base, action: 'UNDONE', toCategory: null })).toBe('Undid Food and dining');
    expect(categoryChange({ ...base, action: 'UNDONE', fromCategory: null, toCategory: null })).toBe('—');
    expect(categoryChange({ ...base, action: 'MERGED', fromCategory: null, toCategory: null })).toBe('—');
  });
});

describe('LearningEngine', () => {
  beforeEach(() => {
    vi.mocked(useAdminAuth).mockReset();
    vi.mocked(adminLearningApi.platformStats).mockReset();
    vi.mocked(adminLearningApi.events).mockReset();
    vi.mocked(adminLearningApi.events).mockResolvedValue(eventsPage([]));
  });

  it('shows an access-denied message when the account lacks MERCHANT_MANAGE', () => {
    mockAuth([]);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue(stats());

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
    expect(adminLearningApi.events).not.toHaveBeenCalled();
  });

  it('shows the 30-day correction rate against the 30 days before, as an improvement', async () => {
    mockAuth(['MERCHANT_MANAGE', 'USER_VIEW']);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue(stats());

    renderPage();

    await waitFor(() => expect(screen.getByText('2.0%')).toBeInTheDocument());
    const delta = screen.getByText('Down from 8.0% in the 30 days before');
    expect(delta.closest('p')).toHaveClass('text-success');
  });

  it('marks a rising correction rate as bad', async () => {
    mockAuth(['MERCHANT_MANAGE', 'USER_VIEW']);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue(stats({
      last30Days: { firstTime: 5, kept: 90, corrected: 10 }, previous30Days: { firstTime: 5, kept: 99, corrected: 1 },
    }));

    renderPage();

    await waitFor(() => expect(screen.getByText('10.0%')).toBeInTheDocument());
    expect(screen.getByText('Up from 1.0% in the 30 days before').closest('p')).toHaveClass('text-danger');
  });

  it('says there is nothing to rate when the last 30 days had only first-time confirmations', async () => {
    mockAuth(['MERCHANT_MANAGE', 'USER_VIEW']);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue(stats({
      last30Days: { firstTime: 40, kept: 0, corrected: 0 },
    }));

    renderPage();

    await waitFor(() => expect(screen.getByText('No repeat confirmations in the last 30 days')).toBeInTheDocument());
    expect(screen.queryByText(/from .* in the 30 days before/)).not.toBeInTheDocument();
  });

  it('says why there is no comparison when the 30 days before had nothing to rate', async () => {
    mockAuth(['MERCHANT_MANAGE', 'USER_VIEW']);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue(stats({
      previous30Days: { firstTime: 40, kept: 0, corrected: 0 },
    }));

    renderPage();

    await waitFor(() => expect(screen.getByText('No repeat confirmations in the 30 days before')).toBeInTheDocument());
  });

  it('shows the all-time tiles, customers with learning and the share sorted by learning', async () => {
    mockAuth(['MERCHANT_MANAGE', 'USER_VIEW']);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue(stats());

    renderPage();

    await waitFor(() => expect(screen.getByText('1,204')).toBeInTheDocument());
    expect(screen.getByText('2,211')).toBeInTheDocument();
    expect(screen.getByText('29')).toBeInTheDocument();
    expect(screen.getByText('41')).toBeInTheDocument();
    expect(screen.getByText('median 6.5 pairs each')).toBeInTheDocument();
    // 380 of 1,000 transactions added.
    expect(screen.getAllByText('38.0%').length).toBeGreaterThan(0);
  });

  it('draws the monthly charts and lists every month with its rate, newest first', async () => {
    mockAuth(['MERCHANT_MANAGE', 'USER_VIEW']);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue(stats());

    renderPage();

    await waitFor(() => expect(screen.getByTestId('learning-monthly-chart')).toBeInTheDocument());
    expect(screen.getByTestId('learning-rate-chart')).toBeInTheDocument();
    const rows = screen.getAllByRole('row').slice(1).filter((r) => /^2026-/.test(r.textContent ?? ''));
    expect(rows.map((r) => within(r).getAllByRole('cell')[0].textContent)).toEqual(['2026-12', '2026-11', '2026-10', '2026-09']);
    expect(within(rows[3]).getByText('8.0%')).toBeInTheDocument();
    expect(within(rows[2]).getByText('1,898')).toBeInTheDocument();
    // A month of first-time confirmations only has no rate, not a 0% that reads as perfect.
    expect(within(rows[0]).getAllByRole('cell')[4]).toHaveTextContent('—');
  });

  it('names each category source, keeps an unknown one as itself, and gives each its share', async () => {
    mockAuth(['MERCHANT_MANAGE', 'USER_VIEW']);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue(stats());

    renderPage();

    const list = await screen.findByRole('list', { name: 'How new transactions got their category' });
    expect(within(list).getByText('Learned')).toBeInTheDocument();
    expect(within(list).getByText('Global rule')).toBeInTheDocument();
    expect(within(list).getByText('Set or changed by hand')).toBeInTheDocument();
    expect(within(list).getByText('SOMETHING_NEW')).toBeInTheDocument();
    expect(within(list).getByText('22.0% · 220')).toBeInTheDocument();
  });

  it('says so when no transactions were added in the last 30 days', async () => {
    mockAuth(['MERCHANT_MANAGE', 'USER_VIEW']);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue(stats({ categorySources: [] }));

    renderPage();

    await waitFor(() => expect(screen.getByText('No customer transactions were added in the last 30 days.')).toBeInTheDocument());
  });

  it('lists the most corrected merchants with their usual change, and the common category changes', async () => {
    mockAuth(['MERCHANT_MANAGE', 'USER_VIEW']);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue(stats());

    renderPage();

    const merchants = await screen.findByRole('list', { name: 'Most corrected merchants' });
    expect(within(merchants).getByText('Merchant Alpha')).toBeInTheDocument();
    expect(within(merchants).getByText('6 corrections · 3 users')).toBeInTheDocument();
    expect(within(merchants).getByText('Usually Food and dining → Groceries')).toBeInTheDocument();
    expect(within(merchants).getByText('1 correction · 1 user')).toBeInTheDocument();
    expect(within(merchants).getAllByText(/^Usually/)).toHaveLength(1);
    const changes = screen.getByRole('list', { name: 'Most common category changes' });
    expect(within(changes).getByText('Shopping → Bills and utilities')).toBeInTheDocument();
  });

  it('shows empty messages instead of charts when customers have no learning yet', async () => {
    mockAuth(['MERCHANT_MANAGE', 'USER_VIEW']);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue(stats({
      trend: [], topCorrectedMerchants: [], topCategoryChanges: [], usersWithLearning: 0,
    }));

    renderPage();

    await waitFor(() => expect(screen.getByText('No learning activity from customers yet.')).toBeInTheDocument());
    expect(screen.queryByTestId('learning-monthly-chart')).not.toBeInTheDocument();
    expect(screen.getAllByText('No corrections yet.')).toHaveLength(2);
    expect(screen.queryByText(/pairs each/)).not.toBeInTheDocument();
  });

  it('lists recent events with a link to each customer and their category change', async () => {
    mockAuth(['MERCHANT_MANAGE', 'USER_VIEW']);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue(stats());
    vi.mocked(adminLearningApi.events).mockResolvedValue(eventsPage([corrected, reset]));

    renderPage();

    const link = await screen.findByRole('link', { name: 'customer-one@example.com' });
    expect(link).toHaveAttribute('href', '/users/u-1');
    const first = link.closest('tr')!;
    expect(within(first).getByText('Corrected')).toBeInTheDocument();
    expect(within(first).getByText('Merchant Alpha')).toBeInTheDocument();
    expect(within(first).getByText('Food and dining → Groceries')).toBeInTheDocument();
    const second = screen.getByRole('link', { name: 'customer-two@example.com' }).closest('tr')!;
    expect(within(second).getByText('Reset')).toBeInTheDocument();
    expect(within(second).getByText('Cleared Travel')).toBeInTheDocument();
    expect(adminLearningApi.events).toHaveBeenCalledWith({ page: 0, size: 25 });
  });

  it('asks for the next page of events', async () => {
    mockAuth(['MERCHANT_MANAGE', 'USER_VIEW']);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue(stats());
    vi.mocked(adminLearningApi.events).mockResolvedValue(eventsPage([corrected], 60));

    renderPage();

    await screen.findByRole('link', { name: 'customer-one@example.com' });
    await userEvent.click(screen.getByRole('button', { name: /next/i }));
    await waitFor(() => expect(adminLearningApi.events).toHaveBeenCalledWith({ page: 1, size: 25 }));
  });

  it('keeps the paging controls when a page of events fails, so the admin can move off it', async () => {
    mockAuth(['MERCHANT_MANAGE', 'USER_VIEW']);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue(stats());
    vi.mocked(adminLearningApi.events).mockImplementation(({ page }) => (
      page === 1 ? Promise.reject(new Error('boom')) : Promise.resolve(eventsPage([corrected], 60))
    ));

    renderPage();

    await screen.findByRole('link', { name: 'customer-one@example.com' });
    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));
    await waitFor(() => expect(screen.queryByRole('link', { name: 'customer-one@example.com' })).not.toBeInTheDocument());
    await userEvent.click(await screen.findByRole('button', { name: 'Previous page' }));
    expect(await screen.findByRole('link', { name: 'customer-one@example.com' })).toBeInTheDocument();
  });

  it('moves back to the last page when the page being read no longer exists', async () => {
    mockAuth(['MERCHANT_MANAGE', 'USER_VIEW']);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue(stats());
    vi.mocked(adminLearningApi.events).mockImplementation(({ page }) => Promise.resolve(
      page === 0 ? eventsPage([corrected], 60) : { ...eventsPage([], 20), totalPages: 1 },
    ));

    renderPage();

    await screen.findByRole('link', { name: 'customer-one@example.com' });
    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));

    await waitFor(() => expect(adminLearningApi.events).toHaveBeenCalledWith({ page: 1, size: 25 }));
    expect(await screen.findByRole('link', { name: 'customer-one@example.com' })).toBeInTheDocument();
  });

  it('does not ask for customer emails without permission to view users', async () => {
    mockAuth(['MERCHANT_MANAGE']);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue(stats());

    renderPage();

    expect(await screen.findByText('1,204')).toBeInTheDocument();
    expect(screen.getByText(/needs permission to view users/)).toBeInTheDocument();
    expect(adminLearningApi.events).not.toHaveBeenCalled();
  });

  it('keeps the events list when the figures fail to load, and says the figures failed', async () => {
    mockAuth(['MERCHANT_MANAGE', 'USER_VIEW']);
    vi.mocked(adminLearningApi.platformStats).mockRejectedValue(new Error('boom'));
    vi.mocked(adminLearningApi.events).mockResolvedValue(eventsPage([corrected]));

    renderPage();

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent("Couldn't load the learning figures"));
    expect(await screen.findByRole('link', { name: 'customer-one@example.com' })).toBeInTheDocument();
  });
});
