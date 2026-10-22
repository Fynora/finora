import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StatementCorrectionsSection } from './StatementCorrectionsSection';
import { adminStatementCorrectionsApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  adminStatementCorrectionsApi: { waiting: vi.fn(), apply: vi.fn() },
}));

const WAITING = {
  statementImportId: 's1', fileName: 'statement.csv', accountName: 'Card', periodStart: '2026-03-01',
  periodEnd: '2026-03-31', rowsChanged: 0, rowsAdded: 3, rowsRemoved: 3, factsChanged: 0,
  checkedAt: '2026-10-22T18:00:00Z',
};

function renderSection() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <StatementCorrectionsSection userId="u1" />
    </QueryClientProvider>
  );
}

describe('StatementCorrectionsSection', () => {
  beforeEach(() => vi.mocked(adminStatementCorrectionsApi.apply).mockReset());

  it('says so when nothing is waiting', async () => {
    vi.mocked(adminStatementCorrectionsApi.waiting).mockResolvedValue([]);
    renderSection();
    await waitFor(() => expect(screen.getByText(/no correction is waiting/i)).toBeInTheDocument());
  });

  it('applies only with a reason of at least 15 characters, and sends the trimmed reason', async () => {
    vi.mocked(adminStatementCorrectionsApi.waiting).mockResolvedValue([WAITING]);
    vi.mocked(adminStatementCorrectionsApi.apply).mockResolvedValue({
      statementImportId: 's1', status: 'APPLIED', rowsChanged: 0, rowsAdded: 3, rowsRemoved: 3, factsChanged: 0,
      balanceChange: null, reason: null, runId: 'r1', userNotified: true,
    });
    const user = userEvent.setup();
    renderSection();

    await user.click(await screen.findByRole('button', { name: 'Apply correction' }));
    const dialog = await screen.findByRole('dialog');
    const confirm = Array.from(dialog.querySelectorAll('button')).find((b) => b.textContent === 'Apply correction')!;
    expect(confirm).toBeDisabled();

    await user.type(screen.getByLabelText('Reason'), 'too short');
    expect(confirm).toBeDisabled();
    await user.type(screen.getByLabelText('Reason'), ' for the audit log  ');
    expect(confirm).toBeEnabled();
    await user.click(confirm);

    await waitFor(() => expect(adminStatementCorrectionsApi.apply)
      .toHaveBeenCalledWith('s1', 'too short for the audit log'));
    expect(await screen.findByRole('status')).toHaveTextContent(/applied.*the user has been told/i);
  });

  it('reports a correction the refresh held back as not applied', async () => {
    vi.mocked(adminStatementCorrectionsApi.waiting).mockResolvedValue([WAITING]);
    vi.mocked(adminStatementCorrectionsApi.apply).mockResolvedValue({
      statementImportId: 's1', status: 'NEEDS_REVIEW', rowsChanged: 0, rowsAdded: 0, rowsRemoved: 0, factsChanged: 0,
      balanceChange: null, reason: 'TRUST_HOLD', runId: 'r1', userNotified: false,
    });
    const user = userEvent.setup();
    renderSection();

    await user.click(await screen.findByRole('button', { name: 'Apply correction' }));
    await user.type(screen.getByLabelText('Reason'), 'Applying the checked correction');
    const dialog = screen.getByRole('dialog');
    await user.click(Array.from(dialog.querySelectorAll('button')).find((b) => b.textContent === 'Apply correction')!);

    expect(await screen.findByRole('status')).toHaveTextContent(/Not applied \(NEEDS_REVIEW: TRUST_HOLD\)\. Nothing in the account changed/);
  });
});
