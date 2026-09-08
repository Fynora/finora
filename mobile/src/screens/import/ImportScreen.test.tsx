import { act, render, screen, fireEvent, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import * as DocumentPicker from 'expo-document-picker';
import { ImportScreen } from './ImportScreen';
import { accountsApi, categoriesApi, importApi, importJobsApi, statementImportsApi } from '../../api/endpoints';
import type { DetectedAccountInfo, ImportSummary, StagedRow } from '../../types';

// The re-import arrival path is exercised by most of this file, so every staging/upload call is a
// stub those tests never expect to be reached -- stageCsv/stagePdf are configured per-test only by
// the fresh-upload describe block below.
jest.mock('../../api/endpoints', () => ({
  accountsApi: { list: jest.fn() },
  categoriesApi: { list: jest.fn() },
  importApi: {
    listSessions: jest.fn(),
    getSession: jest.fn(),
    discardSession: jest.fn(),
    stageCsv: jest.fn(),
    stagePdf: jest.fn(),
    confirm: jest.fn(),
    // Phase 4 (Medium-Tier Parity). Defaulted to empty right in the factory, same reasoning as
    // importJobsApi.availability just below -- an unconfigured jest.fn() returns undefined, not a
    // promise, which useQuery's queryFn contract doesn't accept; only the retry-section describe
    // block below needs a real list.
    listFailures: jest.fn().mockResolvedValue([]),
  },
  // Phase 4 (Medium-Tier Parity). Defaulted to unavailable right in the factory, not per-test --
  // this file has many independent describe blocks, each with its own beforeEach, and every one of
  // them exercises the pre-existing synchronous upload path. Resolving false here (rather than
  // leaving availability() an unconfigured jest.fn(), which returns undefined -- not a promise --
  // and would break useQuery's queryFn contract) means none of them need to know this API exists.
  // Only the async-path describe block below overrides it.
  importJobsApi: {
    availability: jest.fn().mockResolvedValue({ asyncImportAvailable: false }),
    submit: jest.fn(),
    progress: jest.fn(),
    timeline: jest.fn(),
    cancel: jest.fn(),
  },
  statementImportsApi: { confirmReimport: jest.fn() },
}));

// Unconfigured by default -- most of this file's tests arrive via the reimport path, never
// reaching handlePick, so DocumentPicker only needs to exist for statementFile.ts's static import
// to resolve. The fresh-upload describe block below configures it per test.
jest.mock('expo-document-picker', () => ({ getDocumentAsync: jest.fn() }));

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
}));

// A controllable stand-in for useRoute -- StatementHistoryScreen.test.tsx's file-level
// jest.mock('@react-navigation/native') pattern, extended with the one hook this screen calls that
// screen didn't. mockNavigate (Track C/C6) is a plain jest.fn(): this screen only ever calls
// navigate() to leave, never asserts on the result of being navigated TO, so nothing here needs
// the shared-getParent()-stub shape the More-stack screens' own test files use.
let mockRouteParams: { reimport?: unknown } | undefined;
const mockNavigate = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useRoute: () => ({ params: mockRouteParams }),
  useNavigation: () => ({ navigate: mockNavigate }),
}));

const api = {
  accounts: accountsApi as jest.Mocked<typeof accountsApi>,
  categories: categoriesApi as jest.Mocked<typeof categoriesApi>,
  import: importApi as jest.Mocked<typeof importApi>,
  importJobs: importJobsApi as jest.Mocked<typeof importJobsApi>,
  statements: statementImportsApi as jest.Mocked<typeof statementImportsApi>,
};

function stagedRow(description: string): StagedRow {
  return {
    date: '2026-07-10',
    description,
    amount: 45,
    type: 'EXPENSE',
    suggestedCategory: 'Transport',
    categorySource: 'rule',
    ruleId: null,
    likelyDuplicate: false,
    referenceNumber: null,
    balanceAfter: null,
    duplicateMatch: null,
    rowPosition: null,
    categoryConfidence: null,
  };
}

// Every field a fresh render actually touches (detected?.suggestedName etc., all optional-chained)
// is happy with an empty object -- nothing in this file's flow reaches matchExistingAccount, the one
// reader that needs `.bank`.
const detected = {} as DetectedAccountInfo;

function reimportParams(statementImportId: string, nonce: number) {
  return {
    reimport: {
      statementImportId,
      accountId: 'acct-1',
      accountName: 'HDFC Savings',
      staging: {
        rows: [stagedRow(`row for ${statementImportId}`)],
        totalParsed: 1,
        flaggedDuplicates: 0,
        detectedAccount: detected,
        unparseableRows: [],
      },
      nonce,
    },
  };
}

function tree() {
  return (
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>
      <ImportScreen />
    </QueryClientProvider>
  );
}

async function settle() {
  await act(async () => {});
}

async function pressImport() {
  fireEvent.press(await screen.findByText(/^Import \d+ transaction/));
  await settle();
}

describe('ImportScreen — re-import confirm attempt key', () => {
  beforeEach(() => {
    mockRouteParams = undefined;
    mockNavigate.mockClear();
    api.accounts.list.mockReset().mockResolvedValue([]);
    api.categories.list.mockReset().mockResolvedValue([]);
    api.import.listSessions.mockReset().mockResolvedValue([]);
    api.statements.confirmReimport.mockReset();
  });

  /**
   * B1's idempotency key is meant to identify one confirm ATTEMPT, kept only so a retry of that
   * same attempt is recognised rather than double-imported (confirmReimport's failed-attempt
   * branch deliberately keeps it — see runConfirm's own comment). The bug: the reimport-arrival
   * block reset every other piece of review state on a new nonce but not this ref, and the ref
   * survives a round trip through the Statement History tab because React Navigation keeps tab
   * screens mounted. A second, completely unconfirmed re-import of a DIFFERENT statement then
   * reused the first one's key -- and claimReimportAttempt on the backend
   * (StatementImportService.java) looks a key up by (user, key) alone, with no statementImportId
   * in the lookup, so it answers "already confirmed" for a re-import that was never even attempted.
   *
   * Reproduced here without a real backend by having the mock enforce that same (user-implicit,
   * key)-only uniqueness rule that claimReimportAttempt enforces, and by having the FIRST call
   * both succeed server-side and still reject the promise -- exactly the "commit landed, response
   * lost" case idempotency keys exist for in the first place (ReimportIdempotencyIT's own target
   * scenario), which is what leaves attemptKey.current holding a key the server has already used.
   */
  it('mints a fresh key for a different re-import rather than reusing one from an earlier failed attempt', async () => {
    const claimedKeys = new Set<string>();
    api.statements.confirmReimport.mockImplementation(async (id, payload) => {
      const key = (payload as { idempotencyKey?: string }).idempotencyKey;
      if (key) {
        if (claimedKeys.has(key)) throw Object.assign(new Error('conflict'), { isConflict: true });
        claimedKeys.add(key);
      }
      if (id === 'stmt-1') {
        // The server committed this claim (recorded above) but the client never sees a response.
        throw new Error('response lost in transit');
      }
      return {
        imported: 1, skipped: 0, duplicatesDetected: 0, transfersIdentified: 0, newMerchantsLearned: 0,
        accountsCreated: [], productsCreated: {}, categoriesAssigned: {}, warnings: [],
        account: null, totalCredits: 0, totalDebits: 45, statementOpeningBalance: null,
        statementClosingBalance: null, statementPeriodStart: null, statementPeriodEnd: null,
        importDurationMs: 1, source: 'reimport',
      };
    });

    mockRouteParams = reimportParams('stmt-1', 1);
    const view = render(tree());
    await pressImport();

    // The first (failed, but server-committed) attempt.
    expect(api.statements.confirmReimport).toHaveBeenCalledTimes(1);
    expect(await screen.findByText(/could not complete the import/i)).toBeTruthy();

    // Arriving at a SECOND, unrelated re-import -- the reachable path is a fresh nonce from
    // History, not a remount, since React Navigation keeps this tab's screen mounted.
    mockRouteParams = reimportParams('stmt-2', 2);
    view.rerender(tree());
    await settle();
    await pressImport();

    expect(api.statements.confirmReimport).toHaveBeenCalledTimes(2);
    const [firstCall, secondCall] = api.statements.confirmReimport.mock.calls;
    expect(firstCall[0]).toBe('stmt-1');
    expect(secondCall[0]).toBe('stmt-2');
    expect(secondCall[1].idempotencyKey).toBeTruthy();
    // The actual bug: without the fix this is the SAME key as the first, doomed attempt, and the
    // mock's claimedKeys check rejects it exactly as the real backend would.
    expect(secondCall[1].idempotencyKey).not.toBe(firstCall[1].idempotencyKey);
    expect(await screen.findByText('Import complete')).toBeTruthy();
  });

  /**
   * Bug fix: this screen never sent the detected statement period at all, even though it already
   * fetches it and shows it on screen ("detected.statementPeriodStart to ...End" a few lines
   * below the review table). ImportService persists this field onto StatementImport -- read by
   * Statement History and the "View in Ledger" period filter -- and gates the PNB-boundary-date
   * opening-balance carry-forward fix on it being non-null, so a missing period silently disabled
   * both for this client, unlike the web one, for the identical statement.
   */
  it('sends the detected statement period on confirm, not just the balances', async () => {
    api.statements.confirmReimport.mockResolvedValue({
      imported: 1, skipped: 0, duplicatesDetected: 0, transfersIdentified: 0, newMerchantsLearned: 0,
      accountsCreated: [], productsCreated: {}, categoriesAssigned: {}, warnings: [],
      account: null, totalCredits: 0, totalDebits: 45, statementOpeningBalance: null,
      statementClosingBalance: null, statementPeriodStart: null, statementPeriodEnd: null,
      importDurationMs: 1, source: 'reimport',
    } as never);
    mockRouteParams = {
      reimport: {
        statementImportId: 'stmt-period',
        accountId: 'acct-1',
        accountName: 'HDFC Savings',
        staging: {
          rows: [stagedRow('row for stmt-period')],
          totalParsed: 1,
          flaggedDuplicates: 0,
          detectedAccount: { statementPeriodStart: '2026-01-01', statementPeriodEnd: '2026-03-31' } as DetectedAccountInfo,
          unparseableRows: [],
        },
        nonce: 1,
      },
    };
    render(tree());

    await pressImport();

    expect(api.statements.confirmReimport).toHaveBeenCalledTimes(1);
    const [, payload] = api.statements.confirmReimport.mock.calls[0];
    expect(payload).toMatchObject({ statementPeriodStart: '2026-01-01', statementPeriodEnd: '2026-03-31' });
  });
});

describe('ImportScreen — Cancel disabled during confirm', () => {
  beforeEach(() => {
    mockRouteParams = undefined;
    mockNavigate.mockClear();
    api.accounts.list.mockReset().mockResolvedValue([]);
    api.categories.list.mockReset().mockResolvedValue([]);
    api.import.listSessions.mockReset().mockResolvedValue([]);
    api.statements.confirmReimport.mockReset();
  });

  // Otherwise a tap on "Cancel" while confirmReimport() is still in flight resets every piece of
  // review state (sessionId, rows, attemptKey) and returns to the upload step -- and if the stale
  // request then succeeds, its own success path (setStep('summary')) lands afterward and yanks the
  // screen forward to a completed import the user believed they'd cancelled, even though the
  // transactions were already committed server-side. Mirrors frontend/src/pages/Import.tsx's own
  // `disabled={confirming}` guard on its "Cancel Import" button, which mobile lacked.
  it('disables Cancel while confirmReimport() is in flight', async () => {
    let resolveConfirm!: (v: ImportSummary) => void;
    api.statements.confirmReimport.mockReturnValue(
      new Promise((resolve) => { resolveConfirm = resolve; })
    );
    mockRouteParams = reimportParams('stmt-1', 1);
    render(tree());

    await pressImport();

    expect(
      screen.getByRole('button', { name: 'Cancel' }).props.accessibilityState.disabled
    ).toBe(true);

    await act(async () => {
      resolveConfirm({
        imported: 1, skipped: 0, duplicatesDetected: 0, transfersIdentified: 0, newMerchantsLearned: 0,
        accountsCreated: [], productsCreated: {}, categoriesAssigned: {}, warnings: [],
        account: null, totalCredits: 0, totalDebits: 45, statementOpeningBalance: null,
        statementClosingBalance: null, statementPeriodStart: null, statementPeriodEnd: null,
        importDurationMs: 1, source: 'reimport',
      });
    });
  });
});

/**
 * Track C/C6: depended on C4's Ledger drill-through filters existing at all -- without them this
 * would land on the whole, unfiltered ledger, no more useful than the Transactions tab a user
 * could already reach on their own.
 */
describe('ImportScreen — "View in Ledger" (Track C/C6)', () => {
  beforeEach(() => {
    mockRouteParams = undefined;
    mockNavigate.mockClear();
    api.accounts.list.mockReset().mockResolvedValue([]);
    api.categories.list.mockReset().mockResolvedValue([]);
    api.import.listSessions.mockReset().mockResolvedValue([]);
    api.statements.confirmReimport.mockReset();
  });

  async function reachSummary(summary: Record<string, unknown>) {
    api.statements.confirmReimport.mockResolvedValue(summary as never);
    mockRouteParams = reimportParams('stmt-1', 1);
    render(tree());
    await pressImport();
    await screen.findByText('Import complete');
  }

  it('filters by the confirmed account and the statement\'s own period', async () => {
    await reachSummary({
      imported: 1, skipped: 0, duplicatesDetected: 0, transfersIdentified: 0, newMerchantsLearned: 0,
      accountsCreated: [], productsCreated: {}, categoriesAssigned: {}, warnings: [],
      account: { id: 'acct-1', name: 'HDFC Savings' }, totalCredits: 0, totalDebits: 45,
      statementOpeningBalance: null, statementClosingBalance: null,
      statementPeriodStart: '2026-07-01', statementPeriodEnd: '2026-07-31',
      importDurationMs: 1, source: 'reimport',
    });

    fireEvent.press(screen.getByText('View in Ledger'));

    expect(mockNavigate).toHaveBeenCalledWith('Transactions', {
      filters: expect.objectContaining({
        accountId: 'acct-1', dateFrom: '2026-07-01', dateTo: '2026-07-31',
        label: 'HDFC Savings · 2026-07-01 to 2026-07-31',
      }),
    });
  });

  // ImportSummary.account is nullable (see the type's own comment) -- must degrade to an
  // unfiltered-by-account Ledger rather than crash reading `.id` off null.
  it('still opens the Ledger when the confirm response carries no account', async () => {
    await reachSummary({
      imported: 1, skipped: 0, duplicatesDetected: 0, transfersIdentified: 0, newMerchantsLearned: 0,
      accountsCreated: [], productsCreated: {}, categoriesAssigned: {}, warnings: [],
      account: null, totalCredits: 0, totalDebits: 45,
      statementOpeningBalance: null, statementClosingBalance: null,
      statementPeriodStart: null, statementPeriodEnd: null,
      importDurationMs: 1, source: 'reimport',
    });

    fireEvent.press(screen.getByText('View in Ledger'));

    expect(mockNavigate).toHaveBeenCalledWith('Transactions', {
      filters: expect.objectContaining({
        accountId: undefined, dateFrom: undefined, dateTo: undefined, label: 'This import',
      }),
    });
  });
});

/**
 * The new-account form has always had credit-limit and due-date data flowing through it --
 * NewAccountForm carries both fields, buildNewAccountPayload sends both to the backend for a
 * CREDIT_CARD account, and initialAccountForm prefills both from whatever the statement itself
 * detected -- but this screen never rendered an input for either one. A user creating a new
 * credit card account from a statement had no way to see, confirm, or correct the detected
 * limit/due date, and no way to enter either at all when the statement didn't print one.
 */
// Unlike `detected` above, this new-account path runs buildNewAccountPayload (lib/importPayload.ts),
// which unconditionally reads `detected?.bank.id` -- safe only because DetectedAccountInfo.bank is
// a required field on every real staging response. The bare `{}` stub is fine for the reimport
// tests above (they never reach buildNewAccountPayload), but would throw here.
const detectedWithBank = { bank: { id: 'OTHER' } } as DetectedAccountInfo;

describe('ImportScreen — new-account credit limit and due date fields', () => {
  beforeEach(() => {
    mockRouteParams = undefined;
    mockNavigate.mockClear();
    api.accounts.list.mockReset().mockResolvedValue([]);
    api.categories.list.mockReset().mockResolvedValue([]);
    api.import.listSessions.mockReset().mockResolvedValue([]);
    api.import.stageCsv.mockReset().mockResolvedValue({
      sessionId: 'session-1',
      multiAccount: false,
      sections: null,
      staging: {
        rows: [stagedRow('Coffee')], totalParsed: 1, flaggedDuplicates: 0,
        detectedAccount: detectedWithBank, unparseableRows: [],
      },
    } as never);
    api.import.confirm.mockReset();
    jest.mocked(DocumentPicker.getDocumentAsync).mockReset().mockResolvedValue({
      canceled: false,
      assets: [{ uri: 'file:///statement.csv', name: 'statement.csv' } as never],
    } as never);
  });

  async function reachReview() {
    render(tree());
    fireEvent.press(await screen.findByText('Choose a file'));
    await act(async () => {});
    await waitFor(() => expect(screen.queryByTestId('upload-completed')).toBeNull(), { timeout: 3000 });
    await screen.findByText(/^Import \d+ transaction/);
  }

  it('hides credit limit and due date fields for the default (non-credit-card) account type', async () => {
    await reachReview();

    expect(screen.queryByLabelText('Credit limit')).toBeNull();
    expect(screen.queryByLabelText('Payment due date')).toBeNull();
  });

  it('shows credit limit and due date fields once the new account is switched to Credit Card', async () => {
    await reachReview();

    fireEvent.press(screen.getByText('Credit Card'));

    expect(screen.getByLabelText('Credit limit')).toBeTruthy();
    expect(screen.getByLabelText('Payment due date')).toBeTruthy();
  });

  it('sends the typed credit limit and due date on confirm', async () => {
    api.import.confirm.mockResolvedValue({
      imported: 1, skipped: 0, duplicatesDetected: 0, transfersIdentified: 0, newMerchantsLearned: 0,
      accountsCreated: [], productsCreated: {}, categoriesAssigned: {}, warnings: [],
      account: null, totalCredits: 0, totalDebits: 45, statementOpeningBalance: null,
      statementClosingBalance: null, statementPeriodStart: null, statementPeriodEnd: null,
      importDurationMs: 1, source: 'fresh',
    } as never);
    await reachReview();

    fireEvent.press(screen.getByText('Credit Card'));
    fireEvent.changeText(screen.getByLabelText('Credit limit'), '50000');
    fireEvent.changeText(screen.getByLabelText('Payment due date'), '2026-09-20');

    await pressImport();

    expect(api.import.confirm).toHaveBeenCalledTimes(1);
    const [payload] = api.import.confirm.mock.calls[0];
    expect(payload.newAccount).toMatchObject({ creditLimit: 50000, dueDate: '2026-09-20' });
  });
});

describe('ImportScreen — upload completion dwell', () => {
  beforeEach(() => {
    mockRouteParams = undefined;
    mockNavigate.mockClear();
    api.accounts.list.mockReset().mockResolvedValue([]);
    api.categories.list.mockReset().mockResolvedValue([]);
    api.import.listSessions.mockReset().mockResolvedValue([]);
    api.import.stageCsv.mockReset().mockResolvedValue({
      sessionId: 'session-1',
      multiAccount: false,
      sections: null,
      staging: { rows: [], totalParsed: 0, flaggedDuplicates: 0, detectedAccount: detected, unparseableRows: [] },
    } as never);
    jest.mocked(DocumentPicker.getDocumentAsync).mockReset().mockResolvedValue({
      canceled: false,
      assets: [{ uri: 'file:///statement.csv', name: 'statement.csv' } as never],
    } as never);
  });

  // The web app's Import.tsx has the identical UPLOAD_COMPLETE_DWELL_MS pause and the identical
  // celebrateThenAdvance mechanism; this locks in that this screen's copy of it actually holds the
  // step on 'upload' (rendering the Completed checkmark) rather than jumping the instant stageCsv
  // resolves.
  it('flashes a Completed checkmark before advancing to the review step', async () => {
    render(tree());

    fireEvent.press(await screen.findByText('Choose a file'));
    await act(async () => {});

    expect(await screen.findByTestId('upload-completed')).toBeTruthy();
    expect(screen.queryByText('Choose a file')).toBeNull();
    // Bug fix: "Cancel upload" used to stay on screen through the whole dwell -- uploadProgress
    // (what its old visibility check read) isn't reset to null until the dwell timer fires, so it
    // outlived the request it was meant to cancel. Pressing it did nothing by then (the abort
    // controller is already null), which is a dead button sitting next to a success checkmark.
    expect(screen.queryByText('Cancel upload')).toBeNull();

    // ...and then it actually does move on to the review step, on its own, with no further
    // interaction. A longer timeout than the default 1000ms: UPLOAD_COMPLETE_DWELL_MS alone is
    // 900ms, real (not faked) timers here, same as every other test in this file.
    await waitFor(() => expect(screen.queryByTestId('upload-completed')).toBeNull(), { timeout: 3000 });
    expect(await screen.findByText(/^Import \d+ transaction/)).toBeTruthy();
  });
});

function jobProgress(over: Partial<import('../../api/endpoints').ImportJobProgress> = {}) {
  return {
    jobId: 'job-1', fileName: 'statement.csv', status: 'QUEUED', userStatus: 'PROCESSING',
    rowsTotal: null, rowsProcessed: 0, createdAt: '2026-09-08T00:00:00Z', startedAt: null,
    finishedAt: null, importSessionId: null, error: null, correlationId: null, ...over,
  } as import('../../api/endpoints').ImportJobProgress;
}

/**
 * Renders with `import-jobs-availability` pre-seeded true, rather than left for the mocked
 * queryFn to resolve on its own. Every test below fires the "Choose a file" press right after
 * render, and upload() reads asyncAvailable SYNCHRONOUSLY at that instant -- so whether React
 * Query's own promise-then-setState chain has committed by then is a genuine race against this
 * test's very next line, not something a fixed number of act() flushes makes deterministic.
 * Seeding the cache directly removes the race; availability() is still mocked (see beforeEach) so
 * anything that calls it again mid-test gets a consistent answer.
 */
function treeAsyncAvailable() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  queryClient.setQueryData(['import-jobs-availability'], { asyncImportAvailable: true });
  return (
    <QueryClientProvider client={queryClient}>
      <ImportScreen />
    </QueryClientProvider>
  );
}

/**
 * Phase 4 (Medium-Tier Parity). The queue, when this deployment has one and the file needs no
 * password -- ImportScreen.upload()'s own doc comment. Real (not faked) timers throughout, same
 * convention as "upload completion dwell" above: ImportProgressCard's own poll schedule starts at
 * 100ms, so a 3000ms waitFor timeout comfortably covers a few real polls settling.
 */
describe('ImportScreen — async import job (Phase 4)', () => {
  beforeEach(() => {
    mockRouteParams = undefined;
    mockNavigate.mockClear();
    api.accounts.list.mockReset().mockResolvedValue([]);
    api.categories.list.mockReset().mockResolvedValue([]);
    api.import.listSessions.mockReset().mockResolvedValue([]);
    api.import.stageCsv.mockReset();
    api.import.getSession.mockReset();
    api.import.discardSession.mockReset().mockResolvedValue(undefined as never);
    api.importJobs.availability.mockReset().mockResolvedValue({ asyncImportAvailable: true });
    api.importJobs.submit.mockReset().mockResolvedValue({ jobId: 'job-1', statusUrl: '/import/jobs/job-1' });
    api.importJobs.progress.mockReset();
    api.importJobs.timeline.mockReset();
    jest.mocked(DocumentPicker.getDocumentAsync).mockReset().mockResolvedValue({
      canceled: false,
      assets: [{ uri: 'file:///statement.csv', name: 'statement.csv' } as never],
    } as never);
  });

  it('submits through the queue instead of staging synchronously', async () => {
    api.importJobs.progress.mockResolvedValue(jobProgress());
    render(treeAsyncAvailable());

    fireEvent.press(await screen.findByText('Choose a file'));
    await settle();

    expect(api.importJobs.submit).toHaveBeenCalledTimes(1);
    expect(api.import.stageCsv).not.toHaveBeenCalled();
    expect(await screen.findByText('Waiting to start')).toBeTruthy();
  });

  it('opens the review step once the queued job completes', async () => {
    api.importJobs.progress.mockResolvedValueOnce(
      jobProgress({ status: 'COMPLETED', userStatus: 'COMPLETED', rowsTotal: 1, rowsProcessed: 1, importSessionId: 'session-1' })
    );
    api.import.getSession.mockResolvedValue({
      sessionId: 'session-1',
      staging: {
        rows: [stagedRow('Coffee')], totalParsed: 1, flaggedDuplicates: 0,
        detectedAccount: detectedWithBank, unparseableRows: [],
      },
    } as never);
    render(treeAsyncAvailable());

    fireEvent.press(await screen.findByText('Choose a file'));
    await settle();

    await waitFor(() => expect(api.import.getSession).toHaveBeenCalledWith('session-1'), { timeout: 3000 });
    expect(await screen.findByText(/^Import \d+ transaction/)).toBeTruthy();
  });

  it('shows the curated failure reason and a way back once the job fails', async () => {
    api.importJobs.progress.mockResolvedValueOnce(
      jobProgress({ status: 'FAILED', userStatus: 'FAILED', error: 'raw stack trace, not for the user' })
    );
    api.importJobs.timeline.mockResolvedValue({
      jobId: 'job-1', status: 'FAILED', userStatus: 'FAILED', failureCode: 'IMPORT_001', stages: [],
    });
    render(treeAsyncAvailable());

    fireEvent.press(await screen.findByText('Choose a file'));
    await settle();

    await waitFor(() => expect(screen.getByText(/couldn't find a transaction table/i)).toBeTruthy(), { timeout: 3000 });
    // The raw, untranslated error never reaches the screen -- see importJob.ts's detail() doc
    // comment on why FAILED returns null there, leaving only the curated timeline reason.
    expect(screen.queryByText('raw stack trace, not for the user')).toBeNull();

    fireEvent.press(screen.getByText('Choose a different file'));
    expect(await screen.findByText('Choose a file')).toBeTruthy();
  });

  it('blocks a multi-account result the same way the synchronous path does', async () => {
    api.importJobs.progress.mockResolvedValueOnce(
      jobProgress({ status: 'COMPLETED', userStatus: 'COMPLETED', rowsTotal: 3, rowsProcessed: 3, importSessionId: 'session-1' })
    );
    // getSession's `staging` is absent for a multi-account result, mirroring
    // PdfStagingSessionResult.staging being null when multiAccount is true.
    api.import.getSession.mockResolvedValue({ sessionId: 'session-1', staging: null } as never);
    render(treeAsyncAvailable());

    fireEvent.press(await screen.findByText('Choose a file'));
    await settle();

    await waitFor(() => expect(api.import.discardSession).toHaveBeenCalledWith('session-1'), { timeout: 3000 });
    expect(await screen.findByText(/more than one account/i)).toBeTruthy();
    expect(await screen.findByText('Choose a file')).toBeTruthy();
  });

  it('returns straight to the dropzone when the job is cancelled, with no failure banner', async () => {
    api.importJobs.progress.mockResolvedValueOnce(jobProgress({ status: 'CANCELLED', userStatus: 'CANCELLED' }));
    render(treeAsyncAvailable());

    fireEvent.press(await screen.findByText('Choose a file'));
    await settle();

    await waitFor(() => expect(screen.getByText('Choose a file')).toBeTruthy(), { timeout: 3000 });
    expect(screen.queryByText('Cancelled')).toBeNull();
  });
});

/**
 * Phase 4 (Medium-Tier Parity). importApi.listFailures() had existed with zero UI callers on
 * either client (see the useQuery's own doc comment in ImportScreen.tsx). No session/state to
 * resume for a document that never became an ImportSession -- "Try again" just reopens the file
 * picker, same as every other fresh-upload entry point on this screen.
 */
describe('ImportScreen — recent failed imports (Phase 4)', () => {
  beforeEach(() => {
    mockRouteParams = undefined;
    mockNavigate.mockClear();
    api.accounts.list.mockReset().mockResolvedValue([]);
    api.categories.list.mockReset().mockResolvedValue([]);
    api.import.listSessions.mockReset().mockResolvedValue([]);
    jest.mocked(DocumentPicker.getDocumentAsync).mockReset().mockResolvedValue({
      canceled: false,
      assets: [{ uri: 'file:///statement.csv', name: 'statement.csv' } as never],
    } as never);
  });

  it('shows nothing when there is no failure history', async () => {
    api.import.listFailures.mockReset().mockResolvedValue([]);
    render(tree());

    await screen.findByText('Choose a file');
    expect(screen.queryByText('Recent failed imports')).toBeNull();
  });

  it('shows the curated failure reason for a recognised code', async () => {
    api.import.listFailures.mockReset().mockResolvedValue([
      { reference: 'ref-1', fileName: 'old-statement.pdf', failureCode: 'IMPORT_001', createdAt: '2026-09-01T00:00:00Z' },
    ]);
    render(tree());

    expect(await screen.findByText('old-statement.pdf')).toBeTruthy();
    expect(await screen.findByText(/couldn't find a transaction table/i)).toBeTruthy();
  });

  it('falls back to a generic message for a code with no curated entry', async () => {
    api.import.listFailures.mockReset().mockResolvedValue([
      { reference: 'ref-2', fileName: 'weird.pdf', failureCode: null, createdAt: '2026-09-01T00:00:00Z' },
    ]);
    render(tree());

    expect(await screen.findByText("Fynora couldn't complete this import.")).toBeTruthy();
  });

  it('opens the file picker from "Try again", the same entry point as a fresh upload', async () => {
    api.import.listFailures.mockReset().mockResolvedValue([
      { reference: 'ref-3', fileName: 'old-statement.pdf', failureCode: 'IMPORT_007', createdAt: '2026-09-01T00:00:00Z' },
    ]);
    api.import.stageCsv.mockReset().mockReturnValue(new Promise(() => {})); // never resolves; only the picker call is asserted
    render(tree());

    fireEvent.press(await screen.findByLabelText('Try importing old-statement.pdf again'));
    await settle();

    expect(DocumentPicker.getDocumentAsync).toHaveBeenCalled();
  });
});
