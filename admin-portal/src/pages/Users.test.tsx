import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Users from './Users';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminUsersApi, banksApi } from '../api/endpoints';
import type { UserSummaryDto } from '../types';

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
// Users.tsx now calls useNotify() (Admin Portal Phase 6) -- without this mock, rendering the page
// outside a real NotificationProvider throws "useNotify must be used within NotificationProvider"
// before any test assertion runs (same class of bug MerchantIntelligence.test.tsx's fix note
// describes for an unmocked hook used inside the rendered tree).
const notifySuccess = vi.fn();
const notifyError = vi.fn();
vi.mock('../context/NotificationContext', () => ({
  useNotify: () => ({ success: notifySuccess, error: notifyError }),
}));
vi.mock('../api/endpoints', () => ({
  adminUsersApi: { list: vi.fn(), get: vi.fn(), suspend: vi.fn(), reactivate: vi.fn(), create: vi.fn(), update: vi.fn() },
  // CreateUserForm fetches this to populate its Role dropdown -- without a mock here,
  // adminRolesApi would be undefined in this module (vi.mock replaces the whole module with
  // only what's listed), and calling adminRolesApi.listRoles() would throw before any test
  // assertion runs, same class of bug the NotificationContext mock above already guards against.
  adminRolesApi: { listRoles: vi.fn(), assignRole: vi.fn() },
  // The Bank filter's options.
  banksApi: { search: vi.fn() },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Users />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

// AdminLayout always renders Sidebar, which reads `permissions` off this same hook.
function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Support Admin',
    logout: vi.fn(),
  }));
}

function pagedResponse(content: UserSummaryDto[], overrides: Partial<Record<string, unknown>> = {}) {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: 1, ...overrides };
}

const AMY: UserSummaryDto = {
  id: 'user-1', email: 'amy@example.com', fullName: 'Amy Active', phoneNumber: '+911234567890',
  phoneVerified: true, status: 'ACTIVE', roleNames: ['USER'], createdAt: new Date().toISOString(),
  planCode: 'PLUS', lastActiveDate: '2026-11-12', importCount: 3,
};

const NEW_USER: UserSummaryDto = {
  ...AMY, id: 'user-2', email: 'new@example.com', fullName: 'Nina New',
  planCode: null, lastActiveDate: null, importCount: 0,
};

const HDFC = {
  id: 'HDFC', officialName: 'HDFC Bank Ltd', shortName: 'HDFC Bank', colorHex: '#004C8F', initials: 'HD',
  logoPath: '', category: null, websiteUrl: null, ifscPrefix: 'HDFC', supportedAccountTypes: [],
};

/** The keys of the n-th list call's filters, so a test can check empty filters are not sent. */
function sentKeys(callIndex: number): string[] {
  const args = vi.mocked(adminUsersApi.list).mock.calls[callIndex][0] as unknown as Record<string, unknown>;
  return Object.keys(args).filter((k) => args[k] !== undefined).sort();
}

beforeEach(() => {
  localStorage.clear();
});

describe('Users', () => {
  beforeEach(() => {
    vi.mocked(adminUsersApi.list).mockReset();
    vi.mocked(adminUsersApi.suspend).mockReset();
    vi.mocked(adminUsersApi.reactivate).mockReset();
    vi.mocked(adminUsersApi.create).mockReset();
    vi.mocked(banksApi.search).mockReset();
    vi.mocked(banksApi.search).mockResolvedValue([HDFC]);
    notifySuccess.mockReset();
    notifyError.mockReset();
  });

  it('shows an access-denied message when the account lacks USER_VIEW', () => {
    mockAuth([]);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([]));

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
  });

  // Audit 2026-11-10: a failed load said "No users match this search".
  it('says the list could not be loaded when the request fails', async () => {
    mockAuth(['USER_VIEW']);
    vi.mocked(adminUsersApi.list).mockRejectedValue({ response: { data: { message: 'Service unavailable' } } });

    renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent("Couldn't load this list: Service unavailable");
    expect(screen.queryByText('No users match this search.')).not.toBeInTheDocument();
  });

  it('renders the user list', async () => {
    mockAuth(['USER_VIEW']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([AMY]));

    renderPage();

    await waitFor(() => expect(screen.getByText('Amy Active')).toBeInTheDocument());
    expect(screen.getByText('amy@example.com')).toBeInTheDocument();
  });

  it('applies a search filter via the FilterBar', async () => {
    mockAuth(['USER_VIEW']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([AMY]));
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(adminUsersApi.list).toHaveBeenCalledWith(
      { q: undefined, status: undefined, page: 0, size: 20 }
    ));

    await user.type(screen.getByPlaceholderText('Search name, email, phone…'), 'amy');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() => expect(adminUsersApi.list).toHaveBeenCalledWith(
      { q: 'amy', status: undefined, page: 0, size: 20 }
    ));
  });

  it('applies a status filter immediately on selection', async () => {
    mockAuth(['USER_VIEW']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([AMY]));
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(adminUsersApi.list).toHaveBeenCalledWith(
      { q: undefined, status: undefined, page: 0, size: 20 }
    ));

    await user.selectOptions(screen.getByRole('combobox', { name: 'Filter by status' }), 'SUSPENDED');

    await waitFor(() => expect(adminUsersApi.list).toHaveBeenCalledWith(
      { q: undefined, status: 'SUSPENDED', page: 0, size: 20 }
    ));
  });

  it('saves the current filters as a named view and re-applies them after clearing', async () => {
    mockAuth(['USER_VIEW']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([AMY]));
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(adminUsersApi.list).toHaveBeenCalledWith(
      { q: undefined, status: undefined, page: 0, size: 20 }
    ));

    const searchInput = screen.getByPlaceholderText('Search name, email, phone…');
    await user.type(searchInput, 'amy');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Filter by status' }), 'ACTIVE');
    await user.click(screen.getByRole('button', { name: /Views/ }));
    await user.click(screen.getByText('+ Save current filters'));
    await user.type(screen.getByPlaceholderText('View name'), 'Active Amys');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await user.clear(searchInput);
    await user.selectOptions(screen.getByRole('combobox', { name: 'Filter by status' }), '');
    await user.click(screen.getByText('Active Amys'));

    await waitFor(() => expect(adminUsersApi.list).toHaveBeenCalledWith(
      { q: 'amy', status: 'ACTIVE', page: 0, size: 20 }
    ));
  });

  // Users list filters (Sid, 2026-11-15): plan, joined dates, last active dates, bank, imports.
  it('sends each new filter to the server and goes back to the first page', async () => {
    mockAuth(['USER_VIEW']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([AMY], { totalElements: 45, totalPages: 3 }));
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(adminUsersApi.list).toHaveBeenCalled());
    await user.click(await screen.findByRole('button', { name: /next/i }));
    await waitFor(() => expect(adminUsersApi.list).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 })));

    await user.selectOptions(screen.getByRole('combobox', { name: 'Filter by plan' }), 'PLUS');
    await waitFor(() => expect(adminUsersApi.list).toHaveBeenLastCalledWith(
      expect.objectContaining({ plan: 'PLUS', page: 0 })));

    await waitFor(() => expect(within(screen.getByRole('combobox', { name: 'Filter by bank' }))
      .getByRole('option', { name: 'HDFC Bank' })).toBeInTheDocument());
    await user.selectOptions(screen.getByRole('combobox', { name: 'Filter by bank' }), 'HDFC');
    fireEvent.change(screen.getByLabelText('Joined from'), { target: { value: '2026-10-01' } });
    fireEvent.change(screen.getByLabelText('Joined to'), { target: { value: '2026-10-31' } });
    fireEvent.change(screen.getByLabelText('Last active from'), { target: { value: '2026-11-01' } });
    fireEvent.change(screen.getByLabelText('Last active to'), { target: { value: '2026-11-14' } });
    fireEvent.change(screen.getByLabelText('Imports min'), { target: { value: '1' } });
    fireEvent.change(screen.getByLabelText('Imports max'), { target: { value: '5' } });

    await waitFor(() => expect(adminUsersApi.list).toHaveBeenLastCalledWith({
      plan: 'PLUS', bankId: 'HDFC', joinedFrom: '2026-10-01', joinedTo: '2026-10-31',
      lastActiveFrom: '2026-11-01', lastActiveTo: '2026-11-14', importsMin: 1, importsMax: 5,
      page: 0, size: 20,
    }));
  });

  it('offers Unknown bank alongside the bank list', async () => {
    mockAuth(['USER_VIEW']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([AMY]));

    renderPage();

    const bank = screen.getByRole('combobox', { name: 'Filter by bank' });
    await waitFor(() => expect(within(bank).getByRole('option', { name: 'HDFC Bank' })).toBeInTheDocument());
    expect(within(bank).getByRole('option', { name: 'Unknown bank' })).toHaveValue('OTHER');
  });

  it('leaves empty filters out of the request', async () => {
    mockAuth(['USER_VIEW']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([AMY]));
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(adminUsersApi.list).toHaveBeenCalled());
    expect(sentKeys(0)).toEqual(['page', 'size']);

    fireEvent.change(screen.getByLabelText('Imports max'), { target: { value: '0' } });
    await waitFor(() => expect(adminUsersApi.list).toHaveBeenLastCalledWith(
      expect.objectContaining({ importsMax: 0 })));
    fireEvent.change(screen.getByLabelText('Imports max'), { target: { value: '' } });
    await user.selectOptions(screen.getByRole('combobox', { name: 'Filter by plan' }), 'FREE');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Filter by plan' }), '');

    await waitFor(() => {
      const last = vi.mocked(adminUsersApi.list).mock.calls.length - 1;
      expect(sentKeys(last)).toEqual(['page', 'size']);
    });
  });

  it('applies a view saved before the new filters, clearing them', async () => {
    localStorage.setItem('finora-admin-views-users', JSON.stringify([{ name: 'Old view', values: { q: 'amy', status: 'ACTIVE' } }]));
    mockAuth(['USER_VIEW']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([AMY]));
    const user = userEvent.setup();

    renderPage();
    await user.selectOptions(screen.getByRole('combobox', { name: 'Filter by plan' }), 'PREMIUM');
    fireEvent.change(screen.getByLabelText('Joined from'), { target: { value: '2026-10-01' } });
    await user.click(screen.getByRole('button', { name: /Views/ }));
    await user.click(screen.getByText('Old view'));

    await waitFor(() => expect(adminUsersApi.list).toHaveBeenLastCalledWith({ q: 'amy', status: 'ACTIVE', page: 0, size: 20 }));
    expect(screen.getByRole('combobox', { name: 'Filter by plan' })).toHaveValue('');
    expect(screen.getByLabelText('Joined from')).toHaveValue('');
  });

  it('saves the new filters in a view and applies them again', async () => {
    mockAuth(['USER_VIEW']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([AMY]));
    const user = userEvent.setup();

    renderPage();
    await user.selectOptions(screen.getByRole('combobox', { name: 'Filter by plan' }), 'PLUS');
    fireEvent.change(screen.getByLabelText('Last active to'), { target: { value: '2026-11-01' } });
    fireEvent.change(screen.getByLabelText('Imports min'), { target: { value: '2' } });
    await user.click(screen.getByRole('button', { name: /Views/ }));
    await user.click(screen.getByText('+ Save current filters'));
    await user.type(screen.getByPlaceholderText('View name'), 'Quiet Plus');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await user.selectOptions(screen.getByRole('combobox', { name: 'Filter by plan' }), '');
    fireEvent.change(screen.getByLabelText('Last active to'), { target: { value: '' } });
    fireEvent.change(screen.getByLabelText('Imports min'), { target: { value: '' } });
    await user.click(screen.getByText('Quiet Plus'));

    await waitFor(() => expect(adminUsersApi.list).toHaveBeenLastCalledWith(
      { plan: 'PLUS', lastActiveTo: '2026-11-01', importsMin: 2, page: 0, size: 20 }));
  });

  it('shows plan, last active and imports for each user', async () => {
    mockAuth(['USER_VIEW']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([AMY, NEW_USER]));

    renderPage();

    const amyRow = (await screen.findByText('Amy Active')).closest('tr')!;
    expect(within(amyRow).getByText('Plus')).toBeInTheDocument();
    expect(within(amyRow).getByText(new Date(2026, 10, 12).toLocaleDateString())).toBeInTheDocument();
    expect(within(amyRow).getByText('3')).toBeInTheDocument();
    const ninaRow = screen.getByText('Nina New').closest('tr')!;
    expect(within(ninaRow).getByText('No plan')).toBeInTheDocument();
    expect(within(ninaRow).getByText('Not seen')).toBeInTheDocument();
    expect(within(ninaRow).getByText('0')).toBeInTheDocument();
  });

  // The Joined filter reads days in India time (the backend's reporting zone), so the column must
  // show the same day: someone who signed up at 00:30 on 11 Nov in India is under 11 Nov, even for
  // an admin whose browser is in another zone.
  it('shows the joined day in India time, the day the Joined filter uses', async () => {
    const zoneBefore = process.env.TZ;
    process.env.TZ = 'America/New_York';
    try {
      mockAuth(['USER_VIEW']);
      vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([{ ...AMY, createdAt: '2026-11-10T19:00:00Z' }]));

      renderPage();

      const row = (await screen.findByText('Amy Active')).closest('tr')!;
      expect(within(row).getByText(new Date(2026, 10, 11).toLocaleDateString())).toBeInTheDocument();
      expect(within(row).queryByText(new Date(2026, 10, 10).toLocaleDateString())).not.toBeInTheDocument();
    } finally {
      process.env.TZ = zoneBefore;
    }
  });

  it("shows the server's message when it refuses a filter", async () => {
    mockAuth(['USER_VIEW']);
    vi.mocked(adminUsersApi.list).mockRejectedValue({ response: { data: { message: 'Imports: the minimum is more than the maximum.' } } });

    renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent('Imports: the minimum is more than the maximum.');
  });

  it('suspends an active user', async () => {
    mockAuth(['USER_VIEW', 'USER_DELETE']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([AMY]));
    vi.mocked(adminUsersApi.suspend).mockResolvedValue({ ...AMY, status: 'SUSPENDED' });
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText('Amy Active')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /Suspend/ }));
    // Custom in-app confirmation (ConfirmDialog), not the browser's own confirm() -- see this
    // page's own doc comment on confirmSuspend for why. Scoped to the dialog: its confirm button
    // shares the accessible name "Suspend" with the row's own trigger button, still in the DOM.
    const dialog = await screen.findByRole('dialog');
    // Privacy workstream 10: no suspension without a reason, and the message reaches the customer.
    expect(within(dialog).getByRole('button', { name: 'Suspend' })).toBeDisabled();
    await user.selectOptions(within(dialog).getByLabelText('Reason'), 'FRAUD');
    await user.type(within(dialog).getByLabelText(/Message to the customer/), '  Chargebacks.  ');
    await user.click(within(dialog).getByRole('button', { name: 'Suspend' }));

    await waitFor(() => expect(adminUsersApi.suspend).toHaveBeenCalledWith('user-1', 'FRAUD', 'Chargebacks.'));
    await waitFor(() => expect(notifySuccess).toHaveBeenCalledWith('User suspended.'));
  });

  it('suspends with no message when the message is left blank', async () => {
    mockAuth(['USER_VIEW', 'USER_DELETE']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([AMY]));
    vi.mocked(adminUsersApi.suspend).mockResolvedValue({ ...AMY, status: 'SUSPENDED' });
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText('Amy Active')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /Suspend/ }));
    const dialog = await screen.findByRole('dialog');
    await user.selectOptions(within(dialog).getByLabelText('Reason'), 'TERMS_VIOLATION');
    await user.click(within(dialog).getByRole('button', { name: 'Suspend' }));

    await waitFor(() => expect(adminUsersApi.suspend).toHaveBeenCalledWith('user-1', 'TERMS_VIOLATION', undefined));
  });

  it('does not suspend when the confirmation is declined', async () => {
    // Suspending signs the user out and blocks their login, from a one-click button in a table
    // row with no undo in the same place -- worth a test of its own that Cancel genuinely stops it.
    mockAuth(['USER_VIEW', 'USER_DELETE']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([AMY]));
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText('Amy Active')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /Suspend/ }));
    await screen.findByText('Suspend Amy Active?');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(adminUsersApi.suspend).not.toHaveBeenCalled();
  });

  it('shows an error notification when suspending fails', async () => {
    mockAuth(['USER_VIEW', 'USER_DELETE']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([AMY]));
    vi.mocked(adminUsersApi.suspend).mockRejectedValue({ response: { data: { message: 'Cannot suspend yourself.' } } });
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText('Amy Active')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /Suspend/ }));
    const dialog = await screen.findByRole('dialog');
    await user.selectOptions(within(dialog).getByLabelText('Reason'), 'SECURITY_RISK');
    await user.click(within(dialog).getByRole('button', { name: 'Suspend' }));

    await waitFor(() => expect(notifyError).toHaveBeenCalledWith('Cannot suspend yourself.'));
  });

  it('shows a Reactivate action for a self-service deactivated account, not a Suspend action', async () => {
    // AdminUserService.reactivate() was widened to accept DEACTIVATED as well as SUSPENDED -- the
    // admin portal's action column has to offer the same button for both, not just SUSPENDED.
    mockAuth(['USER_VIEW', 'USER_DELETE']);
    const deactivated: UserSummaryDto = { ...AMY, id: 'user-2', fullName: 'Dana Deactivated', status: 'DEACTIVATED' };
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([deactivated]));
    vi.mocked(adminUsersApi.reactivate).mockResolvedValue({ ...deactivated, status: 'ACTIVE' });
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText('Dana Deactivated')).toBeInTheDocument());

    // { selector: 'span' } excludes the status filter dropdown's own "Deactivated" <option>,
    // which otherwise matches the same text.
    expect(screen.getByText('Deactivated', { selector: 'span' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Suspend/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Reactivate/ }));

    // window.prompt is stubbed to return '' (see src/test/setup.ts) -- an empty note trims to
    // undefined, matching a plain reactivation with no note attached.
    await waitFor(() => expect(adminUsersApi.reactivate).toHaveBeenCalledWith('user-2', undefined));
  });

  it('cancels the reactivation when the admin dismisses the reason prompt', async () => {
    mockAuth(['USER_VIEW', 'USER_DELETE']);
    const deactivated: UserSummaryDto = { ...AMY, id: 'user-2', fullName: 'Dana Deactivated', status: 'DEACTIVATED' };
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([deactivated]));
    vi.mocked(window.prompt).mockReturnValueOnce(null);
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText('Dana Deactivated')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /Reactivate/ }));

    expect(adminUsersApi.reactivate).not.toHaveBeenCalled();
  });

  it('passes a trimmed reason to the reactivate call when the admin provides one', async () => {
    mockAuth(['USER_VIEW', 'USER_DELETE']);
    const deactivated: UserSummaryDto = { ...AMY, id: 'user-2', fullName: 'Dana Deactivated', status: 'DEACTIVATED' };
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([deactivated]));
    vi.mocked(adminUsersApi.reactivate).mockResolvedValue({ ...deactivated, status: 'ACTIVE' });
    vi.mocked(window.prompt).mockReturnValueOnce('  Confirmed over a support call  ');
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText('Dana Deactivated')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /Reactivate/ }));

    await waitFor(() => expect(adminUsersApi.reactivate)
      .toHaveBeenCalledWith('user-2', 'Confirmed over a support call'));
  });

  it('offers no suspend/reactivate action for an account pending deletion', async () => {
    mockAuth(['USER_VIEW', 'USER_DELETE']);
    const pending: UserSummaryDto = { ...AMY, id: 'user-3', fullName: 'Pat Pending', status: 'PENDING_DELETION' };
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([pending]));

    renderPage();
    await waitFor(() => expect(screen.getByText('Pat Pending')).toBeInTheDocument());

    expect(screen.getByText('Pending Deletion', { selector: 'span' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Suspend/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Reactivate/ })).not.toBeInTheDocument();
  });

  it('creates a new user via the New user form', async () => {
    mockAuth(['USER_VIEW', 'USER_CREATE']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([]));
    vi.mocked(adminUsersApi.create).mockResolvedValue(AMY);
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(adminUsersApi.list).toHaveBeenCalled());

    await user.click(screen.getByRole('button', { name: /New user/ }));
    const form = screen.getByText('Create a user').closest('form')!;
    // Bug fix: CreateUserForm's labels used to be unassociated siblings of their inputs (a real
    // axe "label" violation, same class fixed on Login.tsx), so this test used to reach each field
    // positionally via getAllByRole('textbox') destructuring -- fragile, and it couldn't have
    // caught the bug it was working around. Now that each label has a real htmlFor/id pairing,
    // getByLabelText both reads more clearly and fails loudly if that association ever regresses.
    await user.type(within(form).getByLabelText('Full name'), 'New Admin');
    await user.type(within(form).getByLabelText('Email'), 'new-admin@example.com');
    await user.type(within(form).getByLabelText('Phone number'), '+919999999999');
    await user.type(within(form).getByLabelText('Temporary password'), 'TempPass123');
    await user.click(within(form).getByRole('button', { name: 'Create user' }));

    await waitFor(() => expect(adminUsersApi.create).toHaveBeenCalledWith({
      fullName: 'New Admin', email: 'new-admin@example.com', phoneNumber: '+919999999999', password: 'TempPass123',
    }));
  });
});
