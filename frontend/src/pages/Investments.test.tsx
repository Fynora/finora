import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Investments from './Investments';
import { accountsApi, networthApi, categoriesApi, transactionsApi, type NetWorthData } from '../api/endpoints';
import { ThemeProvider } from '../context/ThemeContext';
import type { Account } from '../types';

// chart.js needs a real canvas, which jsdom doesn't provide.
vi.mock('react-chartjs-2', () => ({ Line: () => <div data-testid="trend-chart" /> }));

vi.mock('../api/endpoints', () => ({
  accountsApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
  networthApi: { current: vi.fn() },
  categoriesApi: { list: vi.fn() },
  transactionsApi: { search: vi.fn() },
}));

function renderInvestments(queryClient = new QueryClient()) {
  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider>
          <Investments />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

function holding(overrides: Partial<Account> = {}): Account {
  return {
    id: 'a1', name: 'Index Fund', accountType: 'INVESTMENT', balance: 50000, investmentKind: 'Mutual Fund', primarySource: 'MANUAL',
    ...overrides,
  } as Account;
}

function netWorth(overrides: Partial<NetWorthData> = {}): NetWorthData {
  return {
    totalAssets: 100000,
    totalLiabilities: 0,
    netWorth: 100000,
    history: [{ date: '2026-07-01', netWorth: 40000 }, { date: '2026-08-01', netWorth: 100000 }],
    ...overrides,
  } as NetWorthData;
}

function pending<T>(): Promise<T> {
  return new Promise<T>(() => {});
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => { resolve = r; });
  return { promise, resolve };
}

async function openMenu(name: string) {
  await userEvent.click(await screen.findByRole('button', { name: `More actions for ${name}` }));
  return screen.getByRole('menu');
}

async function openUpdate(name = 'Index Fund') {
  await userEvent.click(within(await openMenu(name)).getByRole('menuitem', { name: 'Update value' }));
  return screen.getByRole('dialog', { name });
}

async function confirmDelete(name: string) {
  await userEvent.click(within(await openMenu(name)).getByRole('menuitem', { name: 'Delete holding' }));
  await userEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Delete' }));
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(categoriesApi.list).mockResolvedValue([{ id: 'cat-inv', name: 'Investments', isSystem: true, icon: 'trending-up', color: 'teal' }]);
  vi.mocked(transactionsApi.search).mockResolvedValue({ content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 } as never);
  vi.mocked(accountsApi.list).mockResolvedValue([holding()]);
  vi.mocked(networthApi.current).mockResolvedValue(netWorth());
  vi.mocked(accountsApi.update).mockResolvedValue(holding());
  vi.mocked(accountsApi.remove).mockResolvedValue({} as never);
});

describe('Investments: what the page shows', () => {
  it('counts only investment accounts and says how much of the assets they are', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([holding(), holding({ id: 's', name: 'Savings', accountType: 'SAVINGS', balance: 999 })]);
    renderInvestments();

    expect(await screen.findByText('1 holding · 50% of your assets')).toBeInTheDocument();
    expect(screen.getAllByText('₹50,000').length).toBeGreaterThan(0);
    expect(screen.queryByText('Savings')).not.toBeInTheDocument();
  });

  it('never offers a manual net worth snapshot', async () => {
    renderInvestments();

    await screen.findByText('Total value');
    expect(screen.queryByRole('button', { name: /snapshot/i })).not.toBeInTheDocument();
  });

  it('says when a card is left out of net worth', async () => {
    vi.mocked(networthApi.current).mockResolvedValue(netWorth({ cardsWithUnknownAmountOwed: 1 }));
    renderInvestments();

    expect(await screen.findByText("Leaves out 1 card whose amount owed isn't known.")).toBeInTheDocument();
  });

  it('invites someone with no holdings to add one', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([]);
    renderInvestments();

    expect(await screen.findByText('No investments yet')).toBeInTheDocument();
    expect(screen.getByText('Add a holding, or import a deposit statement.')).toBeInTheDocument();
    expect(screen.queryByText('Total value')).not.toBeInTheDocument();
  });
});

describe('Investments: loading and failures', () => {
  it('never shows the empty state while the first load is in flight', () => {
    vi.mocked(accountsApi.list).mockReturnValue(pending<Account[]>());
    vi.mocked(networthApi.current).mockReturnValue(pending<NetWorthData>());
    renderInvestments();

    expect(screen.getByText('Loading your investments')).toBeInTheDocument();
    expect(screen.queryByText('No investments yet')).not.toBeInTheDocument();
  });

  it('states no total when holdings failed to load, and keeps net worth', async () => {
    vi.mocked(accountsApi.list).mockRejectedValue(new Error('down'));
    renderInvestments();

    expect(await screen.findByText('Could not load your investments.')).toBeInTheDocument();
    expect(screen.queryByText('Total value')).not.toBeInTheDocument();
    expect(screen.queryByText('No investments yet')).not.toBeInTheDocument();
    expect(screen.getByText('₹1,00,000')).toBeInTheDocument();
  });

  it('still lists holdings when net worth failed to load', async () => {
    vi.mocked(networthApi.current).mockRejectedValue(new Error('down'));
    renderInvestments();

    expect(await screen.findByText('Could not load your net worth.')).toBeInTheDocument();
    expect(screen.getByText('Index Fund')).toBeInTheDocument();
    expect(screen.getByText('1 holding')).toBeInTheDocument();
  });

  it('keeps the holdings on screen when a refresh fails', async () => {
    vi.mocked(accountsApi.list).mockResolvedValueOnce([holding(), holding({ id: 'a2', name: 'Gold' })]).mockRejectedValue(new Error('down'));
    renderInvestments();

    await confirmDelete('Gold');

    await waitFor(() => expect(accountsApi.list).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('Index Fund')).toBeInTheDocument();
    expect(screen.queryByText('Could not load your investments.')).not.toBeInTheDocument();
  });

  it('does not fall back to a skeleton while a delete reloads the list', async () => {
    vi.mocked(accountsApi.list).mockResolvedValueOnce([holding(), holding({ id: 'a2', name: 'Gold' })]).mockReturnValue(pending<Account[]>());
    renderInvestments();

    await confirmDelete('Gold');

    await waitFor(() => expect(accountsApi.list).toHaveBeenCalledTimes(2));
    expect(screen.getByText('Index Fund')).toBeInTheDocument();
    expect(screen.queryByText('Loading your investments')).not.toBeInTheDocument();
  });

  it('a slower, older reload cannot bring a deleted holding back', async () => {
    const older = deferred<Account[]>();
    const a = holding({ id: 'a', name: 'Alpha' });
    const b = holding({ id: 'b', name: 'Beta' });
    const c = holding({ id: 'c', name: 'Gamma' });
    vi.mocked(accountsApi.list).mockResolvedValueOnce([a, b, c]).mockReturnValueOnce(older.promise).mockResolvedValue([c]);
    renderInvestments();

    await confirmDelete('Alpha');
    await waitFor(() => expect(screen.queryByText('Alpha')).not.toBeInTheDocument());
    await confirmDelete('Beta');
    await waitFor(() => expect(accountsApi.list).toHaveBeenCalledTimes(3));
    older.resolve([a, b, c]);
    await new Promise((r) => setTimeout(r, 0));

    expect(screen.queryByText('Alpha')).not.toBeInTheDocument();
    expect(screen.queryByText('Beta')).not.toBeInTheDocument();
    expect(screen.getByText('Gamma')).toBeInTheDocument();
  });
});

describe('Investments: update value', () => {
  it('saves the new value and shows it before the reload returns', async () => {
    vi.mocked(accountsApi.list).mockResolvedValueOnce([holding()]).mockReturnValue(pending<Account[]>());
    renderInvestments();

    const dialog = await openUpdate();
    const input = within(dialog).getByLabelText('Current value');
    await userEvent.clear(input);
    await userEvent.type(input, '65,000');
    expect(within(dialog).getByText('Up ₹15,000 from the last value you saved.')).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save value' }));

    expect(accountsApi.update).toHaveBeenCalledWith('a1', { name: 'Index Fund', accountType: 'INVESTMENT', balance: 65000 });
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.getAllByText('₹65,000').length).toBeGreaterThan(0);
  });

  it('a double click sends one request', async () => {
    vi.mocked(accountsApi.update).mockReturnValue(pending<Account>());
    renderInvestments();

    const dialog = await openUpdate();
    await userEvent.dblClick(within(dialog).getByRole('button', { name: 'Save value' }));

    expect(accountsApi.update).toHaveBeenCalledTimes(1);
  });

  it('Escape does not pretend to cancel a save already on its way', async () => {
    vi.mocked(accountsApi.update).mockReturnValue(pending<Account>());
    renderInvestments();

    const dialog = await openUpdate();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save value' }));
    await userEvent.keyboard('{Escape}');

    expect(screen.getByRole('dialog', { name: 'Index Fund' })).toBeInTheDocument();
  });

  it.each([
    ['100.555', 'Current value can have at most 2 decimal places.'],
    ['1000000000000', 'Current value is too large.'],
    ['0', 'Current value must be greater than zero.'],
    ['abc', 'Current value must be greater than zero.'],
  ])('refuses %j without calling the server', async (typed, message) => {
    renderInvestments();

    const dialog = await openUpdate();
    const input = within(dialog).getByLabelText('Current value');
    await userEvent.clear(input);
    await userEvent.type(input, `${typed}{Enter}`);

    expect(await within(dialog).findByText(message)).toBeInTheDocument();
    expect(accountsApi.update).not.toHaveBeenCalled();
  });

  it("keeps the dialog open with the server's message when a save is refused", async () => {
    vi.mocked(accountsApi.update).mockRejectedValue({ response: { data: { message: 'Not now.' } } });
    renderInvestments();

    const dialog = await openUpdate();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save value' }));

    expect(await within(dialog).findByText('Not now.')).toBeInTheDocument();
  });

  it('an older reload arriving late does not undo a newer value', async () => {
    const older = deferred<Account[]>();
    vi.mocked(accountsApi.list)
      .mockResolvedValueOnce([holding()])
      .mockReturnValueOnce(older.promise)
      .mockResolvedValue([holding({ balance: 70000 })]);
    renderInvestments();

    for (const value of ['60000', '70000']) {
      const dialog = await openUpdate();
      const input = within(dialog).getByLabelText('Current value');
      await userEvent.clear(input);
      await userEvent.type(input, `${value}{Enter}`);
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    }
    older.resolve([holding({ balance: 60000 })]);
    await new Promise((r) => setTimeout(r, 0));

    expect(screen.getAllByText('₹70,000').length).toBeGreaterThan(0);
    expect(screen.queryByText('₹60,000')).not.toBeInTheDocument();
  });

  it('refreshes the Dashboard after a save', async () => {
    const queryClient = new QueryClient();
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    renderInvestments(queryClient);

    const dialog = await openUpdate();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save value' }));

    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ['dashboard-summary'] }));
  });
});

describe('Investments: add holding', () => {
  async function openAdd() {
    await userEvent.click(await screen.findByRole('button', { name: 'Add holding' }));
    return screen.getByRole('dialog', { name: 'Add holding' });
  }

  it('adds a holding for any user, with the chosen type', async () => {
    const created = holding({ id: 'new', name: 'PPF', balance: 2000, investmentKind: 'PPF/NPS' });
    vi.mocked(accountsApi.create).mockResolvedValue(created);
    vi.mocked(accountsApi.list).mockResolvedValueOnce([holding()]).mockResolvedValue([holding(), created]);
    renderInvestments();

    const dialog = await openAdd();
    await userEvent.type(within(dialog).getByLabelText('Name'), ' PPF ');
    await userEvent.type(within(dialog).getByLabelText('Current value'), '2,000');
    await userEvent.selectOptions(within(dialog).getByLabelText('Type'), 'PPF/NPS');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add holding' }));

    expect(accountsApi.create).toHaveBeenCalledWith({ name: 'PPF', accountType: 'INVESTMENT', balance: 2000, investmentKind: 'PPF/NPS' });
    expect(await screen.findByText('PPF')).toBeInTheDocument();
  });

  it('asks for a name first', async () => {
    renderInvestments();

    const dialog = await openAdd();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add holding' }));

    expect(within(dialog).getByText('Give this holding a name.')).toBeInTheDocument();
    expect(accountsApi.create).not.toHaveBeenCalled();
  });

  it('shows the server message when the holding is refused', async () => {
    vi.mocked(accountsApi.create).mockRejectedValue({ response: { data: { message: 'You can track up to 100 investment holdings. Delete one to add another.' } } });
    renderInvestments();

    const dialog = await openAdd();
    await userEvent.type(within(dialog).getByLabelText('Name'), 'Gold');
    await userEvent.type(within(dialog).getByLabelText('Current value'), '100{Enter}');

    expect(await within(dialog).findByText('You can track up to 100 investment holdings. Delete one to add another.')).toBeInTheDocument();
  });

  it('falls back to a plain message when the refusal carries none', async () => {
    vi.mocked(accountsApi.create).mockRejectedValue(new Error('network'));
    renderInvestments();

    const dialog = await openAdd();
    await userEvent.type(within(dialog).getByLabelText('Name'), 'Gold');
    await userEvent.type(within(dialog).getByLabelText('Current value'), '100{Enter}');

    expect(await within(dialog).findByText('Could not add this holding.')).toBeInTheDocument();
  });
});

describe('Investments: delete holding', () => {
  it('names the holding and what deleting it does', async () => {
    renderInvestments();

    await userEvent.click(within(await openMenu('Index Fund')).getByRole('menuitem', { name: 'Delete holding' }));

    expect(within(screen.getByRole('alertdialog')).getByText('"Index Fund" will be removed from your net worth. This can\'t be undone.')).toBeInTheDocument();
  });

  it('locks the row while it is being deleted', async () => {
    vi.mocked(accountsApi.remove).mockReturnValue(pending());
    renderInvestments();

    await confirmDelete('Index Fund');

    await waitFor(() => expect(screen.getByRole('button', { name: 'More actions for Index Fund' })).toBeDisabled());
  });

  it('says so when the delete fails, and keeps the holding', async () => {
    vi.mocked(accountsApi.remove).mockRejectedValue(new Error('down'));
    renderInvestments();

    await confirmDelete('Index Fund');

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not delete this holding.');
    expect(screen.getByText('Index Fund')).toBeInTheDocument();
  });
});
