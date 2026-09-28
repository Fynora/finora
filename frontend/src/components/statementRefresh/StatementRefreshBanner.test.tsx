import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { StatementRefreshBanner } from './StatementRefreshBanner';
import { statementRefreshApi, type RefreshPendingStatement, type RefreshRunDetail } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  statementRefreshApi: { overview: vi.fn(), applyAll: vi.fn(), run: vi.fn(), refreshOne: vi.fn() },
}));

const api = vi.mocked(statementRefreshApi);

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
  return render(<QueryClientProvider client={client}><StatementRefreshBanner /></QueryClientProvider>);
}

describe('StatementRefreshBanner', () => {
  beforeEach(() => {
    Object.values(api).forEach((f) => f.mockReset());
  });

  it('shows nothing while refreshing is switched off', async () => {
    api.overview.mockResolvedValue({ enabled: false, savePasswordAvailable: false, updatable: [pending('s1')], needsPassword: [] });
    const { container } = renderBanner();
    await waitFor(() => expect(api.overview).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it('shows nothing when there is nothing to update', async () => {
    api.overview.mockResolvedValue({ enabled: true, savePasswordAvailable: false, updatable: [], needsPassword: [] });
    const { container } = renderBanner();
    await waitFor(() => expect(api.overview).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it('updates every statement in one tap, following remaining, then says what changed', async () => {
    api.overview.mockResolvedValueOnce({ enabled: true, savePasswordAvailable: false,
      updatable: [pending('s1'), pending('s2')], needsPassword: [] })
      .mockResolvedValue({ enabled: true, savePasswordAvailable: false, updatable: [], needsPassword: [] });
    api.applyAll.mockResolvedValueOnce({ results: [detail('s1')], remaining: 1 })
      .mockResolvedValueOnce({ results: [detail('s2', { status: 'NO_CHANGES', changed: [], rowsChanged: 0, balanceChange: null })], remaining: 0 });
    const user = userEvent.setup();
    renderBanner();

    expect(await screen.findByText('We now read 2 of your statements more accurately')).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: /update 2 statements/i }));

    const summary = await screen.findByTestId('refresh-summary');
    expect(api.applyAll).toHaveBeenCalledTimes(2);
    expect(within(summary).getByText(/1 statement was updated/i)).toBeInTheDocument();
    expect(within(summary).getByText(/Amount: ₹45\.00 → ₹450\.00/)).toBeInTheDocument();
    expect(within(summary).getByText(/balance changed by -₹405\.00/i)).toBeInTheDocument();
    expect(within(summary).getByText(/already up to date/i)).toBeInTheDocument();
  });

  it('stops when a call makes no progress, so it can never loop', async () => {
    api.overview.mockResolvedValue({ enabled: true, savePasswordAvailable: false, updatable: [pending('s1')], needsPassword: [] });
    api.applyAll.mockResolvedValue({ results: [], remaining: 1 });
    const user = userEvent.setup();
    renderBanner();
    expect(await screen.findByText('We now read one of your statements more accurately')).toBeInTheDocument();

    await user.click(await screen.findByRole('button', { name: /update 1 statement/i }));

    await waitFor(() => expect(api.applyAll).toHaveBeenCalledTimes(1));
  });

  it('flags a removed row the user had edited', async () => {
    api.overview.mockResolvedValue({ enabled: true, savePasswordAvailable: false, updatable: [pending('s1')], needsPassword: [] });
    api.applyAll.mockResolvedValue({ results: [detail('s1', { changed: [], rowsChanged: 0, rowsRemoved: 1,
      removed: [{ transactionId: 't9', date: '2026-07-09', description: 'PAGE 1 OF 2', amount: '3', type: 'EXPENSE', userEdited: true }] })],
      remaining: 0 });
    const user = userEvent.setup();
    renderBanner();

    await user.click(await screen.findByRole('button', { name: /update 1 statement/i }));

    expect(await screen.findByText(/you had edited this row/i)).toBeInTheDocument();
  });

  it('asks for a locked statement\'s password, keeps it only if ticked, and shows what changed', async () => {
    api.overview.mockResolvedValue({ enabled: true, savePasswordAvailable: true, updatable: [],
      needsPassword: [pending('locked', { status: 'NEEDS_PASSWORD' })] });
    api.refreshOne.mockResolvedValueOnce({ statementId: 'locked', status: 'NEEDS_PASSWORD', reason: 'IMPORT_PDF_PASSWORD_INVALID', runId: null })
      .mockResolvedValueOnce({ statementId: 'locked', status: 'APPLIED', reason: null, runId: 'run-locked' });
    api.run.mockResolvedValue(detail('locked'));
    const user = userEvent.setup();
    renderBanner();

    await user.click(await screen.findByRole('button', { name: /enter the password for locked\.pdf/i }));
    const dialog = screen.getByTestId('refresh-password-dialog');
    const keep = within(dialog).getByLabelText(/keep this password/i);
    expect(keep).not.toBeChecked();
    await user.type(within(dialog).getByLabelText(/statement password/i), 'WRONG');
    await user.click(within(dialog).getByRole('button', { name: /update statement/i }));
    expect(await within(dialog).findByText(/didn't open this statement/i)).toBeInTheDocument();
    expect(api.refreshOne).toHaveBeenLastCalledWith('locked', 'WRONG', false);

    await user.clear(within(dialog).getByLabelText(/statement password/i));
    await user.type(within(dialog).getByLabelText(/statement password/i), 'SYNTH1234');
    await user.click(keep);
    await user.click(within(dialog).getByRole('button', { name: /update statement/i }));

    expect(await screen.findByTestId('refresh-summary')).toBeInTheDocument();
    expect(api.refreshOne).toHaveBeenLastCalledWith('locked', 'SYNTH1234', true);
    expect(api.run).toHaveBeenCalledWith('run-locked');
  });

  it('does not offer to keep the password where the server cannot', async () => {
    api.overview.mockResolvedValue({ enabled: true, savePasswordAvailable: false, updatable: [],
      needsPassword: [pending('locked', { status: 'NEEDS_PASSWORD' })] });
    const user = userEvent.setup();
    renderBanner();

    await user.click(await screen.findByRole('button', { name: /enter the password for locked\.pdf/i }));

    expect(screen.queryByLabelText(/keep this password/i)).not.toBeInTheDocument();
  });
});
