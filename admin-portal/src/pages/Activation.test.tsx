import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Activation from './Activation';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminActivationApi } from '../api/endpoints';
import type { ActivationReport, ActivationWeekRow } from '../api/endpoints';

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

/** The fixture week's counts (1 at SIGNED_UP ... 9 at GOT_DATA_IN, 45 people) by step reached. */
const WEEK_REACHED = {
  SIGNED_UP: 45, OPENED_IMPORT: 44, CHOSE_FILE: 42, FILE_READ: 24, REVIEW_OPENED: 17, GOT_DATA_IN: 9,
};

function emptyWeek(weekStart: string): ActivationWeekRow {
  return {
    weekStart, counts: zero({}), cameBackLaterDay: 0,
    reached: { SIGNED_UP: 0, OPENED_IMPORT: 0, CHOSE_FILE: 0, FILE_READ: 0, REVIEW_OPENED: 0, GOT_DATA_IN: 0 },
  };
}

function signupWeek(weekStart: string, signedUp: number, gotDataIn: number): ActivationWeekRow {
  const all = signedUp + gotDataIn;
  return {
    weekStart, counts: zero({ SIGNED_UP: signedUp, GOT_DATA_IN: gotDataIn }), cameBackLaterDay: gotDataIn,
    reached: {
      SIGNED_UP: all, OPENED_IMPORT: gotDataIn, CHOSE_FILE: gotDataIn, FILE_READ: gotDataIn, REVIEW_OPENED: gotDataIn,
      GOT_DATA_IN: gotDataIn,
    },
  };
}

function rowsOf(table: HTMLElement): string[][] {
  return within(table).getAllByRole('row').slice(1)
    .map((r) => Array.from(r.querySelectorAll('td, th')).map((c) => c.textContent ?? ''));
}

function report(over: Partial<ActivationReport> = {}): ActivationReport {
  const counts = Object.fromEntries(STEPS.map((s, i) => [s, i + 1])) as Record<(typeof STEPS)[number], number>;
  return {
    funnelGoLive: '2026-10-17',
    weeks: [{ weekStart: '2026-10-12', counts, reached: WEEK_REACHED, cameBackLaterDay: 2 }],
    reached: {
      signups: 45,
      steps: [
        { step: 'SIGNED_UP', reached: 45, lost: 0, lostBeforeFunnelRecord: 0 },
        { step: 'OPENED_IMPORT', reached: 44, lost: 1, lostBeforeFunnelRecord: 1 },
        { step: 'CHOSE_FILE', reached: 42, lost: 2, lostBeforeFunnelRecord: 0 },
        { step: 'FILE_READ', reached: 24, lost: 18, lostBeforeFunnelRecord: 0 },
        { step: 'REVIEW_OPENED', reached: 17, lost: 7, lostBeforeFunnelRecord: 0 },
        { step: 'GOT_DATA_IN', reached: 9, lost: 8, lostBeforeFunnelRecord: 0 },
      ],
      stoppedAt: counts,
    },
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
    landingPaths: {
      // 23 Oct 2026 20:00 UTC is already 24 Oct in IST.
      trackingStart: '2026-10-23T20:00:00Z',
      pages: [
        { path: '/banks/example-bank', signups: 4, gotDataIn: 3 },
        { path: '/help', signups: 2, gotDataIn: 0 },
      ],
      other: { path: null, signups: 1, gotDataIn: 1 },
      none: { path: null, signups: 5, gotDataIn: 2 },
    },
    secondImport: { users: 3, medianDays: 2, cameBackLaterDay: 2 },
    monthStartNudges: [{ month: '2026-11-01', nudgesSent: 5, importedWithin3Days: 2 }],
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
    await screen.findByRole('table', { name: 'Steps reached by signup week' });

    fireEvent.click(screen.getByRole('button', { name: 'Stopped at' }));

    const table = screen.getByRole('table', { name: 'Furthest step by signup week' });
    const headers = within(table).getAllByRole('columnheader').map((h) => h.textContent);
    expect(headers).toEqual([
      'Signup week', 'Signed up', 'Opened import', 'Chose a file', 'Refused', 'Read failed', 'Held',
      'Read, not reviewed', 'Reviewed, not confirmed', 'Got data in', 'Came back another day',
    ]);
    // The week's counts, in the same order, then the total row.
    expect(rowsOf(table)).toEqual([
      ['12 Oct 2026', '1', '2', '3', '4', '5', '6', '7', '8', '9', '2'],
      ['Total', '1', '2', '3', '4', '5', '6', '7', '8', '9', '2'],
    ]);
    expect(screen.getByRole('button', { name: 'Stopped at' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByRole('list', { name: 'Steps reached' })).not.toBeInTheDocument();
  });

  it('leads with a verdict: the biggest drop, the activation rate and who came back', async () => {
    renderPage();

    expect(await screen.findByLabelText('Verdict')).toHaveTextContent(
      'Biggest drop: 18 of 42 chose a file but never had one read. Activation 20% (9 of 45 got data in). 2 came back another day.',
    );
  });

  it('shows steps reached by default, each with its drop, the biggest marked and split by where people stopped', async () => {
    renderPage();

    const list = await screen.findByRole('list', { name: 'Steps reached' });
    expect(screen.getByRole('button', { name: 'Reached' })).toHaveAttribute('aria-pressed', 'true');
    const items = within(list).getAllByRole('listitem');
    expect(items.map((i) => i.getAttribute('aria-label'))).toEqual([
      'Signed up', 'Opened import', 'Chose a file', 'File read', 'Opened the review', 'Got data in',
    ]);
    expect(items[0]).toHaveTextContent('45 (100%)');
    expect(items[1]).toHaveTextContent('44 (98%)−1 of 45 (2%) · 1 signed up before this step was recorded');
    expect(items[3]).toHaveTextContent(
      '24 (53%)−18 of 42 (43%) · biggest drop · No result 3 · Refused 4 · Read failed 5 · Held 6',
    );
    expect(items[5]).toHaveTextContent('9 (20%)−8 of 17 (47%)');
    expect(items[5]).not.toHaveTextContent('biggest drop');
  });

  it('shows steps reached by signup week, with who came back and a total row', async () => {
    renderPage();

    const table = await screen.findByRole('table', { name: 'Steps reached by signup week' });
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent)).toEqual([
      'Signup week', 'Signed up', 'Opened import', 'Chose a file', 'File read', 'Opened the review', 'Got data in',
      'Came back another day',
    ]);
    expect(rowsOf(table)).toEqual([
      ['12 Oct 2026', '45', '44', '42', '24', '17', '9', '2'],
      ['Total', '45', '44', '42', '24', '17', '9', '2'],
    ]);
    expect(within(table).queryAllByRole('link')).toHaveLength(0);
  });

  it('shows the last 8 weeks with runs of quiet weeks folded, a total of those shown, and all weeks on request', async () => {
    vi.mocked(adminActivationApi.report).mockResolvedValue(report({
      weeks: [
        signupWeek('2026-08-17', 5, 5), signupWeek('2026-08-24', 1, 0),                 // before the last 8
        signupWeek('2026-08-31', 2, 1), emptyWeek('2026-09-07'), emptyWeek('2026-09-14'),
        emptyWeek('2026-09-21'), signupWeek('2026-09-28', 3, 0), signupWeek('2026-10-05', 0, 2),
        signupWeek('2026-10-12', 1, 1), emptyWeek('2026-10-19'),
      ],
    }));
    renderPage();

    const table = await screen.findByRole('table', { name: 'Steps reached by signup week' });
    expect(rowsOf(table)).toEqual([
      ['31 Aug 2026', '3', '1', '1', '1', '1', '1', '1'],
      ['7 Sep 2026 – 21 Sep 2026', 'No signups in 3 weeks'],
      ['28 Sep 2026', '3', '0', '0', '0', '0', '0', '0'],
      ['5 Oct 2026', '2', '2', '2', '2', '2', '2', '2'],
      ['12 Oct 2026', '2', '1', '1', '1', '1', '1', '1'],
      ['19 Oct 2026', 'No signups'],
      ['Total', '10', '4', '4', '4', '4', '4', '4'],
    ]);

    fireEvent.click(screen.getByRole('button', { name: 'Show all 10 weeks' }));

    const all = rowsOf(screen.getByRole('table', { name: 'Steps reached by signup week' }));
    expect(all[0]).toEqual(['17 Aug 2026', '10', '5', '5', '5', '5', '5', '5']);
    expect(all[all.length - 1]).toEqual(['Total', '21', '9', '9', '9', '9', '9', '9']);
    expect(screen.getByRole('button', { name: 'Show the last 8 weeks only' })).toHaveAttribute('aria-expanded', 'true');
  });

  it('offers no week toggle with 8 weeks or fewer', async () => {
    renderPage();

    await screen.findByRole('table', { name: 'Steps reached by signup week' });
    expect(screen.queryByRole('button', { name: /Show all/ })).not.toBeInTheDocument();
  });

  it('says there are no signups yet, with no bars and no table', async () => {
    vi.mocked(adminActivationApi.report).mockResolvedValue(report({
      weeks: [],
      reached: {
        signups: 0,
        steps: (['SIGNED_UP', 'OPENED_IMPORT', 'CHOSE_FILE', 'FILE_READ', 'REVIEW_OPENED', 'GOT_DATA_IN'] as const)
          .map((step) => ({ step, reached: 0, lost: 0, lostBeforeFunnelRecord: 0 })),
        stoppedAt: zero({}),
      },
      secondImport: { users: 0, medianDays: null, cameBackLaterDay: 0 },
    }));
    renderPage();

    expect(await screen.findByLabelText('Verdict')).toHaveTextContent('No signups counted yet.');
    expect(screen.getAllByText('No signups to count yet.')).toHaveLength(2);
    expect(screen.queryByRole('list', { name: 'Steps reached' })).not.toBeInTheDocument();
  });

  it('falls back to the stopped-at table, with no verdict, for a backend from before the reached counts', async () => {
    vi.mocked(adminActivationApi.report).mockResolvedValue(report({
      reached: undefined,
      weeks: [{ weekStart: '2026-10-12', counts: zero({ SIGNED_UP: 2, GOT_DATA_IN: 1 }) }],
    }));
    renderPage();

    const table = await screen.findByRole('table', { name: 'Furthest step by signup week' });
    expect(rowsOf(table)[0]).toEqual(['12 Oct 2026', '2', '0', '0', '0', '0', '0', '0', '0', '1', '—']);
    expect(rowsOf(table)[1]).toEqual(['Total', '2', '0', '0', '0', '0', '0', '0', '0', '1', '—']);
    expect(screen.queryByLabelText('Verdict')).not.toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Funnel view' })).not.toBeInTheDocument();
  });

  it('keeps the notes on these numbers behind a toggle', async () => {
    renderPage();
    const toggle = await screen.findByRole('button', { name: 'About these numbers' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByText('Counts are small; one person changes them.')).not.toBeVisible();

    fireEvent.click(toggle);

    expect(screen.getByRole('button', { name: 'Hide notes on these numbers' })).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('Counts are small; one person changes them.')).toBeVisible();
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
    await screen.findByRole('table', { name: 'Steps reached by signup week' });
    const firstCall = vi.mocked(adminActivationApi.report).mock.calls[0];

    fireEvent.change(screen.getByLabelText('Answers from'), { target: { value: '2026-09-01' } });

    await waitFor(() => expect(adminActivationApi.report).toHaveBeenLastCalledWith('2026-09-01', firstCall[1]));
  });

  // A new range refetches the whole report; the page must not drop to "Loading…" while it does.
  it('keeps the tables on screen while a new range loads', async () => {
    renderPage();
    await screen.findByRole('table', { name: 'Steps reached by signup week' });
    vi.mocked(adminActivationApi.report).mockReturnValue(new Promise(() => {}));

    fireEvent.change(screen.getByLabelText('Answers from'), { target: { value: '2026-09-01' } });

    await waitFor(() => expect(adminActivationApi.report).toHaveBeenCalledTimes(2));
    expect(screen.getByRole('table', { name: 'Steps reached by signup week' })).toBeInTheDocument();
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument();
  });

  // A typed start after the end would be a 400 that blanks the page; it is ignored instead.
  it('ignores a start date after the end date', async () => {
    renderPage();
    await screen.findByRole('table', { name: 'Steps reached by signup week' });

    fireEvent.change(screen.getByLabelText('Answers from'), { target: { value: '2999-01-01' } });

    expect(adminActivationApi.report).toHaveBeenCalledTimes(1);
    expect(screen.getByLabelText('Answers from')).not.toHaveValue('2999-01-01');
  });

  it('says when a new range fails, rather than passing the old numbers off as the new range', async () => {
    renderPage();
    await screen.findByRole('table', { name: 'Steps reached by signup week' });
    vi.mocked(adminActivationApi.report).mockRejectedValue(new Error('down'));

    fireEvent.change(screen.getByLabelText('Answers from'), { target: { value: '2026-09-01' } });

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load that range. Showing the previous report.');
  });

  it('noLinksInFunnelCells', async () => {
    renderPage();

    const table = await screen.findByRole('table', { name: 'Steps reached by signup week' });
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

  it('shows signups by landing page: the named pages, the rest summed, and those with none recorded', async () => {
    renderPage();

    const table = await screen.findByRole('table', { name: 'Signups by landing page' });
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent))
      .toEqual(['Landing page', 'Signed up', 'Got data in']);
    const rows = within(table).getAllByRole('row').slice(1).map((r) => within(r).getAllByRole('cell').map((c) => c.textContent));
    expect(rows).toEqual([
      ['/banks/example-bank', '4', '3'],
      ['/help', '2', '0'],
      ['Other pages', '1', '1'],
      ['No landing page recorded', '5', '2'],
    ]);
  });

  it('leaves out the other and none lines when nobody is on them, and says when there are no signups', async () => {
    vi.mocked(adminActivationApi.report).mockResolvedValue(report({
      landingPaths: {
        trackingStart: '2026-10-23T20:00:00Z', pages: [{ path: '/help', signups: 1, gotDataIn: 0 }],
        other: { path: null, signups: 0, gotDataIn: 0 }, none: { path: null, signups: 0, gotDataIn: 0 },
      },
    }));
    const { unmount } = renderPage();
    const table = await screen.findByRole('table', { name: 'Signups by landing page' });
    expect(within(table).getAllByRole('row')).toHaveLength(2);
    unmount();

    vi.mocked(adminActivationApi.report).mockResolvedValue(report({
      landingPaths: {
        trackingStart: '2026-10-23T20:00:00Z', pages: [],
        other: { path: null, signups: 0, gotDataIn: 0 }, none: { path: null, signups: 0, gotDataIn: 0 },
      },
    }));
    renderPage();
    expect(await screen.findByText('No signups since landing pages began to be recorded.')).toBeInTheDocument();
    expect(screen.queryByRole('table', { name: 'Signups by landing page' })).not.toBeInTheDocument();
  });

  it('dates the start of landing-page tracking in IST, says when it is not known, and hides the table from an older backend', async () => {
    const { unmount } = renderPage();
    expect(await screen.findByText(/Landing pages are recorded from 24 Oct 2026; only signups made since then are counted/)).toBeInTheDocument();
    unmount();

    vi.mocked(adminActivationApi.report).mockResolvedValue(report({
      landingPaths: { trackingStart: null, pages: [], other: { path: null, signups: 0, gotDataIn: 0 }, none: { path: null, signups: 0, gotDataIn: 0 } },
    }));
    const second = renderPage();
    expect(await screen.findByText('When landing pages began to be recorded is not known, so that table is empty.')).toBeInTheDocument();
    second.unmount();

    vi.mocked(adminActivationApi.report).mockResolvedValue(report({ landingPaths: undefined }));
    renderPage();
    await screen.findByRole('table', { name: 'Funnel by signup source' });
    expect(screen.queryByRole('table', { name: 'Signups by landing page' })).not.toBeInTheDocument();
    expect(screen.queryByText(/Landing pages are recorded from/)).not.toBeInTheDocument();
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

  // Gate 2b PR 5 (spec §6): month-start nudges sent, and imports within 3 days of one.
  it('shows month-start nudges per month', async () => {
    vi.mocked(adminActivationApi.report).mockResolvedValue(report());
    renderPage();

    const table = await screen.findByRole('table', { name: 'Month-start nudges by month' });
    expect(within(table).getByText('Nov 2026')).toBeInTheDocument();
    expect(within(table).getByText('5')).toBeInTheDocument();
    expect(within(table).getByText('2')).toBeInTheDocument();
  });

  it('says when no month-start nudge has been sent', async () => {
    vi.mocked(adminActivationApi.report).mockResolvedValue(report({ monthStartNudges: [] }));
    renderPage();

    expect(await screen.findByText('No month-start nudges sent yet.')).toBeInTheDocument();
  });
});
