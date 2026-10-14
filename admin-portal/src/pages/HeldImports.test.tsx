import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import HeldImports from './HeldImports';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminBlankedCopyApi, adminHeldImportApi } from '../api/endpoints';
import type { HeldImportRow, HeldImportDetail } from '../types';

// Same reason Notifications.test.tsx mocks these two: AdminLayout renders ThemeToggle (calls
// useTheme()) and there's no real ThemeProvider mounted here.
vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminHeldImportApi: {
    list: vi.fn(),
    summary: vi.fn(),
    get: vi.fn(),
    reprocess: vi.fn(),
    reprocessAll: vi.fn(),
    resolve: vi.fn(),
    download: vi.fn(),
  },
  adminBlankedCopyApi: {
    status: vi.fn(),
    heldImport: vi.fn(),
    heldStatement: vi.fn(),
  },
}));
// jsdom has no URL.createObjectURL; the panel's own test covers what is handed to the download.
vi.mock('../lib/download', () => ({ downloadBlob: vi.fn() }));

const heldRow: HeldImportRow = {
  id: '11111111-1111-1111-1111-111111111111',
  userId: '22222222-2222-2222-2222-222222222222',
  fileName: 'hdfc-june.pdf',
  sourceFormat: 'PDF',
  failureCode: 'IllegalStateException',
  attemptCount: 2,
  recoveryCount: 0,
  createdAt: '2026-09-01T08:59:00Z',
  heldAt: '2026-09-01T09:02:00Z',
};

const heldDetail: HeldImportDetail = {
  job: heldRow,
  lastError: 'IllegalStateException: no header row found near "Txn Date  Narration"',
  correlationId: 'corr-123',
  objectKey: 'statements/11/22/112233.bin',
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <HeldImports />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function mockAuth(permissions: string[], roles: string[] = []) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    roles,
    fullName: 'Ops Admin',
  }));
}

describe('HeldImports', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(adminHeldImportApi.summary).mockResolvedValue({ held: 3, reprocessing: 1 });
    vi.mocked(adminHeldImportApi.list).mockResolvedValue({
      content: [heldRow], page: 0, size: 25, totalElements: 1, totalPages: 1,
    });
    vi.mocked(adminHeldImportApi.get).mockResolvedValue(heldDetail);
    vi.mocked(adminBlankedCopyApi.status).mockResolvedValue({ enabled: false });
    vi.mocked(adminHeldImportApi.reprocess).mockResolvedValue(heldRow);
    vi.mocked(adminHeldImportApi.reprocessAll).mockResolvedValue({ reprocessed: 3 });
    vi.mocked(adminHeldImportApi.resolve).mockResolvedValue(heldRow);
  });

  it('is gated on IMPORT_TRIAGE_MANAGE, not on any permission an admin happens to hold', () => {
    // Reading a held statement is a different kind of access from watching the pipeline's health.
    mockAuth(['PLATFORM_DIAGNOSTICS_VIEW', 'LEARNING_QUEUE_MANAGE']);
    renderPage();

    expect(screen.getByText(/don't have access to this section/i)).toBeInTheDocument();
    expect(adminHeldImportApi.list).not.toHaveBeenCalled();
  });

  it('lists held statements with the curated failure code', async () => {
    mockAuth(['IMPORT_TRIAGE_MANAGE']);
    renderPage();

    expect(await screen.findByText('hdfc-june.pdf')).toBeInTheDocument();
    expect(screen.getByText('IllegalStateException')).toBeInTheDocument();
  });

  /**
   * The privacy split the whole feature rests on: the list is safe to leave open, the detail view
   * is not. If the raw parser error ever reached the table, twenty-five people's statement content
   * would be on screen without a single audit entry being written.
   */
  it('never shows the raw parser error until a row is opened', async () => {
    mockAuth(['IMPORT_TRIAGE_MANAGE']);
    renderPage();
    await screen.findByText('hdfc-june.pdf');

    expect(screen.queryByText(/no header row found/)).not.toBeInTheDocument();
    expect(adminHeldImportApi.get).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: /details/i }));

    expect(await screen.findByText(/no header row found/)).toBeInTheDocument();
    expect(adminHeldImportApi.get).toHaveBeenCalledWith(heldRow.id);
  });

  it('tells the operator that opening a record is logged against them', async () => {
    mockAuth(['IMPORT_TRIAGE_MANAGE']);
    renderPage();
    await screen.findByText('hdfc-june.pdf');
    await userEvent.click(screen.getByRole('button', { name: /details/i }));

    expect(await screen.findByText(/logged against your account/i)).toBeInTheDocument();
  });

  it('reprocesses one job and refreshes the queue', async () => {
    mockAuth(['IMPORT_TRIAGE_MANAGE']);
    renderPage();
    await screen.findByText('hdfc-june.pdf');
    await userEvent.click(screen.getByRole('button', { name: /details/i }));
    await screen.findByText(/no header row found/);

    await userEvent.click(screen.getByRole('button', { name: /^reprocess$/i }));

    await waitFor(() => expect(adminHeldImportApi.reprocess).toHaveBeenCalledWith(heldRow.id));
  });

  /**
   * A 409 here says something specific and actionable -- "the user already re-uploaded this" --
   * and it is the only part an operator can act on. Swallowing it into a generic toast would throw
   * away the whole message.
   */
  it('surfaces the server\'s own conflict message rather than a generic failure', async () => {
    vi.mocked(adminHeldImportApi.reprocess).mockRejectedValue({
      response: { data: { message: 'This user has already re-uploaded the same statement.' } },
    });
    mockAuth(['IMPORT_TRIAGE_MANAGE']);
    renderPage();
    await screen.findByText('hdfc-june.pdf');
    await userEvent.click(screen.getByRole('button', { name: /details/i }));
    await screen.findByText(/no header row found/);

    await userEvent.click(screen.getByRole('button', { name: /^reprocess$/i }));

    expect(await screen.findByText(/already re-uploaded the same statement/i)).toBeInTheDocument();
  });

  async function openDetail() {
    mockAuth(['IMPORT_TRIAGE_MANAGE']);
    renderPage();
    await screen.findByText('hdfc-june.pdf');
    await userEvent.click(screen.getByRole('button', { name: /details/i }));
    await screen.findByText(/no header row found/);
  }

  /**
   * Resolving now tells the user, by email and push, in the operator's own words. Nothing goes until
   * the operator has written something and confirmed they have read what will be sent -- the message
   * leaves the building and cannot be recalled.
   */
  it('cannot resolve until a message to the user is written', async () => {
    await openDetail();

    const resolveButton = screen.getByRole('button', { name: /resolve and notify user/i });
    expect(resolveButton).toBeDisabled();

    await userEvent.type(screen.getByLabelText(/message to the user/i), '   ');
    expect(resolveButton).toBeDisabled();
    expect(adminHeldImportApi.resolve).not.toHaveBeenCalled();
  });

  it('shows the message that will be sent and asks for confirmation before resolving', async () => {
    await openDetail();

    await userEvent.type(
      screen.getByLabelText(/message to the user/i), 'Please download the statement again from your bank.');
    await userEvent.click(screen.getByRole('button', { name: /resolve and notify user/i }));

    expect(await screen.findByText(/will be sent to the user by email and push/i)).toBeInTheDocument();
    expect(screen.getByTestId('resolve-preview')).toHaveTextContent(
      'Please download the statement again from your bank.');
    expect(adminHeldImportApi.resolve).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: /send and resolve/i }));

    await waitFor(() => expect(adminHeldImportApi.resolve).toHaveBeenCalledWith(
      heldRow.id, 'Please download the statement again from your bank.'));
  });

  it('lets the operator go back and edit instead of sending', async () => {
    await openDetail();
    await userEvent.type(screen.getByLabelText(/message to the user/i), 'A draft');
    await userEvent.click(screen.getByRole('button', { name: /resolve and notify user/i }));

    await userEvent.click(await screen.findByRole('button', { name: /^edit$/i }));

    expect(screen.getByLabelText(/message to the user/i)).toHaveValue('A draft');
    expect(adminHeldImportApi.resolve).not.toHaveBeenCalled();
  });

  it('caps the message at 500 characters and shows how many are left', async () => {
    await openDetail();

    const box = screen.getByLabelText(/message to the user/i);
    expect(box).toHaveAttribute('maxlength', '500');
    await userEvent.type(box, 'hello');

    expect(screen.getByText('495 characters left')).toBeInTheDocument();
  });

  it('shows the server\'s reason when resolving is refused', async () => {
    vi.mocked(adminHeldImportApi.resolve).mockRejectedValue({
      response: { data: { message: 'Only a held import can be resolved; this one is FAILED.' } },
    });
    await openDetail();
    await userEvent.type(screen.getByLabelText(/message to the user/i), 'Something');
    await userEvent.click(screen.getByRole('button', { name: /resolve and notify user/i }));
    await userEvent.click(await screen.findByRole('button', { name: /send and resolve/i }));

    expect(await screen.findByText(/only a held import can be resolved/i)).toBeInTheDocument();
  });

  it('describes the queue as failures on our side, not damaged or wrong files', async () => {
    mockAuth(['IMPORT_TRIAGE_MANAGE']);
    renderPage();

    expect(await screen.findByText(/failed on our side/i)).toBeInTheDocument();
    expect(screen.queryByText(/scanned or damaged file/i)).not.toBeInTheDocument();
  });

  it('lets an operator with an admin role download the held statement', async () => {
    vi.mocked(adminHeldImportApi.download).mockResolvedValue(undefined);
    mockAuth(['IMPORT_TRIAGE_MANAGE'], ['ADMIN']);
    renderPage();
    await screen.findByText('hdfc-june.pdf');
    await userEvent.click(screen.getByRole('button', { name: /details/i }));
    await screen.findByText(/no header row found/);

    await userEvent.click(screen.getByRole('button', { name: /download statement/i }));

    await waitFor(() => expect(adminHeldImportApi.download)
      .toHaveBeenCalledWith(heldRow.id, heldRow.fileName));
  });

  /**
   * Found in review: this used to always show a fixed generic string, discarding whatever the
   * server actually said -- unlike reprocess/resolve on this same page, which both surface the
   * real message. A download can fail for a specific, actionable reason too (e.g. the job was
   * resolved by someone else a moment ago), and that reason is what an operator needs to read.
   */
  it('surfaces the server\'s own download error rather than a generic failure', async () => {
    vi.mocked(adminHeldImportApi.download).mockRejectedValue({
      response: { data: { message: 'This job was already resolved by another admin.' } },
    });
    mockAuth(['IMPORT_TRIAGE_MANAGE'], ['ADMIN']);
    renderPage();
    await screen.findByText('hdfc-june.pdf');
    await userEvent.click(screen.getByRole('button', { name: /details/i }));
    await screen.findByText(/no header row found/);

    await userEvent.click(screen.getByRole('button', { name: /download statement/i }));

    expect(await screen.findByText(/already resolved by another admin/i)).toBeInTheDocument();
  });

  /** Mirrors HeldStatementDetail's identical rule and identical reasoning: a role that can work
   *  the queue must not see a control for an action the backend would refuse anyway. */
  it('hides the download control from a non-admin role', async () => {
    mockAuth(['IMPORT_TRIAGE_MANAGE'], ['SUPPORT']);
    renderPage();
    await screen.findByText('hdfc-june.pdf');
    await userEvent.click(screen.getByRole('button', { name: /details/i }));
    await screen.findByText(/no header row found/);

    expect(screen.queryByRole('button', { name: /download statement/i })).not.toBeInTheDocument();
  });

  /**
   * Found in review: the panel was not keyed on selectedId, and the table stays clickable with
   * the panel open (not a modal) -- so switching from one job's Details to another's kept the
   * same component instance and carried the first job's failed-download error into the second
   * job's view, misattributing it.
   */
  it('does not carry a failed download error over when switching to a different held job', async () => {
    const secondRow: HeldImportRow = {
      ...heldRow, id: '33333333-3333-3333-3333-333333333333', fileName: 'sbi-july.pdf',
    };
    vi.mocked(adminHeldImportApi.list).mockResolvedValue({
      content: [heldRow, secondRow], page: 0, size: 25, totalElements: 2, totalPages: 1,
    });
    vi.mocked(adminHeldImportApi.get).mockImplementation((jobId: string) =>
      Promise.resolve(jobId === secondRow.id ? { ...heldDetail, job: secondRow } : heldDetail));
    vi.mocked(adminHeldImportApi.download).mockRejectedValue(new Error('network error'));
    mockAuth(['IMPORT_TRIAGE_MANAGE'], ['ADMIN']);
    renderPage();
    await screen.findByText('hdfc-june.pdf');

    const [firstDetailsButton] = await screen.findAllByRole('button', { name: /details/i });
    await userEvent.click(firstDetailsButton);
    await userEvent.click(await screen.findByRole('button', { name: /download statement/i }));
    await screen.findByText(/could not download this statement/i);

    // Straight to the SECOND row's Details, panel A still open the whole time -- selectedId
    // never passes through null, which is exactly the path that must not carry state over.
    const [, secondDetailsButton] = await screen.findAllByRole('button', { name: /details/i });
    await userEvent.click(secondDetailsButton);
    // Confirms the panel actually switched to job B's data (not just that the stale error faded
    // on its own) -- the filename now appears twice, once in the table row and once in the panel.
    await waitFor(() => expect(screen.getAllByText('sbi-july.pdf')).toHaveLength(2));

    expect(screen.queryByText(/could not download this statement/i)).not.toBeInTheDocument();
  });

  describe('blanked copy', () => {
    async function openDetails() {
      renderPage();
      await screen.findByText('hdfc-june.pdf');
      await userEvent.click(screen.getByRole('button', { name: /details/i }));
      await screen.findByText(/no header row found/);
    }

    /** Unlike the real document, the copy is not pinned to the admin roles: it is blanked. */
    it('is offered to a non-admin role when the switch is on, and fetches this job\'s copy', async () => {
      vi.mocked(adminBlankedCopyApi.status).mockResolvedValue({ enabled: true });
      vi.mocked(adminBlankedCopyApi.heldImport).mockResolvedValue({
        fileName: 'blanked-JOB-1.trace', trace: '# finora-pdf-trace v3\n', check: 'MATCHES',
        notCheckedReason: null, differences: [],
      });
      mockAuth(['IMPORT_TRIAGE_MANAGE'], ['SUPPORT']);
      await openDetails();

      await userEvent.click(await screen.findByRole('button', { name: 'Download blanked copy' }));

      expect(adminBlankedCopyApi.heldImport).toHaveBeenCalledWith(heldRow.id);
      expect(await screen.findByText('Matches the real statement')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /download statement/i })).not.toBeInTheDocument();
    });

    it('is not offered when the switch is off', async () => {
      mockAuth(['IMPORT_TRIAGE_MANAGE'], ['SUPPORT']);
      await openDetails();
      await waitFor(() => expect(adminBlankedCopyApi.status).toHaveBeenCalled());

      expect(screen.queryByRole('button', { name: 'Download blanked copy' })).not.toBeInTheDocument();
    });

    function mockCsvJob() {
      const csvRow: HeldImportRow = { ...heldRow, fileName: 'hdfc-june.csv', sourceFormat: 'CSV' };
      vi.mocked(adminHeldImportApi.list).mockResolvedValue({
        content: [csvRow], page: 0, size: 25, totalElements: 1, totalPages: 1,
      });
      vi.mocked(adminHeldImportApi.get).mockResolvedValue({ ...heldDetail, job: csvRow });
    }

    async function openCsvDetails() {
      renderPage();
      await screen.findByText('hdfc-june.csv');
      await userEvent.click(screen.getByRole('button', { name: /details/i }));
      await screen.findByRole('button', { name: /download statement/i });
    }

    /** The server cannot copy a CSV yet; the operator is told so in place of a button that would
     *  only ever answer 409. */
    it('says a CSV import cannot be copied yet, in place of the button', async () => {
      mockCsvJob();
      vi.mocked(adminBlankedCopyApi.status).mockResolvedValue({ enabled: true });
      mockAuth(['IMPORT_TRIAGE_MANAGE'], ['ADMIN']);
      await openCsvDetails();

      expect(await screen.findByText('Blanked copy not available for CSV yet.')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Download blanked copy' })).not.toBeInTheDocument();
    });

    it('says nothing about CSV when the switch is off', async () => {
      mockCsvJob();
      mockAuth(['IMPORT_TRIAGE_MANAGE'], ['ADMIN']);
      await openCsvDetails();
      await waitFor(() => expect(adminBlankedCopyApi.status).toHaveBeenCalled());

      expect(screen.queryByText('Blanked copy not available for CSV yet.')).not.toBeInTheDocument();
    });
  });

  it('shows the waiting and reprocessing counts separately', async () => {
    mockAuth(['IMPORT_TRIAGE_MANAGE']);
    renderPage();

    expect(await screen.findByText('3')).toBeInTheDocument();
    expect(screen.getByText('1')).toBeInTheDocument();
  });
});
