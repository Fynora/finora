import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import LayoutStudio from './LayoutStudio';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminStatementAnalysisApi, adminAnalysisRunApi } from '../api/endpoints';
import type {
  PagedResponse, StatementAnalysisDto, StatementAnalysisDetailDto, StatementAnalysisSummaryDto,
} from '../types';

// AdminLayout now renders ThemeToggle (dark-mode support), which calls useTheme() --
// same reason adminSearchApi is stubbed below for GlobalSearch: a real ThemeProvider isn't
// mounted in these tests, so without this mock every AdminLayout-wrapped page throws before
// any assertion runs.
vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminStatementAnalysisApi: { paged: vi.fn(), summary: vi.fn(), byReference: vi.fn() },
  adminAnalysisRunApi: { analyze: vi.fn() },
}));

const notifySuccess = vi.fn();
const notifyError = vi.fn();
vi.mock('../context/NotificationContext', () => ({
  useNotify: () => ({ success: notifySuccess, error: notifyError }),
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <LayoutStudio />
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

const SUMMARY: StatementAnalysisSummaryDto = {
  analysesInWindow: 3,
  totalAnalysesEver: 42,
  parsed: 38,
  failed: 4,
  distinctLayouts: 7,
  rowsExtractedInWindow: 822,
  unanchoredRowsInWindow: 689,
  // Deliberately NOT in descending order: the page must sort, not trust the payload's key order.
  unanchoredReasons: { 'UNANCHORED_DATE_UNPARSEABLE:99-XXX-99': 49, UNANCHORED_DATE_COLUMN_EMPTY: 640 },
};

/** A document read successfully. */
const PARSED: StatementAnalysisDto = {
  reference: 'SA-20260806-0145',
  sourceFormat: 'PDF',
  layoutFingerprint: 'FP-1-7A91D3C2',
  outcome: 'PARSED',
  failureCode: null,
  sectionCount: 1,
  rowCount: 569,
  unanchoredReasons: { UNANCHORED_DATE_COLUMN_EMPTY: 649 },
  unanchoredRowCount: 649,
  durationMs: 812,
  byteSize: 204800,
  createdAt: '2026-08-06T10:15:00Z',
  identityChecked: true,
  bankName: 'HDFC Bank',
  statementType: 'SAVINGS',
};

/** A document that never opened -- no fingerprint, and a row count that was never measured. */
const LOCKED: StatementAnalysisDto = {
  reference: 'SA-20260806-0146',
  sourceFormat: 'PDF',
  layoutFingerprint: null,
  outcome: 'FAILED',
  // The stored ErrorCode NAME -- what the API really returns -- not the wire code IMPORT_008.
  failureCode: 'IMPORT_PDF_PASSWORD_REQUIRED',
  sectionCount: null,
  rowCount: null,
  unanchoredReasons: {},
  unanchoredRowCount: 0,
  durationMs: 40,
  byteSize: 1024,
  createdAt: '2026-08-06T10:16:00Z',
  // Never opened, so detection never ran.
  identityChecked: false,
  bankName: null,
  statementType: null,
};

const DETAIL: StatementAnalysisDetailDto = {
  analysis: PARSED,
  timesLayoutSeen: 12,
  timesLayoutFailed: 11,
};

/** A parse failure: 200 with a FAILED analysis, not an HTTP error -- see the backend controller. */
const ENCRYPTED_DETAIL: StatementAnalysisDetailDto = {
  analysis: LOCKED,
  timesLayoutSeen: 0,
  timesLayoutFailed: 0,
};

function pageOf(content: StatementAnalysisDto[], page = 0, totalElements = content.length): PagedResponse<StatementAnalysisDto> {
  return { content, page, size: 20, totalElements, totalPages: Math.ceil(totalElements / 20) };
}

beforeEach(() => {
  vi.clearAllMocks();
  mockAuth(['PLATFORM_DIAGNOSTICS_VIEW', 'ENGINE_ANALYSIS_RUN']);
  vi.mocked(adminStatementAnalysisApi.summary).mockResolvedValue(SUMMARY);
  vi.mocked(adminStatementAnalysisApi.paged).mockResolvedValue(pageOf([PARSED, LOCKED]));
  vi.mocked(adminStatementAnalysisApi.byReference).mockResolvedValue(DETAIL);
});

describe('LayoutStudio', () => {
  it('shows the engine summary and the dominant unanchored reason first', async () => {
    renderPage();

    expect(await screen.findByText('FP-1-7A91D3C2')).toBeInTheDocument();
    expect(screen.getByText('822')).toBeInTheDocument();

    // Order matters, not just presence: the dominant reason is the one that decides whether a
    // capability is worth building, and a test that only asserted both names would pass with the
    // list reversed.
    const reasons = screen.getAllByText(/^Date column empty$|^Date not understood/);
    expect(reasons[0]).toHaveTextContent('Date column empty');
    expect(reasons[1]).toHaveTextContent('Date not understood: 99-XXX-99');
  });

  it('renders a never-measured row count as absent rather than as zero', async () => {
    // The distinction the whole diagnostics change was built to preserve. Rendering null as 0 would
    // make a document that never opened look like a document the parser read and found empty --
    // which sends someone hunting a parser capability when the answer was a wrong password.
    renderPage();

    const lockedRow = (await screen.findByRole('button', { name: 'SA-20260806-0146' })).closest('tr');
    expect(lockedRow).not.toBeNull();
    const cells = within(lockedRow as HTMLElement).getAllByRole('cell');
    expect(cells[2]).toHaveTextContent('—');
    expect(cells[2]).not.toHaveTextContent('0');
    // Unmatched lines were never counted either: an empty histogram on an unread file is not zero.
    expect(cells[3]).toHaveTextContent('—');
  });

  it('opens one analysis and shows how often its layout has already failed', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'SA-20260806-0145' }));

    await waitFor(() => {
      expect(adminStatementAnalysisApi.byReference).toHaveBeenCalledWith('SA-20260806-0145');
    });
    const history = await screen.findByRole('heading', { name: 'This layout' });
    const drawer = screen.getByRole('dialog');
    expect(within(drawer).getByText('Bank')).toBeInTheDocument();
    expect(within(drawer).getAllByText('HDFC Bank').length).toBeGreaterThan(0);
    expect(within(drawer).getByText('Savings')).toBeInTheDocument();
    const section = history.closest('section');
    expect(within(section as HTMLElement).getByText('12')).toBeInTheDocument();
    expect(within(section as HTMLElement).getByText('11')).toBeInTheDocument();
  });

  // Backend V267 (Gate 1 spec §5.1): an upload refused before the engine read it is a third
  // outcome. It must never be shown as "Read" -- nothing was read -- nor as a parser failure.
  describe('an upload refused before reading', () => {
    const REFUSED: StatementAnalysisDto = {
      ...LOCKED,
      reference: 'SA-20261010-0007',
      sourceFormat: 'CSV',
      outcome: 'REJECTED',
      failureCode: 'HTTP_415',
      durationMs: null,
      byteSize: null,
    };

    it('is badged Refused with its reason, not Read and not a failure', async () => {
      vi.mocked(adminStatementAnalysisApi.paged).mockResolvedValue(pageOf([PARSED, REFUSED]));
      renderPage();

      const row = (await screen.findByRole('button', { name: 'SA-20261010-0007' })).closest('tr') as HTMLElement;
      const badge = within(row).getByText('Refused');
      expect(badge).toHaveAttribute('title', expect.stringMatching(/not the type the upload asked for.*HTTP_415/));
      expect(badge).toHaveClass('text-warning');
      expect(within(row).queryByText('Read')).not.toBeInTheDocument();
    });

    it('says in the drawer that the file was turned away before the engine read it', async () => {
      vi.mocked(adminStatementAnalysisApi.paged).mockResolvedValue(pageOf([REFUSED]));
      vi.mocked(adminStatementAnalysisApi.byReference).mockResolvedValue({
        analysis: REFUSED, timesLayoutSeen: 0, timesLayoutFailed: 0,
      });
      const user = userEvent.setup();
      renderPage();

      await user.click(await screen.findByRole('button', { name: 'SA-20261010-0007' }));

      const drawer = await screen.findByRole('dialog');
      expect(await within(drawer).findByText(/not the type the upload asked for/)).toBeInTheDocument();
      expect(within(drawer).getByText('Refusal code')).toBeInTheDocument();
      expect(within(drawer).queryByText(/The engine got transactions out of this file/)).not.toBeInTheDocument();
      expect(within(drawer).queryByText(/every line was matched/i)).not.toBeInTheDocument();
    });

    it('explains the gap between the total and Read + Failed', async () => {
      vi.mocked(adminStatementAnalysisApi.summary).mockResolvedValue({ ...SUMMARY, totalAnalysesEver: 45, rejected: 3 });
      renderPage();

      expect(await screen.findByText(/3 were refused before the engine read them/)).toBeInTheDocument();
    });

    it('says nothing about refusals when there are none, or the backend predates the count', async () => {
      renderPage();

      await screen.findByText('Uploads analysed');
      expect(screen.queryByText(/refused before the engine read/)).not.toBeInTheDocument();
    });
  });

  it('names the fields it cannot show instead of rendering empty placeholders for them', async () => {
    // The page's central rule. A tile reading "Parser version: 0" or "Verification: —" looks like a
    // measurement and gets acted on; naming the gap does not.
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Not recorded yet' })).toBeInTheDocument();
    expect(screen.getByText('Parser version')).toBeInTheDocument();
    expect(screen.getByText('Verification findings')).toBeInTheDocument();
    expect(screen.getByText('Approval state')).toBeInTheDocument();
  });

  it('does not claim every line matched for a file that was never read', async () => {
    vi.mocked(adminStatementAnalysisApi.byReference).mockResolvedValue(ENCRYPTED_DETAIL);
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'SA-20260806-0146' }));

    expect(await screen.findByText(/not measured — the file failed before its lines were read/i)).toBeInTheDocument();
    expect(screen.queryByText(/every line was matched/i)).not.toBeInTheDocument();
  });

  it('keeps raw evidence collapsed until asked for', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'SA-20260806-0145' }));
    const toggle = await screen.findByRole('button', { name: /raw evidence/i });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');

    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText(/"timesLayoutSeen": 12/)).toBeInTheDocument();
  });

  it('says so when the evidence itself cannot be loaded', async () => {
    // The one state a diagnostics page must never render as a blank screen: its own failure.
    vi.mocked(adminStatementAnalysisApi.summary).mockRejectedValue(new Error('boom'));
    vi.mocked(adminStatementAnalysisApi.paged).mockRejectedValue(new Error('boom'));
    renderPage();

    expect(await screen.findByText(/couldn't load statement analyses/i)).toBeInTheDocument();
  });

  it('keeps the rest of the page when only a page of the table fails, and can retry it', async () => {
    let failPage1 = true;
    vi.mocked(adminStatementAnalysisApi.paged).mockImplementation(async (page) => {
      if (page === 1 && failPage1) throw new Error('blip');
      return pageOf([page === 0 ? PARSED : LOCKED], page, 25);
    });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Next page' }));

    expect(await screen.findByText(/couldn't load statement analyses for page 2/i)).toBeInTheDocument();
    // The summary strip is still there -- the failure did not take the whole screen.
    expect(screen.getByText('Uploads analysed')).toBeInTheDocument();

    failPage1 = false;
    await user.click(screen.getByRole('button', { name: /try again/i }));
    expect(await screen.findByRole('button', { name: 'SA-20260806-0146' })).toBeInTheDocument();
  });

  it('tells an admin the table is empty rather than showing nothing at all', async () => {
    vi.mocked(adminStatementAnalysisApi.paged).mockResolvedValue(pageOf([]));
    renderPage();

    expect(await screen.findByText(/no statements have been analysed yet/i)).toBeInTheDocument();
  });

  it('is gated on the diagnostics permission', async () => {
    mockAuth([]);
    renderPage();

    await waitFor(() => {
      expect(adminStatementAnalysisApi.paged).not.toHaveBeenCalled();
    });
    expect(screen.queryByText('FP-1-7A91D3C2')).not.toBeInTheDocument();
  });

  it('asks the server for one page at a time and moves between pages', async () => {
    // The table used to load 50 rows at once with no way to see older uploads.
    vi.mocked(adminStatementAnalysisApi.paged).mockImplementation(async (page) =>
      page === 0 ? pageOf([PARSED], 0, 25) : pageOf([LOCKED], 1, 25));
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByRole('button', { name: 'SA-20260806-0145' })).toBeInTheDocument();
    expect(adminStatementAnalysisApi.paged).toHaveBeenCalledWith(0, 20, undefined);
    expect(screen.getByText('Page 1 of 2')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Next page' }));

    expect(await screen.findByRole('button', { name: 'SA-20260806-0146' })).toBeInTheDocument();
    // Page 2 is read as of the first page's newest row, so later uploads cannot shift it.
    expect(adminStatementAnalysisApi.paged).toHaveBeenLastCalledWith(1, 20, PARSED.createdAt);
    expect(screen.getByText('Page 2 of 2')).toBeInTheDocument();
    expect(screen.getByText('Showing 21–25 of 25')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled();
  });

  it('advances one page per click even while the next page is still loading', async () => {
    // Found in a real browser: the pager read its page number from the last RESPONSE, which
    // placeholderData keeps on screen while the next page loads, so two quick clicks both asked
    // for page 2.
    let releasePage1: (value: PagedResponse<StatementAnalysisDto>) => void = () => {};
    vi.mocked(adminStatementAnalysisApi.paged).mockImplementation((page) => {
      if (page === 0) return Promise.resolve(pageOf([PARSED], 0, 45));
      if (page === 1) return new Promise((resolve) => { releasePage1 = resolve; });
      return Promise.resolve(pageOf([LOCKED], page, 45));
    });
    const user = userEvent.setup();
    renderPage();

    const next = await screen.findByRole('button', { name: 'Next page' });
    await user.click(next);
    // Page 2 is still loading, so page 1's rows stand in for it -- and are marked as such.
    expect(screen.getByRole('table').closest('[aria-busy]')).toHaveAttribute('aria-busy', 'true');
    await user.click(next);

    await waitFor(() => expect(adminStatementAnalysisApi.paged).toHaveBeenLastCalledWith(2, 20, PARSED.createdAt));
    expect(await screen.findByText('Page 3 of 3')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('table').closest('[aria-busy]')).toHaveAttribute('aria-busy', 'false'));
    releasePage1(pageOf([PARSED], 1, 45));
  });

  it('fetches the first page only once while freezing the list at it', async () => {
    renderPage();
    expect(await screen.findByText(/showing uploads up to/i)).toBeInTheDocument();
    expect(adminStatementAnalysisApi.paged).toHaveBeenCalledTimes(1);
  });

  it('shows an upload that arrived mid-browse only after Refresh, back on the first page', async () => {
    const NEWER: StatementAnalysisDto = { ...PARSED, reference: 'SA-20260806-0200', createdAt: '2026-08-06T11:00:00Z' };
    const OLDER: StatementAnalysisDto = { ...LOCKED, createdAt: '2026-08-06T10:00:00Z' };
    let arrived = false;
    vi.mocked(adminStatementAnalysisApi.paged).mockImplementation(async (page, _size, before) => {
      // The server's snapshot filter, in miniature, over rows in real newest-first order.
      const all = arrived ? [NEWER, PARSED, OLDER] : [PARSED, OLDER];
      const visible = before ? all.filter((a) => a.createdAt <= before) : all;
      return { content: visible.slice(page, page + 1), page, size: 1, totalElements: visible.length, totalPages: visible.length };
    });
    const user = userEvent.setup();
    renderPage();

    await screen.findByRole('button', { name: 'SA-20260806-0145' });
    arrived = true;
    await user.click(screen.getByRole('button', { name: 'Next page' }));
    // Frozen: page 2 is still the row that was second when the list was opened.
    expect(await screen.findByRole('button', { name: 'SA-20260806-0146' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'SA-20260806-0200' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /refresh/i }));

    expect(await screen.findByRole('button', { name: 'SA-20260806-0200' })).toBeInTheDocument();
    expect(screen.getByText(/page 1 of 3/i)).toBeInTheDocument();
    // And the list is re-frozen at the NEW newest row, not the old one: the next page asks for it.
    await user.click(screen.getByRole('button', { name: 'Next page' }));
    await waitFor(() => expect(adminStatementAnalysisApi.paged).toHaveBeenLastCalledWith(1, 20, NEWER.createdAt));
    expect(await screen.findByRole('button', { name: 'SA-20260806-0145' })).toBeInTheDocument();
  });

  it('shows the five largest reasons first and the rest on request', async () => {
    vi.mocked(adminStatementAnalysisApi.summary).mockResolvedValue({
      ...SUMMARY,
      unanchoredReasons: Object.fromEntries(
        [1, 2, 3, 4, 5, 6, 7].map((n) => [`UNANCHORED_DATE_UNPARSEABLE:${'9'.repeat(n)}`, n * 10])),
    });
    const user = userEvent.setup();
    renderPage();

    const reasonsPanel = (await screen.findByRole('heading', { name: /why lines were not matched/i })).closest('section') as HTMLElement;
    expect(within(reasonsPanel).getAllByText(/^Date not understood/)).toHaveLength(5);
    // Largest first, smallest two hidden.
    expect(within(reasonsPanel).getAllByText(/^Date not understood/)[0]).toHaveTextContent('9999999');
    expect(within(reasonsPanel).queryByText('Date not understood: 9')).not.toBeInTheDocument();

    await user.click(within(reasonsPanel).getByRole('button', { name: /show all 7 reasons \(2 more\)/i }));
    expect(within(reasonsPanel).getAllByText(/^Date not understood/)).toHaveLength(7);
    // Seven lines of the same kind carry its explanation once, not seven times.
    expect(within(reasonsPanel).getAllByText(/9 = a digit, X = a letter/)).toHaveLength(1);

    await user.click(within(reasonsPanel).getByRole('button', { name: /show only the largest/i }));
    expect(within(reasonsPanel).getAllByText(/^Date not understood/)).toHaveLength(5);
  });

  it('offers no "show all" toggle when every reason already fits', async () => {
    renderPage();
    await screen.findByRole('heading', { name: /why lines were not matched/i });
    expect(screen.queryByRole('button', { name: /show all/i })).not.toBeInTheDocument();
  });

  it('explains every term in plain words when asked', async () => {
    const user = userEvent.setup();
    renderPage();

    const toggle = await screen.findByRole('button', { name: /what do these terms mean/i });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText(/unmatched lines \(unanchored rows\)/i)).not.toBeInTheDocument();

    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText(/unmatched lines \(unanchored rows\)/i)).toBeInTheDocument();
    expect(screen.getByText(/statement format \(layout \/ fingerprint\)/i)).toBeInTheDocument();
  });

  it('shows which bank and kind of statement each upload was read as', async () => {
    renderPage();

    const row = (await screen.findByRole('button', { name: 'SA-20260806-0145' })).closest('tr') as HTMLElement;
    expect(within(row).getByText('HDFC Bank')).toBeInTheDocument();
    expect(within(row).getByText(/Savings ·/)).toBeInTheDocument();
    // The fingerprint moved under the bank rather than disappearing.
    expect(within(row).getByText('FP-1-7A91D3C2')).toBeInTheDocument();
  });

  it('keeps "bank not recognised" and "never looked" as two different answers', async () => {
    vi.mocked(adminStatementAnalysisApi.paged).mockResolvedValue(pageOf([
      { ...PARSED, reference: 'SA-A', bankName: null, statementType: null },
      LOCKED,
    ]));
    renderPage();

    const looked = (await screen.findByRole('button', { name: 'SA-A' })).closest('tr') as HTMLElement;
    expect(within(looked).getByText('Bank not recognised')).toBeInTheDocument();

    const never = screen.getByRole('button', { name: 'SA-20260806-0146' }).closest('tr') as HTMLElement;
    const cell = within(never).getAllByRole('cell')[0];
    expect(cell).toHaveTextContent('—');
    expect(cell).not.toHaveTextContent('Bank not recognised');
    expect(cell).toHaveAttribute('title', expect.stringMatching(/not recorded/i));
  });

  it('names a composite statement by every product it holds', async () => {
    vi.mocked(adminStatementAnalysisApi.paged).mockResolvedValue(pageOf([
      { ...PARSED, bankName: 'HSBC', statementType: 'SAVINGS,FIXED_DEPOSIT' },
    ]));
    renderPage();

    expect(await screen.findByText(/Savings \+ Fixed deposit ·/)).toBeInTheDocument();
  });

  it('names a failure in plain words rather than as a code', async () => {
    renderPage();

    const lockedRow = (await screen.findByRole('button', { name: 'SA-20260806-0146' })).closest('tr') as HTMLElement;
    expect(within(lockedRow).getByText('Password needed')).toBeInTheDocument();
    expect(within(lockedRow).queryByText('IMPORT_PDF_PASSWORD_REQUIRED')).not.toBeInTheDocument();
  });

  it('falls back to the raw code for a failure it has no wording for', async () => {
    vi.mocked(adminStatementAnalysisApi.paged).mockResolvedValue(
      pageOf([{ ...LOCKED, failureCode: 'SOME_FUTURE_CODE' }]));
    renderPage();

    const badge = await screen.findByText('Other failure');
    expect(badge.closest('span')).toHaveAttribute('title', expect.stringContaining('SOME_FUTURE_CODE'));
  });

  it('names a codeless failure (stored as an exception class name) as an unexpected error', async () => {
    vi.mocked(adminStatementAnalysisApi.paged).mockResolvedValue(
      pageOf([{ ...LOCKED, failureCode: 'NullPointerException' }]));
    renderPage();

    const badge = await screen.findByText('Unexpected error');
    expect(badge.closest('span')).toHaveAttribute('title', expect.stringContaining('NullPointerException'));
  });

  describe('analysing a document', () => {
    function pdf(name = 'statement.pdf') {
      return new File(['%PDF-1.4 pretend'], name, { type: 'application/pdf' });
    }

    it('runs the engine and opens the analysis it produced', async () => {
      const user = userEvent.setup();
      vi.mocked(adminAnalysisRunApi.analyze).mockResolvedValue(DETAIL);
      renderPage();

      await user.upload(await screen.findByLabelText(/statement \(pdf or csv\)/i), pdf());
      await user.click(screen.getByRole('button', { name: /^analyse$/i }));

      await waitFor(() => expect(adminAnalysisRunApi.analyze).toHaveBeenCalled());
      // The detail panel opening is the observable outcome -- an admin who just analysed something
      // should be looking at it, not hunting for it in the table.
      expect(await screen.findByRole('heading', { name: 'This layout' })).toBeInTheDocument();
    });

    it('asks for a password when the document turns out to be encrypted', async () => {
      // An encrypted PDF comes back as a 200 with a FAILED analysis, so nothing throws. The page has
      // to notice the failure CODE to offer the retry. The fixture carries the stored enum NAME the
      // real API returns; the page once matched only the wire code IMPORT_008 and so never offered
      // this prompt for a real upload.
      const user = userEvent.setup();
      vi.mocked(adminAnalysisRunApi.analyze).mockResolvedValue(ENCRYPTED_DETAIL);
      renderPage();

      await user.upload(await screen.findByLabelText(/statement \(pdf or csv\)/i), pdf('locked.pdf'));
      expect(screen.queryByLabelText(/document password/i)).not.toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: /^analyse$/i }));

      expect(await screen.findByLabelText(/document password/i)).toBeInTheDocument();
      expect(notifyError).toHaveBeenCalledWith(expect.stringMatching(/encrypted/i));
    });

    it('still recognises the wire-code form of the password failure', async () => {
      const user = userEvent.setup();
      vi.mocked(adminAnalysisRunApi.analyze).mockResolvedValue({
        ...ENCRYPTED_DETAIL, analysis: { ...LOCKED, failureCode: 'IMPORT_009' },
      });
      renderPage();

      await user.upload(await screen.findByLabelText(/statement \(pdf or csv\)/i), pdf('locked.pdf'));
      await user.click(screen.getByRole('button', { name: /^analyse$/i }));

      expect(await screen.findByLabelText(/document password/i)).toBeInTheDocument();
    });

    it('sends the password in the request body once given', async () => {
      const user = userEvent.setup();
      vi.mocked(adminAnalysisRunApi.analyze).mockResolvedValue(ENCRYPTED_DETAIL);
      renderPage();

      await user.upload(await screen.findByLabelText(/statement \(pdf or csv\)/i), pdf('locked.pdf'));
      await user.click(screen.getByRole('button', { name: /^analyse$/i }));
      await user.type(await screen.findByLabelText(/document password/i), 'letmein');
      await user.click(screen.getByRole('button', { name: /^analyse$/i }));

      expect(adminAnalysisRunApi.analyze).toHaveBeenLastCalledWith(expect.any(File), 'letmein');
    });

    it('hides the upload panel from someone who may only read the reports', async () => {
      // Running the engine is a separately grantable permission (V61) precisely because viewing
      // diagnostics is documented as read-only. The UI has to honour that split, not just the API.
      mockAuth(['PLATFORM_DIAGNOSTICS_VIEW']);
      renderPage();

      expect(await screen.findByText('FP-1-7A91D3C2')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /^analyse$/i })).not.toBeInTheDocument();
    });
  });
});
