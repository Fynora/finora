import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import ImportHealth from './ImportHealth';
import { adminImportHealthApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  adminImportHealthApi: { failures: vi.fn(), telemetry: vi.fn(), refresh: vi.fn() },
}));
vi.mock('../components/AdminLayout', () => ({
  AdminLayout: ({ title, children }: { title: string; children: React.ReactNode }) => <div><h1>{title}</h1>{children}</div>,
}));
vi.mock('../components/ProtectedRoute', () => ({
  RequirePermission: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

const TELEMETRY = {
  completedJobs: 120, withTelemetry: 100, predatesTelemetry: 20, notCompleted: 7,
  byReliabilityStatus: { CLEAN: 80, REVIEW_RECOMMENDED: 15, NEEDS_ATTENTION: 5 },
  byTextSource: { NATIVE_PDF: 90, OCR: 10 },
  headerReconstructionUncertain: 2, withFailedFindings: 4, withWarningFindings: 12,
  byParserVersion: [{ parserVersion: 'abc1234', jobs: 60, clean: 50, reviewRecommended: 8, needsAttention: 2 }],
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter><ImportHealth /></MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-12T06:30:00Z'));
  vi.mocked(adminImportHealthApi.failures).mockResolvedValue([
    { failureCode: 'IMPORT_NO_HEADER_DETECTED', count: 9, lastSeen: '2026-11-11T04:30:00Z', bank: 'HDFC Bank savings' },
    { failureCode: 'UNKNOWN_FAILURE', count: 2, lastSeen: '2026-11-10T04:30:00Z', bank: null },
  ]);
  vi.mocked(adminImportHealthApi.telemetry).mockResolvedValue(TELEMETRY);
  vi.mocked(adminImportHealthApi.refresh).mockResolvedValue({
    parserVersion: 'def5678', statementsAwaitingCheck: 3,
    byStatus: [{ status: 'CHANGES', statements: 4, users: 2, rowsChanged: 6, rowsAdded: 1, rowsRemoved: 0, rowsConflicting: 0, factsChanged: 1 }],
  });
});

afterEach(() => {
  vi.useRealTimers();
});

describe('Import Health', () => {
  it('lists failed imports by reason in plain words, over the last 7 days by default', async () => {
    renderPage();

    expect(await screen.findByText('No transaction table found')).toBeInTheDocument();
    expect(screen.getByText('HDFC Bank savings')).toBeInTheDocument();
    expect(screen.getByText('Unclassified failure')).toBeInTheDocument();
    expect(screen.getByText('Not named')).toBeInTheDocument();
    expect(screen.getByText(/11 failed in this window/)).toBeInTheDocument();
    expect(adminImportHealthApi.failures).toHaveBeenCalledWith(expect.stringMatching(/^2026-11-05T06:30/));
  });

  it('asks for a different window when one is picked', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('No transaction table found');

    await user.selectOptions(screen.getByRole('combobox', { name: 'Window' }), '30');

    await waitFor(() => expect(adminImportHealthApi.failures).toHaveBeenLastCalledWith(expect.stringMatching(/^2026-10-13T06:30/)));
  });

  it('says so when no import failed, and says why when the list cannot load', async () => {
    vi.mocked(adminImportHealthApi.failures).mockResolvedValueOnce([]);
    const { unmount } = renderPage();
    expect(await screen.findByText('No customer import failed in this window.')).toBeInTheDocument();
    unmount();

    vi.mocked(adminImportHealthApi.failures).mockRejectedValueOnce({ response: { data: { message: 'Access denied' } } });
    renderPage();
    expect(await screen.findByText("Couldn't load this list: Access denied")).toBeInTheDocument();
    expect(screen.queryByText('No customer import failed in this window.')).not.toBeInTheDocument();
  });

  it('shows check results out of the imports that carry them', async () => {
    renderPage();

    expect(await screen.findByText('80 of 100')).toBeInTheDocument();
    expect(screen.getByText('Needs attention', { selector: 'dt' })).toBeInTheDocument();
    expect(screen.getByText('5 of 100')).toBeInTheDocument();
    expect(screen.getByText('4 of 100')).toBeInTheDocument();
    expect(screen.getByText('Text in the file')).toBeInTheDocument();
    expect(screen.getByText('90 of 100')).toBeInTheDocument();
    expect(screen.getByText('abc1234')).toBeInTheDocument();
  });

  it('says the checks could not load instead of showing nothing', async () => {
    vi.mocked(adminImportHealthApi.telemetry).mockRejectedValue(new Error('Network Error'));
    renderPage();

    expect(await screen.findByText("Couldn't load the import checks. Please try again.")).toBeInTheDocument();
  });

  it('shows the running build\'s re-check, and another build on request', async () => {
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText(/3 statements still waiting to be checked/)).toBeInTheDocument();
    expect(screen.getByText('(running now)', { exact: false })).toBeInTheDocument();
    const row = screen.getByText('Changes found').closest('tr') as HTMLElement;
    expect(within(row).getByText('6 / 1 / 0')).toBeInTheDocument();
    expect(adminImportHealthApi.refresh).toHaveBeenCalledWith(undefined);

    await user.type(screen.getByLabelText('Build'), ' 0a1b2c3 ');
    await user.click(screen.getByRole('button', { name: 'Show' }));
    await waitFor(() => expect(adminImportHealthApi.refresh).toHaveBeenLastCalledWith('0a1b2c3'));
  });

  it('says so when the server does not know its build', async () => {
    vi.mocked(adminImportHealthApi.refresh).mockResolvedValue({ parserVersion: null, statementsAwaitingCheck: 0, byStatus: [] });
    renderPage();

    expect(await screen.findByText(/doesn't know which build it's running/)).toBeInTheDocument();
    expect(screen.queryByText('No statement has been checked against this build yet.')).not.toBeInTheDocument();
  });
});
