import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import NeedsADecision, { claudeLine, nextStepText, openLink, whoText } from './NeedsADecision';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminDecisionQueueApi } from '../api/endpoints';
import { timeLeftLabel } from '../lib/timeLeft';
import type { DecisionQueueRow } from '../types';

vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminDecisionQueueApi: { list: vi.fn() },
  adminSearchApi: { search: vi.fn() },
  adminDashboardApi: { overview: vi.fn(() => new Promise(() => {})) },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/needs-a-decision']}>
        <NeedsADecision />
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

const HOUR = 3_600_000;

function row(overrides: Partial<DecisionQueueRow>): DecisionQueueRow {
  const heldAt = new Date(Date.now() - 2 * HOUR).toISOString();
  return {
    kind: 'IMPORT',
    jobId: crypto.randomUUID(),
    heldId: null,
    bank: null,
    sourceFormat: 'PDF',
    heldAt,
    promiseAt: new Date(Date.parse(heldAt) + 48 * HOUR).toISOString(),
    who: 'NOBODY',
    whoName: null,
    nextStep: 'FIX_AND_REPROCESS',
    prNumber: null,
    claudeFix: null,
    alsoDecides: 0,
    ...overrides,
  };
}

describe('timeLeftLabel', () => {
  const promise = '2026-11-14T12:00:00Z';
  const at = (iso: string) => Date.parse(iso);

  it('exactlyAtPromiseIsNotPast', () => {
    expect(timeLeftLabel(promise, at(promise))).toEqual({ text: '0 min left', past: false });
  });
  it('oneMinutePastIsPast', () => {
    expect(timeLeftLabel(promise, at('2026-11-14T12:01:00Z'))).toEqual({ text: '1 min past the promise', past: true });
  });
  it('hoursRoundDown', () => {
    expect(timeLeftLabel(promise, at('2026-11-14T05:01:00Z')).text).toBe('6 h left');
    expect(timeLeftLabel(promise, at('2026-11-14T15:59:00Z')).text).toBe('3 h past the promise');
  });
  it('countsDaysOnceTwoDaysLate', () => {
    expect(timeLeftLabel(promise, at('2026-11-17T13:00:00Z')).text).toBe('3 days past the promise');
  });
  it('anUnreadableTimeSaysSo', () => {
    expect(timeLeftLabel('not a time', at(promise))).toEqual({ text: 'Time unknown', past: false });
  });
});

describe('row words and links', () => {
  it('names the PR to merge', () => {
    expect(nextStepText(row({ nextStep: 'MERGE_PR', prNumber: 123 }))).toBe('Review and merge PR #123');
  });
  it('names the admin, or says an admin when the name is unknown', () => {
    expect(nextStepText(row({ nextStep: 'ADMIN_LOOKING', who: 'ADMIN', whoName: 'Asha' }))).toBe('Asha is looking into it');
    expect(whoText(row({ who: 'ADMIN', whoName: null }))).toBe('An admin');
    expect(whoText(row({ who: 'CLAUDE' }))).toBe('Claude');
    expect(whoText(row({ who: 'NOBODY' }))).toBe('Nobody yet');
  });
  it('says when Claude never started or stopped reporting, in words for each kind', () => {
    expect(nextStepText(row({ kind: 'IMPORT', nextStep: 'CLAUDE_DID_NOT_START' })))
      .toBe('Claude did not start: fix the reader and reprocess, or resolve with a message');
    expect(nextStepText(row({ kind: 'STATEMENT', nextStep: 'CLAUDE_DID_NOT_START' })))
      .toBe('Claude did not start: check the evidence, then approve or reject');
    expect(nextStepText(row({ nextStep: 'CLAUDE_STALLED' })))
      .toBe("Claude's run stopped reporting back: check Background jobs, then handle it by hand");
  });
  it('does not call a stalled run "working" on the Claude line', () => {
    const sent = { state: 'SENT' as const, reason: null, publicPr: null, privatePr: null, attempt: 1, monthlyCap: 50 };
    expect(claudeLine(row({ nextStep: 'CLAUDE_STALLED', claudeFix: sent })))
      .toBe('Attempt 1 of 2 sent, nothing back for over 4 hours');
    expect(claudeLine(row({ nextStep: 'CLAUDE_WORKING', claudeFix: sent })))
      .toBe('Claude is working on it (attempt 1 of 2)');
  });
  it('opens each kind where it is decided', () => {
    expect(openLink(row({ kind: 'IMPORT', jobId: 'abc' }))).toBe('/held-imports?job=abc');
    expect(openLink(row({ kind: 'STATEMENT', heldId: 'HLD-2026-000001' }))).toBe('/held-statements/HLD-2026-000001');
    expect(openLink(row({ kind: 'NO_RECORD' }))).toBe('/held-statements');
  });
});

describe('NeedsADecision page', () => {
  beforeEach(() => {
    vi.mocked(adminDecisionQueueApi.list).mockReset();
    mockAuth(['IMPORT_TRIAGE_MANAGE', 'TRUST_REVIEW_MANAGE']);
  });

  it('lists rows in the order the server sends, with who, next step, time left and Claude', async () => {
    vi.mocked(adminDecisionQueueApi.list).mockResolvedValue([
      row({ kind: 'STATEMENT', heldId: 'HLD-2026-000009', bank: 'Sample Bank', nextStep: 'MERGE_PR', prNumber: 42,
        heldAt: new Date(Date.now() - 51 * HOUR).toISOString(),
        promiseAt: new Date(Date.now() - 3 * HOUR - 60_000).toISOString(),
        claudeFix: { state: 'PR_OPEN', reason: null, publicPr: 42, privatePr: 7, attempt: 1, monthlyCap: 50 } }),
      row({ who: 'CLAUDE', nextStep: 'CLAUDE_WORKING',
        claudeFix: { state: 'SENT', reason: null, publicPr: null, privatePr: null, attempt: 1, monthlyCap: 50 } }),
    ]);

    renderPage();

    const rows = await screen.findAllByTestId('decision-row');
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByText('Review and merge PR #42')).toBeInTheDocument();
    expect(within(rows[0]).getByText('3 h past the promise')).toBeInTheDocument();
    expect(within(rows[0]).getByText('Nobody yet')).toBeInTheDocument();
    expect(within(rows[0]).getByText(/Held statement · Sample Bank/)).toBeInTheDocument();
    expect(within(rows[0]).getByRole('link', { name: 'Open' })).toHaveAttribute('href', '/held-statements/HLD-2026-000009');
    expect(within(rows[1]).getByText('Claude is working on a fix')).toBeInTheDocument();
    expect(within(rows[1]).getByText('Claude is working on it (attempt 1 of 2)')).toBeInTheDocument();
    expect(within(rows[1]).getByText(/^4[56] h left$/)).toBeInTheDocument();
    expect(screen.getByText('2 waiting · 1 with nobody on it · 1 past the promise')).toBeInTheDocument();
  });

  it('says Claude is off once, not "Claude: Claude fix off"', async () => {
    vi.mocked(adminDecisionQueueApi.list).mockResolvedValue([
      row({ claudeFix: { state: 'OFF', reason: null, publicPr: null, privatePr: null, attempt: 0, monthlyCap: 50 } }),
    ]);

    renderPage();

    const [only] = await screen.findAllByTestId('decision-row');
    expect(within(only).getByText('off')).toBeInTheDocument();
    expect(within(only).queryByText(/Claude fix off/)).not.toBeInTheDocument();
  });

  it('names the re-uploads a review also decides', async () => {
    vi.mocked(adminDecisionQueueApi.list).mockResolvedValue([
      row({ kind: 'STATEMENT', heldId: 'HLD-2026-000002', nextStep: 'CHECK_AND_DECIDE', alsoDecides: 2 }),
    ]);

    renderPage();

    const [only] = await screen.findAllByTestId('decision-row');
    expect(within(only).getByText('2 re-uploads of this statement')).toBeInTheDocument();
  });

  it('keeps counting down between fetches', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const promiseAt = new Date(Date.now() + 90 * 60_000 + 10_000).toISOString(); // 1 h 30 min left
      vi.mocked(adminDecisionQueueApi.list).mockResolvedValue([row({ promiseAt })]);
      renderPage();
      expect(await screen.findByText('1 h left')).toBeInTheDocument();

      vi.mocked(adminDecisionQueueApi.list).mockReturnValue(new Promise(() => {})); // later fetches never land
      await act(async () => { vi.advanceTimersByTime(31 * 60_000); });

      expect(screen.getByText('59 min left')).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('keeps the list and warns when a refresh fails', async () => {
    vi.mocked(adminDecisionQueueApi.list).mockResolvedValueOnce([row({})]);
    renderPage();
    expect(await screen.findAllByTestId('decision-row')).toHaveLength(1);

    vi.mocked(adminDecisionQueueApi.list).mockRejectedValue(new Error('network down'));
    screen.getByRole('button', { name: /Refresh/ }).click();

    expect(await screen.findByRole('alert')).toHaveTextContent(/Could not refresh/);
    expect(screen.getAllByTestId('decision-row')).toHaveLength(1);
  });

  it('says which kind is missing for an admin who may decide only one', async () => {
    vi.mocked(adminDecisionQueueApi.list).mockResolvedValue([]);
    mockAuth(['IMPORT_TRIAGE_MANAGE']);

    renderPage();

    expect(await screen.findByText(/Showing held imports only/)).toBeInTheDocument();
  });

  it('says when nothing is waiting', async () => {
    vi.mocked(adminDecisionQueueApi.list).mockResolvedValue([]);

    renderPage();

    expect(await screen.findByText('Nothing is waiting on a decision.')).toBeInTheDocument();
    expect(screen.queryByTestId('decision-row')).not.toBeInTheDocument();
  });

  it('shows a load error instead of an empty list', async () => {
    vi.mocked(adminDecisionQueueApi.list).mockRejectedValue(new Error('network down'));

    renderPage();

    expect(await screen.findByText(/holds waiting on a decision/i)).toBeInTheDocument();
    expect(screen.queryByText('Nothing is waiting on a decision.')).not.toBeInTheDocument();
  });

  it('works with either hold permission, and says what is needed with neither', async () => {
    vi.mocked(adminDecisionQueueApi.list).mockResolvedValue([]);
    mockAuth(['TRUST_REVIEW_MANAGE']);
    const { unmount } = renderPage();
    expect(await screen.findByText('Nothing is waiting on a decision.')).toBeInTheDocument();
    unmount();

    mockAuth(['PLATFORM_DIAGNOSTICS_VIEW']);
    renderPage();
    expect(screen.getByText(/requires the IMPORT_TRIAGE_MANAGE or TRUST_REVIEW_MANAGE permission/)).toBeInTheDocument();
    expect(adminDecisionQueueApi.list).toHaveBeenCalledTimes(1);
  });
});
