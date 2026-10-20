import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { peopleApi, type BillShare } from '../../api/endpoints';
import { invalidateFinancialData } from '../../lib/invalidateFinancialData';
import type { Transaction } from '../../types';
import { PeopleTransactionSection } from './PeopleTransactionSection';

// Hoisted above the imports by babel-jest. Every name and amount here is invented.
jest.mock('../../api/endpoints', () => ({
  peopleApi: { billShares: jest.fn(), unlink: jest.fn(), addEntry: jest.fn(), personOf: jest.fn() },
}));
jest.mock('../../lib/invalidateFinancialData', () => ({ invalidateFinancialData: jest.fn() }));

const billShares = peopleApi.billShares as jest.Mock;
const unlink = peopleApi.unlink as jest.Mock;
const addEntry = peopleApi.addEntry as jest.Mock;
const personOf = peopleApi.personOf as jest.Mock;

function txn(overrides: Partial<Transaction> = {}): Transaction {
  return {
    id: 'b-oct', accountId: 'a', categoryId: 'cat', categoryName: 'Rent', date: '2026-10-01', description: 'SAMPLE OWNER',
    merchant: 'sample owner', paymentMethod: 'UPI', amount: 36000, type: 'EXPENSE', tags: [], notes: null,
    reconciliationStatus: 'OK', recurring: true, needsCategoryReview: false, pendingBankCorrection: false,
    categoryManuallySet: true, counterpartyType: 'PERSON', international: false,
    ...overrides,
  } as Transaction;
}

const share = (overrides: Partial<BillShare> = {}): BillShare => ({
  creditId: 'c-a', billId: 'b-oct', label: 'Flatmate A', date: '2026-10-04', amount: 9000, fromRule: false, ...overrides,
});

function show(t: Transaction) {
  const handlers = { onShareOfABill: jest.fn(), onSplit: jest.fn(), onOpenPerson: jest.fn() };
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>
      <PeopleTransactionSection transaction={t} {...handlers} />
    </QueryClientProvider>,
  );
  return handlers;
}

// How the server refuses an answer it cannot keep: an answered error with its own sentence.
const refused = (message: string) => Object.assign(new Error(message), {
  isAxiosError: true, response: { status: 422, data: { message, errorCode: 'TXN_007' } },
});

const press = (name: string) => fireEvent.press(screen.getByRole('button', { name }));

beforeEach(() => {
  billShares.mockReset().mockResolvedValue([]);
  unlink.mockReset().mockResolvedValue(undefined);
  addEntry.mockReset().mockResolvedValue({});
  personOf.mockReset().mockResolvedValue({ personKey: 'vpa:friend.k', label: 'Friend K' });
  (invalidateFinancialData as jest.Mock).mockReset();
});

describe('PeopleTransactionSection: a bill', () => {
  it('shows what was paid, the user\'s share, and each share with Undo', async () => {
    billShares.mockResolvedValue([share(), share({ creditId: 'c-b', label: 'Flatmate B', date: '2026-10-03' })]);
    show(txn());
    expect(await screen.findByText('Your share ₹18,000')).toBeTruthy();
    expect(screen.getByText('Paid ₹36,000 · ₹18,000 came back')).toBeTruthy();
    expect(screen.getByText('Flatmate A · 4 Oct')).toBeTruthy();
    expect(screen.getAllByText('+₹9,000')).toHaveLength(2);
    press('Undo the share from Flatmate A');
    await waitFor(() => expect(unlink).toHaveBeenCalledWith('c-a', 'b-oct'));
    await waitFor(() => expect(invalidateFinancialData).toHaveBeenCalled());
  });

  it('never shows a share below zero, and a rule\'s share is removed from the person, not here', async () => {
    billShares.mockResolvedValue([share({ amount: 36000, fromRule: true })]);
    show(txn());
    expect(await screen.findByText('Your share ₹0')).toBeTruthy();
    expect(screen.getByText('Every month')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Undo the share from Flatmate A' })).toBeNull();
  });

  it('offers "Split this" on any expense, and "Lent" on a payment to a person', async () => {
    const { onSplit } = show(txn());
    await waitFor(() => expect(billShares).toHaveBeenCalledWith('b-oct'));
    press('Split this');
    expect(onSplit).toHaveBeenCalledTimes(1);
    press('Lent');
    await waitFor(() => expect(addEntry).toHaveBeenCalledWith('b-oct', 'LENT'));
    expect(await screen.findByText('Saved as lent.')).toBeTruthy();
  });

  it('offers no "Lent" on a payment to a business', async () => {
    show(txn({ counterpartyType: 'BUSINESS' }));
    await waitFor(() => expect(billShares).toHaveBeenCalled());
    expect(screen.getByRole('button', { name: 'Split this' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Lent' })).toBeNull();
  });

  it('still offers the answers when the shares cannot be read', async () => {
    billShares.mockRejectedValue(new Error('older server'));
    show(txn());
    await waitFor(() => expect(billShares).toHaveBeenCalled());
    expect(screen.getByRole('button', { name: 'Split this' })).toBeTruthy();
    expect(screen.queryByText(/Your share/)).toBeNull();
  });
});

describe('PeopleTransactionSection: the person', () => {
  it('opens the person\'s page from a payment with them', async () => {
    const { onOpenPerson } = show(txn());
    fireEvent.press(await screen.findByRole('button', { name: 'See Friend K' }));
    expect(personOf).toHaveBeenCalledWith('b-oct');
    expect(onOpenPerson).toHaveBeenCalledWith('vpa:friend.k');
  });

  it('offers no page when the payee does not name one person, or for a business', async () => {
    personOf.mockRejectedValue(new Error('422'));
    show(txn());
    await waitFor(() => expect(personOf).toHaveBeenCalled());
    expect(screen.queryByRole('button', { name: /^See / })).toBeNull();
    personOf.mockClear();
    show(txn({ id: 'biz', counterpartyType: 'BUSINESS' }));
    await waitFor(() => expect(billShares).toHaveBeenCalledWith('biz'));
    expect(personOf).not.toHaveBeenCalled();
  });
});

describe('PeopleTransactionSection: money from a person', () => {
  const credit = txn({ id: 'c-a', type: 'INCOME', amount: 9000, date: '2026-10-04', categoryName: 'Friend Repayment' });

  it('offers a share of a bill, paid back and borrowed, and reads no shares', async () => {
    const { onShareOfABill } = show(credit);
    press('Share of a bill');
    expect(onShareOfABill).toHaveBeenCalledTimes(1);
    press('Paid back');
    await waitFor(() => expect(addEntry).toHaveBeenCalledWith('c-a', 'PAID_BACK'));
    expect(await screen.findByText('Saved as paid back.')).toBeTruthy();
    press('Borrowed');
    await waitFor(() => expect(addEntry).toHaveBeenCalledWith('c-a', 'BORROWED'));
    expect(billShares).not.toHaveBeenCalled();
  });

  it('says why, in the server\'s words, when the server refuses the answer', async () => {
    addEntry.mockRejectedValueOnce(refused("Fynora can't tell this sender apart from others, so it can't keep this for them."));
    show(credit);
    press('Borrowed');
    expect(await screen.findByText("Fynora can't tell this sender apart from others, so it can't keep this for them.")).toBeTruthy();
  });

  it('says so when an answer fails', async () => {
    addEntry.mockRejectedValueOnce(new Error('offline'));
    show(credit);
    press('Paid back');
    expect(await screen.findByText("Couldn't save that. Try again.")).toBeTruthy();
    expect(invalidateFinancialData).not.toHaveBeenCalled();
  });

  it('shows nothing for money from a business', () => {
    show(txn({ type: 'INCOME', counterpartyType: 'BUSINESS' }));
    expect(screen.queryByRole('button', { name: 'Share of a bill' })).toBeNull();
    expect(screen.queryByText('Shared with people')).toBeNull();
  });
});
