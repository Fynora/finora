import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Setup from './Setup';
import { accountsApi, banksApi } from '../api/endpoints';
import type { Account } from '../types';

vi.mock('../api/endpoints', () => ({
  accountsApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
  banksApi: { list: vi.fn() },
}));

function account(overrides: Partial<Account> = {}): Account {
  return {
    id: 'a1',
    name: 'Salary Account',
    accountType: 'SAVINGS',
    balance: 15000,
    bank: { id: 'OTHER', officialName: null, shortName: 'Bank' },
    status: 'ACTIVE',
    statementsCount: 0,
    transactionsCount: 0,
    accountHolderName: null,
    accountNumberMasked: null,
    lastImportedAt: null,
    lastStatementPeriodStart: null,
    lastStatementPeriodEnd: null,
    ...overrides,
  } as unknown as Account;
}

function renderPage(queryClient = new QueryClient()) {
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Setup />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('Setup', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(banksApi.list).mockResolvedValue([]);
  });

  // The bug: `accounts` started `[]`, indistinguishable from "genuinely no accounts yet" -- the
  // worst instance of this class of bug on the page, since the real account cards (bank logo,
  // balance, statement period, masked number) are visually much richer than a budget row or a
  // goal card, making the empty->real content jump the most jarring of the three.
  it('never shows the empty state while the initial fetch is still in flight', async () => {
    let resolveList: (a: Account[]) => void;
    vi.mocked(accountsApi.list).mockReturnValue(
      new Promise((resolve) => {
        resolveList = resolve;
      })
    );

    renderPage();

    expect(screen.queryByText(/No accounts yet/)).not.toBeInTheDocument();

    resolveList!([account()]);
    await waitFor(() => expect(screen.getByText('Salary Account')).toBeInTheDocument());
    expect(screen.queryByText(/No accounts yet/)).not.toBeInTheDocument();
  });

  it('shows the empty state once loading finishes with genuinely no accounts', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([]);
    renderPage();

    expect(await screen.findByText(/No accounts yet/)).toBeInTheDocument();
  });

  it('renders each account once loaded, with its balance', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([
      account({ id: 'a1', name: 'Salary Account', balance: 15000 }),
      account({ id: 'a2', name: 'Travel Card', accountType: 'CREDIT_CARD', balance: -2500 }),
    ]);
    renderPage();

    expect(await screen.findByText('Salary Account')).toBeInTheDocument();
    expect(screen.getByText('Travel Card')).toBeInTheDocument();
  });

  // The server records a balance it is given as one the user stated today; a blank box used to be
  // sent as 0, which would make every older statement uploaded afterwards look already counted.
  it('sends no balance when the box is left blank, and the typed figure when it is not', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([]);
    vi.mocked(accountsApi.create).mockResolvedValue(account());
    renderPage();
    await screen.findByText(/No accounts yet/);

    const nameInput = screen.getByText('Name').nextElementSibling as HTMLInputElement;
    const balanceInput = screen.getByText('Balance').nextElementSibling as HTMLInputElement;

    fireEvent.change(nameInput, { target: { value: 'Blank Balance' } });
    fireEvent.click(screen.getByRole('button', { name: /^add$/i }));
    await waitFor(() => expect(accountsApi.create).toHaveBeenCalledTimes(1));
    expect(vi.mocked(accountsApi.create).mock.calls[0][0].balance).toBeUndefined();

    fireEvent.change(nameInput, { target: { value: 'Typed Balance' } });
    fireEvent.change(balanceInput, { target: { value: '2500' } });
    fireEvent.click(screen.getByRole('button', { name: /^add$/i }));
    await waitFor(() => expect(accountsApi.create).toHaveBeenCalledTimes(2));
    expect(vi.mocked(accountsApi.create).mock.calls[1][0].balance).toBe(2500);
  });
});

describe('Setup — a card whose amount owed is not known', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(banksApi.list).mockResolvedValue([]);
  });

  const unknownCard = () => account({
    id: 'card-1', name: 'ICICI Bank Rupay', accountType: 'CREDIT_CARD', balance: -100396.53,
    amountOwedKnown: false, balanceStated: false,
  });

  it('shows no figure for it, anywhere on its card', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([unknownCard()]);
    renderPage();
    expect(await screen.findByText('ICICI Bank Rupay')).toBeInTheDocument();
    expect(screen.getByText('Amount owed not known')).toBeInTheDocument();
    expect(screen.queryByText(/1,00,396/)).not.toBeInTheDocument();
  });

  it('"Add it" saves the amount as the card balance, then reloads the accounts and every total', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([unknownCard()]);
    vi.mocked(accountsApi.update).mockResolvedValue(account({ id: 'card-1', accountType: 'CREDIT_CARD', balance: 12345.67 }));
    const queryClient = new QueryClient();
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    renderPage(queryClient);

    fireEvent.click(await screen.findByRole('button', { name: 'Add it' }));
    fireEvent.change(screen.getByLabelText('Amount owed today (₹)'), { target: { value: '12345.67' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(accountsApi.update).toHaveBeenCalledWith('card-1',
      { name: 'ICICI Bank Rupay', accountType: 'CREDIT_CARD', balance: 12345.67 }));
    await waitFor(() => expect(accountsApi.list).toHaveBeenCalledTimes(2));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['dashboard-summary'] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['accounts'] });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('shows a card with a known amount as before', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([account({ name: 'HDFC Card', accountType: 'CREDIT_CARD', balance: 4200, amountOwedKnown: true })]);
    renderPage();
    expect(await screen.findByText('HDFC Card')).toBeInTheDocument();
    expect(screen.queryByText('Amount owed not known')).not.toBeInTheDocument();
  });
});

const HDFC = {
  id: 'HDFC', officialName: 'HDFC Bank', shortName: 'HDFC', colorHex: '#0B2447', initials: 'H',
  logoPath: '', category: null, websiteUrl: null, ifscPrefix: null, supportedAccountTypes: [],
} as const;
const SBI = { ...HDFC, id: 'SBI', officialName: 'State Bank of India', shortName: 'SBI', initials: 'SB' };

async function openEdit(accountName: string) {
  await screen.findByText(accountName);
  fireEvent.click(screen.getByRole('button', { name: 'More actions' }));
  fireEvent.click(screen.getByRole('button', { name: /edit account/i }));
  return screen.getByRole('dialog');
}

describe('Setup — Edit account (name and bank)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    vi.mocked(banksApi.list).mockResolvedValue([{ ...HDFC, supportedAccountTypes: [] }, { ...SBI, supportedAccountTypes: [] }]);
  });

  it("saves a new name and bank, then reloads the accounts everywhere they're shown", async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([account({ id: 'a1', name: 'Bank Statement Import', statementsCount: 1 })]);
    vi.mocked(accountsApi.update).mockResolvedValue(account({ id: 'a1', name: 'Salary', bank: { ...HDFC } as never }));
    const queryClient = new QueryClient();
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    renderPage(queryClient);

    const dialog = await openEdit('Bank Statement Import');
    expect(within(dialog).getByRole('heading', { name: 'Edit account' })).toBeInTheDocument();
    const save = within(dialog).getByRole('button', { name: 'Save' });
    expect(save).toBeDisabled(); // nothing changed yet

    fireEvent.change(within(dialog).getByLabelText('Account name'), { target: { value: 'Salary' } });
    const bank = within(dialog).getByLabelText('Bank') as HTMLSelectElement;
    expect(bank.value).toBe('OTHER');
    await waitFor(() => expect(within(bank).getByRole('option', { name: 'HDFC Bank' })).toBeInTheDocument());
    fireEvent.change(bank, { target: { value: 'HDFC' } });
    fireEvent.click(save);

    await waitFor(() => expect(accountsApi.update).toHaveBeenCalledWith('a1',
      { name: 'Salary', accountType: 'SAVINGS', bankId: 'HDFC' }));
    await waitFor(() => expect(accountsApi.list).toHaveBeenCalledTimes(2));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['accounts'] });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('narrows the bank list as the user searches, keeping the chosen bank listed', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([account({ id: 'a1', name: 'Salary Account', bank: { ...SBI } as never })]);
    renderPage();

    const dialog = await openEdit('Salary Account');
    const bank = within(dialog).getByLabelText('Bank');
    await waitFor(() => expect(within(bank).getByRole('option', { name: 'HDFC Bank' })).toBeInTheDocument());
    fireEvent.change(within(dialog).getByLabelText('Search banks'), { target: { value: 'hdfc' } });

    const names = within(bank).getAllByRole('option').map((o) => o.textContent);
    expect(names).toEqual(['HDFC Bank', 'State Bank of India', 'Other bank']);
  });

  it('shows an error and keeps the dialog open when the save fails', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([account({ id: 'a1', name: 'Salary Account' })]);
    vi.mocked(accountsApi.update).mockRejectedValue({ response: { data: { message: 'Name is too long' } } });
    renderPage();

    const dialog = await openEdit('Salary Account');
    fireEvent.change(within(dialog).getByLabelText('Account name'), { target: { value: 'Renamed' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));

    expect(await within(dialog).findByText('Name is too long')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('does not save a blank name', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([account({ id: 'a1', name: 'Salary Account' })]);
    renderPage();

    const dialog = await openEdit('Salary Account');
    fireEvent.change(within(dialog).getByLabelText('Account name'), { target: { value: '   ' } });
    expect(within(dialog).getByRole('button', { name: 'Save' })).toBeDisabled();
  });

  it('keeps the bank read-only for a Bank Sync account, and never sends one for it', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([account({
      id: 'aa1', name: 'Synced', primarySource: 'ACCOUNT_AGGREGATOR', bank: { ...SBI } as never,
    })]);
    vi.mocked(accountsApi.update).mockResolvedValue(account({ id: 'aa1', name: 'Synced savings' }));
    renderPage();

    const dialog = await openEdit('Synced');
    expect(within(dialog).getByLabelText('Bank')).toBeDisabled();
    expect(within(dialog).getByText(/set by bank sync/i)).toBeInTheDocument();

    fireEvent.change(within(dialog).getByLabelText('Account name'), { target: { value: 'Synced savings' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(accountsApi.update).toHaveBeenCalledWith('aa1',
      { name: 'Synced savings', accountType: 'SAVINGS' }));
  });
});

describe('Setup — "Which bank is this?"', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    vi.mocked(banksApi.list).mockResolvedValue([{ ...HDFC, supportedAccountTypes: [] }]);
  });

  const imported = (overrides: Partial<Account> = {}) =>
    account({ id: 'imp1', name: 'Bank Statement Import', statementsCount: 1, lastImportedAt: '2026-10-20T10:00:00Z', ...overrides });
  const prompt = () => screen.queryByRole('note', { name: /which bank is/i });

  it('asks on an imported account with no bank', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([imported()]);
    renderPage();
    await screen.findByText('Bank Statement Import');
    expect(prompt()).toBeInTheDocument();
  });

  it('does not ask on a hand-made account, a Bank Sync account, or one with a bank', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([
      account({ id: 'man1', name: 'Cash Box' }),
      imported({ id: 'aa1', name: 'Synced', primarySource: 'ACCOUNT_AGGREGATOR' }),
      imported({ id: 'hd1', name: 'My HDFC', bank: { ...HDFC } as never }),
    ]);
    renderPage();
    await screen.findByText('My HDFC');
    expect(prompt()).not.toBeInTheDocument();
  });

  it('remembers a dismissal for that account', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([imported()]);
    const { unmount } = renderPage();
    await screen.findByText('Bank Statement Import');

    fireEvent.click(within(prompt()!).getByRole('button', { name: "Don't ask again" }));
    expect(prompt()).not.toBeInTheDocument();

    unmount();
    renderPage();
    await screen.findByText('Bank Statement Import');
    expect(prompt()).not.toBeInTheDocument();
  });

  it('opens the bank choice, and choosing a bank saves it', async () => {
    vi.mocked(accountsApi.list).mockResolvedValueOnce([imported()])
      .mockResolvedValue([imported({ bank: { ...HDFC } as never })]);
    vi.mocked(accountsApi.update).mockResolvedValue(imported({ bank: { ...HDFC } as never }));
    renderPage();
    await screen.findByText('Bank Statement Import');

    fireEvent.click(within(prompt()!).getByRole('button', { name: 'Choose bank' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByRole('heading', { name: 'Which bank is this?' })).toBeInTheDocument();
    const bank = within(dialog).getByLabelText('Bank');
    await waitFor(() => expect(within(bank).getByRole('option', { name: 'HDFC Bank' })).toBeInTheDocument());
    fireEvent.change(bank, { target: { value: 'HDFC' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(accountsApi.update).toHaveBeenCalledWith('imp1',
      { name: 'Bank Statement Import', accountType: 'SAVINGS', bankId: 'HDFC' }));
    await waitFor(() => expect(prompt()).not.toBeInTheDocument());
  });

  it('counts "Other bank" again as an answer, without a request', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([imported()]);
    renderPage();
    await screen.findByText('Bank Statement Import');

    fireEvent.click(within(prompt()!).getByRole('button', { name: 'Choose bank' }));
    const dialog = screen.getByRole('dialog');
    expect((within(dialog).getByLabelText('Bank') as HTMLSelectElement).value).toBe('OTHER');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(accountsApi.update).not.toHaveBeenCalled();
    expect(prompt()).not.toBeInTheDocument();
  });
});
