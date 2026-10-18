import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Activation from './Activation';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminActivationApi } from '../api/endpoints';
import type { ActivationReport } from '../api/endpoints';

// Same reasons as PlatformAnalytics.test.tsx: AdminLayout renders ThemeToggle and GlobalSearch.
vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({ useAdminAuth: vi.fn() }));
vi.mock('../api/endpoints', () => ({
  adminActivationApi: { report: vi.fn() },
  adminSearchApi: { search: vi.fn(() => new Promise(() => {})) },
}));

const STEPS = [
  'SIGNED_UP', 'OPENED_IMPORT', 'CHOSE_FILE', 'REFUSED', 'READ_FAILED', 'HELD',
  'READ_NOT_REVIEWED', 'REVIEWED_NOT_CONFIRMED', 'GOT_DATA_IN',
] as const;

function report(over: Partial<ActivationReport> = {}): ActivationReport {
  const counts = Object.fromEntries(STEPS.map((s, i) => [s, i + 1])) as Record<(typeof STEPS)[number], number>;
  return {
    funnelGoLive: '2026-10-17',
    weeks: [{ weekStart: '2026-10-12', counts }],
    answers: [
      { moment: 'NO_FILE', answer: 'NO_STATEMENT', count: 3 },
      { moment: 'NO_FILE', answer: 'SKIPPED', count: 1 },
    ],
    answersByStep: [{ step: 'OPENED_IMPORT', moment: 'NO_FILE', answer: 'NO_STATEMENT', count: 3 }],
    focusByStep: [{ step: 'SIGNED_UP', label: 'NONE', count: 2 }],
    trackingByStep: [{ step: 'SIGNED_UP', label: 'PAPER', count: 1 }],
    deletionReasons: [{ month: '2026-10-01', reason: 'NOTHING_USEFUL', count: 2 }],
    ...over,
  };
}

function renderPage() {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => p === 'PLATFORM_ANALYTICS_VIEW',
    permissions: ['PLATFORM_ANALYTICS_VIEW'],
    fullName: 'Support Admin',
    logout: vi.fn(),
  }));
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Activation />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('Activation', () => {
  beforeEach(() => {
    vi.mocked(adminActivationApi.report).mockReset().mockResolvedValue(report());
  });

  it('rendersStepColumnsInOrder', async () => {
    renderPage();

    const table = await screen.findByRole('table', { name: 'Funnel by signup week' });
    const headers = within(table).getAllByRole('columnheader').map((h) => h.textContent);
    expect(headers).toEqual([
      'Signup week', 'Signed up', 'Opened import', 'Chose a file', 'Refused', 'Read failed', 'Held',
      'Read, not reviewed', 'Reviewed, not confirmed', 'Got data in',
    ]);
    // The week's counts, in the same order.
    const cells = within(table).getAllByRole('cell').map((c) => c.textContent);
    expect(cells.slice(1)).toEqual(['1', '2', '3', '4', '5', '6', '7', '8', '9']);
  });

  it('showsGoLiveLine', async () => {
    renderPage();

    expect(await screen.findByText(/Funnel steps that use the import-funnel record count from 17 Oct 2026\./)).toBeInTheDocument();
    expect(screen.getByText('Counts are small; one person changes them.')).toBeInTheDocument();
  });

  it('showsEmptyGoLiveLine', async () => {
    vi.mocked(adminActivationApi.report).mockResolvedValue(report({ funnelGoLive: null }));
    renderPage();

    expect(await screen.findByText('The import-funnel record has no rows yet.')).toBeInTheDocument();
  });

  it('rangeChangeRefetches', async () => {
    renderPage();
    await screen.findByRole('table', { name: 'Funnel by signup week' });
    const firstCall = vi.mocked(adminActivationApi.report).mock.calls[0];

    fireEvent.change(screen.getByLabelText('Answers from'), { target: { value: '2026-09-01' } });

    await waitFor(() => expect(adminActivationApi.report).toHaveBeenLastCalledWith('2026-09-01', firstCall[1]));
  });

  it('noLinksInFunnelCells', async () => {
    renderPage();

    const table = await screen.findByRole('table', { name: 'Funnel by signup week' });
    expect(within(table).queryAllByRole('link')).toHaveLength(0);
  });

  it('shows answers, steps and deletion reasons in plain words', async () => {
    renderPage();

    expect(await screen.findAllByText("I don't have my statement")).not.toHaveLength(0);
    expect(screen.getAllByText('Skip').length).toBeGreaterThan(0);
    expect(screen.getByText("It didn't show me anything useful")).toBeInTheDocument();
    // By step, each item with its count: an unanswered onboarding question reads "No answer", never "NONE".
    expect(screen.getByText(/Notebook or paper: 1/)).toBeInTheDocument();
    expect(screen.getByText(/No answer: 2/)).toBeInTheDocument();
    expect(screen.queryByText(/NONE/)).not.toBeInTheDocument();
  });

  it('says so when the report cannot load', async () => {
    vi.mocked(adminActivationApi.report).mockRejectedValue(new Error('down'));
    renderPage();

    expect(await screen.findByText('The activation report could not be loaded.')).toBeInTheDocument();
  });
});
