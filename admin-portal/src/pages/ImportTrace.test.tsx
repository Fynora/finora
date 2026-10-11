import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ImportTrace from './ImportTrace';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminImportTraceApi } from '../api/endpoints';
import type { ImportTrace as Trace } from '../types';

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
  adminImportTraceApi: {
    byAnalysis: vi.fn(),
    byJob: vi.fn(),
  },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ImportTrace />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function mockAuth(permissions: string[] = ['PLATFORM_DIAGNOSTICS_VIEW']) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Ops Admin',
  }));
}

/** A queued import that staged successfully and has not been confirmed — the common shape. */
function trace(over: Partial<Trace> = {}): Trace {
  return {
    analysisReference: 'SA-20260806-0145',
    importJobId: '0f8b1c2d-0000-0000-0000-000000000001',
    importSessionId: '0f8b1c2d-0000-0000-0000-000000000002',
    correlationId: 'worker-abc123',
    analysis: {
      analysis: {
        reference: 'SA-20260806-0145',
        sourceFormat: 'PDF',
        layoutFingerprint: 'fp-hdfc-7c1',
        outcome: 'PARSED',
        failureCode: null,
        sectionCount: 1,
        rowCount: 128,
        unanchoredReasons: {},
        unanchoredRowCount: 0,
        durationMs: 2400,
        byteSize: 90210,
        createdAt: '2026-08-06T09:00:00Z',
        identityChecked: true,
        bankName: 'HDFC Bank',
        statementType: 'SAVINGS',
      },
      timesLayoutSeen: 4,
      timesLayoutFailed: 0,
    },
    job: {
      status: 'COMPLETED',
      attemptCount: 1,
      rowsTotal: 128,
      rowsProcessed: 128,
      lastError: null,
      queuedAt: '2026-08-06T09:00:00Z',
      startedAt: '2026-08-06T09:00:01Z',
      finishedAt: '2026-08-06T09:00:04Z',
      totalDurationMs: 4000,
    },
    stages: [
      { stage: 'PARSING', attempt: 1, outcome: 'COMPLETED', startedAt: '2026-08-06T09:00:01Z', endedAt: '2026-08-06T09:00:02Z', durationMs: 900 },
      { stage: 'IMPORTING', attempt: 1, outcome: 'SKIPPED', startedAt: '2026-08-06T09:00:04Z', endedAt: '2026-08-06T09:00:04Z', durationMs: 0 },
    ],
    verification: [
      { sectionIndex: 0, rule: 'BALANCE_CHAIN', outcome: 'VERIFIED', details: { checked: 128 }, recordedAt: '2026-08-06T09:00:03Z' },
    ],
    learning: { events: 3, byStatus: { COMPLETED: 3 }, outstanding: [] },
    completion: { statementImportId: null, transactionsImported: null, transactionsSkipped: null, importedAt: null, sessionConfirmedAt: null },
    ...over,
  };
}

async function traceFor(handle = 'SA-20260806-0145') {
  const user = userEvent.setup();
  renderPage();
  await user.type(screen.getByLabelText('Reference or job id'), handle);
  await user.click(screen.getByRole('button', { name: /trace/i }));
  return user;
}

beforeEach(() => {
  mockAuth();
  vi.mocked(adminImportTraceApi.byAnalysis).mockReset().mockResolvedValue(trace());
  vi.mocked(adminImportTraceApi.byJob).mockReset().mockResolvedValue(trace());
});

describe('Import Trace — one import, end to end', () => {
  it('assembles the blocks that were previously three separate queries', async () => {
    await traceFor();

    // Queue, parsing, verification and learning in one view -- the criterion is that an operator
    // does not have to know that three tables exist, let alone join them by hand.
    expect(await screen.findByText('Queue')).toBeInTheDocument();
    expect(screen.getByText('Parsing')).toBeInTheDocument();
    expect(screen.getByText('Verification')).toBeInTheDocument();
    expect(screen.getByText('Learning')).toBeInTheDocument();
    expect(screen.getByText('BALANCE_CHAIN')).toBeInTheDocument();
    expect(screen.getByText('fp-hdfc-7c1')).toBeInTheDocument();
  });

  it('reaches the same trace from a job id as from a reference', async () => {
    // A support conversation produces a reference; the queue produces a job id. Neither is the
    // "real" handle, which is why there are two routes and no guessing.
    const user = userEvent.setup();
    renderPage();
    await user.selectOptions(screen.getByLabelText('Handle'), 'job');
    await user.type(screen.getByLabelText('Reference or job id'), '0f8b1c2d-0000-0000-0000-000000000001');
    await user.click(screen.getByRole('button', { name: /trace/i }));

    await waitFor(() => expect(adminImportTraceApi.byJob).toHaveBeenCalled());
    expect(adminImportTraceApi.byAnalysis).not.toHaveBeenCalled();
  });
});

describe('Import Trace — an absent block is an answer', () => {
  it('says a synchronous import had no job rather than rendering an empty panel', async () => {
    vi.mocked(adminImportTraceApi.byAnalysis).mockResolvedValue(trace({ job: null, stages: [] }));
    await traceFor();

    // "This path does not record that" and "that did not happen" are different facts, and blank
    // space says neither.
    expect(await screen.findByText(/ran synchronously, so it never had a job/i)).toBeInTheDocument();
    expect(screen.getByText(/synchronous path is not separately timed/i)).toBeInTheDocument();
  });

  it('does not let an unconfirmed import read as a failed one', async () => {
    // Staging successfully and importing are different events. A completion panel showing zeroes
    // would say the import landed and imported nothing.
    await traceFor();

    expect(await screen.findByText(/Nothing was confirmed/i)).toBeInTheDocument();
    expect(screen.getByText(/confirming is still the user’s decision/i)).toBeInTheDocument();
  });

  it('distinguishes "no verification recorded" from "everything passed"', async () => {
    vi.mocked(adminImportTraceApi.byAnalysis).mockResolvedValue(trace({ verification: [] }));
    await traceFor();

    expect(await screen.findByText(/not the same as every rule passing/i)).toBeInTheDocument();
  });

  it('reports an uncounted job as uncounted rather than as zero rows', async () => {
    vi.mocked(adminImportTraceApi.byAnalysis).mockResolvedValue(trace({
      job: { ...trace().job!, status: 'PARSING', rowsTotal: null, rowsProcessed: 0 },
    }));
    await traceFor();

    expect(await screen.findByText('Not counted')).toBeInTheDocument();
    expect(screen.queryByText('0 of 0')).not.toBeInTheDocument();
  });
});

describe('Import Trace — reports, does not judge', () => {
  it('shows where a worker died without calling the import unhealthy', async () => {
    vi.mocked(adminImportTraceApi.byAnalysis).mockResolvedValue(trace({
      stages: [
        { stage: 'ANALYZING', attempt: 1, outcome: 'RUNNING', startedAt: '2026-08-06T09:00:01Z', endedAt: null, durationMs: null },
      ],
    }));
    await traceFor();

    // The one inference the page does draw, because an operator would otherwise have to know that
    // RUNNING on a finished job means a dead worker.
    expect(await screen.findByText(/worker died here/i)).toBeInTheDocument();
  });

  it('carries no overall verdict, score or health badge', async () => {
    await traceFor();
    await screen.findByText('Queue');

    // The position VerificationReport and LayoutIntelligence both take: a summary judgement needs a
    // weighting policy nothing here can calibrate, and a number that quietly becomes a verdict is
    // the failure mode those were written to avoid.
    expect(screen.queryByText(/healthy|unhealthy|overall (status|score)|looks fine/i)).not.toBeInTheDocument();
  });
});

describe('Import Trace — a balance mismatch is readable without a database query', () => {
  const MISMATCH_LIST = 'Rows that disagreed with the running balance';

  /** What the backend keeps for a balance column that slipped a row through identical same-day
   *  debits and then caught up: two rows out, by gaps that cancel. Positions and yes/no only. */
  function slippedColumn(over: Record<string, unknown> = {}) {
    return trace({
      verification: [{
        sectionIndex: 0,
        rule: 'BALANCE_CHAIN',
        outcome: 'WARNING',
        recordedAt: '2026-08-06T09:00:03Z',
        details: {
          rowsChecked: 51,
          rowsWithBalance: 52,
          anchoredOnOpeningBalance: true,
          discrepanciesCount: 2,
          discrepancyShapes: [
            {
              rowIndex: 12, sameDayAsPrevious: true, sameDayAsNext: true,
              directionLooksFlipped: false, balanceDidNotMove: true, gapMatchesAmountOfRows: [11, 13, 10],
            },
            {
              rowIndex: 16, sameDayAsPrevious: true, sameDayAsNext: false,
              directionLooksFlipped: false, balanceDidNotMove: false, gapMatchesAmountOfRows: [15],
            },
          ],
          gapsCancel: true,
          ...over,
        },
      }],
    });
  }

  it('says where each row is and what kind of mismatch it looks like', async () => {
    vi.mocked(adminImportTraceApi.byAnalysis).mockResolvedValue(slippedColumn());
    await traceFor();

    // Counted from one, and the matching rows in reading order whatever order they arrived in.
    expect(await screen.findByText(
      'Row 13: balance did not move; gap equals the amount on rows 11, 12, 14; same day as the rows before and after',
    )).toBeInTheDocument();
    expect(screen.getByText(
      'Row 17: gap equals the amount on row 16; same day as the row before',
    )).toBeInTheDocument();
    expect(screen.getByText(/The gaps cancel each other out\./)).toBeInTheDocument();
    // What the numbers count, said in words: this page has no rows on it to count them against.
    expect(screen.getByText(/Row numbers count down the rows as the customer’s review screen lists them/))
      .toBeInTheDocument();
  });

  it('keeps the counts on the flat line and never prints a list of objects there', async () => {
    vi.mocked(adminImportTraceApi.byAnalysis).mockResolvedValue(slippedColumn());
    await traceFor();
    await screen.findByText(/Row 13:/);

    // Testing Library collapses runs of whitespace in the text it reads, so one space, not two.
    expect(screen.getByText(
      'rowsChecked=51 rowsWithBalance=52 anchoredOnOpeningBalance=true discrepanciesCount=2',
    )).toBeInTheDocument();
    expect(screen.queryByText(/\[object Object\]/)).not.toBeInTheDocument();
    expect(screen.queryByText(/discrepancyShapes=/)).not.toBeInTheDocument();
  });

  it('names a flipped direction by what was measured', async () => {
    vi.mocked(adminImportTraceApi.byAnalysis).mockResolvedValue(slippedColumn({
      discrepanciesCount: 1,
      discrepancyShapes: [{
        rowIndex: 1, sameDayAsPrevious: false, sameDayAsNext: false,
        directionLooksFlipped: true, balanceDidNotMove: false, gapMatchesAmountOfRows: [],
      }],
      gapsCancel: false,
    }));
    await traceFor();

    expect(await screen.findByText('Row 2: gap is twice this row’s own amount')).toBeInTheDocument();
    // One gap has nothing to cancel against, so saying it "does not cancel" would be noise.
    expect(screen.queryByText(/cancel/)).not.toBeInTheDocument();
  });

  it('says so when a mismatch fits none of the patterns, rather than leaving the row blank', async () => {
    vi.mocked(adminImportTraceApi.byAnalysis).mockResolvedValue(slippedColumn({
      discrepanciesCount: 1,
      discrepancyShapes: [{
        rowIndex: 2, sameDayAsPrevious: false, sameDayAsNext: false,
        directionLooksFlipped: false, balanceDidNotMove: false, gapMatchesAmountOfRows: [],
      }],
      gapsCancel: false,
    }));
    await traceFor();

    expect(await screen.findByText('Row 3: gap matches no amount in the rows')).toBeInTheDocument();
  });

  it('says when it is showing only the first rows of many, and that their gaps do not cancel', async () => {
    vi.mocked(adminImportTraceApi.byAnalysis).mockResolvedValue(slippedColumn({
      discrepanciesCount: 87, gapsCancel: false,
    }));
    await traceFor();

    expect(await screen.findByText(/Showing the first 2 of 87 rows that disagreed\./)).toBeInTheDocument();
    expect(screen.getByText(/The gaps do not cancel each other out\./)).toBeInTheDocument();
  });

  it('survives a finding whose shapes are not what this version expects', async () => {
    // Written by a later version, or damaged: one line of the trace degrades, not the page.
    vi.mocked(adminImportTraceApi.byAnalysis).mockResolvedValue(slippedColumn({
      discrepancyShapes: [
        null, 'row 13', { balanceDidNotMove: true }, { rowIndex: '12' },
        { rowIndex: 4, gapMatchesAmountOfRows: 'rows 3 and 5' },
      ],
    }));
    await traceFor();

    expect(await screen.findByText('Row 5: gap matches no amount in the rows')).toBeInTheDocument();
    expect(within(screen.getByRole('list', { name: MISMATCH_LIST })).getAllByRole('listitem')).toHaveLength(1);
  });

  it('renders a verified balance chain exactly as before', async () => {
    await traceFor();
    await screen.findByText('BALANCE_CHAIN');

    expect(screen.getByText('checked=128')).toBeInTheDocument();
    expect(screen.queryByRole('list', { name: MISMATCH_LIST })).not.toBeInTheDocument();
    expect(screen.queryByText(/Row numbers/)).not.toBeInTheDocument();
  });

  it('prints a reason histogram as its contents, not as [object Object]', async () => {
    vi.mocked(adminImportTraceApi.byAnalysis).mockResolvedValue(trace({
      verification: [{
        sectionIndex: 0, rule: 'ROW_ACCOUNTING', outcome: 'WARNING', recordedAt: '2026-08-06T09:00:03Z',
        details: {
          droppedTransactionCandidateCount: 3,
          droppedTransactionCandidateReasons: { PAGE_FOOTER_OR_CLOSING_MARKER: 2, BUCKET_EMPTY: 1 },
          mismatches: ['debitTotal', 'debitCount'],
        },
      }],
    }));
    await traceFor();

    expect(await screen.findByText(
      'droppedTransactionCandidateCount=3 '
      + 'droppedTransactionCandidateReasons={"PAGE_FOOTER_OR_CLOSING_MARKER":2,"BUCKET_EMPTY":1} '
      + 'mismatches=debitTotal,debitCount',
    )).toBeInTheDocument();
  });
});

describe('Import Trace — access', () => {
  it('is gated on the same read-only permission as its sibling diagnostics screens', async () => {
    mockAuth([]);
    renderPage();

    expect(screen.queryByRole('button', { name: /trace/i })).not.toBeInTheDocument();
    expect(adminImportTraceApi.byAnalysis).not.toHaveBeenCalled();
  });
});
