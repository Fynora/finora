import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import BackgroundJobs, { formatDuration, lastRunDuration } from './BackgroundJobs';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminBackgroundJobsApi } from '../api/endpoints';
import type { BackgroundJobDto } from '../types';

vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminBackgroundJobsApi: { list: vi.fn(), failures: vi.fn() },
  adminSearchApi: { search: vi.fn() },
  adminDashboardApi: { overview: vi.fn(() => new Promise(() => {})) },
}));

function renderPage(path = '/background-jobs') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <BackgroundJobs />
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

function job(overrides: Partial<BackgroundJobDto>): BackgroundJobDto {
  return {
    key: 'X.y', name: 'Some job', purpose: 'Does a thing.', schedule: 'every 15 minutes', state: 'OK',
    lastStartedAt: '2026-11-13T08:30:00Z', lastFinishedAt: '2026-11-13T08:30:05Z',
    lastSuccessAt: '2026-11-13T08:30:05Z', lastFailedItems: 0, lastHandled: 0, consecutiveFailures: 0,
    brokenSince: null, running: false, source: 'BACKEND', runUrl: null, offReason: null, ...overrides,
  };
}

const JOBS: BackgroundJobDto[] = [
  job({ key: 'AccountPurgeSweepService.scheduledSweep', name: 'Account deletion sweep', state: 'CRASHED',
    purpose: 'Deletes accounts customers asked to delete.', consecutiveFailures: 3,
    brokenSince: '2026-11-13T08:35:00Z', lastSuccessAt: '2026-11-12T08:35:00Z' }),
  job({ key: 'NotificationDispatcher.poll', name: 'Notification sender', state: 'PARTLY_FAILED',
    lastFailedItems: 2, lastHandled: 10, schedule: 'every 30 seconds' }),
  job({ key: 'GmailDiscoveryWorker.scheduledDiscovery', name: 'Gmail discovery', state: 'OFF' }),
  job({ key: 'ReferralGrantSweepService.scheduledSweep', name: 'Referral free months', state: 'OK' }),
];

describe('BackgroundJobs', () => {
  beforeEach(() => {
    vi.mocked(adminBackgroundJobsApi.list).mockReset();
    vi.mocked(adminBackgroundJobsApi.failures).mockReset();
    mockAuth(['PLATFORM_DIAGNOSTICS_VIEW']);
  });

  it('showsBrokenJobsFirstWithTheirState', async () => {
    vi.mocked(adminBackgroundJobsApi.list).mockResolvedValue(JOBS);
    renderPage();

    const rows = await screen.findAllByTestId('background-job');
    expect(rows.map((r) => within(r).getByTestId('job-name').textContent)).toEqual([
      'Account deletion sweep', 'Notification sender', 'Gmail discovery', 'Referral free months',
    ]);
    expect(within(rows[0]).getByText('Crashed')).toBeInTheDocument();
    expect(within(rows[0]).getByText(/3 runs in a row failed/)).toBeInTheDocument();
    expect(within(rows[1]).getByText('Partly failed')).toBeInTheDocument();
    expect(within(rows[1]).getByText(/2 of 10 items failed/)).toBeInTheDocument();
    expect(within(rows[3]).getByText('OK')).toBeInTheDocument();
  });

  it('headerCountsJobsNeedingAttention', async () => {
    vi.mocked(adminBackgroundJobsApi.list).mockResolvedValue(JOBS);
    renderPage();
    expect(await screen.findByText('2 jobs need attention')).toBeInTheDocument();
  });

  it('saysAllIsWellWhenNothingIsBroken', async () => {
    vi.mocked(adminBackgroundJobsApi.list).mockResolvedValue([JOBS[2], JOBS[3]]);
    renderPage();
    expect(await screen.findByText('Every job is running normally')).toBeInTheDocument();
  });

  it('offJobsReadSwitchedOffNotBroken', async () => {
    vi.mocked(adminBackgroundJobsApi.list).mockResolvedValue(JOBS);
    renderPage();
    const rows = await screen.findAllByTestId('background-job');
    expect(within(rows[2]).getByText('Off')).toBeInTheDocument();
    expect(within(rows[2]).getByText(/switched off on purpose/i)).toBeInTheDocument();
  });

  it('showsAnOverdueOrRunningJobPlainly', async () => {
    vi.mocked(adminBackgroundJobsApi.list).mockResolvedValue([
      job({ key: 'A.b', name: 'Stuck job', state: 'OVERDUE', running: true }),
    ]);
    renderPage();
    const row = await screen.findByTestId('background-job');
    expect(within(row).getByText('Overdue')).toBeInTheDocument();
    expect(within(row).getByText(/Running since/)).toBeInTheDocument();
  });

  it('tappingAJobShowsItsFailures', async () => {
    vi.mocked(adminBackgroundJobsApi.list).mockResolvedValue(JOBS);
    vi.mocked(adminBackgroundJobsApi.failures).mockResolvedValue([
      { occurredAt: '2026-11-13T08:35:00Z', outcome: 'CRASHED', failedItems: 0,
        errorSummary: 'IllegalStateException at AccountPurgeSweepService:537' },
    ]);
    renderPage();

    const rows = await screen.findAllByTestId('background-job');
    await userEvent.click(within(rows[0]).getByRole('button', { name: /Account deletion sweep/ }));

    expect(await screen.findByText('IllegalStateException at AccountPurgeSweepService:537')).toBeInTheDocument();
    expect(adminBackgroundJobsApi.failures).toHaveBeenCalledWith('AccountPurgeSweepService.scheduledSweep');
  });

  it('aJobWithNoFailuresSaysSo', async () => {
    vi.mocked(adminBackgroundJobsApi.list).mockResolvedValue(JOBS);
    vi.mocked(adminBackgroundJobsApi.failures).mockResolvedValue([]);
    renderPage();
    const rows = await screen.findAllByTestId('background-job');
    await userEvent.click(within(rows[3]).getByRole('button', { name: /Referral free months/ }));
    expect(await screen.findByText('No failures in the last 30 days.')).toBeInTheDocument();
  });

  it('saysWhenOnlyTheNewest50FailuresAreShown', async () => {
    vi.mocked(adminBackgroundJobsApi.list).mockResolvedValue(JOBS);
    vi.mocked(adminBackgroundJobsApi.failures).mockResolvedValue(Array.from({ length: 50 }, (_, i) => ({
      occurredAt: new Date(Date.UTC(2026, 10, 13, 8, 0, i)).toISOString(), outcome: 'CRASHED' as const,
      failedItems: 0, errorSummary: `E at A:${i}`,
    })));
    renderPage('/background-jobs?job=AccountPurgeSweepService.scheduledSweep');
    expect(await screen.findByText('The 50 most recent are shown.')).toBeInTheDocument();
  });

  it('aFailedLoadShowsLoadError', async () => {
    vi.mocked(adminBackgroundJobsApi.list).mockRejectedValue(new Error('network down'));
    renderPage();
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.queryByTestId('background-job')).toBeNull();
  });

  it('jobQueryParamOpensThatJob', async () => {
    vi.mocked(adminBackgroundJobsApi.list).mockResolvedValue(JOBS);
    vi.mocked(adminBackgroundJobsApi.failures).mockResolvedValue([]);
    renderPage('/background-jobs?job=NotificationDispatcher.poll');

    await waitFor(() =>
      expect(adminBackgroundJobsApi.failures).toHaveBeenCalledWith('NotificationDispatcher.poll'));
    expect(await screen.findByText('No failures in the last 30 days.')).toBeInTheDocument();
  });

  it('isHiddenWithoutThePermission', () => {
    mockAuth([]);
    vi.mocked(adminBackgroundJobsApi.list).mockResolvedValue(JOBS);
    renderPage();
    expect(screen.queryByTestId('background-job')).toBeNull();
  });
});

describe('lastRunDuration', () => {
  it('formatsHowLongTheLastRunTook', () => {
    expect(formatDuration(400)).toBe('under a second');
    expect(formatDuration(4_200)).toBe('4 s');
    expect(formatDuration(59_999)).toBe('59 s');
    expect(formatDuration(125_000)).toBe('2 min 5 s');
    expect(formatDuration(120_000)).toBe('2 min');
    expect(formatDuration(3_780_000)).toBe('1 h 3 min');
  });

  it('isTheGapBetweenTheLastStartAndFinish', () => {
    expect(lastRunDuration(job({ lastStartedAt: '2026-11-13T08:30:00Z', lastFinishedAt: '2026-11-13T08:30:04Z' })))
      .toBe('4 s');
  });

  it('isNothingWhileRunning_whenOff_orWhenTimesAreMissingOrOutOfOrder', () => {
    expect(lastRunDuration(job({ running: true }))).toBeNull();
    expect(lastRunDuration(job({ state: 'OFF' }))).toBeNull();
    expect(lastRunDuration(job({ lastStartedAt: null }))).toBeNull();
    expect(lastRunDuration(job({ lastFinishedAt: null }))).toBeNull();
    expect(lastRunDuration(job({ lastStartedAt: '2026-11-13T08:31:00Z', lastFinishedAt: '2026-11-13T08:30:00Z' })))
      .toBeNull();
  });
});

describe('BackgroundJobs durations', () => {
  beforeEach(() => {
    vi.mocked(adminBackgroundJobsApi.list).mockReset();
    vi.mocked(adminBackgroundJobsApi.failures).mockReset();
    mockAuth(['PLATFORM_DIAGNOSTICS_VIEW']);
  });

  it('showsHowLongEachJobsLastRunTookOnItsRow', async () => {
    vi.mocked(adminBackgroundJobsApi.list).mockResolvedValue([
      job({ key: 'ImportJobWorker.poll', name: 'Statement import worker',
        lastStartedAt: '2026-11-13T08:30:00Z', lastFinishedAt: '2026-11-13T08:32:05Z' }),
    ]);
    renderPage();
    const row = await screen.findByTestId('background-job');
    expect(within(row).getByText(/took 2 min 5 s/)).toBeInTheDocument();
  });
});

describe('BackgroundJobs GitHub rows', () => {
  beforeEach(() => {
    vi.mocked(adminBackgroundJobsApi.list).mockReset();
    vi.mocked(adminBackgroundJobsApi.failures).mockReset();
    mockAuth(['PLATFORM_DIAGNOSTICS_VIEW']);
  });

  it('githubRowsShowTagFailedLabelAndRunLink', async () => {
    vi.mocked(adminBackgroundJobsApi.list).mockResolvedValue([
      job({ key: 'github:Fynora/finora/e2e-nightly.yml', name: 'Web app tests (nightly)', state: 'CRASHED',
        source: 'GITHUB', runUrl: 'https://github.com/Fynora/finora/actions/runs/42', consecutiveFailures: 1 }),
    ]);
    vi.mocked(adminBackgroundJobsApi.failures).mockResolvedValue([]);
    renderPage('/background-jobs?job=' + encodeURIComponent('github:Fynora/finora/e2e-nightly.yml'));

    const row = await screen.findByTestId('background-job');
    expect(within(row).getByText('GitHub')).toBeInTheDocument();
    expect(within(row).getByText('Failed')).toBeInTheDocument();
    expect(within(row).queryByText('Crashed')).toBeNull();
    expect(within(row).getByText(/^Last run failed/)).toBeInTheDocument();
    const link = within(row).getByRole('link', { name: /Open this run on GitHub/ });
    expect(link).toHaveAttribute('href', 'https://github.com/Fynora/finora/actions/runs/42');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('offGitHubRowShowsItsReason', async () => {
    vi.mocked(adminBackgroundJobsApi.list).mockResolvedValue([
      job({ key: 'github:Fynora/finora/github-traffic-metrics.yml', name: 'GitHub traffic metrics', state: 'OFF',
        source: 'GITHUB', offReason: 'Needs a GitHub token from Sid' }),
    ]);
    renderPage();
    const row = await screen.findByTestId('background-job');
    expect(within(row).getByText('Needs a GitHub token from Sid')).toBeInTheDocument();
    expect(within(row).queryByText(/Switched off on purpose/)).toBeNull();
  });

  it('backendRowsHaveNoGitHubTagOrLink', async () => {
    vi.mocked(adminBackgroundJobsApi.list).mockResolvedValue([job({ key: 'A.b', name: 'Some job' })]);
    renderPage();
    const row = await screen.findByTestId('background-job');
    expect(within(row).queryByText('GitHub')).toBeNull();
    expect(within(row).queryByRole('link', { name: /GitHub/ })).toBeNull();
  });
});
