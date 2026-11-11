import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import AuditLog from './AuditLog';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminAuditApi } from '../api/endpoints';
import { pagedResponse } from '../test/pagedResponse';
import type { AdminAuditLogRow } from '../types';

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
  adminAuditApi: { forUser: vi.fn(), global: vi.fn() },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AuditLog />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

// AdminLayout always renders Sidebar, which reads `permissions` off this same hook, so every
// mock here must supply it (see MerchantIntelligence.test.tsx's bug-fix comment for why).
function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Support Admin',
    logout: vi.fn(),
  }));
}

// pagedResponse() is imported from ../test/pagedResponse -- see that file's own doc comment for
// why this was a per-file duplicate before (and, in this file specifically, wrong: it hardcoded
// `content: unknown[]` instead of being generic).

const NO_FILTERS = { q: undefined, dateFrom: undefined, dateTo: undefined, sortDir: 'desc' };

/** One feed row with every field the API returns, overridable per test. */
function row(overrides: Partial<AdminAuditLogRow> = {}): AdminAuditLogRow {
  return {
    id: 'log-1', userId: 'user-abcdef12-0000-0000-0000-000000000000', userName: null, userEmail: null,
    actorId: null, actorName: null, actorEmail: null, systemActor: null, action: 'USER_LOGIN', entityType: 'User',
    entityId: null, metadata: null, requestId: null, createdAt: new Date().toISOString(), redactedAt: null,
    ...overrides,
  };
}

beforeEach(() => {
  localStorage.clear();
});

describe('AuditLog', () => {
  beforeEach(() => {
    vi.mocked(adminAuditApi.global).mockReset();
  });

  it('shows an access-denied message when the account lacks AUDIT_VIEW', () => {
    mockAuth([]);
    vi.mocked(adminAuditApi.global).mockResolvedValue(pagedResponse([]));

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
  });

  it('shows the empty message when there is no activity yet', async () => {
    mockAuth(['AUDIT_VIEW']);
    vi.mocked(adminAuditApi.global).mockResolvedValue(pagedResponse([]));

    renderPage();

    await waitFor(() => expect(screen.getByText('No activity recorded yet.')).toBeInTheDocument());
  });

  it('groups entries under a "Today" day header and shows action + entity detail', async () => {
    mockAuth(['AUDIT_VIEW']);
    const now = new Date().toISOString();
    vi.mocked(adminAuditApi.global).mockResolvedValue(pagedResponse([
      row({ id: 'log-1', action: 'USER_LOGIN', entityType: 'User', createdAt: now }),
      row({ id: 'log-2', action: 'MERCHANT_UPDATED', entityType: 'Merchant', entityId: 'merchant-12345678', createdAt: now }),
    ]));

    renderPage();

    await waitFor(() => expect(screen.getByText('Today')).toBeInTheDocument());
    expect(screen.getByText('USER_LOGIN')).toBeInTheDocument();
    expect(screen.getByText('MERCHANT_UPDATED')).toBeInTheDocument();
    expect(screen.getByText(/^Merchant ·/)).toBeInTheDocument();
  });

  it('paginates via the next/previous controls', async () => {
    mockAuth(['AUDIT_VIEW']);
    vi.mocked(adminAuditApi.global).mockImplementation((page: number) =>
      Promise.resolve(pagedResponse(
        [row({ id: `log-page-${page}`, action: 'ACCOUNT_CREATED', entityType: 'Account' })],
        { page, totalElements: 50, totalPages: 2 },
      ))
    );
    const user = userEvent.setup();

    renderPage();

    await waitFor(() => expect(screen.getByText('Page 1 of 2')).toBeInTheDocument());

    await user.click(screen.getByText('Page 1 of 2').parentElement!.querySelector('button:last-of-type')!);

    await waitFor(() => expect(adminAuditApi.global).toHaveBeenCalledWith(1, 25, NO_FILTERS));
  });

  it('applies a search filter via the FilterBar', async () => {
    mockAuth(['AUDIT_VIEW']);
    vi.mocked(adminAuditApi.global).mockResolvedValue(pagedResponse([]));
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(adminAuditApi.global).toHaveBeenCalledWith(0, 25, NO_FILTERS));

    await user.type(screen.getByPlaceholderText('Search action or entity type…'), 'MERCHANT_UPDATED');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() => expect(adminAuditApi.global).toHaveBeenCalledWith(
      0, 25, { q: 'MERCHANT_UPDATED', dateFrom: undefined, dateTo: undefined, sortDir: 'desc' }
    ));
  });

  it('switches sort direction immediately via the sort select', async () => {
    mockAuth(['AUDIT_VIEW']);
    vi.mocked(adminAuditApi.global).mockResolvedValue(pagedResponse([]));
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(adminAuditApi.global).toHaveBeenCalledWith(0, 25, NO_FILTERS));

    await user.selectOptions(screen.getByDisplayValue('Newest first'), 'asc');

    await waitFor(() => expect(adminAuditApi.global).toHaveBeenCalledWith(
      0, 25, { q: undefined, dateFrom: undefined, dateTo: undefined, sortDir: 'asc' }
    ));
  });

  it('saves the current filters as a named view, then applies it back after clearing the search', async () => {
    mockAuth(['AUDIT_VIEW']);
    vi.mocked(adminAuditApi.global).mockResolvedValue(pagedResponse([]));
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(adminAuditApi.global).toHaveBeenCalledWith(0, 25, NO_FILTERS));

    const searchInput = screen.getByPlaceholderText('Search action or entity type…');
    await user.type(searchInput, 'BANK_UPDATED');
    await user.click(screen.getByRole('button', { name: /Views/ }));
    await user.click(screen.getByText('+ Save current filters'));
    await user.type(screen.getByPlaceholderText('View name'), 'Bank changes');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    // Clear the search box so applying the saved view is the only thing that could restore it.
    await user.clear(searchInput);
    await user.click(screen.getByText('Bank changes'));

    await waitFor(() => expect(adminAuditApi.global).toHaveBeenCalledWith(
      0, 25, { q: 'BANK_UPDATED', dateFrom: undefined, dateTo: undefined, sortDir: 'desc' }
    ));
    expect(searchInput).toHaveValue('BANK_UPDATED');

    // And it really did persist to localStorage under this page's own key (useSavedViews.test.ts
    // covers the hook's persistence mechanics in isolation; this just proves AuditLog wired the
    // right storage key through, not a made-up one).
    const stored = JSON.parse(localStorage.getItem('finora-admin-views-audit-log')!);
    expect(stored).toEqual([{ name: 'Bank changes', values: {
      q: 'BANK_UPDATED', dateFrom: '', dateTo: '', sortDir: 'desc', userId: '', actorId: '', action: '', requestId: '',
    } }]);
  });

  // ---- Drill-down (2026-11-11) ----

  const DRILL_ROW = row({
    id: 'log-drill', userId: 'cust-0000-1111', userName: 'Asha Rao', userEmail: 'asha@example.com',
    actorId: 'admin-0000-2222', actorName: 'Ops Admin', actorEmail: 'ops@example.com',
    action: 'SUBSCRIPTION_PLAN_CHANGED', entityType: 'Subscription', entityId: 'sub-0000-3333',
    metadata: { actorId: 'admin-0000-2222', toPlanCode: 'PLUS', reason: 'Goodwill', extra: { nested: 1 } },
    requestId: 'req-0000-4444',
  });

  it('names who was involved on the row, and opens it to show everything recorded', async () => {
    mockAuth(['AUDIT_VIEW', 'USER_VIEW']);
    vi.mocked(adminAuditApi.global).mockResolvedValue(pagedResponse([DRILL_ROW]));
    const user = userEvent.setup();
    renderPage();

    const entry = await screen.findByRole('button', { name: /SUBSCRIPTION_PLAN_CHANGED/ });
    expect(entry).toHaveTextContent('by Ops Admin (ops@example.com)');
    expect(entry).toHaveTextContent('Asha Rao (asha@example.com)');
    expect(entry).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText('req-0000-4444')).not.toBeInTheDocument();

    await user.click(entry);

    expect(entry).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('cust-0000-1111')).toBeInTheDocument();
    expect(screen.getByText('admin-0000-2222')).toBeInTheDocument();
    expect(screen.getByText('sub-0000-3333')).toBeInTheDocument();
    expect(screen.getByText('req-0000-4444')).toBeInTheDocument();
    expect(screen.getByRole('rowheader', { name: 'toPlanCode' })).toBeInTheDocument();
    expect(screen.getByText('PLUS')).toBeInTheDocument();
    expect(screen.getByText('{"nested":1}')).toBeInTheDocument();
    // Shown as "Done by" already, so not repeated among the details.
    expect(screen.queryByRole('rowheader', { name: 'actorId' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open profile' })).toHaveAttribute('href', '/users/cust-0000-1111');
  });

  it('narrows the feed to one admin, shows it as a chip, and clears it again', async () => {
    mockAuth(['AUDIT_VIEW', 'USER_VIEW']);
    vi.mocked(adminAuditApi.global).mockResolvedValue(pagedResponse([DRILL_ROW]));
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: /SUBSCRIPTION_PLAN_CHANGED/ }));
    await user.click(screen.getByRole('button', { name: 'Only this admin' }));

    await waitFor(() => expect(adminAuditApi.global).toHaveBeenLastCalledWith(
      0, 25, { ...NO_FILTERS, actorId: 'admin-0000-2222' }));
    const chips = screen.getByRole('list', { name: 'Narrowed to' });
    expect(chips).toHaveTextContent('Done by: Ops Admin (ops@example.com)');

    await user.click(screen.getByRole('button', { name: 'Remove the done by filter' }));

    await waitFor(() => expect(adminAuditApi.global).toHaveBeenLastCalledWith(0, 25, NO_FILTERS));
    expect(screen.queryByRole('list', { name: 'Narrowed to' })).not.toBeInTheDocument();
  });

  it('narrows to one account, one action and one request with exact filters', async () => {
    mockAuth(['AUDIT_VIEW', 'USER_VIEW']);
    vi.mocked(adminAuditApi.global).mockResolvedValue(pagedResponse([DRILL_ROW]));
    const user = userEvent.setup();
    renderPage();

    const open = async () => {
      const entry = await screen.findByRole('button', { name: /SUBSCRIPTION_PLAN_CHANGED/ });
      if (entry.getAttribute('aria-expanded') === 'false') await user.click(entry);
    };
    await open();
    await user.click(screen.getByRole('button', { name: 'Only this account' }));
    await open();
    await user.click(screen.getByRole('button', { name: 'Only this action' }));
    await open();
    await user.click(screen.getByRole('button', { name: 'Everything from this request' }));

    await waitFor(() => expect(adminAuditApi.global).toHaveBeenLastCalledWith(0, 25, {
      ...NO_FILTERS, userId: 'cust-0000-1111', action: 'SUBSCRIPTION_PLAN_CHANGED', requestId: 'req-0000-4444',
    }));
  });

  /** FINANCE/OPS hold AUDIT_VIEW without USER_VIEW: the API sends no customer name, and the page
   *  must neither invent one nor link to a profile they cannot open. */
  it('says a customer is hidden, and offers no profile link, without USER_VIEW', async () => {
    mockAuth(['AUDIT_VIEW']);
    vi.mocked(adminAuditApi.global).mockResolvedValue(pagedResponse([{ ...DRILL_ROW, userName: null, userEmail: null }]));
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: /SUBSCRIPTION_PLAN_CHANGED/ }));

    expect(screen.getByText('Customer (needs USER_VIEW to see who)')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Open profile' })).not.toBeInTheDocument();
  });

  it('says when retention removed the details of an entry, instead of showing none', async () => {
    mockAuth(['AUDIT_VIEW']);
    vi.mocked(adminAuditApi.global).mockResolvedValue(pagedResponse([
      row({ metadata: { redacted: true }, redactedAt: '2026-10-01T00:00:00Z' }),
    ]));
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: /USER_LOGIN/ }));

    expect(screen.getByText(/Removed on 01 Oct 2026, 05:30 IST after the retention period/)).toBeInTheDocument();
    expect(screen.queryByRole('rowheader', { name: 'redacted' })).not.toBeInTheDocument();
  });

  it('applies a view saved before drill-down existed, with the drill filters cleared', async () => {
    localStorage.setItem('finora-admin-views-audit-log', JSON.stringify([
      { name: 'Old view', values: { q: 'BANK_UPDATED', dateFrom: '', dateTo: '', sortDir: 'desc' } },
    ]));
    mockAuth(['AUDIT_VIEW']);
    vi.mocked(adminAuditApi.global).mockResolvedValue(pagedResponse([]));
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: /Views/ }));
    await user.click(screen.getByText('Old view'));

    await waitFor(() => expect(adminAuditApi.global).toHaveBeenLastCalledWith(
      0, 25, { ...NO_FILTERS, q: 'BANK_UPDATED' }));
  });

  it('puts an entry from 00:30 IST under that IST day, matching the date filter', async () => {
    mockAuth(['AUDIT_VIEW']);
    // 00:30 IST on 15 Jan 2026 is 19:00 UTC on 14 Jan.
    vi.mocked(adminAuditApi.global).mockResolvedValue(pagedResponse([row({ createdAt: '2026-01-14T19:00:00Z' })]));
    renderPage();

    expect(await screen.findByText(/15 January 2026/)).toBeInTheDocument();
  });

  it('says who acted when the admin was recorded under another key, without repeating it', async () => {
    mockAuth(['AUDIT_VIEW', 'USER_VIEW']);
    vi.mocked(adminAuditApi.global).mockResolvedValue(pagedResponse([row({
      action: 'ACCOUNT_SUSPENDED', actorId: 'admin-0000-2222', actorName: 'Ops Admin', actorEmail: 'ops@example.com',
      metadata: { reason: 'TERMS_VIOLATION', suspendedBy: 'admin-0000-2222' },
    })]));
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: /ACCOUNT_SUSPENDED/ }));

    expect(screen.getByText('Ops Admin (ops@example.com)')).toBeInTheDocument();
    expect(screen.getByRole('rowheader', { name: 'reason' })).toBeInTheDocument();
    expect(screen.queryByRole('rowheader', { name: 'suspendedBy' })).not.toBeInTheDocument();
  });

  it('names an automatic job as who acted', async () => {
    mockAuth(['AUDIT_VIEW']);
    vi.mocked(adminAuditApi.global).mockResolvedValue(pagedResponse([row({
      action: 'MERCHANT_RENAMED', systemActor: 'merchant-rail-name-recheck',
      metadata: { actor: 'merchant-rail-name-recheck', newName: 'Swiggy' },
    })]));
    const user = userEvent.setup();
    renderPage();

    const entry = await screen.findByRole('button', { name: /MERCHANT_RENAMED/ });
    expect(entry).toHaveTextContent('by automatic job merchant-rail-name-recheck');
    await user.click(entry);
    expect(screen.queryByRole('rowheader', { name: 'actor' })).not.toBeInTheDocument();
    expect(screen.getByRole('rowheader', { name: 'newName' })).toBeInTheDocument();
  });
});
