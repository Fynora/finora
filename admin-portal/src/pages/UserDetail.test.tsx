import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import UserDetail from './UserDetail';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminUsersApi } from '../api/endpoints';
import type { UserDetailDto } from '../types';

/**
 * Characterisation tests for UserDetail, the largest source file in the repository (1770 lines)
 * and, until now, one of the few admin pages with no test at all.
 *
 * Written BEFORE splitting the file into components, and deliberately pinned to the behaviour a
 * split is most likely to break silently: WHICH PERMISSION GATES WHICH SECTION. Eight sections are
 * each rendered behind their own `hasPermission(...)` check. Moving one into a new component and
 * dropping or mistyping its guard would leak another user's financial data to an admin who is not
 * authorised to see it, and nothing in the type system or the existing suite would notice.
 *
 * These assert the gate, not the section internals. Internals are free to change during the
 * refactor; the gates are not.
 */

// AdminLayout now renders ThemeToggle (dark-mode support), which calls useTheme() --
// same reason adminSearchApi is stubbed below for GlobalSearch: a real ThemeProvider isn't
// mounted in these tests, so without this mock every AdminLayout-wrapped page throws before
// any assertion runs.
vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({ useAdminAuth: vi.fn() }));
const notify = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }));
vi.mock('../context/NotificationContext', () => ({
  useNotify: () => notify,
}));

// Every section fetches on mount. Only adminUsersApi.get needs real shape; the rest just have to
// exist and never resolve, since these tests assert on gating rather than on section data.
// Everything is inlined because vi.mock factories are hoisted above any top-level declaration.
vi.mock('../api/endpoints', () => {
  const pending = () => new Promise(() => {});
  return {
    adminUsersApi: { get: vi.fn(), suspend: vi.fn(), reactivate: vi.fn(), update: vi.fn(), setInternal: vi.fn(), purge: vi.fn() },
    adminAuditApi: { forUser: vi.fn(pending) },
    adminRolesApi: { listRoles: vi.fn(pending), assignRole: vi.fn(), revokeRole: vi.fn() },
    adminAccountsApi: { forUser: vi.fn(pending), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
    adminTransactionsApi: { forUser: vi.fn(pending), delete: vi.fn() },
    adminUserMerchantsApi: { list: vi.fn(pending), rename: vi.fn(), merge: vi.fn(), confirmCategory: vi.fn() },
    adminUserRulesApi: { list: vi.fn(pending), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
    adminUserRelationshipsApi: { list: vi.fn(pending), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
    adminUserLearningApi: { summary: vi.fn(pending), timeline: vi.fn(pending) },
    adminUserWorkspaceApi: { dashboard: vi.fn(pending), activity: vi.fn(pending) },
    adminUserAnalyticsApi: {
      topMerchants: vi.fn(pending), topCategories: vi.fn(pending), trend: vi.fn(pending),
      categoryConfidence: vi.fn(pending), learningGrowth: vi.fn(pending),
    },
    banksApi: { list: vi.fn(pending) },
    // AdminLayout renders GlobalSearch, which imports this. vi.mock replaces the whole module, so
    // omitting it makes GlobalSearch throw before any assertion runs.
    adminSearchApi: { search: vi.fn(pending) },
  };
});

// Matches UserDetailDto exactly. Getting this wrong is not a soft failure: the page renders
// `user.roleNames.join(', ')` unguarded, so a fixture missing that field throws during render and
// every assertion in the file fails with a misleading "element not found".
const USER: UserDetailDto = {
  id: 'user-1',
  email: 'someone@example.com',
  fullName: 'Target User',
  phoneNumber: '+919876543210', // synthetic-ok: invented, matches the number the backend ITs use
  phoneVerified: true,
  status: 'ACTIVE',
  roleNames: ['USER'],
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-02T00:00:00Z',
  accountCount: 2,
  transactionCount: 17,
  internalAccount: false,
  accountScope: 'USER',
  suspensionReason: null,
  suspensionMessage: null,
  suspendedAt: null,
};

/**
 * Every section's heading, paired with the single permission that reveals it.
 *
 * Queried by heading ROLE, not by text: "Accounts" is also the label of a stat tile in the profile
 * header, so a plain text query matches two elements and throws.
 */
const SECTION_GATES: ReadonlyArray<readonly [string, string]> = [
  ['Accounts', 'USER_VIEW'],
  ['Recent transactions', 'TRANSACTION_DELETE'],
  ['Merchants', 'MERCHANT_MANAGE'],
  ['Rules', 'RULE_MANAGE'],
  ['Relationships', 'RELATIONSHIP_MANAGE'],
  ['Learning Engine', 'MERCHANT_MANAGE'],
  ['Analytics', 'PLATFORM_ANALYTICS_VIEW'],
  ['Intelligence Workspace', 'RECONCILIATION_VIEW'],
];

function renderWith(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(
    mockAdminAuthState({
      permissions,
      hasPermission: (p: string) => permissions.includes(p),
    })
  );
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/users/user-1']}>
        <Routes>
          <Route path="/users/:id" element={<UserDetail />} />
          <Route path="/users" element={<p>The users list</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(adminUsersApi.get).mockResolvedValue(USER);
});

describe('UserDetail — page-level access', () => {
  it('renders nothing but the permission notice without USER_VIEW', async () => {
    renderWith([]);
    expect(await screen.findByText(/don't have access to this section/i)).toBeInTheDocument();
    expect(screen.queryByText('Target User')).not.toBeInTheDocument();
  });

  it('shows the profile once USER_VIEW is present', async () => {
    renderWith(['USER_VIEW']);
    expect(await screen.findByText('Target User')).toBeInTheDocument();
  });
});

describe('UserDetail — section gating', () => {
  /*
   * The load-bearing assertions for the refactor. Each section must appear for exactly the
   * permission that guards it, and must NOT appear for an admin holding only USER_VIEW.
   */
  it.each(SECTION_GATES)('shows "%s" when the admin holds %s', async (heading, permission) => {
    renderWith(['USER_VIEW', permission]);
    expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument();
  });

  // Accounts is excluded: it is gated on USER_VIEW itself, the same permission that grants the
  // page, so there is no "has the page but not this section" state to assert for it.
  it.each(SECTION_GATES.filter(([, permission]) => permission !== 'USER_VIEW'))(
    'hides "%s" from an admin with only USER_VIEW',
    async (heading) => {
      renderWith(['USER_VIEW']);
      await screen.findByText('Target User'); // page has rendered
      expect(screen.queryByRole('heading', { name: heading })).not.toBeInTheDocument();
    }
  );

  /** MERCHANT_MANAGE gates two different sections. A split that gives one of them the wrong guard
   *  would still pass a test that only checked the other. */
  it('reveals both MERCHANT_MANAGE sections together', async () => {
    renderWith(['USER_VIEW', 'MERCHANT_MANAGE']);
    expect(await screen.findByRole('heading', { name: 'Merchants' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Learning Engine' })).toBeInTheDocument();
  });

  it('shows every section to an admin holding all of the permissions', async () => {
    renderWith(['USER_VIEW', ...SECTION_GATES.map(([, p]) => p)]);
    for (const [heading] of SECTION_GATES) {
      expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument();
    }
  });
});

describe('UserDetail — profile actions', () => {
  it('offers suspend only with USER_DELETE', async () => {
    renderWith(['USER_VIEW']);
    await screen.findByText('Target User');
    expect(screen.queryByRole('button', { name: /suspend/i })).not.toBeInTheDocument();

    renderWith(['USER_VIEW', 'USER_DELETE']);
    await waitFor(() => expect(screen.getAllByRole('button', { name: /suspend/i }).length).toBeGreaterThan(0));
  });

  // Privacy workstream 10: an account suspended before reasons were recorded must be able to get
  // one, and every suspension shows what the customer was told.
  it('shows a suspension with no reason, and records one, sending the notice', async () => {
    vi.mocked(adminUsersApi.get).mockResolvedValue({ ...USER, status: 'SUSPENDED', suspendedAt: '2026-10-01T00:00:00Z' });
    vi.mocked(adminUsersApi.suspend).mockResolvedValue({ ...USER, status: 'SUSPENDED' });
    renderWith(['USER_VIEW', 'USER_DELETE']);
    await screen.findByText('Target User');

    expect(await screen.findByText(/No reason recorded/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /record reason/i }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('sends the customer the suspension notice again');
    fireEvent.change(screen.getByLabelText('Reason'), { target: { value: 'TERMS_VIOLATION' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save and notify' }));

    await waitFor(() => expect(adminUsersApi.suspend).toHaveBeenCalledWith('user-1', 'TERMS_VIOLATION', undefined));
  });

  it('shows the recorded reason and message, and offers to change them', async () => {
    vi.mocked(adminUsersApi.get).mockResolvedValue({
      ...USER, status: 'SUSPENDED', suspensionReason: 'FRAUD', suspensionMessage: 'Chargebacks.', suspendedAt: '2026-11-01T00:00:00Z',
    });
    renderWith(['USER_VIEW', 'USER_DELETE']);
    await screen.findByText('Target User');

    const details = await screen.findByTestId('suspension-details');
    expect(details).toHaveTextContent('Suspended for: Fraud');
    expect(details).toHaveTextContent('Message to the customer: Chargebacks.');
    fireEvent.click(screen.getByRole('button', { name: /change reason/i }));
    expect(await screen.findByLabelText('Reason')).toHaveValue('FRAUD');
    expect(screen.getByLabelText(/Message to the customer/)).toHaveValue('Chargebacks.');
  });

  it('offers profile editing only with USER_UPDATE', async () => {
    renderWith(['USER_VIEW']);
    await screen.findByText('Target User');
    expect(screen.queryByRole('button', { name: /edit profile/i })).not.toBeInTheDocument();

    renderWith(['USER_VIEW', 'USER_UPDATE']);
    await waitFor(() => expect(screen.getAllByRole('button', { name: /edit profile/i }).length).toBeGreaterThan(0));
  });

  it('shows the Roles panel only with ROLE_MANAGE', async () => {
    renderWith(['USER_VIEW']);
    await screen.findByText('Target User');
    expect(screen.queryByRole('heading', { name: 'Roles' })).not.toBeInTheDocument();

    renderWith(['USER_VIEW', 'ROLE_MANAGE']);
    expect(await screen.findByRole('heading', { name: 'Roles' })).toBeInTheDocument();
  });
});

/** Gate 2a spec §6: leave an internal or test account out of the activation counts. */
describe('UserDetail — internal account flag', () => {
  const LABEL = 'Internal or test account (left out of activation counts)';

  it('togglesInternalFlag', async () => {
    vi.mocked(adminUsersApi.setInternal).mockResolvedValue({} as never);
    renderWith(['USER_VIEW', 'USER_UPDATE']);

    const box = await screen.findByRole('checkbox', { name: LABEL });
    expect(box).not.toBeChecked();
    fireEvent.click(box);

    await waitFor(() => expect(adminUsersApi.setInternal).toHaveBeenCalledWith('user-1', true));
  });

  it('shows the current value and clears it', async () => {
    vi.mocked(adminUsersApi.get).mockResolvedValue({ ...USER, internalAccount: true });
    vi.mocked(adminUsersApi.setInternal).mockResolvedValue({} as never);
    renderWith(['USER_VIEW', 'USER_UPDATE']);

    const box = await screen.findByRole('checkbox', { name: LABEL });
    expect(box).toBeChecked();
    fireEvent.click(box);

    await waitFor(() => expect(adminUsersApi.setInternal).toHaveBeenCalledWith('user-1', false));
  });

  it('says so when the change fails, and the box keeps the saved value', async () => {
    vi.mocked(adminUsersApi.setInternal).mockRejectedValue(new Error('down'));
    renderWith(['USER_VIEW', 'USER_UPDATE']);

    const box = await screen.findByRole('checkbox', { name: LABEL });
    fireEvent.click(box);

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not change this');
    expect(box).not.toBeChecked();
  });

  it('hiddenWithoutUserUpdate', async () => {
    renderWith(['USER_VIEW']);
    await screen.findByText('Target User');

    expect(screen.queryByRole('checkbox', { name: LABEL })).not.toBeInTheDocument();
  });

  // Admin accounts are already left out of the counts, and the server refuses the flag for them.
  it('is not offered for an admin account', async () => {
    vi.mocked(adminUsersApi.get).mockResolvedValue({ ...USER, accountScope: 'ADMIN', roleNames: ['ADMIN'] });
    renderWith(['USER_VIEW', 'USER_UPDATE']);
    await screen.findByText('Target User');

    expect(screen.queryByRole('checkbox', { name: LABEL })).not.toBeInTheDocument();
  });
});

// Audit 2026-11-10: a missing user and a refused action both used to look like nothing happened.
describe('UserDetail — failures are visible', () => {
  it('says the account could not be loaded instead of loading forever', async () => {
    vi.mocked(adminUsersApi.get).mockRejectedValue({ response: { status: 404, data: { message: 'User not found' } } });
    renderWith(['USER_VIEW']);

    expect(await screen.findByRole('alert')).toHaveTextContent('User not found');
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument();
  });

  // Review 2026-11-11: a failed background refresh (e.g. right after an action) must not replace a
  // profile that is already on screen with an error.
  it('keeps the loaded profile on screen when a later refresh fails', async () => {
    vi.mocked(adminUsersApi.get)
      .mockReset()
      .mockResolvedValueOnce({ ...USER, status: 'SUSPENDED' })
      .mockRejectedValue({ response: { data: { message: 'Service unavailable' } } });
    vi.mocked(adminUsersApi.reactivate).mockResolvedValue({ ...USER } as never);
    renderWith(['USER_VIEW', 'USER_DELETE']);

    await screen.findByText('Target User');
    fireEvent.click(screen.getByRole('button', { name: /reactivate account/i }));
    await waitFor(() => expect(adminUsersApi.get).toHaveBeenCalledTimes(2));

    expect(screen.getByText('Target User')).toBeInTheDocument();
    expect(screen.queryByText('Service unavailable')).not.toBeInTheDocument();
  });

  it('reports a refused suspension', async () => {
    vi.mocked(adminUsersApi.suspend).mockRejectedValue({ response: { data: { message: 'You cannot suspend this account' } } });
    renderWith(['USER_VIEW', 'USER_DELETE']);
    await screen.findByText('Target User');

    fireEvent.click(screen.getByRole('button', { name: /suspend account/i }));
    await screen.findByRole('dialog');
    fireEvent.change(screen.getByLabelText('Reason'), { target: { value: 'TERMS_VIOLATION' } });
    fireEvent.click(screen.getByRole('button', { name: 'Suspend' }));

    await waitFor(() => expect(notify.error).toHaveBeenCalledWith('You cannot suspend this account'));
  });

  it('reports a refused role grant', async () => {
    const { adminRolesApi } = await import('../api/endpoints');
    vi.mocked(adminRolesApi.listRoles).mockResolvedValue([{ id: 'r-ops', name: 'OPS', description: 'Ops', permissions: [] }]);
    vi.mocked(adminRolesApi.assignRole).mockRejectedValue({ response: { data: { message: 'Not allowed' } } });
    renderWith(['USER_VIEW', 'ROLE_MANAGE']);
    await screen.findByText('Target User');

    fireEvent.change(await screen.findByLabelText('Grant a role'), { target: { value: 'OPS' } });
    fireEvent.click(screen.getByRole('button', { name: 'Grant' }));

    await waitFor(() => expect(notify.error).toHaveBeenCalledWith('Not allowed'));
  });
});

// Support-assisted account deletion (2026-11-12): instant and irreversible, so it asks for the
// account's email typed out before the button does anything.
describe('UserDetail — deleting an account', () => {
  it('is offered only with USER_DELETE, and only on a customer account that is not already deleted', async () => {
    renderWith(['USER_VIEW']);
    await screen.findByText('Target User');
    expect(screen.queryByRole('button', { name: /Delete account permanently/ })).not.toBeInTheDocument();

    vi.mocked(adminUsersApi.get).mockResolvedValue({ ...USER, accountScope: 'ADMIN' });
    renderWith(['USER_VIEW', 'USER_DELETE']);
    await waitFor(() => expect(screen.getAllByText('Target User').length).toBeGreaterThan(0));
    expect(screen.queryByRole('button', { name: /Delete account permanently/ })).not.toBeInTheDocument();
  });

  it('is not offered for an account that is already deleted', async () => {
    vi.mocked(adminUsersApi.get).mockResolvedValue({ ...USER, status: 'DELETED' });
    renderWith(['USER_VIEW', 'USER_DELETE']);
    await screen.findByText('Target User');

    expect(screen.queryByRole('button', { name: /Delete account permanently/ })).not.toBeInTheDocument();
  });

  it('deletes only once the email is typed, then returns to the users list', async () => {
    vi.mocked(adminUsersApi.purge).mockResolvedValue(undefined);
    renderWith(['USER_VIEW', 'USER_DELETE']);
    await screen.findByText('Target User');

    fireEvent.click(screen.getByRole('button', { name: /Delete account permanently/ }));
    const dialog = screen.getByRole('dialog');
    const confirm = within(dialog).getByRole('button', { name: 'Delete permanently' });
    expect(confirm).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText(/Type the account's email/), { target: { value: 'someone@example' } });
    expect(confirm).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText(/Type the account's email/), { target: { value: ' SOMEONE@example.com ' } });
    expect(confirm).toBeEnabled();
    fireEvent.click(confirm);

    await waitFor(() => expect(adminUsersApi.purge).toHaveBeenCalledWith('user-1'));
    expect(await screen.findByText('The users list')).toBeInTheDocument();
    expect(notify.success).toHaveBeenCalledWith('Account deleted.');
  });

  it('cancelling deletes nothing', async () => {
    renderWith(['USER_VIEW', 'USER_DELETE']);
    await screen.findByText('Target User');

    fireEvent.click(screen.getByRole('button', { name: /Delete account permanently/ }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }));

    expect(adminUsersApi.purge).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('shows the reason for a refusal, and explains a deletion that failed partway', async () => {
    vi.mocked(adminUsersApi.purge)
      .mockRejectedValueOnce({ response: { status: 400, data: { message: 'You cannot purge your own account.' } } })
      .mockRejectedValueOnce({ response: { status: 500, data: { message: 'Unexpected error' } } });
    renderWith(['USER_VIEW', 'USER_DELETE']);
    await screen.findByText('Target User');
    fireEvent.click(screen.getByRole('button', { name: /Delete account permanently/ }));
    const dialog = screen.getByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText(/Type the account's email/), { target: { value: 'someone@example.com' } });

    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete permanently' }));
    await waitFor(() => expect(notify.error).toHaveBeenCalledWith('You cannot purge your own account.'));
    const loadsBefore = vi.mocked(adminUsersApi.get).mock.calls.length;
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete permanently' }));
    await waitFor(() => expect(notify.error).toHaveBeenLastCalledWith(expect.stringContaining('marked for deletion and will be completed automatically')));
    expect(screen.getByText('Target User')).toBeInTheDocument();
    // The account is now marked for deletion on the server, so the page reloads its status.
    await waitFor(() => expect(vi.mocked(adminUsersApi.get).mock.calls.length).toBeGreaterThan(loadsBefore));
  });
});
