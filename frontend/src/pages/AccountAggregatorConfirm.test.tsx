import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useSearchParams } from 'react-router-dom';
import AccountAggregatorConfirm from './AccountAggregatorConfirm';
import { accountAggregatorApi, accountsApi } from '../api/endpoints';
import type { Account } from '../types';

// Task 9 (Plan 5). See AccountAggregatorConfirm.tsx's own top-of-file comment: the design spec's
// originally-suggested "Is this your [Bank] account ending ****1234?" copy assumes a specific
// suggested candidate the backend does not actually expose (traced
// AccountAggregatorIdentityResolutionService.resolveAndAttach directly -- match.candidates() is
// never persisted). accountsApi.list() is mocked here too, unlike the plan's own draft test,
// because the real component genuinely depends on it for the account picker.
vi.mock('../api/endpoints', () => ({
  accountAggregatorApi: {
    list: vi.fn(), initiate: vi.fn(), confirmExistingAccount: vi.fn(), confirmNewAccount: vi.fn(),
    disconnect: vi.fn(),
  },
  accountsApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
}));

function account(overrides: Partial<Account> = {}): Account {
  return {
    id: 'account-1',
    name: 'Everyday Checking',
    accountType: 'SAVINGS',
    balance: 1000,
    bank: { id: 'hdfc', officialName: 'HDFC Bank', shortName: 'HDFC', colorHex: '#000', initials: 'HD', logoPath: '' },
    accountNumberMasked: '****1234',
    lastImportedAt: null,
    lastStatementPeriodStart: null,
    lastStatementPeriodEnd: null,
    ...overrides,
  } as Account;
}

// Stands in for the real Settings shell to prove which pane a redirect actually lands on --
// real Settings.tsx reads this same `?tab=` param to pick a pane.
function SettingsProbe() {
  const [params] = useSearchParams();
  return <p>Landed on Settings, tab={params.get('tab') ?? '(none)'}</p>;
}

function renderConfirm(linkId = 'link-1') {
  return render(
    <MemoryRouter initialEntries={[`/app/settings/bank-sync/${linkId}/confirm`]}>
      <Routes>
        <Route path="/app/settings/bank-sync/:linkId/confirm" element={<AccountAggregatorConfirm />} />
        <Route path="/app/settings" element={<SettingsProbe />} />
      </Routes>
    </MemoryRouter>
  );
}

describe('AccountAggregatorConfirm', () => {
  beforeEach(() => {
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([account()]);
    vi.mocked(accountAggregatorApi.confirmExistingAccount).mockReset();
    vi.mocked(accountAggregatorApi.confirmNewAccount).mockReset();
  });

  it('confirms the existing account when the user picks it', async () => {
    vi.mocked(accountAggregatorApi.confirmExistingAccount).mockResolvedValue({} as never);
    const user = userEvent.setup();
    renderConfirm();

    await user.click(await screen.findByRole('button', { name: /yes, this is my account/i }));

    expect(accountAggregatorApi.confirmExistingAccount).toHaveBeenCalledWith('link-1', expect.any(String));
  });

  it('lands back on the Bank Sync pane, not General, after confirming', async () => {
    // Bug found in a fresh review pass: Settings became a nav+pane shell in this same redesign,
    // and this page's navigate('/app/settings') was never updated to carry the tab that shell
    // now needs -- it silently landed on General instead of back on Bank Sync.
    vi.mocked(accountAggregatorApi.confirmExistingAccount).mockResolvedValue({} as never);
    const user = userEvent.setup();
    renderConfirm();

    await user.click(await screen.findByRole('button', { name: /yes, this is my account/i }));

    expect(await screen.findByText('Landed on Settings, tab=bank-sync')).toBeInTheDocument();
  });

  it('confirms a new account when the user says this is different', async () => {
    vi.mocked(accountAggregatorApi.confirmNewAccount).mockResolvedValue({} as never);
    const user = userEvent.setup();
    renderConfirm();

    await user.click(await screen.findByRole('button', { name: /this is a different\/new account/i }));

    expect(accountAggregatorApi.confirmNewAccount).toHaveBeenCalledWith('link-1');
  });

  it('offers only the new-account option when the user has no existing accounts', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([]);
    renderConfirm();

    await screen.findByRole('button', { name: /this is a different\/new account/i });
    expect(screen.queryByRole('button', { name: /yes, this is my account/i })).not.toBeInTheDocument();
  });

  // Bug found during Plan 5's own post-implementation review: a failed accountsApi.list() used to
  // hide the entire action panel, including "This is a different/new account" -- which has no
  // dependency on the account list at all and must still work when that unrelated call fails.
  it('still offers the new-account option when loading existing accounts fails', async () => {
    vi.mocked(accountsApi.list).mockRejectedValue(new Error('network error'));
    vi.mocked(accountAggregatorApi.confirmNewAccount).mockResolvedValue({} as never);
    const user = userEvent.setup();
    renderConfirm();

    await screen.findByText(/couldn't load your existing accounts/i);
    expect(screen.queryByRole('button', { name: /yes, this is my account/i })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /this is a different\/new account/i }));

    expect(accountAggregatorApi.confirmNewAccount).toHaveBeenCalledWith('link-1');
  });
});
