import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import LearningEngine from './LearningEngine';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminLearningApi } from '../api/endpoints';
import { categoryChange, correctionRate, formatPercent } from './LearningEngine';
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
    last30Days: { learned: 98, corrected: 2 },
    previous30Days: { learned: 92, corrected: 8 },
    trend: [
      { month: '2026-09', learned: 276, corrected: 24, undone: 1, reset: 2 },
      { month: '2026-10', learned: 1898, corrected: 5, undone: 6, reset: 1 },
      { month: '2026-11', learned: 8, corrected: 0, undone: 0, reset: 0 },
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

describe('correctionRate and formatPercent', () => {
  it('is corrected out of all confirmations, and has no value with none', () => {
    expect(correctionRate({ learned: 3, corrected: 1 })).toBe(0.25);
    expect(correctionRate({ learned: 0, corrected: 0 })).toBeNull();
    expect(correctionRate({ learned: 0, corrected: 2 })).toBe(1);
  });

  it('shows one decimal, never rounds a real correction down to 0.0%, and a dash for no value', () => {
    expect(formatPercent(0.25)).toBe('25.0%');
    expect(formatPercent(0)).toBe('0.0%');
    expect(formatPercent(5 / 1903)).toBe('0.3%');
    expect(formatPercent(1 / 5000)).toBe('<0.1%');
    expect(formatPercent(0.0005)).toBe('0.1%');
    expect(formatPercent(null)).toBe('—');
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
  it('names what the merchant had for an undo, reset or merge', () => {
    expect(categoryChange({ ...base, action: 'RESET', toCategory: null })).toBe('Was Food and dining');
    expect(categoryChange({ ...base, action: 'UNDONE', fromCategory: null, toCategory: null })).toBe('—');
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
    mockAuth(['MERCHANT_MANAGE']);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue(stats());

    renderPage();

    await waitFor(() => expect(screen.getByText('2.0%')).toBeInTheDocument());
    const delta = screen.getByText('8.0% in the 30 days before');
    expect(delta.closest('p')).toHaveClass('text-success');
  });

  it('marks a rising correction rate as bad', async () => {
    mockAuth(['MERCHANT_MANAGE']);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue(stats({
      last30Days: { learned: 90, corrected: 10 }, previous30Days: { learned: 99, corrected: 1 },
    }));

    renderPage();

    await waitFor(() => expect(screen.getByText('10.0%')).toBeInTheDocument());
    expect(screen.getByText('1.0% in the 30 days before').closest('p')).toHaveClass('text-danger');
  });

  it('says there is nothing to rate when the last 30 days had no confirmations', async () => {
    mockAuth(['MERCHANT_MANAGE']);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue(stats({
      last30Days: { learned: 0, corrected: 0 },
    }));

    renderPage();

    await waitFor(() => expect(screen.getByText('No confirmations in the last 30 days')).toBeInTheDocument());
    expect(screen.queryByText(/in the 30 days before/)).not.toBeInTheDocument();
  });

  it('shows the all-time tiles, customers with learning and the share sorted by learning', async () => {
    mockAuth(['MERCHANT_MANAGE']);
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
    mockAuth(['MERCHANT_MANAGE']);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue(stats());

    renderPage();

    await waitFor(() => expect(screen.getByTestId('learning-monthly-chart')).toBeInTheDocument());
    expect(screen.getByTestId('learning-rate-chart')).toBeInTheDocument();
    const rows = screen.getAllByRole('row').slice(1).filter((r) => /^2026-/.test(r.textContent ?? ''));
    expect(rows.map((r) => within(r).getAllByRole('cell')[0].textContent)).toEqual(['2026-11', '2026-10', '2026-09']);
    expect(within(rows[2]).getByText('8.0%')).toBeInTheDocument();
    expect(within(rows[1]).getByText('1,898')).toBeInTheDocument();
  });

  it('names each category source, keeps an unknown one as itself, and gives each its share', async () => {
    mockAuth(['MERCHANT_MANAGE']);
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
    mockAuth(['MERCHANT_MANAGE']);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue(stats({ categorySources: [] }));

    renderPage();

    await waitFor(() => expect(screen.getByText('No customer transactions were added in the last 30 days.')).toBeInTheDocument());
  });

  it('lists the most corrected merchants with their usual change, and the common category changes', async () => {
    mockAuth(['MERCHANT_MANAGE']);
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
    mockAuth(['MERCHANT_MANAGE']);
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
    mockAuth(['MERCHANT_MANAGE']);
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
    expect(within(second).getByText('Was Travel')).toBeInTheDocument();
    expect(adminLearningApi.events).toHaveBeenCalledWith({ page: 0, size: 25 });
  });

  it('asks for the next page of events', async () => {
    mockAuth(['MERCHANT_MANAGE']);
    vi.mocked(adminLearningApi.platformStats).mockResolvedValue(stats());
    vi.mocked(adminLearningApi.events).mockResolvedValue(eventsPage([corrected], 60));

    renderPage();

    await screen.findByRole('link', { name: 'customer-one@example.com' });
    await userEvent.click(screen.getByRole('button', { name: /next/i }));
    await waitFor(() => expect(adminLearningApi.events).toHaveBeenCalledWith({ page: 1, size: 25 }));
  });

  it('keeps the events list when the figures fail to load, and says the figures failed', async () => {
    mockAuth(['MERCHANT_MANAGE']);
    vi.mocked(adminLearningApi.platformStats).mockRejectedValue(new Error('boom'));
    vi.mocked(adminLearningApi.events).mockResolvedValue(eventsPage([corrected]));

    renderPage();

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent("Couldn't load the learning figures"));
    expect(await screen.findByRole('link', { name: 'customer-one@example.com' })).toBeInTheDocument();
  });
});
