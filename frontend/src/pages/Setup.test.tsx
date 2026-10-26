import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
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
