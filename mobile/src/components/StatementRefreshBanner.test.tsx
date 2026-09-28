import { fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StatementRefreshBanner } from './StatementRefreshBanner';
import { statementRefreshApi, type RefreshPendingStatement, type RefreshRunDetail } from '../api/endpoints';
import { ThemeProvider } from '../theme';

jest.mock('../api/endpoints', () => ({
  statementRefreshApi: { overview: jest.fn(), applyAll: jest.fn(), run: jest.fn(), refreshOne: jest.fn() },
}));

const api = statementRefreshApi as jest.Mocked<typeof statementRefreshApi>;

function pending(id: string, over: Partial<RefreshPendingStatement> = {}): RefreshPendingStatement {
  return {
    statementImportId: id, status: 'CHANGES', fileName: `${id}.pdf`, accountName: 'Savings',
    periodStart: '2026-07-01', periodEnd: '2026-07-31', rowsChanged: 1, rowsAdded: 0, rowsRemoved: 0,
    factsChanged: 0, passwordSaved: false, ...over,
  };
}

function detail(id: string, over: Partial<RefreshRunDetail> = {}): RefreshRunDetail {
  return {
    runId: `run-${id}`, statementImportId: id, fileName: `${id}.pdf`, accountName: 'Savings',
    periodStart: '2026-07-01', periodEnd: '2026-07-31', status: 'APPLIED', createdAt: '2026-09-28T10:00:00Z',
    rowsChanged: 1, rowsAdded: 0, rowsRemoved: 0, factsChanged: 0, balanceChange: -405, reason: null,
    changed: [{ transactionId: 't1', date: '2026-07-01', description: 'SAMPLE GROCER', amount: '450', type: 'EXPENSE',
      changes: [{ field: 'AMOUNT', before: '45', after: '450' }] }],
    added: [], removed: [], skippedAsDuplicate: [], facts: [], ...over,
  };
}

function renderBanner() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={client}>
      <ThemeProvider><StatementRefreshBanner /></ThemeProvider>
    </QueryClientProvider>
  );
}

describe('StatementRefreshBanner (mobile)', () => {
  beforeEach(() => {
    Object.values(api).forEach((f) => (f as jest.Mock).mockReset());
  });

  it('shows nothing while refreshing is switched off', async () => {
    api.overview.mockResolvedValue({ enabled: false, savePasswordAvailable: false, updatable: [pending('s1')], needsPassword: [] });
    renderBanner();
    await waitFor(() => expect(api.overview).toHaveBeenCalled());
    expect(screen.queryByTestId('statement-refresh-banner')).not.toBeOnTheScreen();
  });

  it('updates every statement in one tap, following remaining, then says what changed', async () => {
    api.overview.mockResolvedValueOnce({ enabled: true, savePasswordAvailable: false,
      updatable: [pending('s1'), pending('s2')], needsPassword: [] })
      .mockResolvedValue({ enabled: true, savePasswordAvailable: false, updatable: [], needsPassword: [] });
    api.applyAll.mockResolvedValueOnce({ results: [detail('s1')], remaining: 1 })
      .mockResolvedValueOnce({ results: [detail('s2', { status: 'NO_CHANGES', changed: [], rowsChanged: 0, balanceChange: null })], remaining: 0 });
    renderBanner();

    expect(await screen.findByText('We now read 2 of your statements more accurately')).toBeOnTheScreen();
    fireEvent.press(screen.getByTestId('statement-refresh-update-all'));

    const summary = await screen.findByTestId('refresh-summary');
    expect(api.applyAll).toHaveBeenCalledTimes(2);
    expect(within(summary).getByText(/1 statement was updated/)).toBeOnTheScreen();
    expect(within(summary).getByText('Amount: ₹45.00 → ₹450.00')).toBeOnTheScreen();
    expect(within(summary).getByText('Account balance changed by -₹405.00')).toBeOnTheScreen();
    expect(within(summary).getByText('Already up to date')).toBeOnTheScreen();
  });

  it('stops when a call makes no progress, so it can never loop', async () => {
    api.overview.mockResolvedValue({ enabled: true, savePasswordAvailable: false, updatable: [pending('s1')], needsPassword: [] });
    api.applyAll.mockResolvedValue({ results: [], remaining: 1 });
    renderBanner();

    expect(await screen.findByText('We now read one of your statements more accurately')).toBeOnTheScreen();
    fireEvent.press(screen.getByTestId('statement-refresh-update-all'));

    // Wait for the update to finish (the button's label comes back), then count: a waitFor on the
    // count alone passes at the first call, before any extra ones.
    await waitFor(() => expect(api.applyAll).toHaveBeenCalled());
    expect(await screen.findByText('Update 1 statement')).toBeOnTheScreen();
    expect(api.applyAll).toHaveBeenCalledTimes(1);
  });

  it("asks for a locked statement's password, keeps it only if switched on, and shows what changed", async () => {
    api.overview.mockResolvedValue({ enabled: true, savePasswordAvailable: true, updatable: [],
      needsPassword: [pending('locked', { status: 'NEEDS_PASSWORD' })] });
    api.refreshOne.mockResolvedValueOnce({ statementId: 'locked', status: 'NEEDS_PASSWORD', reason: 'IMPORT_PDF_PASSWORD_INVALID', runId: null })
      .mockResolvedValueOnce({ statementId: 'locked', status: 'APPLIED', reason: null, runId: 'run-locked' });
    api.run.mockResolvedValue(detail('locked'));
    renderBanner();

    fireEvent.press(await screen.findByTestId('statement-refresh-password-locked'));
    const keep = screen.getByTestId('refresh-keep-password');
    expect(keep.props.value).toBe(false);
    fireEvent.changeText(screen.getByLabelText('Statement password'), 'WRONG');
    fireEvent.press(screen.getByTestId('refresh-password-submit'));
    expect(await screen.findByText(/didn't open this statement/)).toBeOnTheScreen();
    expect(api.refreshOne).toHaveBeenLastCalledWith('locked', 'WRONG', false);

    fireEvent.changeText(screen.getByLabelText('Statement password'), 'SYNTH1234');
    fireEvent(screen.getByTestId('refresh-keep-password'), 'valueChange', true);
    fireEvent.press(screen.getByTestId('refresh-password-submit'));

    expect(await screen.findByTestId('refresh-summary')).toBeOnTheScreen();
    expect(api.refreshOne).toHaveBeenLastCalledWith('locked', 'SYNTH1234', true);
    expect(api.run).toHaveBeenCalledWith('run-locked');
  });

  it('does not offer to keep the password where the server cannot', async () => {
    api.overview.mockResolvedValue({ enabled: true, savePasswordAvailable: false, updatable: [],
      needsPassword: [pending('locked', { status: 'NEEDS_PASSWORD' })] });
    renderBanner();

    fireEvent.press(await screen.findByTestId('statement-refresh-password-locked'));

    expect(screen.getByTestId('refresh-password-sheet')).toBeOnTheScreen();
    expect(screen.queryByTestId('refresh-keep-password')).not.toBeOnTheScreen();
  });

  it('flags a removed row the user had edited', async () => {
    api.overview.mockResolvedValue({ enabled: true, savePasswordAvailable: false, updatable: [pending('s1')], needsPassword: [] });
    api.applyAll.mockResolvedValue({ results: [detail('s1', { changed: [], rowsChanged: 0, rowsRemoved: 1,
      removed: [{ transactionId: 't9', date: '2026-07-09', description: 'PAGE 1 OF 2', amount: '3', type: 'EXPENSE', userEdited: true }] })],
      remaining: 0 });
    renderBanner();

    fireEvent.press(await screen.findByTestId('statement-refresh-update-all'));

    expect(await screen.findByText('You had edited this row')).toBeOnTheScreen();
  });
});
