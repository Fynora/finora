import { act, render, screen, fireEvent, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import * as DocumentPicker from 'expo-document-picker';
import { ImportScreen } from './ImportScreen';
import { accountsApi, categoriesApi, importApi, statementImportsApi } from '../../api/endpoints';
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

describe('ImportScreen — new-account opening balance field', () => {
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
        rows: [stagedRow('Groceries')],
        totalParsed: 1,
        flaggedDuplicates: 0,
        // Unlike the shared empty `detected` fixture above, buildNewAccountPayload (only reached
        // once a fresh-upload confirm actually goes through, which no other test in this file
        // does) dereferences `.bank.id` unconditionally, so this path needs a real bank object.
        detectedAccount: { bank: { id: 'OTHER' } } as DetectedAccountInfo,
        unparseableRows: [],
      },
    } as never);
    api.import.confirm.mockReset().mockResolvedValue({
      imported: 1, skipped: 0, duplicatesDetected: 0, transfersIdentified: 0, newMerchantsLearned: 0,
      accountsCreated: [], productsCreated: {}, categoriesAssigned: {}, warnings: [],
      account: null, totalCredits: 0, totalDebits: 45, statementOpeningBalance: null,
      statementClosingBalance: null, statementPeriodStart: null, statementPeriodEnd: null,
      importDurationMs: 1, source: 'upload',
    } as never);
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

  // lib/importPayload.ts's NewAccountForm already carries openingBalance, buildNewAccountPayload()
  // already sends it, and initialAccountForm() already prefills it from the detected statement --
  // but the screen itself never rendered a field for it, so it could only ever be sent as whatever
  // the detected value (or '') happened to be, with no way for the user to set or correct it.
  it('lets the user set an opening balance for a new account and sends it on confirm', async () => {
    await reachReview();

    fireEvent.changeText(screen.getByLabelText('Opening balance'), '5000');
    await pressImport();

    expect(api.import.confirm).toHaveBeenCalledTimes(1);
    const [payload] = api.import.confirm.mock.calls[0];
    expect(payload.newAccount).toMatchObject({ openingBalance: 5000 });
  });

  // The other half of the original gap: initialAccountForm() already prefills openingBalance from
  // what the statement itself stated, but with no field to render it in, that prefill was invisible
  // and could never be corrected. Covers the field showing the detected value AND staying editable.
  it('prefills the opening balance from the detected statement, and lets the user correct it', async () => {
    api.import.stageCsv.mockResolvedValue({
      sessionId: 'session-1',
      multiAccount: false,
      sections: null,
      staging: {
        rows: [stagedRow('Groceries')],
        totalParsed: 1,
        flaggedDuplicates: 0,
        detectedAccount: { bank: { id: 'OTHER' }, openingBalance: 1200 } as DetectedAccountInfo,
        unparseableRows: [],
      },
    } as never);
    await reachReview();

    expect(screen.getByLabelText('Opening balance').props.value).toBe('1200');

    fireEvent.changeText(screen.getByLabelText('Opening balance'), '1250');
    await pressImport();

    const [payload] = api.import.confirm.mock.calls[0];
    expect(payload.newAccount).toMatchObject({ openingBalance: 1250 });
  });
});

// Phase 5 (Low-Priority Polish). StagingResult.verification is threaded through hydrateReviewFrom
// into a new VerificationPanel -- these cover the wiring (does the field reach the screen, does an
// absent report render nothing) rather than the panel's own internals, which VerificationPanel's
// own test file covers.
describe('ImportScreen — statement verification panel (Phase 5)', () => {
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

  async function reachReview() {
    render(tree());
    fireEvent.press(await screen.findByText('Choose a file'));
    await settle();
    await waitFor(() => expect(screen.queryByTestId('upload-completed')).toBeNull(), { timeout: 3000 });
    await screen.findByText(/^Import \d+ transaction/);
  }

  it('renders no verification panel when the staging result carries none', async () => {
    api.import.stageCsv.mockReset().mockResolvedValue({
      sessionId: 'session-1',
      multiAccount: false,
      sections: null,
      staging: {
        rows: [stagedRow('Groceries')],
        totalParsed: 1,
        flaggedDuplicates: 0,
        detectedAccount: detected,
        unparseableRows: [],
      },
    } as never);

    await reachReview();

    expect(screen.queryByText('Statement verification')).toBeNull();
  });

  it('shows the verdict for a verified statement and expands to the finding summary', async () => {
    api.import.stageCsv.mockReset().mockResolvedValue({
      sessionId: 'session-1',
      multiAccount: false,
      sections: null,
      staging: {
        rows: [stagedRow('Groceries')],
        totalParsed: 1,
        flaggedDuplicates: 0,
        detectedAccount: detected,
        unparseableRows: [],
        verification: {
          reliabilityStatus: 'CLEAN',
          textSource: 'NATIVE_PDF',
          headerReconstructionUncertain: false,
          findings: [
            {
              rule: 'BALANCE_CHAIN',
              outcome: 'VERIFIED',
              details: { rowsChecked: 12, rowsWithBalance: 12, anchoredOnOpeningBalance: true, discrepancies: [] },
            },
          ],
        },
      },
    } as never);

    await reachReview();

    expect(await screen.findByText('Statement verification')).toBeTruthy();
    expect(screen.getByText('Imported successfully')).toBeTruthy();

    fireEvent.press(screen.getByText('Statement verification'));

    expect(await screen.findByText(/12 transaction\(s\) checked/)).toBeTruthy();
  });
});
