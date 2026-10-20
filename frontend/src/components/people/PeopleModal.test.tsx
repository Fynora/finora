import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { peopleApi, type BillShare } from '../../api/endpoints';
import type { Transaction } from '../../types';
import { PeopleModal, qualifiesForPeople } from './PeopleModal';

// Every name and amount here is invented.
vi.mock('../../api/endpoints', () => ({
  peopleApi: { billShares: vi.fn(), unlink: vi.fn(), addEntry: vi.fn(), personOf: vi.fn() },
}));
const refreshed = vi.fn();
vi.mock('../../lib/invalidateMoneyFigures', () => ({ useInvalidateMoneyFigures: () => refreshed }));

const billShares = vi.mocked(peopleApi.billShares);
const unlink = vi.mocked(peopleApi.unlink);
const addEntry = vi.mocked(peopleApi.addEntry);
const personOf = vi.mocked(peopleApi.personOf);

function txn(overrides: Partial<Transaction> = {}): Transaction {
  return {
    id: 'b-oct', accountId: 'a', categoryId: 'cat', categoryName: 'Rent', date: '2026-10-01', description: 'SAMPLE OWNER',
    merchant: 'sample owner', amount: 36000, type: 'EXPENSE', reconciliationStatus: 'OK', counterpartyType: 'PERSON',
    ...overrides,
  } as Transaction;
}
const share = (overrides: Partial<BillShare> = {}): BillShare => ({
  creditId: 'c-a', billId: 'b-oct', label: 'Flatmate A', date: '2026-10-04', amount: 9000, fromRule: false, ...overrides,
});

function show(t: Transaction) {
  const handlers = { onClose: vi.fn(), onShareOfABill: vi.fn(), onSplit: vi.fn(), onOpenPerson: vi.fn() };
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>
      <PeopleModal transaction={t} {...handlers} />
    </QueryClientProvider>,
  );
  return handlers;
}

const press = (name: string) => userEvent.click(screen.getByRole('button', { name }));
const refused = (message: string) => Object.assign(new Error(message), { response: { status: 422, data: { message } } });

beforeEach(() => {
  billShares.mockReset().mockResolvedValue([]);
  unlink.mockReset().mockResolvedValue(undefined);
  addEntry.mockReset().mockResolvedValue({} as never);
  personOf.mockReset().mockResolvedValue({ personKey: 'vpa:friend.k', label: 'Friend K' });
  refreshed.mockReset();
});

describe('qualifiesForPeople', () => {
  it('is any plain expense, or plain money from a person; never a transfer, a refund or a business credit', () => {
    expect(qualifiesForPeople(txn())).toBe(true);
    expect(qualifiesForPeople(txn({ counterpartyType: 'BUSINESS' }))).toBe(true);
    expect(qualifiesForPeople(txn({ type: 'INCOME' }))).toBe(true);
    expect(qualifiesForPeople(txn({ type: 'INCOME', counterpartyType: 'BUSINESS' }))).toBe(false);
    expect(qualifiesForPeople(txn({ reconciliationStatus: 'TRANSFER' }))).toBe(false);
  });
});

describe('PeopleModal: a bill', () => {
  it('shows what was paid, the user\'s share, and each share with Undo', async () => {
    billShares.mockResolvedValue([share(), share({ creditId: 'c-b', label: 'Flatmate B', date: '2026-10-03' })]);
    show(txn());
    expect(await screen.findByText('Your share ₹18,000')).toBeInTheDocument();
    expect(screen.getByText('Paid ₹36,000 · ₹18,000 came back')).toBeInTheDocument();
    expect(screen.getByText('Flatmate A · 4 Oct')).toBeInTheDocument();
    expect(screen.getAllByText('+₹9,000')).toHaveLength(2);
    await press('Undo the share from Flatmate A');
    await waitFor(() => expect(unlink).toHaveBeenCalledWith('c-a', 'b-oct'));
    expect(refreshed).toHaveBeenCalled();
  });

  it('never shows a share below zero, and a rule\'s share is removed from the person, not here', async () => {
    billShares.mockResolvedValue([share({ amount: 36000, fromRule: true })]);
    show(txn());
    expect(await screen.findByText('Your share ₹0')).toBeInTheDocument();
    expect(screen.getByText('Every month')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Undo the share from Flatmate A' })).toBeNull();
  });

  it('offers "Split this" on any expense, and "Lent" on a payment to a person', async () => {
    const { onSplit } = show(txn());
    await waitFor(() => expect(billShares).toHaveBeenCalledWith('b-oct'));
    await press('Split this');
    expect(onSplit).toHaveBeenCalledTimes(1);
    await press('Lent');
    await waitFor(() => expect(addEntry).toHaveBeenCalledWith('b-oct', 'LENT'));
    expect(await screen.findByText('Saved as lent.')).toBeInTheDocument();
  });

  it('offers no "Lent" on a payment to a business', async () => {
    show(txn({ counterpartyType: 'BUSINESS' }));
    await waitFor(() => expect(billShares).toHaveBeenCalled());
    expect(screen.getByRole('button', { name: 'Split this' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Lent' })).toBeNull();
    expect(personOf).not.toHaveBeenCalled();
  });

  it('opens the person\'s page from a payment with them, and offers none when the payee names no one person', async () => {
    const { onOpenPerson } = show(txn());
    await userEvent.click(await screen.findByRole('button', { name: 'See Friend K' }));
    expect(personOf).toHaveBeenCalledWith('b-oct');
    expect(onOpenPerson).toHaveBeenCalledWith('vpa:friend.k');
  });

  it('still offers the answers when the shares cannot be read', async () => {
    billShares.mockRejectedValue(new Error('older server'));
    personOf.mockRejectedValue(new Error('422'));
    show(txn());
    await waitFor(() => expect(billShares).toHaveBeenCalled());
    expect(screen.getByRole('button', { name: 'Split this' })).toBeInTheDocument();
    expect(screen.queryByText(/Your share/)).toBeNull();
    await waitFor(() => expect(personOf).toHaveBeenCalled());
    expect(screen.queryByRole('button', { name: /^See / })).toBeNull();
  });
});

describe('PeopleModal: money from a person', () => {
  const credit = txn({ id: 'c-a', type: 'INCOME', amount: 9000, date: '2026-10-04', categoryName: 'Friend Repayment' });

  it('offers a share of a bill, paid back and borrowed, and reads no shares', async () => {
    const { onShareOfABill } = show(credit);
    await press('Share of a bill');
    expect(onShareOfABill).toHaveBeenCalledTimes(1);
    await press('Paid back');
    await waitFor(() => expect(addEntry).toHaveBeenCalledWith('c-a', 'PAID_BACK'));
    expect(await screen.findByText('Saved as paid back.')).toBeInTheDocument();
    await press('Borrowed');
    await waitFor(() => expect(addEntry).toHaveBeenCalledWith('c-a', 'BORROWED'));
    expect(billShares).not.toHaveBeenCalled();
  });

  it('says why, in the server\'s words, when the server refuses the answer', async () => {
    addEntry.mockRejectedValueOnce(refused("Fynora can't tell this sender apart from others, so it can't keep this for them."));
    show(credit);
    await press('Borrowed');
    expect(await screen.findByText("Fynora can't tell this sender apart from others, so it can't keep this for them.")).toBeInTheDocument();
    expect(refreshed).not.toHaveBeenCalled();
  });
});
