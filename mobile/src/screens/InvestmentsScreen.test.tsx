import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider, onlineManager } from '@tanstack/react-query';
import { useNavigation } from '@react-navigation/native';
import { usePreventScreenCapture } from '../lib/screenCapture';
import { InvestmentsScreen } from './InvestmentsScreen';
import { accountsApi, categoriesApi, networthApi, transactionsApi } from '../api/endpoints';
import type { Account } from '../types';
import { expectSkeletonNotSpinner, never } from '../test/loadingStates';
import { expectHaptics } from '../test/haptics';
import { AppAlert } from '../lib/appAlert';

jest.mock('../api/endpoints', () => ({
  accountsApi: { list: jest.fn(), create: jest.fn(), update: jest.fn(), remove: jest.fn() },
  networthApi: { current: jest.fn() },
  categoriesApi: { list: jest.fn() },
  transactionsApi: { search: jest.fn() },
}));

const accounts = accountsApi as jest.Mocked<typeof accountsApi>;
const networth = networthApi as jest.Mocked<typeof networthApi>;
const categories = categoriesApi as jest.Mocked<typeof categoriesApi>;
const transactions = transactionsApi as jest.Mocked<typeof transactionsApi>;

const bank = {
  id: 'OTHER', officialName: null, shortName: 'Other', colorHex: '#000000', initials: 'OT',
  logoPath: '', category: null, websiteUrl: null, ifscPrefix: null, supportedAccountTypes: [],
};

function account(over: Partial<Account>): Account {
  return {
    id: 'a-1', name: 'Index fund', accountType: 'INVESTMENT', balance: 150000, investmentKind: 'Mutual Fund', bank,
    lastImportedAt: null, lastStatementPeriodStart: null, lastStatementPeriodEnd: null,
    statementsCount: 0, transactionsCount: 0, status: 'ACTIVE', primarySource: 'MANUAL', ...over,
  };
}

const fixedDeposit = account({ id: 'a-2', name: 'Fixed Deposit', balance: 200000, investmentKind: 'FD', interestRate: 7.1 });

function client() {
  return new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
}

function renderScreen(queryClient = client()) {
  return render(
    <QueryClientProvider client={queryClient}>
      <InvestmentsScreen />
    </QueryClientProvider>,
  );
}

async function settle() {
  await act(async () => {});
}

async function loaded() {
  await screen.findByText('Holdings');
}

async function openMenu(name: string) {
  fireEvent.press(await screen.findByLabelText(`More actions for ${name}`));
  await settle();
}

async function openUpdate(name = 'Index fund') {
  await openMenu(name);
  fireEvent.press(screen.getByLabelText('Update value'));
  await settle();
}

async function openAdd() {
  fireEvent.press(await screen.findByLabelText('Add holding'));
  await settle();
}

function pressAddInSheet() {
  fireEvent.press(screen.getAllByRole('button', { name: 'Add holding' }).at(-1)!);
}

beforeEach(() => {
  accounts.list.mockReset().mockResolvedValue([
    account({}),
    fixedDeposit,
    account({ id: 'a-3', name: 'HDFC Savings', accountType: 'SAVINGS', balance: 999999 }),
  ]);
  accounts.create.mockReset().mockResolvedValue(account({ id: 'new', name: 'Gold ETF', balance: 25000 }));
  accounts.update.mockReset().mockResolvedValue(account({}));
  accounts.remove.mockReset().mockResolvedValue(undefined as never);
  networth.current.mockReset().mockResolvedValue({
    totalAssets: 500000, totalLiabilities: 50000, netWorth: 450000,
    history: [{ date: '2026-06-01', netWorth: 400000 }, { date: '2026-07-01', netWorth: 450000 }],
  });
  categories.list.mockReset().mockResolvedValue([{ id: 'cat-inv', name: 'Investments', isSystem: true, icon: 'trending-up', color: 'teal' }]);
  transactions.search.mockReset().mockResolvedValue({ content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 });
});

describe('InvestmentsScreen: what it shows', () => {
  it('counts only investment accounts and says how much of the assets they are', async () => {
    renderScreen();
    await loaded();

    expect(screen.getByText('₹3,50,000')).toBeTruthy();
    expect(screen.getByText('2 holdings · 70% of your assets')).toBeTruthy();
    expect(screen.queryByText('HDFC Savings')).toBeNull();
  });

  it('shows a deposit\'s terms', async () => {
    renderScreen();
    await loaded();

    expect(screen.getByText('57% · 7.1% a year')).toBeTruthy();
  });

  it('no longer offers a manual net worth snapshot', async () => {
    renderScreen();
    await loaded();

    expect(screen.queryByRole('button', { name: /snapshot/i })).toBeNull();
  });

  it('invites someone with no holdings to add one', async () => {
    accounts.list.mockResolvedValue([]);
    renderScreen();

    expect(await screen.findByText('No investments yet. Add a holding, or import a deposit statement.')).toBeTruthy();
    expect(screen.queryByText('Holdings')).toBeNull();
  });

  it('opens the payments behind Money going in', async () => {
    transactions.search.mockResolvedValue({
      content: [{ id: 't', description: 'SIP', date: new Date().toISOString().slice(0, 10), amount: 1000, type: 'EXPENSE', reconciliationStatus: 'INVESTMENT_TRANSFER' }],
      page: 0, size: 100, totalElements: 1, totalPages: 1,
    } as never);
    renderScreen();
    const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
    navigate.mockClear();

    fireEvent.press(await screen.findByLabelText('See payments'));

    expect(navigate).toHaveBeenCalledWith('Transactions', {
      filters: expect.objectContaining({ categoryId: 'cat-inv', label: 'Investments · last 12 months' }),
    });
  });
});

describe('InvestmentsScreen: failures', () => {
  afterEach(() => onlineManager.setOnline(true));

  it('still lists holdings when the net worth endpoint fails', async () => {
    networth.current.mockRejectedValue(new Error('down'));
    renderScreen();
    await loaded();

    expect(screen.getByText('Could not load your net worth.')).toBeTruthy();
    expect(screen.getByText('2 holdings')).toBeTruthy();
  });

  it('does not state a portfolio of zero when the accounts endpoint fails', async () => {
    accounts.list.mockRejectedValue(new Error('down'));
    renderScreen();

    expect(await screen.findByText('Could not load your investments.')).toBeTruthy();
    expect(screen.queryByText('₹0')).toBeNull();
    expect(screen.queryByText(/No investments yet/)).toBeNull();
    expect(screen.getByText('₹4,50,000')).toBeTruthy();
  });

  it('keeps the holdings on screen when a refresh fails', async () => {
    const queryClient = client();
    renderScreen(queryClient);
    await loaded();

    accounts.list.mockRejectedValueOnce(new Error('offline'));
    await act(async () => { await queryClient.refetchQueries({ queryKey: ['accounts'] }); });
    await settle();

    expect(queryClient.getQueryState(['accounts'])?.status).toBe('error');
    expect(screen.getByText('Index fund')).toBeTruthy();
    expect(screen.queryByText('Could not load your investments.')).toBeNull();
  });

  it('says the figures are unavailable offline rather than printing zero', async () => {
    onlineManager.setOnline(false);
    renderScreen();

    expect(await screen.findByText('Could not load your investments.')).toBeTruthy();
    expect(screen.getByText('Could not load your net worth.')).toBeTruthy();
    expect(screen.queryByText('₹0')).toBeNull();
  });

  it('paints a negative net worth as a loss, and says when a card is left out', async () => {
    networth.current.mockResolvedValue({ totalAssets: 100, totalLiabilities: 200, netWorth: -100, history: [], cardsWithUnknownAmountOwed: 1 });
    renderScreen();
    await loaded();

    expect(screen.getByText('-₹100')).toBeTruthy();
    expect(screen.getByText("Leaves out 1 card whose amount owed isn't known.")).toBeTruthy();
  });
});

describe('InvestmentsScreen: update value', () => {
  it('saves the value and shows it before the refresh returns', async () => {
    renderScreen();
    await loaded();
    accounts.list.mockReturnValue(never());

    await openUpdate();
    fireEvent.changeText(screen.getByLabelText(/Current value/), '1,65,000');
    expect(screen.getByText('Up ₹15,000 from the last value you saved.')).toBeTruthy();
    fireEvent.press(screen.getByText('Save value'));

    await waitFor(() => expect(accounts.update).toHaveBeenCalledWith('a-1', { name: 'Index fund', accountType: 'INVESTMENT', balance: 165000 }));
    await waitFor(() => expect(screen.getByText('₹1,65,000')).toBeTruthy());
    await waitFor(() => expect(screen.queryByText('Save value')).toBeNull());
    expectHaptics({ success: 1 });
  });

  it('a double tap sends one request', async () => {
    accounts.update.mockReturnValue(never());
    renderScreen();
    await openUpdate();

    const save = screen.getByText('Save value');
    fireEvent.press(save);
    fireEvent.press(save);
    await settle();

    expect(accounts.update).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['100.555', 'Current value can have at most 2 decimal places.'],
    ['1000000000000', 'Current value is too large.'],
    ['0', 'Current value must be greater than zero.'],
  ])('refuses %j without calling the server', async (typed, message) => {
    renderScreen();
    await openUpdate();

    fireEvent.changeText(screen.getByLabelText(/Current value/), typed);
    fireEvent.press(screen.getByText('Save value'));

    expect(await screen.findByText(message)).toBeTruthy();
    expect(accounts.update).not.toHaveBeenCalled();
    expectHaptics({ warning: 1 });
  });

  it('keeps the sheet open with a message when the server refuses', async () => {
    accounts.update.mockRejectedValueOnce(new Error('down'));
    renderScreen();
    await openUpdate();

    fireEvent.press(screen.getByText('Save value'));

    expect(await screen.findByText('Could not save this value. Try again.')).toBeTruthy();
    expect(screen.getByText('Save value')).toBeTruthy();
    expectHaptics({ error: 1 });
  });

  it('does not offer Update value on a bank-linked holding', async () => {
    accounts.list.mockResolvedValue([account({ primarySource: 'ACCOUNT_AGGREGATOR' })]);
    renderScreen();
    await openMenu('Index fund');

    expect(screen.queryByLabelText('Update value')).toBeNull();
    expect(screen.getByLabelText('Delete holding')).toBeTruthy();
  });
});

describe('InvestmentsScreen: add holding', () => {
  it('adds a holding with the value entered', async () => {
    renderScreen();
    await loaded();
    await openAdd();

    fireEvent.changeText(screen.getByLabelText(/^Name/), ' Gold ETF ');
    fireEvent.changeText(screen.getByLabelText(/Current value/), '25,000');
    pressAddInSheet();

    await waitFor(() => expect(accounts.create).toHaveBeenCalledWith({ name: 'Gold ETF', accountType: 'INVESTMENT', balance: 25000, investmentKind: 'Mutual Fund' }));
    expectHaptics({ success: 1 });
  });

  it('an add the server refuses gives one error', async () => {
    accounts.create.mockRejectedValueOnce(new Error('down'));
    renderScreen();
    await loaded();
    await openAdd();

    fireEvent.changeText(screen.getByLabelText(/^Name/), 'Gold ETF');
    fireEvent.changeText(screen.getByLabelText(/Current value/), '25000');
    pressAddInSheet();

    expect(await screen.findByText('Could not add this holding.')).toBeTruthy();
    expectHaptics({ error: 1 });
  });

  it('a holding with no name gives one warning and sends nothing', async () => {
    renderScreen();
    await loaded();
    await openAdd();

    fireEvent.changeText(screen.getByLabelText(/Current value/), '25000');
    pressAddInSheet();

    expect(await screen.findByText('Give this holding a name.')).toBeTruthy();
    expect(accounts.create).not.toHaveBeenCalled();
    expectHaptics({ warning: 1 });
  });

  it('refuses a value that is not a positive number', async () => {
    renderScreen();
    await loaded();
    await openAdd();
    fireEvent.changeText(screen.getByLabelText(/^Name/), 'Bad');

    for (const bad of ['0', '-500', 'abc', '']) {
      fireEvent.changeText(screen.getByLabelText(/Current value/), bad);
      pressAddInSheet();
      await settle();
    }

    expect(accounts.create).not.toHaveBeenCalled();
    expectHaptics({ warning: 4 });
  });
});

describe('InvestmentsScreen: delete holding', () => {
  async function chooseDelete(name: string) {
    await openMenu(name);
    fireEvent.press(screen.getByLabelText('Delete holding'));
    await settle();
  }

  it('asks first, naming the holding, and removes it with one success', async () => {
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    renderScreen();
    await chooseDelete('Index fund');

    expect(alertSpy.mock.calls[0][1]).toBe('"Index fund" will be removed from your net worth. This can\'t be undone.');
    expectHaptics({});
    await act(async () => { alertSpy.mock.calls[0][2]!.find((b) => b.text === 'Delete')!.onPress!(); });

    await waitFor(() => expect(accounts.remove).toHaveBeenCalledWith('a-1'));
    expectHaptics({ success: 1 });
    alertSpy.mockRestore();
  });

  it('a deleted holding leaves the list even if the refresh fails', async () => {
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    renderScreen();
    await chooseDelete('Index fund');
    accounts.list.mockRejectedValue(new Error('offline'));
    await act(async () => { alertSpy.mock.calls[0][2]!.find((b) => b.text === 'Delete')!.onPress!(); });
    // React Query tells the screen on a timer; waitFor alone did not always flush that update.
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)); });

    expect(screen.queryByLabelText('More actions for Index fund')).toBeNull();
    expect(screen.getByLabelText('More actions for Fixed Deposit')).toBeTruthy();
    alertSpy.mockRestore();
  });

  it('says so when the delete is refused', async () => {
    accounts.remove.mockRejectedValueOnce(new Error('down'));
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    renderScreen();
    await chooseDelete('Index fund');
    await act(async () => { alertSpy.mock.calls[0][2]!.find((b) => b.text === 'Delete')!.onPress!(); });

    expect(await screen.findByText('Could not delete this holding.')).toBeTruthy();
    expectHaptics({ error: 1 });
    alertSpy.mockRestore();
  });
});

describe('InvestmentsScreen: first load', () => {
  it('calls usePreventScreenCapture on mount', () => {
    renderScreen();

    expect(usePreventScreenCapture).toHaveBeenCalled();
  });

  it('shows the investments skeleton, not a spinner', () => {
    accounts.list.mockReturnValue(never());
    networth.current.mockReturnValue(never());
    renderScreen();

    expectSkeletonNotSpinner();
    expect(screen.getByTestId('investments-skeleton')).toBeTruthy();
  });

  it('with the accounts known to hold no investments, the skeleton shows the empty card', async () => {
    accounts.list.mockResolvedValue([]);
    networth.current.mockReturnValue(never());
    renderScreen();

    expect(await screen.findAllByTestId('skeleton-empty-state', { hidden: true })).toHaveLength(1);
  });
});
