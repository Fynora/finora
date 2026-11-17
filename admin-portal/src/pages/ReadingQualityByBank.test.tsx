import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ReadingQualityByBank, { percent, recordedPart, weekLabel, weekRate } from './ReadingQualityByBank';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminReadingQualityApi } from '../api/endpoints';
import type { ReadingQualityBankRow, ReadingQualityReport, StagedOutcomesReport, StagedOutcomesWeek } from '../types';

vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminReadingQualityApi: { report: vi.fn(), stagedOutcomes: vi.fn() },
  adminSearchApi: { search: vi.fn() },
  adminDashboardApi: { overview: vi.fn(() => new Promise(() => {})) },
}));

const WEEKS = ['2026-11-02', '2026-11-09'];

function bank(overrides: Partial<ReadingQualityBankRow>): ReadingQualityBankRow {
  return {
    bank: 'Sample Bank',
    statementsRead: 20,
    rowsRead: 1200,
    failedReads: 0,
    holds: 2,
    problemRate: 0.1,
    fixes: 1,
    weeks: WEEKS.map((weekStart) => ({ weekStart, statementsRead: 10, rowsRead: 600, failedReads: 0, holds: 1, fixes: 0 })),
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

describe('weekLabel', () => {
  it('namesSeptemberWithThreeLetters', () => {
    expect(weekLabel('2026-09-07')).toBe('7 Sep');
  });
});

describe('weekRate', () => {
  it('isNullWhenNothingWasTried', () => {
    expect(weekRate({ weekStart: '2026-11-09', statementsRead: 0, rowsRead: 0, failedReads: 0, holds: 0, fixes: 0 })).toBeNull();
  });
  it('isHoldsOverStatements', () => {
    expect(weekRate({ weekStart: '2026-11-09', statementsRead: 4, rowsRead: 9, failedReads: 0, holds: 1, fixes: 0 })).toBe(0.25);
  });
  it('countsFailedReadsAsTriedAndAsProblems', () => {
    expect(weekRate({ weekStart: '2026-11-09', statementsRead: 3, rowsRead: 9, failedReads: 1, holds: 1, fixes: 0 })).toBe(0.5);
  });
  it('aWeekOfOnlyFailures_isAllProblems', () => {
    expect(weekRate({ weekStart: '2026-11-09', statementsRead: 0, rowsRead: 0, failedReads: 2, holds: 0, fixes: 0 })).toBe(1);
  });
});

describe('ReadingQualityByBank', () => {
  beforeEach(() => {
    vi.mocked(adminReadingQualityApi.report).mockReset();
    // The staged-outcomes panel stays loading unless a test gives it a report.
    vi.mocked(adminReadingQualityApi.stagedOutcomes).mockReset().mockReturnValue(new Promise(() => {}));
    mockAuth(['PLATFORM_DIAGNOSTICS_VIEW']);
  });

  it('showsBanksInTheOrderServedWithTheirCounts', async () => {
    vi.mocked(adminReadingQualityApi.report).mockResolvedValue(report({
      banks: [
        bank({ bank: 'Alpha Bank', statementsRead: 36, rowsRead: 2500, failedReads: 4, holds: 2, problemRate: 0.15, fixes: 2 }),
        bank({ bank: 'Beta Bank', statementsRead: 12, rowsRead: 900, holds: 1, problemRate: 1 / 12, fixes: 0 }),
      ],
    }));

    renderPage();

    expect(await screen.findByText('Alpha Bank')).toBeInTheDocument();
    const rows = bodyRows();
    expect(within(rows[0]).getByRole('rowheader')).toHaveTextContent('Alpha Bank');
    const alpha = within(rows[0]).getAllByRole('cell');
    expect(alpha[0]).toHaveTextContent('36');
    expect(alpha[1]).toHaveTextContent('2,500');
    expect(alpha[2]).toHaveTextContent('4');
    expect(alpha[3]).toHaveTextContent('2');
    expect(alpha[4]).toHaveTextContent('15%');
    expect(within(rows[1]).getByRole('rowheader')).toHaveTextContent('Beta Bank');
    expect(rows[1]).toHaveTextContent('8.3%');
    expect(adminReadingQualityApi.report).toHaveBeenCalledWith(12);
  });

  it('aBankWithoutARate_saysTooFewToJudge', async () => {
    vi.mocked(adminReadingQualityApi.report).mockResolvedValue(report({
      banks: [bank({ bank: 'Gamma Bank', statementsRead: 3, holds: 1, problemRate: null })],
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
    expect(cells[3]).toHaveTextContent('4');
    expect(cells[5]).toHaveTextContent('2');
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

describe('recordedPart', () => {
  it('aWeekThatEndedBeforeRecording_isNone', () => {
    expect(recordedPart('2026-11-09', '2026-11-16')).toBe('none');
  });
  it('theWeekRecordingBeganIn_isPart', () => {
    expect(recordedPart('2026-11-16', '2026-11-18')).toBe('part');
  });
  it('aWeekFromTheDayRecordingBegan_isAll', () => {
    expect(recordedPart('2026-11-16', '2026-11-16')).toBe('all');
    expect(recordedPart('2026-11-23', '2026-11-18')).toBe('all');
  });
  it('withNoRecordingDate_isAll', () => {
    expect(recordedPart('2026-11-09', null)).toBe('all');
  });
});

describe('Staged, but was it imported?', () => {
  function week(weekStart: string | null, overrides: Partial<StagedOutcomesWeek> = {}): StagedOutcomesWeek {
    return {
      weekStart, staged: 0, imported: 0, discardedByCustomer: 0, removedByPhone: 0, expired: 0, stillOpen: 0,
      notRecorded: 0, freeLimitShown: 0, freeLimitRefused: 0, freeLimitThenImported: 0,
      discardReasons: { wrongFile: 0, tooManyMonths: 0, looksWrong: 0, later: 0, other: 0, noReason: 0 },
      ...overrides,
    };
  }

  function outcomes(): StagedOutcomesReport {
    return {
      weeks: 12,
      recordedSince: '2026-11-18',
      weekly: [
        week('2026-11-09', { staged: 7, notRecorded: 7 }),
        week('2026-11-16', { staged: 12, imported: 6, discardedByCustomer: 2, removedByPhone: 1, expired: 1,
          stillOpen: 1, notRecorded: 1, freeLimitShown: 3, freeLimitRefused: 1, freeLimitThenImported: 1 }),
      ],
      total: week(null, { staged: 19, imported: 6, discardedByCustomer: 2, removedByPhone: 1, expired: 1,
        stillOpen: 1, notRecorded: 8, freeLimitShown: 3, freeLimitRefused: 1, freeLimitThenImported: 1,
        discardReasons: { wrongFile: 0, tooManyMonths: 1, looksWrong: 0, later: 0, other: 0, noReason: 1 } }),
    };
  }

  beforeEach(() => {
    vi.mocked(adminReadingQualityApi.report).mockReset().mockResolvedValue(report());
    vi.mocked(adminReadingQualityApi.stagedOutcomes).mockReset();
    mockAuth(['PLATFORM_DIAGNOSTICS_VIEW']);
  });

  function outcomeTable() {
    return within(screen.getByRole('region', { name: 'Staged, but was it imported?' })).getByRole('table');
  }

  it('showsEachWeekNewestFirst_withItsOutcomes_andTheTotal', async () => {
    vi.mocked(adminReadingQualityApi.stagedOutcomes).mockResolvedValue(outcomes());

    renderPage();

    await screen.findByText('Shown, then imported');
    const rows = within(outcomeTable()).getAllByRole('row').slice(2); // two header rows
    expect(within(rows[0]).getByRole('rowheader')).toHaveTextContent('16 Nov');
    expect(within(rows[0]).getByRole('rowheader')).toHaveTextContent('recording began this week');
    expect(within(rows[0]).getAllByRole('cell').map((c) => c.textContent))
      .toEqual(['12', '6', '2', '1', '1', '1', '1', '3', '1', '1']);
    expect(within(rows[1]).getByRole('rowheader')).toHaveTextContent('9 Nov');
    expect(within(rows[1]).getByRole('rowheader')).toHaveTextContent('before recording began');
    expect(within(rows[2]).getByRole('rowheader')).toHaveTextContent('Last 12 weeks');
    expect(within(rows[2]).getAllByRole('cell')[0]).toHaveTextContent('19');
    expect(screen.getByText(/Recorded from 18 Nov/)).toBeInTheDocument();
    // Only the reasons given are named, over the whole period.
    expect(screen.getByTestId('discard-reasons'))
      .toHaveTextContent('Why customers discarded, last 12 weeks: Covers too many months 1 · No reason given 1');
    expect(adminReadingQualityApi.stagedOutcomes).toHaveBeenCalledWith(12);
  });

  it('aFailedLoad_saysSo_withoutHidingTheBankTable', async () => {
    vi.mocked(adminReadingQualityApi.stagedOutcomes).mockRejectedValue(new Error('boom'));

    renderPage();

    expect(await screen.findByText('Could not load what became of staged statements.')).toBeInTheDocument();
    expect(screen.getByText('No statements read in this period.')).toBeInTheDocument();
  });

  it('theFourWeekSwitch_asksForFourWeeks', async () => {
    vi.mocked(adminReadingQualityApi.stagedOutcomes).mockResolvedValue(outcomes());

    renderPage();
    await screen.findByText('Shown, then imported');
    fireEvent.click(screen.getByRole('button', { name: 'Last 4 weeks' }));

    await vi.waitFor(() => expect(adminReadingQualityApi.stagedOutcomes).toHaveBeenCalledWith(4));
  });
});
