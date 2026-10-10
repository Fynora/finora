import { act, render, screen, waitFor, fireEvent } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StatementHistoryScreen } from './StatementHistoryScreen';
import { importJobsApi, statementImportsApi, type ImportJobProgress } from '../api/endpoints';
import { PDF_PASSWORD_INVALID, PDF_PASSWORD_REQUIRED } from '../api/errorCodes';
import type { AccountStatementGroup } from '../types';
import { detail as jobDetail, label as jobLabel } from '../lib/importJob';

// Scoped to re-importing a password-protected statement -- the one flow here where the server's
// answer changes what the screen DOES rather than only what it says.
jest.mock('../api/endpoints', () => ({
  // The statement-refresh banner (step 5): switched off, so it renders nothing.
  statementRefreshApi: {
    overview: jest.fn().mockResolvedValue({ enabled: false, savePasswordAvailable: false, updatable: [], needsPassword: [] }),
  },
  statementImportsApi: {
    listGroupedByAccount: jest.fn(),
    reimport: jest.fn(),
    remove: jest.fn(),
    downloadFile: jest.fn(),
    transactions: jest.fn(),
  },
  // The "Recent imports" card: empty unless a test says otherwise, so it renders nothing.
  importJobsApi: {
    recent: jest.fn().mockResolvedValue([]),
    dismiss: jest.fn(),
  },
}));

// `mock`-prefixed so Jest allows the factory below to close over it -- the factory is hoisted
// above this declaration, and that prefix is the documented opt-out.
const mockNavigateToImport = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ getParent: () => ({ navigate: mockNavigateToImport }) }),
}));

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
}));

const api = statementImportsApi as jest.Mocked<typeof statementImportsApi>;

const bank = {
  id: 'OTHER', officialName: null, shortName: 'Other', colorHex: '#000000', initials: 'OT',
  logoPath: '', category: null, websiteUrl: null, ifscPrefix: null, supportedAccountTypes: [],
};

// One group only: a lone account auto-expands, so the statement row's actions are on screen
// without having to drive the disclosure open first.
const groups: AccountStatementGroup[] = [{
  accountId: 'acct-1',
  accountName: 'HDFC Savings',
  accountType: 'SAVINGS',
  bank,
  deleted: false,
  deletedAt: null,
  primarySource: 'MANUAL',
  statements: [{
    id: 'stmt-1',
    fileName: 'protected-statement.pdf',
    statementPeriodStart: null,
    statementPeriodEnd: null,
    openingBalance: null,
    closingBalance: null,
    transactionsImported: 12,
    transactionsSkipped: 0,
    importedAt: '2026-08-01T10:00:00Z',
    duplicateCount: 0,
  }],
}];

function reimportResult() {
  return {
    staging: {
      rows: [], totalParsed: 0, flaggedDuplicates: 0, unparseableRows: [],
      detectedAccount: {} as never,
    },
    accountId: 'acct-1',
    accountName: 'HDFC Savings',
  };
}

function rejectWith(errorCode: string) {
  // Matches what apiErrorCode() reads: an axios error whose response body carries errorCode.
  return Object.assign(new Error('Request failed'), {
    isAxiosError: true,
    response: { status: 422, data: { errorCode, message: 'server copy' } },
  });
}

function renderScreen() {
  // gcTime 0 so the cache is collected as soon as the screen unmounts. Left at its default, the
  // client keeps a garbage-collection timer alive past teardown and Jest reports a worker that
  // would not exit -- noise in a full run, invisible when this file runs alone.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <StatementHistoryScreen />
    </QueryClientProvider>
  );
}

async function tapReimport() {
  fireEvent.press(await screen.findByLabelText('Re-import'));
  await settle();
}

/** Types a password into the unlock prompt and submits it. */
async function submitPassword(value: string) {
  fireEvent.changeText(screen.getByLabelText('Statement password'), value);
  // The shared Button exposes its label as text, not as an accessibilityLabel.
  fireEvent.press(screen.getByText('Re-import statement'));
  await settle();
}

/**
 * Lets handleReimport's `finally { setBusyId(null) }` land before assertions run.
 *
 * Without it a test returns while that last state update is still queued, React applies it to an
 * already-unmounted tree, and the run fills with act() warnings. It has to happen inside the test
 * -- an afterEach runs after RNTL has already unmounted, which is too late to help.
 */
async function settle() {
  await act(async () => {});
}

describe('StatementHistoryScreen — re-importing a password-protected statement', () => {
  beforeEach(() => {
    mockNavigateToImport.mockReset();
    api.listGroupedByAccount.mockReset().mockResolvedValue(groups);
    api.reimport.mockReset().mockResolvedValue(reimportResult());
  });

  it('re-imports in one tap when no password is needed', async () => {
    renderScreen();

    await tapReimport();

    // The majority case. Trying first is what keeps it a single tap -- offering a password field
    // up front, as the upload flow does, would tax every unprotected statement to help a few.
    await waitFor(() => expect(api.reimport).toHaveBeenCalledWith('stmt-1', undefined));
    expect(screen.queryByLabelText('Statement password')).toBeNull();
  });

  it('hands the staged rows to the Import tab rather than reviewing them here', async () => {
    renderScreen();

    await tapReimport();

    await waitFor(() => expect(mockNavigateToImport).toHaveBeenCalledTimes(1));
    const [screenName, params] = mockNavigateToImport.mock.calls[0];
    expect(screenName).toBe('Import');
    expect(params.reimport).toMatchObject({ statementImportId: 'stmt-1', accountId: 'acct-1' });
    // The nonce is what lets the Import tab tell a fresh arrival from a stale param it has
    // already consumed -- without it, tapping Import later would re-enter this same re-import.
    expect(typeof params.reimport.nonce).toBe('number');
  });

  it('prompts for the password when the stored file turns out to be protected', async () => {
    api.reimport.mockReset().mockRejectedValue(rejectWith(PDF_PASSWORD_REQUIRED));
    renderScreen();

    await tapReimport();

    expect(await screen.findByLabelText('Statement password')).toBeTruthy();
    // A locked statement is not a failed re-import, and saying so would send someone looking for a
    // problem with the statement instead of for the password.
    expect(screen.queryByText(/could not re-import this statement/i)).toBeNull();
    expect(mockNavigateToImport).not.toHaveBeenCalled();
  });

  it('lets the user reveal the password they typed into the unlock prompt', async () => {
    // The bank's statement password has no confirmation field and the only other feedback is the
    // re-import failing again, so the prompt carries the same Show/Hide control TextField renders.
    api.reimport.mockReset().mockRejectedValue(rejectWith(PDF_PASSWORD_REQUIRED));
    renderScreen();

    await tapReimport();
    const field = await screen.findByLabelText('Statement password');
    fireEvent.changeText(field, 'AAAA1234');
    expect(field.props.secureTextEntry).toBe(true);

    fireEvent.press(screen.getByLabelText('Show password'));
    expect(screen.getByLabelText('Statement password').props.secureTextEntry).toBe(false);

    fireEvent.press(screen.getByLabelText('Hide password'));
    expect(screen.getByLabelText('Statement password').props.secureTextEntry).toBe(true);
  });

  it('retries with the password and continues to the Import tab', async () => {
    api.reimport.mockReset()
      .mockRejectedValueOnce(rejectWith(PDF_PASSWORD_REQUIRED))
      .mockResolvedValueOnce(reimportResult());
    renderScreen();

    await tapReimport();
    await screen.findByLabelText('Statement password');

    await submitPassword('AAAA1234');

    await waitFor(() => expect(api.reimport).toHaveBeenCalledTimes(2));
    expect(api.reimport).toHaveBeenLastCalledWith('stmt-1', 'AAAA1234');
    await waitFor(() => expect(mockNavigateToImport).toHaveBeenCalledTimes(1));

    // The regression this guards: the password unlocked staging, but confirmReimport() re-parses
    // the same stored bytes server-side and needs it again -- ConfirmRequest had nowhere to carry
    // it, so every reimport-confirm of a protected statement failed unconditionally regardless of
    // whether the password above was ever correct. Dropping it here, before the Import tab ever
    // sees it, is exactly how that happened. See StatementImportService.confirmReimport's doc
    // comment for the incident this is named after.
    const [, params] = mockNavigateToImport.mock.calls[0];
    expect(params.reimport.password).toBe('AAAA1234');
  });

  // Otherwise a tap on "Cancel" while the retried reimport() is still in flight dismisses the
  // prompt -- and if that stale call then succeeds, its own success path navigates to the Import
  // tab anyway (unmount doesn't cancel the in-flight request), pulling the user away from wherever
  // they went after cancelling; if it fails as PDF_PASSWORD_INVALID, the prompt silently reopens
  // with a "wrong password" message the user never asked to see again.
  it('disables Cancel on the password prompt while the retry is in flight', async () => {
    api.reimport.mockReset().mockRejectedValueOnce(rejectWith(PDF_PASSWORD_REQUIRED));
    renderScreen();
    await tapReimport();
    await screen.findByLabelText('Statement password');

    let resolveReimport!: (v: ReturnType<typeof reimportResult>) => void;
    api.reimport.mockReset().mockReturnValue(
      new Promise((resolve) => { resolveReimport = resolve; })
    );
    fireEvent.changeText(screen.getByLabelText('Statement password'), 'AAAA1234');
    fireEvent.press(screen.getByText('Re-import statement'));
    await settle();

    expect(
      screen.getByRole('button', { name: 'Cancel' }).props.accessibilityState.disabled
    ).toBe(true);

    await act(async () => { resolveReimport(reimportResult()); });
  });

  it('does not invent a password for a statement that never needed one', async () => {
    renderScreen();

    await tapReimport();

    await waitFor(() => expect(mockNavigateToImport).toHaveBeenCalledTimes(1));
    const [, params] = mockNavigateToImport.mock.calls[0];
    expect(params.reimport.password).toBeUndefined();
  });

  it('keeps the prompt open with an inline error when the password is rejected', async () => {
    api.reimport.mockReset().mockRejectedValue(rejectWith(PDF_PASSWORD_REQUIRED));
    renderScreen();

    await tapReimport();
    await screen.findByLabelText('Statement password');

    api.reimport.mockRejectedValue(rejectWith(PDF_PASSWORD_INVALID));
    await submitPassword('WRONG999');

    expect(await screen.findByText(/didn't open this statement/i)).toBeTruthy();
    // Still open, and still holding what was typed -- clearing it reads as though the app lost
    // the statement.
    expect(screen.getByLabelText('Statement password').props.value).toBe('WRONG999');
  });

  it('still reports a genuine re-import failure as an error, not as a password problem', async () => {
    api.reimport.mockReset().mockRejectedValue(rejectWith('GEN_002'));
    renderScreen();

    await tapReimport();

    expect(await screen.findByText('server copy')).toBeTruthy();
    expect(screen.queryByLabelText('Statement password')).toBeNull();
  });

  it('does not offer the statement password to the OS keychain', async () => {
    api.reimport.mockReset().mockRejectedValue(rejectWith(PDF_PASSWORD_REQUIRED));
    renderScreen();

    await tapReimport();
    const field = await screen.findByLabelText('Statement password');

    // The bank's password for one document, not a Fynora credential.
    expect(field.props.autoComplete).toBe('off');
    expect(field.props.textContentType).toBe('none');
    expect(field.props.secureTextEntry).toBe(true);
  });

  it('does not stage a second re-import for a double-tap on the same row', async () => {
    renderScreen();
    const button = await screen.findByLabelText('Re-import');

    // Both presses inside ONE act() block, not two separate fireEvent.press calls. Each individual
    // fireEvent.press is its own act(), which flushes the pending setBusyId and re-renders before
    // returning -- by the second call Pressable's own `disabled` prop has already caught up and the
    // press never reaches the handler at all, passing this assertion whether or not the guard being
    // tested exists (confirmed: it does, with the guard removed). A real double-tap lands both
    // touches in the same JS tick, before `disabled` reaches the native side; wrapping both calls in
    // one act() reproduces that by deferring the re-render until after both have fired. Without the
    // guard this stages a second server-side session for one re-import (B5 in
    // mobile-correctness-trust-roadmap.md); the confirm side is already claimed atomically by V133,
    // but nothing upstream of it was.
    act(() => {
      fireEvent.press(button);
      fireEvent.press(button);
    });
    await settle();

    expect(api.reimport).toHaveBeenCalledTimes(1);
  });

  it('allows re-importing a different statement while one is still in flight', async () => {
    // The guard must be scoped per-row, not to the whole screen -- a global lock here would repeat
    // the CategoryReviewScreen mistake of blocking an unrelated row's action just because another
    // row's request hasn't settled yet.
    api.listGroupedByAccount.mockReset().mockResolvedValue([{
      ...groups[0],
      statements: [
        groups[0].statements[0],
        { ...groups[0].statements[0], id: 'stmt-2', fileName: 'other-statement.pdf' },
      ],
    }]);
    renderScreen();
    const buttons = await screen.findAllByLabelText('Re-import');
    expect(buttons).toHaveLength(2);

    fireEvent.press(buttons[0]);
    fireEvent.press(buttons[1]);
    await settle();

    expect(api.reimport).toHaveBeenCalledTimes(2);
    expect(api.reimport).toHaveBeenCalledWith('stmt-1', undefined);
    expect(api.reimport).toHaveBeenCalledWith('stmt-2', undefined);
  });

  it('does not offer re-import for a statement whose account was deleted', async () => {
    api.listGroupedByAccount.mockReset().mockResolvedValue([
      { ...groups[0], deleted: true, deletedAt: '2026-08-01T00:00:00Z' },
    ]);
    renderScreen();

    // There is nowhere to replay the rows into, so the action is present but not usable rather
    // than silently failing at the server.
    const button = await screen.findByLabelText('Re-import');
    expect(button.props.accessibilityState.disabled).toBe(true);
  });

  // Parallel gap to web's StatementHistory.tsx (same fix, same underlying bug): this button had
  // no primarySource check at all, so an AA-linked account's statement history still offered
  // re-import unconditionally -- only refused by AccountAggregatorGuard's 409 after staging
  // completed and the user tried to confirm.
  it('does not offer re-import for a statement whose account is AA-linked', async () => {
    api.listGroupedByAccount.mockReset().mockResolvedValue([
      { ...groups[0], primarySource: 'ACCOUNT_AGGREGATOR' },
    ]);
    renderScreen();

    expect(await screen.findByText('Bank Sync active')).toBeTruthy();
    const button = await screen.findByLabelText('Re-import');
    expect(button.props.accessibilityState.disabled).toBe(true);
  });

  it('offers re-import normally for a manually-imported account (the default fixture)', async () => {
    renderScreen();

    expect(screen.queryByText('Bank Sync active')).toBeNull();
    const button = await screen.findByLabelText('Re-import');
    expect(button.props.accessibilityState.disabled).toBeFalsy();
  });
});

/**
 * Held, rejected and resolved-statement pushes all land on this screen. It used to list only
 * statements that finished importing, so the upload a push was about appeared nowhere, and a
 * rejected import could not be found again on this app at all once the Import tab's progress card
 * was gone.
 */
describe('StatementHistoryScreen — recent imports', () => {
  const jobs = importJobsApi as jest.Mocked<typeof importJobsApi>;

  function job(overrides: Partial<ImportJobProgress>): ImportJobProgress {
    return {
      jobId: 'job-1', fileName: 'statement.pdf', status: 'FAILED', userStatus: 'FAILED',
      rowsTotal: 191, rowsProcessed: 191, createdAt: '2026-10-03T14:18:58Z', startedAt: null,
      finishedAt: null, importSessionId: null, error: null, correlationId: null,
      ...overrides,
    };
  }

  beforeEach(() => {
    api.listGroupedByAccount.mockReset().mockResolvedValue([]);
    jobs.recent.mockReset();
  });

  it('shows a rejected import with the reason the server gives', async () => {
    jobs.recent.mockResolvedValue([job({
      fileName: 'rejected.pdf',
      error: 'We checked this statement and could not read it accurately enough to import it. Nothing was added to your accounts.',
    })]);
    renderScreen();

    expect(await screen.findByText('Recent imports')).toBeOnTheScreen();
    expect(screen.getByText('rejected.pdf')).toBeOnTheScreen();
    expect(screen.getByText(/Couldn't finish/)).toBeOnTheScreen();
    expect(screen.getByText(/could not read it accurately enough to import it/)).toBeOnTheScreen();
  });

  it('never shows a failed import without a reason, even when the server sends none', async () => {
    jobs.recent.mockResolvedValue([job({ error: null })]);
    renderScreen();

    expect(await screen.findByText("Fynora couldn't complete this import. Please try again.")).toBeOnTheScreen();
  });

  // The held copy itself belongs to lib/importJob and is asserted there; this checks the card shows
  // whatever that module says, so a copy change does not have to touch this file too.
  it('shows a held import as being checked', async () => {
    const held = job({ status: 'HELD_FOR_TRUST_REVIEW', userStatus: 'HELD_FOR_REVIEW', fileName: 'held.pdf' });
    jobs.recent.mockResolvedValue([held]);
    renderScreen();

    expect(await screen.findByText('held.pdf')).toBeOnTheScreen();
    const escaped = jobLabel(held).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    expect(screen.getByText(new RegExp(`^${escaped}`))).toBeOnTheScreen();
    expect(screen.getByText(jobDetail(held) as string)).toBeOnTheScreen();
  });

  // A failure stays listed until newer uploads push it out -- including after the statement was
  // uploaded again and imported -- so once read, the user can clear it.
  it('dismisses a failed import from the card', async () => {
    const failed = job({ jobId: 'job-failed', fileName: 'rejected.pdf' });
    const held = job({ jobId: 'job-held', status: 'HELD_FOR_TRUST_REVIEW', userStatus: 'HELD_FOR_REVIEW', fileName: 'held.pdf' });
    jobs.recent.mockResolvedValueOnce([failed, held]).mockResolvedValue([held]);
    jobs.dismiss.mockReset().mockResolvedValue(undefined);
    renderScreen();

    fireEvent.press(await screen.findByLabelText('Dismiss rejected.pdf'));
    await settle();

    expect(screen.queryByText('rejected.pdf')).toBeNull();
    expect(jobs.dismiss).toHaveBeenCalledWith('job-failed');
    expect(screen.getByText('held.pdf')).toBeOnTheScreen();
  });

  // Gate 1 spec §4: an overdue hold's apology offers uploading a different statement meanwhile, so
  // the row that shows the apology offers the way to do it. Within the promise it offers nothing.
  it('offers a different statement on an overdue hold, and only there', async () => {
    jobs.recent.mockResolvedValue([
      job({ jobId: 'job-overdue', status: 'HELD_FOR_TRUST_REVIEW', userStatus: 'HELD_FOR_REVIEW', fileName: 'overdue.pdf', holdOverdue: true }),
      job({ jobId: 'job-held', status: 'HELD_FOR_REVIEW', userStatus: 'HELD_FOR_REVIEW', fileName: 'held.pdf', holdOverdue: false }),
    ]);
    renderScreen();

    expect(await screen.findByText('overdue.pdf')).toBeOnTheScreen();
    expect(screen.queryByLabelText('Upload a different statement instead of held.pdf')).toBeNull();
    fireEvent.press(screen.getByLabelText('Upload a different statement instead of overdue.pdf'));

    expect(mockNavigateToImport).toHaveBeenCalledWith('Import');
  });

  it('offers no dismiss on an import that is not over', async () => {
    jobs.recent.mockResolvedValue([
      job({ jobId: 'job-held', status: 'HELD_FOR_TRUST_REVIEW', userStatus: 'HELD_FOR_REVIEW', fileName: 'held.pdf' }),
      job({ jobId: 'job-running', status: 'PARSING', userStatus: 'PROCESSING', fileName: 'running.pdf' }),
    ]);
    renderScreen();

    expect(await screen.findByText('held.pdf')).toBeOnTheScreen();
    expect(screen.queryByLabelText(/^Dismiss/)).toBeNull();
  });

  it('offers dismiss on a cancelled import', async () => {
    jobs.recent.mockResolvedValue([job({ status: 'CANCELLED', userStatus: 'CANCELLED', fileName: 'cancelled.csv' })]);
    renderScreen();

    expect(await screen.findByLabelText('Dismiss cancelled.csv')).toBeOnTheScreen();
  });

  it('keeps the row and says so when dismissing fails', async () => {
    jobs.recent.mockResolvedValue([job({ jobId: 'job-failed', fileName: 'rejected.pdf' })]);
    jobs.dismiss.mockReset().mockRejectedValue(new Error('network error'));
    renderScreen();

    fireEvent.press(await screen.findByLabelText('Dismiss rejected.pdf'));
    await settle();

    expect(screen.getByText("Couldn't dismiss rejected.pdf. Please try again.")).toBeOnTheScreen();
    expect(screen.getByText('rejected.pdf')).toBeOnTheScreen();
  });

  it('leaves completed imports to the statement list', async () => {
    jobs.recent.mockResolvedValue([job({ status: 'COMPLETED', userStatus: 'COMPLETED', fileName: 'done.pdf' })]);
    renderScreen();

    await waitFor(() => expect(jobs.recent).toHaveBeenCalled());
    await settle();
    expect(screen.queryByText('Recent imports')).toBeNull();
    expect(screen.queryByText('done.pdf')).toBeNull();
  });

  it('keeps the statement list when the recent imports cannot load', async () => {
    jobs.recent.mockRejectedValue(new Error('offline'));
    api.listGroupedByAccount.mockResolvedValue(groups);
    renderScreen();

    expect(await screen.findByText('HDFC Savings')).toBeOnTheScreen();
    expect(screen.queryByText('Recent imports')).toBeNull();
  });
});
