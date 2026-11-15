import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ReadingQualityByBank, { percent, weekRate } from './ReadingQualityByBank';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminReadingQualityApi } from '../api/endpoints';
import type { ReadingQualityBankRow, ReadingQualityReport } from '../types';

vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminReadingQualityApi: { report: vi.fn() },
  adminSearchApi: { search: vi.fn() },
  adminDashboardApi: { overview: vi.fn(() => new Promise(() => {})) },
}));

const WEEKS = ['2026-11-02', '2026-11-09'];

function bank(overrides: Partial<ReadingQualityBankRow>): ReadingQualityBankRow {
  return {
    bank: 'Sample Bank',
    statementsRead: 20,
    rowsRead: 1200,
    holds: 2,
    holdRate: 0.1,
    fixes: 1,
    weeks: WEEKS.map((weekStart) => ({ weekStart, statementsRead: 10, rowsRead: 600, holds: 1, fixes: 0 })),
    ...overrides,
  };
}

function report(overrides: Partial<ReadingQualityReport> = {}): ReadingQualityReport {
  return {
    weeks: 12,
    minStatements: 10,
    weekStarts: WEEKS,
    banks: [],
    bankNotKnown: { importHolds: 0, trustHolds: 0, fixes: 0 },
    ...overrides,
  };
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/reading-quality']}>
        <ReadingQualityByBank />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Ops Admin',
    logout: vi.fn(),
  }));
}

function bodyRows() {
  const table = screen.getByRole('table');
  return within(table).getAllByRole('row').slice(1);
}

describe('percent', () => {
  it('dropsATrailingZero', () => {
    expect(percent(0.2)).toBe('20%');
  });
  it('keepsOneDecimal', () => {
    expect(percent(0.153)).toBe('15.3%');
  });
  it('zero', () => {
    expect(percent(0)).toBe('0%');
  });
});

describe('weekRate', () => {
  it('isNullWhenNothingWasRead', () => {
    expect(weekRate({ weekStart: '2026-11-09', statementsRead: 0, rowsRead: 0, holds: 0, fixes: 0 })).toBeNull();
  });
  it('isHoldsOverStatements', () => {
    expect(weekRate({ weekStart: '2026-11-09', statementsRead: 4, rowsRead: 9, holds: 1, fixes: 0 })).toBe(0.25);
  });
});

describe('ReadingQualityByBank', () => {
  beforeEach(() => {
    vi.mocked(adminReadingQualityApi.report).mockReset();
    mockAuth(['PLATFORM_DIAGNOSTICS_VIEW']);
  });

  it('showsBanksInTheOrderServedWithTheirCounts', async () => {
    vi.mocked(adminReadingQualityApi.report).mockResolvedValue(report({
      banks: [
        bank({ bank: 'Alpha Bank', statementsRead: 40, rowsRead: 2500, holds: 6, holdRate: 0.15, fixes: 2 }),
        bank({ bank: 'Beta Bank', statementsRead: 12, rowsRead: 900, holds: 1, holdRate: 1 / 12, fixes: 0 }),
      ],
    }));

    renderPage();

    expect(await screen.findByText('Alpha Bank')).toBeInTheDocument();
    const rows = bodyRows();
    expect(within(rows[0]).getByRole('rowheader')).toHaveTextContent('Alpha Bank');
    expect(rows[0]).toHaveTextContent('2,500');
    expect(rows[0]).toHaveTextContent('15%');
    expect(within(rows[1]).getByRole('rowheader')).toHaveTextContent('Beta Bank');
    expect(rows[1]).toHaveTextContent('8.3%');
    expect(adminReadingQualityApi.report).toHaveBeenCalledWith(12);
  });

  it('aBankWithoutARate_saysTooFewToJudge', async () => {
    vi.mocked(adminReadingQualityApi.report).mockResolvedValue(report({
      banks: [bank({ bank: 'Gamma Bank', statementsRead: 3, holds: 1, holdRate: null })],
    }));

    renderPage();

    const row = (await screen.findByText('Gamma Bank')).closest('tr')!;
    expect(row).toHaveTextContent('too few to judge');
    expect(row).not.toHaveTextContent('%');
  });

  it('showsHoldsWithNoBankOnTheirOwnLine', async () => {
    vi.mocked(adminReadingQualityApi.report).mockResolvedValue(report({
      banks: [bank({ bank: 'Alpha Bank' })],
      bankNotKnown: { importHolds: 3, trustHolds: 1, fixes: 2 },
    }));

    renderPage();

    const row = (await screen.findByText('Bank not known')).closest('tr')!;
    expect(row).toHaveTextContent('3 import holds');
    expect(row).toHaveTextContent('1 statement holds');
    const cells = within(row).getAllByRole('cell');
    expect(cells[2]).toHaveTextContent('4');
    expect(cells[4]).toHaveTextContent('2');
  });

  it('hidesTheBankNotKnownLineWhenItIsEmpty', async () => {
    vi.mocked(adminReadingQualityApi.report).mockResolvedValue(report({ banks: [bank({ bank: 'Alpha Bank' })] }));

    renderPage();

    await screen.findByText('Alpha Bank');
    expect(screen.queryByText('Bank not known')).toBeNull();
  });

  it('theFourWeekSwitch_asksForFourWeeks', async () => {
    vi.mocked(adminReadingQualityApi.report).mockResolvedValue(report({ banks: [bank({ bank: 'Alpha Bank' })] }));

    renderPage();
    await screen.findByText('Alpha Bank');
    fireEvent.click(screen.getByRole('button', { name: 'Last 4 weeks' }));

    expect(screen.getByRole('button', { name: 'Last 4 weeks' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Last 12 weeks' })).toHaveAttribute('aria-pressed', 'false');
    expect(adminReadingQualityApi.report).toHaveBeenLastCalledWith(4);
  });

  it('nothingRead_saysSo', async () => {
    vi.mocked(adminReadingQualityApi.report).mockResolvedValue(report());

    renderPage();

    expect(await screen.findByText('No statements read in this period.')).toBeInTheDocument();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('aFailedLoad_saysSo', async () => {
    vi.mocked(adminReadingQualityApi.report).mockRejectedValue(new Error('boom'));

    renderPage();

    expect(await screen.findByText('Could not load reading quality.')).toBeInTheDocument();
  });

  it('withoutDiagnosticsPermission_neverAsks', () => {
    mockAuth(['TRUST_REVIEW_MANAGE']);
    vi.mocked(adminReadingQualityApi.report).mockResolvedValue(report());

    renderPage();

    expect(adminReadingQualityApi.report).not.toHaveBeenCalled();
  });
});
