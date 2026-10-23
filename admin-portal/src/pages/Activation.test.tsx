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

function zero(some: Partial<Record<(typeof STEPS)[number], number>>): Record<(typeof STEPS)[number], number> {
  return { ...(Object.fromEntries(STEPS.map((s) => [s, 0])) as Record<(typeof STEPS)[number], number>), ...some };
}

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
    // 23 Oct 2026 20:00 UTC is already 24 Oct in IST.
    sourceTrackingStart: '2026-10-23T20:00:00Z',
    bySource: [
      { kind: 'UTM', utmSource: 'reel', counts: zero({ SIGNED_UP: 2, GOT_DATA_IN: 3 }), secondImport: { users: 2, medianDays: 4.5, cameBackLaterDay: 1 } },
      { kind: 'GOOGLE', utmSource: null, counts: zero({ CHOSE_FILE: 1 }), secondImport: { users: 0, medianDays: null, cameBackLaterDay: 0 } },
      { kind: 'REFERRAL', utmSource: null, counts: zero({ GOT_DATA_IN: 1 }), secondImport: { users: 0, medianDays: null, cameBackLaterDay: 0 } },
      { kind: 'DIRECT', utmSource: null, counts: zero({}), secondImport: { users: 0, medianDays: null, cameBackLaterDay: 0 } },
      { kind: 'UNKNOWN_BEFORE_TRACKING', utmSource: null, counts: zero({ GOT_DATA_IN: 1 }), secondImport: { users: 1, medianDays: 1, cameBackLaterDay: 1 } },
    ],
    secondImport: { users: 3, medianDays: 2, cameBackLaterDay: 2 },
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

  // A new range refetches the whole report; the page must not drop to "Loading…" while it does.
  it('keeps the tables on screen while a new range loads', async () => {
    renderPage();
    await screen.findByRole('table', { name: 'Funnel by signup week' });
    vi.mocked(adminActivationApi.report).mockReturnValue(new Promise(() => {}));

    fireEvent.change(screen.getByLabelText('Answers from'), { target: { value: '2026-09-01' } });

    await waitFor(() => expect(adminActivationApi.report).toHaveBeenCalledTimes(2));
    expect(screen.getByRole('table', { name: 'Funnel by signup week' })).toBeInTheDocument();
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument();
  });

  // A typed start after the end would be a 400 that blanks the page; it is ignored instead.
  it('ignores a start date after the end date', async () => {
    renderPage();
    await screen.findByRole('table', { name: 'Funnel by signup week' });

    fireEvent.change(screen.getByLabelText('Answers from'), { target: { value: '2999-01-01' } });

    expect(adminActivationApi.report).toHaveBeenCalledTimes(1);
    expect(screen.getByLabelText('Answers from')).not.toHaveValue('2999-01-01');
  });

  it('says when a new range fails, rather than passing the old numbers off as the new range', async () => {
    renderPage();
    await screen.findByRole('table', { name: 'Funnel by signup week' });
    vi.mocked(adminActivationApi.report).mockRejectedValue(new Error('down'));

    fireEvent.change(screen.getByLabelText('Answers from'), { target: { value: '2026-09-01' } });

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load that range. Showing the previous report.');
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

  it('shows one row per source with steps in order and the second import', async () => {
    renderPage();

    const table = await screen.findByRole('table', { name: 'Funnel by signup source' });
    const headers = within(table).getAllByRole('columnheader').map((h) => h.textContent);
    expect(headers).toEqual([
      'Source', 'Signed up', 'Opened import', 'Chose a file', 'Refused', 'Read failed', 'Held',
      'Read, not reviewed', 'Reviewed, not confirmed', 'Got data in', 'Second import', 'Median days to second',
      'Came back another day',
    ]);
    const rows = within(table).getAllByRole('row').slice(1).map((r) => within(r).getAllByRole('cell').map((c) => c.textContent));
    expect(rows.map((r) => r[0])).toEqual([
      'utm_source: reel', 'Google', "Friend's referral code", 'Direct (incl. mobile app)', 'Unknown (before tracking)',
    ]);
    expect(rows[0]).toEqual(['utm_source: reel', '2', '0', '0', '0', '0', '0', '0', '0', '3', '2', '4.5 days', '1']);
    expect(rows[1].slice(-3)).toEqual(['0', '—', '0']);
    expect(rows[4].slice(-3)).toEqual(['1', '1.0 day', '1']);
    expect(within(table).queryAllByRole('link')).toHaveLength(0);
  });

  it('shows the second import beside the totals', async () => {
    renderPage();

    const totals = await screen.findByLabelText('Totals');
    // weeks: counts 1..9 → 45 signups, 9 got data in.
    expect(within(totals).getByText('Signups counted').nextSibling).toHaveTextContent('45');
    expect(within(totals).getByText('Got data in').nextSibling).toHaveTextContent('9');
    expect(within(totals).getByText('Second import').nextSibling).toHaveTextContent('3');
    expect(within(totals).getByText('Median days, first to second import').nextSibling).toHaveTextContent('2.0 days');
    expect(within(totals).getByText('Came back another day').nextSibling).toHaveTextContent('2');
  });

  it('dates the start of source tracking in IST and says what unknown means', async () => {
    renderPage();

    expect(await screen.findByText(
      'Signup sources are recorded from 24 Oct 2026; earlier signups with nothing recorded show as unknown, not direct.',
    )).toBeInTheDocument();
  });

  it('says when the start of source tracking is not known', async () => {
    vi.mocked(adminActivationApi.report).mockResolvedValue(report({ sourceTrackingStart: null }));
    renderPage();

    expect(await screen.findByText(/When signup sources began to be recorded is not known/)).toBeInTheDocument();
  });

  it('shows a dash for the median when nobody has a second import', async () => {
    vi.mocked(adminActivationApi.report).mockResolvedValue(report({ secondImport: { users: 0, medianDays: null, cameBackLaterDay: 0 } }));
    renderPage();

    const totals = await screen.findByLabelText('Totals');
    expect(within(totals).getByText('Median days, first to second import').nextSibling).toHaveTextContent('—');
  });

  it('says so when the report cannot load', async () => {
    vi.mocked(adminActivationApi.report).mockRejectedValue(new Error('down'));
    renderPage();

    expect(await screen.findByText('The activation report could not be loaded.')).toBeInTheDocument();
  });
});
