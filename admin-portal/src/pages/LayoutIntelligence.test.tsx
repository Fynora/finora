import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import LayoutIntelligence from './LayoutIntelligence';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminLayoutsApi, adminLayoutRegistryApi } from '../api/endpoints';
import type { LayoutEvidenceReport, LayoutProfileView, LayoutSummary, RegistryEntry, UnknownHeaderSummary } from '../types';

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
  adminLayoutsApi: {
    overview: vi.fn(),
    drifting: vi.fn(),
    unknownHeaders: vi.fn(),
    evidence: vi.fn(),
    timeline: vi.fn(),
  },
  adminLayoutRegistryApi: {
    registry: vi.fn(),
    reviewQueue: vi.fn(),
    resolveReview: vi.fn(),
    update: vi.fn(),
    profiles: vi.fn(),
    createProfile: vi.fn(),
    renameProfile: vi.fn(),
    linkToProfile: vi.fn(),
    unlinkFromProfile: vi.fn(),
  },
}));

function renderPage(path = '/layout-intelligence') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <LayoutIntelligence />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function mockAuth(permissions: string[] = ['PLATFORM_DIAGNOSTICS_VIEW']) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Ops Admin',
  }));
}

const EVIDENCE: LayoutEvidenceReport = {
  totalImportsAnalysed: 42,
  distinctLayouts: 9,
  recurringLayouts: 3,
  importsOnRecurringLayouts: 12,
  medianDurationFirstEncounter: 1200,
  medianDurationRecurring: 1180,
  avgUnknownHeadersFirstEncounter: 1.5,
  avgUnknownHeadersRecurring: 1.5,
  avgSkippedRowsFirstEncounter: 0.25,
  avgSkippedRowsRecurring: 0.25,
  verdict: 'Recurring layouts import at effectively the same speed as first encounters. No performance case for layout reuse.',
};

const LAYOUT: LayoutSummary = {
  fingerprint: 'FP-1-A1B2C3D4',
  sourceFormat: 'PDF',
  columns: 6,
  usageCount: 4,
  firstSeen: '2026-05-01T00:00:00Z',
  lastSeen: '2026-08-01T00:00:00Z',
  stableCapabilities: ['RUNNING_BALANCE'],
  unstableCapabilities: ['DR_CR_SUFFIX'],
  unknownHeaders: ['Chq/Ref. No.'],
  medianDurationMs: 1180,
  totalRowsImported: 400,
  totalRowsSkipped: 1,
};

beforeEach(() => {
  vi.clearAllMocks();
  mockAuth();
  vi.mocked(adminLayoutRegistryApi.reviewQueue).mockResolvedValue([]);
  vi.mocked(adminLayoutRegistryApi.profiles).mockResolvedValue([]);
  vi.mocked(adminLayoutRegistryApi.registry).mockResolvedValue([]);
  vi.mocked(adminLayoutsApi.evidence).mockResolvedValue(EVIDENCE);
  vi.mocked(adminLayoutsApi.overview).mockResolvedValue([LAYOUT]);
  vi.mocked(adminLayoutsApi.drifting).mockResolvedValue([]);
  vi.mocked(adminLayoutsApi.unknownHeaders).mockResolvedValue([]);
});

describe('LayoutIntelligence', () => {
  /**
   * The reason this page exists. The evidence report is what decides whether structural learning is
   * ever built (proposal §11, precondition 3), and until this page shipped it was computed by the
   * backend and readable by nobody.
   */
  it('renders the evidence verdict, which is the point of the page', async () => {
    renderPage();
    expect(await screen.findByText(/No performance case for layout reuse/)).toBeInTheDocument();
  });

  /**
   * A "no evidence" verdict is a SUCCESSFUL outcome, and the numbers behind it are near-identical
   * by construction. If the page only rendered the table, a reader would supply their own, more
   * encouraging conclusion -- which is exactly what the verdict text exists to prevent.
   */
  it('shows the first-encounter and recurrence figures side by side', async () => {
    renderPage();
    expect(await screen.findByText('Median import duration')).toBeInTheDocument();
    expect(screen.getByText('Imports analysed')).toBeInTheDocument();
    expect(screen.getByText('42')).toBeInTheDocument();
  });

  /**
   * The backend omits a figure when there is not enough data to compute it, and renders it as null.
   * A UI that fell back with `?? 0` would print "0 ms" and close the question with a number nobody
   * earned -- EvidenceReport's own doc comment calls that out as worse than no report at all.
   */
  it('renders an unmeasured figure as "Not measured", never as zero', async () => {
    vi.mocked(adminLayoutsApi.evidence).mockResolvedValue({
      ...EVIDENCE,
      medianDurationFirstEncounter: null,
      medianDurationRecurring: null,
      avgSkippedRowsFirstEncounter: null,
      avgSkippedRowsRecurring: null,
      verdict: 'Too few have a recorded duration to compare.',
    });
    renderPage();

    await screen.findByText(/Too few have a recorded duration/);
    expect(screen.getAllByText('Not measured').length).toBeGreaterThanOrEqual(4);
    expect(screen.queryByText('0 ms')).not.toBeInTheDocument();
    expect(screen.queryByText('0.00')).not.toBeInTheDocument();
  });

  it('lists layouts with their unstable capabilities', async () => {
    renderPage();
    expect(await screen.findByText('FP-1-A1B2C3D4')).toBeInTheDocument();
    expect(screen.getByText('DR_CR_SUFFIX')).toBeInTheDocument();
  });

  /** A single observation is not a trend, and the UI has to say so rather than showing a bare 1. */
  it('marks a layout seen only once', async () => {
    vi.mocked(adminLayoutsApi.overview).mockResolvedValue([{ ...LAYOUT, usageCount: 1 }]);
    renderPage();
    expect(await screen.findByText(/seen once/)).toBeInTheDocument();
  });

  /**
   * layoutCount > 1 is the signal worth acting on: a header spanning several DISTINCT layouts is a
   * gap in the parser's hint lists rather than one bank's quirk. That distinction is the whole
   * value of this tab, so it is stated in the row rather than left to be inferred from a number.
   */
  it('distinguishes an unknown header that spans layouts from one that does not', async () => {
    const spanning: UnknownHeaderSummary = {
      header: 'Value Dt', importCount: 12, layoutCount: 4,
      fingerprints: ['FP-1-A', 'FP-1-B'], firstSeen: '2026-05-01T00:00:00Z', lastSeen: '2026-08-01T00:00:00Z',
    };
    const isolated: UnknownHeaderSummary = { ...spanning, header: 'Cheque Img', layoutCount: 1, fingerprints: ['FP-1-A'] };
    vi.mocked(adminLayoutsApi.unknownHeaders).mockResolvedValue([spanning, isolated]);

    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /Unknown headers/ }));

    expect(await screen.findByText('Value Dt')).toBeInTheDocument();
    expect(screen.getByText(/Spans layouts/)).toBeInTheDocument();
    expect(screen.getByText('Single layout')).toBeInTheDocument();
  });

  /** Drift says "this changed", never "this is wrong" -- the empty state has to read that way too. */
  it('shows the drifting tab with no false alarm when nothing changed', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /Drifting/ }));
    expect(await screen.findByText(/No layout has changed structurally/)).toBeInTheDocument();
  });

  it('degrades to the rest of the page when the evidence report fails', async () => {
    vi.mocked(adminLayoutsApi.evidence).mockRejectedValue(new Error('boom'));
    renderPage();
    expect(await screen.findByText(/Couldn't load the evidence report/)).toBeInTheDocument();
    // The layout table is an independent query and must still render.
    expect(await screen.findByText('FP-1-A1B2C3D4')).toBeInTheDocument();
  });

  it('is gated on PLATFORM_DIAGNOSTICS_VIEW', async () => {
    mockAuth([]);
    renderPage();
    await waitFor(() => {
      expect(screen.queryByText(/Is layout reuse worth building/)).not.toBeInTheDocument();
    });
  });
});

function entry(overrides: Partial<RegistryEntry> = {}): RegistryEntry {
  return {
    fingerprint: 'FP-1-AAAA0001', name: null, status: 'OBSERVED', sourceFormat: 'PDF', parser: null,
    observationCount: 0, stagingCount: 2, firstSeen: '2026-09-01T00:00:00Z', lastSeen: '2026-09-02T00:00:00Z',
    needsReview: true, reviewReasons: ['NEW_LAYOUT', 'BLANK_DESCRIPTIONS', 'VERIFICATION_NOT_PASSED:BALANCE_CHAIN', 'IDENTITY_CONFLICT'], reviewFlaggedAt: '2026-09-01T00:00:00Z',
    reviewAnalysisReference: 'SA-000001', acknowledgedReasons: [], profileId: null, profileName: null,
    profileVersion: null, profileLinkSource: null, ...overrides,
  };
}

describe('LayoutIntelligence — layout review queue and profiles', () => {
  it('opens the review queue from the alert email link, with the reasons in plain words and the count on the tab', async () => {
    vi.mocked(adminLayoutRegistryApi.reviewQueue).mockResolvedValue([entry()]);
    renderPage('/layout-intelligence?tab=review');

    expect(await screen.findByText('FP-1-AAAA0001')).toBeInTheDocument();
    expect(screen.getByText('New layout')).toBeInTheDocument();
    expect(screen.getByText('Mostly blank descriptions')).toBeInTheDocument();
    expect(screen.getByText('Verification did not pass: BALANCE_CHAIN')).toBeInTheDocument();
    expect(screen.getByText('Seen as a different bank or account type')).toBeInTheDocument();
    expect(screen.getByText('SA-000001')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Needs review \(1\)/ })).toBeInTheDocument();
  });

  it('resolves a flagged layout for a curator', async () => {
    mockAuth(['PLATFORM_DIAGNOSTICS_VIEW', 'LAYOUT_REGISTRY_MANAGE']);
    vi.mocked(adminLayoutRegistryApi.reviewQueue).mockResolvedValue([entry()]);
    vi.mocked(adminLayoutRegistryApi.resolveReview).mockResolvedValue(entry({ needsReview: false }));
    const user = userEvent.setup();
    renderPage('/layout-intelligence?tab=review');

    await user.click(await screen.findByRole('button', { name: /Resolve/ }));

    await waitFor(() => expect(adminLayoutRegistryApi.resolveReview).toHaveBeenCalled());
    expect(vi.mocked(adminLayoutRegistryApi.resolveReview).mock.calls[0][0]).toBe('FP-1-AAAA0001');
  });

  it('shows the queue read-only without LAYOUT_REGISTRY_MANAGE', async () => {
    vi.mocked(adminLayoutRegistryApi.reviewQueue).mockResolvedValue([entry()]);
    renderPage('/layout-intelligence?tab=review');

    expect(await screen.findByText('FP-1-AAAA0001')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Resolve/ })).not.toBeInTheDocument();
    expect(screen.getByLabelText('Name for FP-1-AAAA0001')).toBeDisabled();
  });

  it('surfaces the server\'s message when an action fails', async () => {
    mockAuth(['PLATFORM_DIAGNOSTICS_VIEW', 'LAYOUT_REGISTRY_MANAGE']);
    vi.mocked(adminLayoutRegistryApi.reviewQueue).mockResolvedValue([entry()]);
    vi.mocked(adminLayoutRegistryApi.resolveReview).mockRejectedValue(
      { response: { data: { message: 'Layout FP-1-AAAA0001 is not waiting for review' } } });
    const user = userEvent.setup();
    renderPage('/layout-intelligence?tab=review');

    await user.click(await screen.findByRole('button', { name: /Resolve/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent('not waiting for review');
  });

  it('lists profiles with their layouts in version order and adds a layout as the next version', async () => {
    mockAuth(['PLATFORM_DIAGNOSTICS_VIEW', 'LAYOUT_REGISTRY_MANAGE']);
    const profile: LayoutProfileView = {
      id: 'p-1', name: 'Sample Bank Credit Card', automatic: true,
      versions: [
        entry({ fingerprint: 'FP-1-OLD00001', profileId: 'p-1', profileName: 'Sample Bank Credit Card', profileVersion: 1, name: 'Old table', profileLinkSource: 'AUTO' }),
        entry({ fingerprint: 'FP-1-NEW00002', profileId: 'p-1', profileName: 'Sample Bank Credit Card', profileVersion: 2 }),
      ],
    };
    vi.mocked(adminLayoutRegistryApi.profiles).mockResolvedValue([profile]);
    vi.mocked(adminLayoutRegistryApi.registry).mockResolvedValue([entry({ fingerprint: 'FP-1-LOOSE003' })]);
    vi.mocked(adminLayoutRegistryApi.linkToProfile).mockResolvedValue(entry());
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /Profiles/ }));

    expect(await screen.findByDisplayValue('Sample Bank Credit Card')).toBeInTheDocument();
    const versions = screen.getAllByText(/^v\d$/).map((el) => el.textContent);
    expect(versions).toEqual(['v1', 'v2']);
    expect(screen.getByText('Old table')).toBeInTheDocument();
    expect(screen.getByText('auto')).toBeInTheDocument();

    await user.selectOptions(await screen.findByLabelText('Add a layout to Sample Bank Credit Card'), 'FP-1-LOOSE003');
    await waitFor(() => expect(adminLayoutRegistryApi.linkToProfile).toHaveBeenCalledWith('FP-1-LOOSE003', 'p-1'));
  });

  it('creates a profile', async () => {
    mockAuth(['PLATFORM_DIAGNOSTICS_VIEW', 'LAYOUT_REGISTRY_MANAGE']);
    vi.mocked(adminLayoutRegistryApi.createProfile).mockResolvedValue({ id: 'p-2', name: 'New Profile', automatic: false, versions: [] });
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /Profiles/ }));
    await user.type(await screen.findByLabelText('New profile name'), 'New Profile');
    await user.click(screen.getByRole('button', { name: /Create profile/ }));

    await waitFor(() => expect(adminLayoutRegistryApi.createProfile).toHaveBeenCalled());
    expect(vi.mocked(adminLayoutRegistryApi.createProfile).mock.calls[0][0]).toBe('New Profile');
  });

  it('renames a profile in place', async () => {
    mockAuth(['PLATFORM_DIAGNOSTICS_VIEW', 'LAYOUT_REGISTRY_MANAGE']);
    vi.mocked(adminLayoutRegistryApi.profiles).mockResolvedValue([{ id: 'p-1', name: 'Old Name', automatic: false, versions: [] }]);
    vi.mocked(adminLayoutRegistryApi.renameProfile).mockResolvedValue(undefined as never);
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /Profiles/ }));
    const field = await screen.findByLabelText('Profile name for Old Name');
    await user.clear(field);
    await user.type(field, 'Better Name{Enter}');

    await waitFor(() => expect(adminLayoutRegistryApi.renameProfile).toHaveBeenCalledWith('p-1', 'Better Name'));
  });
});
