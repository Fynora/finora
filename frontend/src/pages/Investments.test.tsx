import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import Investments from './Investments';
import { accountsApi, networthApi, categoriesApi, transactionsApi, type NetWorthData } from '../api/endpoints';
import { ThemeProvider } from '../context/ThemeContext';
import type { Account } from '../types';

// The charts themselves are not under test here and chart.js needs a real canvas, which jsdom
// doesn't provide -- the page's loading behaviour is what these tests are about.
vi.mock('react-chartjs-2', () => ({
  Doughnut: () => <div data-testid="allocation-chart" />,
  Line: () => <div data-testid="trend-chart" />,
}));

vi.mock('../api/endpoints', () => ({
  accountsApi: { list: vi.fn(), create: vi.fn(), remove: vi.fn() },
  networthApi: { current: vi.fn(), saveSnapshot: vi.fn() },
  // The SIPs & broker transfers section reads the Investments category and its transactions.
  categoriesApi: { list: vi.fn() },
  transactionsApi: { search: vi.fn() },
}));

function renderInvestments() {
  return render(
    <ThemeProvider>
      <Investments />
    </ThemeProvider>
  );
}

function holding(overrides: Partial<Account> = {}): Account {
  return {
    id: 'a1',
    name: 'Index Fund',
    accountType: 'INVESTMENT',
    balance: 50000,
    investmentKind: 'Mutual Fund',
    ...overrides,
  } as Account;
}

function netWorth(overrides: Partial<NetWorthData> = {}): NetWorthData {
  return {
    totalAssets: 50000,
    totalLiabilities: 0,
    netWorth: 50000,
    history: [
      { date: '2026-07-01', netWorth: 40000 },
      { date: '2026-08-01', netWorth: 50000 },
    ],
    ...overrides,
  } as NetWorthData;
}

function emptyPage() {
  return { content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 };
}

function pending<T>(): Promise<T> {
  return new Promise<T>(() => {});
}

describe('Investments — loading states', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // The activity section is not what these tests are about: a user with an Investments category
    // and no transactions in it. Its own behaviour is covered in InvestmentActivity.test.tsx and in
    // the describe block at the bottom of this file.
    vi.mocked(categoriesApi.list).mockResolvedValue([
      { id: 'cat-inv', name: 'Investments', isSystem: true, icon: 'trending-up', color: 'teal' },
    ]);
    vi.mocked(transactionsApi.search).mockResolvedValue(emptyPage());
  });

  /**
   * The page-level `if (loading) return <p>Loading…</p>` is gone in favour of per-section regions.
   * Each announces immediately, before useDelayedLoading's window lets any shape render.
   */
  it('announces each loading section immediately instead of gating the whole page', () => {
    vi.mocked(accountsApi.list).mockReturnValue(pending<Account[]>());
    vi.mocked(networthApi.current).mockReturnValue(pending<NetWorthData>());

    renderInvestments();

    expect(screen.getByText('Loading your investment totals')).toBeInTheDocument();
    expect(screen.getByText('Loading your holdings')).toBeInTheDocument();
    // Both charts route their own loading through ChartContainer's Region.
    expect(screen.getByText('Loading your allocation')).toBeInTheDocument();
    expect(screen.getByText('Loading your net worth trend')).toBeInTheDocument();
  });

  /**
   * Removing the page gate exposes every `length === 0` empty state to the loading window. A user
   * with holdings must never be told they have none just because the fetch hasn't landed --
   * the flash-of-empty-state bug class §2 was written to kill.
   */
  it('never shows an empty state while the initial fetch is still in flight', () => {
    vi.mocked(accountsApi.list).mockReturnValue(pending<Account[]>());
    vi.mocked(networthApi.current).mockReturnValue(pending<NetWorthData>());

    renderInvestments();

    expect(screen.queryByText('No holdings yet')).not.toBeInTheDocument();
    expect(screen.queryByText('No investments yet')).not.toBeInTheDocument();
    expect(screen.queryByText('Building your net worth trend')).not.toBeInTheDocument();
  });

  /**
   * The trap the roadmap missed: load() is also called after add and delete, so a mutation refetch
   * used to re-enter the SAME page-level gate. Turned into a skeleton that would mean the entire
   * page collapsing after every add -- the opposite of the roadmap's UX rule, which puts a refetch
   * on the "stale content stays, spinner shows" row.
   */
  it('does not collapse into a skeleton when a delete triggers a refetch', async () => {
    const user = userEvent.setup();
    vi.mocked(accountsApi.list).mockResolvedValue([holding()]);
    vi.mocked(networthApi.current).mockResolvedValue(netWorth());
    vi.mocked(accountsApi.remove).mockResolvedValue(undefined as never);

    renderInvestments();
    expect(await screen.findByText('Index Fund')).toBeInTheDocument();

    // The refetch after the delete never settles, so the in-flight state is inspectable.
    vi.mocked(accountsApi.list).mockReturnValue(pending<Account[]>());
    vi.mocked(networthApi.current).mockReturnValue(pending<NetWorthData>());

    // Two "Delete" buttons exist once the dialog opens (the row's and the dialog's confirm), so the
    // second click is scoped to the dialog rather than relying on query order.
    await user.click(screen.getByRole('button', { name: 'Delete' }));
    const dialog = await screen.findByRole('alertdialog');
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }));

    expect(await screen.findByText('Refreshing…')).toBeInTheDocument();
    // Content stayed put; no section fell back to a loading region.
    expect(screen.getByText('Index Fund')).toBeInTheDocument();
    expect(screen.queryByText('Loading your holdings')).not.toBeInTheDocument();
    expect(screen.queryByText('Loading your investment totals')).not.toBeInTheDocument();
  });

  /**
   * Keeping the list on screen during a refresh is what makes a SECOND load reachable at all --
   * before Phase 5 the page-level gate replaced every Delete button while the refetch ran. Without
   * a request guard, the first delete's refetch (whose GET was issued BEFORE the second delete)
   * lands last and overwrites the correct result, resurrecting the deleted holding as a live,
   * deletable row until the page is reloaded.
   */
  it('ignores a superseded refetch instead of resurrecting a deleted holding', async () => {
    const user = userEvent.setup();
    const alpha = holding({ id: 'a1', name: 'Alpha' });
    const beta = holding({ id: 'a2', name: 'Beta' });

    const listDeferred: Array<(a: Account[]) => void> = [];
    vi.mocked(accountsApi.list).mockImplementation(
      () => new Promise<Account[]>((resolve) => { listDeferred.push(resolve); })
    );
    vi.mocked(networthApi.current).mockResolvedValue(netWorth());
    vi.mocked(accountsApi.remove).mockResolvedValue(undefined as never);

    renderInvestments();

    // Initial load.
    await waitFor(() => expect(listDeferred).toHaveLength(1));
    listDeferred[0]([alpha, beta]);
    expect(await screen.findByText('Alpha')).toBeInTheDocument();

    async function deleteRow(name: string) {
      const row = screen.getByText(name).closest('div')!;
      await user.click(within(row).getByRole('button', { name: 'Delete' }));
      const dialog = await screen.findByRole('alertdialog');
      await user.click(within(dialog).getByRole('button', { name: 'Delete' }));
    }

    await deleteRow('Alpha');
    await waitFor(() => expect(listDeferred).toHaveLength(2));
    // Second delete starts while the first refetch is still in flight.
    await deleteRow('Beta');
    await waitFor(() => expect(listDeferred).toHaveLength(3));

    // The newer refetch (post-both-deletes) resolves first...
    listDeferred[2]([]);
    await waitFor(() => expect(screen.queryByText('Beta')).not.toBeInTheDocument());

    // ...then the superseded one lands with its pre-second-delete snapshot. It must be ignored.
    listDeferred[1]([beta]);
    await act(async () => { await Promise.resolve(); });

    expect(screen.queryByText('Beta')).not.toBeInTheDocument();
    expect(screen.queryByText('Alpha')).not.toBeInTheDocument();
  });

  /**
   * The empty states are gated on `loading`, which the shared `.finally` clears on the error path
   * too -- so a failed initial fetch used to leave a user with holdings told, in three places, that
   * they have none.
   */
  it('does not claim the user has nothing when the initial fetch failed', async () => {
    vi.mocked(accountsApi.list).mockRejectedValue(new Error('offline'));
    vi.mocked(networthApi.current).mockRejectedValue(new Error('offline'));

    renderInvestments();

    expect(await screen.findByText('Could not load investments.')).toBeInTheDocument();
    expect(screen.queryByText('No holdings yet')).not.toBeInTheDocument();
    expect(screen.queryByText('No investments yet')).not.toBeInTheDocument();
    expect(screen.queryByText('Building your net worth trend')).not.toBeInTheDocument();
  });

  it('keeps the Add button spinning until the refetched list is on screen', async () => {
    const user = userEvent.setup();
    vi.mocked(accountsApi.list).mockResolvedValue([]);
    vi.mocked(networthApi.current).mockResolvedValue(netWorth({ history: [] }));
    vi.mocked(accountsApi.create).mockResolvedValue(holding() as never);

    renderInvestments();
    await screen.findByText('No holdings yet');

    await user.type(screen.getByLabelText('Name'), 'Index Fund');
    await user.type(screen.getByLabelText('Current value'), '50000');

    // create() resolves but the follow-up refetch does not -- the button must still be busy.
    vi.mocked(accountsApi.list).mockReturnValue(pending<Account[]>());
    vi.mocked(networthApi.current).mockReturnValue(pending<NetWorthData>());

    await user.click(screen.getByRole('button', { name: 'Add' }));

    // Its accessible name gains the busy suffix while loading, which is itself the assertion that
    // the pending state is announced rather than conveyed only as "disabled".
    await waitFor(() => expect(screen.getByRole('button', { name: /^Add\s*,\s*loading$/ })).toBeDisabled());
  });

  // Adding a holding is free on every plan -- there is no entitlement check on this page at all.
  // A user who downgraded from Premium keeps every holding they already had and can still add and
  // delete them: nothing about the page reads the plan.
  describe('no plan gate', () => {
    it('shows the Add Investment form, and no upgrade prompt, without asking for any entitlement', async () => {
      vi.mocked(accountsApi.list).mockResolvedValue([]);
      vi.mocked(networthApi.current).mockResolvedValue(netWorth({ history: [] }));

      renderInvestments();

      expect(await screen.findByLabelText('Name')).toBeInTheDocument();
      expect(screen.getByLabelText('Current value')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Add' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /upgrade/i })).not.toBeInTheDocument();
      expect(screen.queryByText(/premium/i)).not.toBeInTheDocument();
    });

    it('adds a holding through the API for any user', async () => {
      const user = userEvent.setup();
      vi.mocked(accountsApi.list).mockResolvedValue([]);
      vi.mocked(networthApi.current).mockResolvedValue(netWorth({ history: [] }));
      vi.mocked(accountsApi.create).mockResolvedValue(holding() as never);

      renderInvestments();
      await screen.findByText('No holdings yet');
      await user.type(screen.getByLabelText('Name'), 'Index Fund');
      await user.type(screen.getByLabelText('Current value'), '50000');
      await user.click(screen.getByRole('button', { name: 'Add' }));

      await waitFor(() => expect(accountsApi.create).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'Index Fund', accountType: 'INVESTMENT', balance: 50000 })));
    });

    it('still shows an existing holding, with a working Delete, next to the Add form', async () => {
      const user = userEvent.setup();
      vi.mocked(accountsApi.list).mockResolvedValue([holding()]);
      vi.mocked(networthApi.current).mockResolvedValue(netWorth());
      vi.mocked(accountsApi.remove).mockResolvedValue(undefined as never);

      renderInvestments();
      expect(await screen.findByText('Index Fund')).toBeInTheDocument();
      expect(screen.getByLabelText('Name')).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'Delete' }));
      const dialog = await screen.findByRole('alertdialog');
      await user.click(within(dialog).getByRole('button', { name: 'Delete' }));

      await waitFor(() => expect(accountsApi.remove).toHaveBeenCalledWith('a1'));
    });
  });

  /**
   * Bug fix: this catch used to be a bare `catch { setError('Could not add this holding.') }`,
   * discarding whatever the server actually said. Wrong once a rejection can be specific and
   * actionable -- the per-user holdings ceiling says exactly what to do ("Delete one to add
   * another") -- and would be silently replaced with a message implying a transient failure worth
   * retrying.
   */
  it('shows the server message when adding a holding is rejected, not a fixed string', async () => {
    const user = userEvent.setup();
    vi.mocked(accountsApi.list).mockResolvedValue([]);
    vi.mocked(networthApi.current).mockResolvedValue(netWorth({ history: [] }));
    vi.mocked(accountsApi.create).mockRejectedValue({
      response: { data: { message: 'You can track up to 100 investment holdings. Delete one to add another.' } },
    });

    renderInvestments();
    await screen.findByText('No holdings yet');

    await user.type(screen.getByLabelText('Name'), 'Index Fund');
    await user.type(screen.getByLabelText('Current value'), '50000');
    await user.click(screen.getByRole('button', { name: 'Add' }));

    expect(await screen.findByText('You can track up to 100 investment holdings. Delete one to add another.'))
      .toBeInTheDocument();
  });

  it('falls back to a generic message when the rejection carries no server message', async () => {
    const user = userEvent.setup();
    vi.mocked(accountsApi.list).mockResolvedValue([]);
    vi.mocked(networthApi.current).mockResolvedValue(netWorth({ history: [] }));
    vi.mocked(accountsApi.create).mockRejectedValue(new Error('network down'));

    renderInvestments();
    await screen.findByText('No holdings yet');

    await user.type(screen.getByLabelText('Name'), 'Index Fund');
    await user.type(screen.getByLabelText('Current value'), '50000');
    await user.click(screen.getByRole('button', { name: 'Add' }));

    expect(await screen.findByText('Could not add this holding.')).toBeInTheDocument();
  });
});

describe('Investments — SIPs & broker transfers section', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(accountsApi.list).mockResolvedValue([holding()]);
    vi.mocked(networthApi.current).mockResolvedValue(netWorth());
    vi.mocked(categoriesApi.list).mockResolvedValue([
      { id: 'cat-inv', name: 'Investments', isSystem: true, icon: 'trending-up', color: 'teal' },
    ]);
  });

  it('lists the Investments-category outflows on the page, next to the holdings', async () => {
    vi.mocked(transactionsApi.search).mockResolvedValue({
      content: [
        { id: 't1', description: 'UPI-GROWW INVEST TECH', date: '2026-08-05', amount: 3000, type: 'EXPENSE', reconciliationStatus: 'INVESTMENT_TRANSFER' },
        { id: 't2', description: 'ACH D- INDIAN CLEARING CORP', date: '2026-07-05', amount: 2000, type: 'EXPENSE', reconciliationStatus: 'INVESTMENT_TRANSFER' },
      ] as never,
      page: 0, size: 100, totalElements: 2, totalPages: 1,
    });

    renderInvestments();

    expect(await screen.findByText('UPI-GROWW INVEST TECH')).toBeInTheDocument();
    expect(screen.getByText('ACH D- INDIAN CLEARING CORP')).toBeInTheDocument();
    expect(screen.getByTestId('invested-total')).toHaveTextContent('₹5,000');
    // The holdings list is still there too.
    expect(screen.getByText('Index Fund')).toBeInTheDocument();
    expect(transactionsApi.search).toHaveBeenCalledWith(expect.objectContaining({ categoryId: 'cat-inv', type: 'EXPENSE' }));
  });

  it('shows the empty state when nothing is filed under Investments', async () => {
    vi.mocked(transactionsApi.search).mockResolvedValue({ content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 });

    renderInvestments();

    expect(await screen.findByText('No SIPs or broker transfers yet')).toBeInTheDocument();
  });
});

