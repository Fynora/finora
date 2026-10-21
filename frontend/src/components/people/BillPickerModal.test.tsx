import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { insightsApi, peopleApi, transactionsApi, type BillOption } from '../../api/endpoints';
import type { Transaction } from '../../types';
import { BillPickerModal } from './BillPickerModal';

// Every name and amount here is invented.
vi.mock('../../api/endpoints', () => ({
  peopleApi: { link: vi.fn(), suggestions: vi.fn(), addSplitRule: vi.fn(), addEntry: vi.fn(), settle: vi.fn() },
  insightsApi: { acknowledge: vi.fn() },
  transactionsApi: { search: vi.fn(), updateCategory: vi.fn() },
  inflowApi: { setChoice: vi.fn() },
}));
const refreshed = vi.fn();
vi.mock('../../lib/invalidateMoneyFigures', () => ({ useInvalidateMoneyFigures: () => refreshed }));

const link = vi.mocked(peopleApi.link);
const suggestions = vi.mocked(peopleApi.suggestions);
const addSplitRule = vi.mocked(peopleApi.addSplitRule);
const acknowledge = vi.mocked(insightsApi.acknowledge);
const search = vi.mocked(transactionsApi.search);

const rentOct: BillOption = { billId: 'b-oct', category: 'Rent', payeeLabel: 'Sample Owner', date: '2026-10-01', amount: 36000, remaining: 36000 };
const rentSep: BillOption = { billId: 'b-sep', category: 'Rent', payeeLabel: 'Sample Owner', date: '2026-09-01', amount: 36000, remaining: 36000 };
const credit = { txnId: 'c-1', label: 'Flatmate A', amount: 9000, date: '2026-10-04' };
const everyMonth = {
  kind: 'EVERY_MONTH' as const, ackKey: 'vpa:flatmate.a|cat', txnId: 'c-1', relatedTxnId: 'b-oct', personKey: 'vpa:flatmate.a',
  label: 'Flatmate A', amount: 9000, date: '2026-10-04', category: 'Rent', bills: [], owed: null,
};

function txn(overrides: Partial<Transaction>): Transaction {
  return {
    id: 't', accountId: 'a', categoryId: 'cat', categoryName: 'Food', date: '2026-10-02', description: 'SAMPLE CAFE',
    merchant: 'sample cafe', amount: 1200, type: 'EXPENSE', counterpartyType: 'BUSINESS', reconciliationStatus: 'OK',
    ...overrides,
  } as Transaction;
}
const page = (content: Transaction[]) => ({ content, page: 0, size: 50, totalElements: content.length, totalPages: 1 });

let client: QueryClient;
function show(props: { options?: BillOption[] | null } = {}) {
  const onClose = vi.fn();
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(
    <QueryClientProvider client={client}>
      <BillPickerModal mode="bill" credit={credit} options={[rentOct, rentSep]} onClose={onClose} {...props} />
    </QueryClientProvider>,
  );
  return { onClose };
}

const press = (name: string | RegExp) => userEvent.click(screen.getByRole('button', { name }));
const refused = (message: string) => Object.assign(new Error(message), { response: { status: 422, data: { message } } });

beforeEach(() => {
  link.mockReset().mockResolvedValue({ creditId: 'c-1', billId: 'b-oct', label: 'Flatmate A', date: '2026-10-04', amount: 9000, fromRule: false });
  suggestions.mockReset().mockResolvedValue({ items: [], expected: [] });
  addSplitRule.mockReset().mockResolvedValue({} as never);
  acknowledge.mockReset().mockResolvedValue(undefined);
  search.mockReset().mockResolvedValue(page([]) as never);
  refreshed.mockReset();
});

describe('BillPickerModal: which bill', () => {
  it('lists the bills in the order given, the first one chosen, and links it', async () => {
    show();
    expect(screen.getByRole('heading', { name: 'Share of which bill?' })).toBeInTheDocument();
    expect(screen.getByText('₹9,000 from Flatmate A · 4 Oct')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Rent, 1 Oct, Sample Owner, ₹36,000' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Rent, 1 Sep, Sample Owner, ₹36,000' })).not.toBeChecked();
    await press('Link to 1 Oct Rent');
    await waitFor(() => expect(link).toHaveBeenCalledWith('c-1', 'b-oct'));
    expect(refreshed).toHaveBeenCalled();
    expect(await screen.findByText('Linked.')).toBeInTheDocument();
    expect(screen.getByText('Your share of the 1 Oct Rent is now ₹27,000.')).toBeInTheDocument();
  });

  it('links the bill the user picks instead', async () => {
    show();
    await userEvent.click(screen.getByRole('radio', { name: 'Rent, 1 Sep, Sample Owner, ₹36,000' }));
    await press('Link to 1 Sep Rent');
    await waitFor(() => expect(link).toHaveBeenCalledWith('c-1', 'b-sep'));
  });

  it('asks "every month?" in place when the server now suggests it for this credit', async () => {
    suggestions.mockResolvedValue({ items: [everyMonth], expected: [] });
    const { onClose } = show();
    await press('Link to 1 Oct Rent');
    expect(await screen.findByText('Flatmate A sent a share in other months too. Link their Rent shares every month?')).toBeInTheDocument();
    await press('Every month');
    await waitFor(() => expect(addSplitRule).toHaveBeenCalledWith('c-1', 'b-oct'));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it('"Just this one" is remembered, and the page\'s own questions refresh', async () => {
    suggestions.mockResolvedValue({ items: [everyMonth], expected: [] });
    const { onClose } = show();
    await press('Link to 1 Oct Rent');
    const refreshedKeys = vi.spyOn(client, 'invalidateQueries');
    await userEvent.click(await screen.findByRole('button', { name: 'Just this one: Flatmate A' }));
    await waitFor(() => expect(acknowledge).toHaveBeenCalledWith('EVERY_MONTH', 'vpa:flatmate.a|cat'));
    expect(onClose).toHaveBeenCalled();
    await waitFor(() => expect(refreshedKeys).toHaveBeenCalledWith({ queryKey: ['insight-acknowledgements'] }));
  });

  it('says why, in the server\'s words, when the server refuses the link', async () => {
    link.mockRejectedValueOnce(refused('Nothing is left of this bill or this payment to link.'));
    show();
    await press('Link to 1 Oct Rent');
    expect(await screen.findByText('Nothing is left of this bill or this payment to link.')).toBeInTheDocument();
  });

  it('says so when linking fails, and stays open to try again', async () => {
    link.mockRejectedValueOnce(new Error('offline'));
    const { onClose } = show();
    await press('Link to 1 Oct Rent');
    expect(await screen.findByText("Couldn't link it. Try again.")).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    expect(refreshed).not.toHaveBeenCalled();
  });

  it('looks up the expenses two weeks either side when it is given no bills', async () => {
    search.mockResolvedValue(page([
      txn({ id: 'e-1', date: '2026-10-02' }),
      txn({ id: 'e-2', date: '2026-10-10', categoryName: 'Travel', merchant: 'sample cabs', description: 'SAMPLE CABS' }),
    ]) as never);
    show({ options: null });
    await waitFor(() => expect(search).toHaveBeenCalledWith(expect.objectContaining({
      type: 'EXPENSE', dateFrom: '2026-09-20', dateTo: '2026-10-18',
    })));
    expect(await screen.findByRole('radio', { name: /^Food, 2 Oct/ })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /^Travel, 10 Oct/ })).toBeInTheDocument();
  });

  it('never offers a transfer, refund or reversal: it is not spending, and the server refuses it', async () => {
    search.mockResolvedValue(page([
      txn({ id: 'e-1', date: '2026-10-02' }),
      txn({ id: 'tr', date: '2026-10-03', categoryName: 'Transfer', reconciliationStatus: 'TRANSFER' }),
      txn({ id: 'rv', date: '2026-10-05', categoryName: 'Rent', reconciliationStatus: 'REVERSAL' }),
    ]) as never);
    show({ options: null });
    expect(await screen.findByRole('radio', { name: /^Food, 2 Oct/ })).toBeInTheDocument();
    expect(screen.getAllByRole('radio')).toHaveLength(1);
  });

  it('"Something else" searches every expense and links the one picked', async () => {
    search.mockResolvedValue(page([
      txn({ id: 'dep', date: '2026-03-01', categoryName: 'Rent', amount: 72000, merchant: 'sample owner', description: 'DEPOSIT' }),
    ]) as never);
    show();
    await press('Something else');
    await userEvent.type(screen.getByRole('textbox', { name: 'Search your payments' }), 'deposit');
    await waitFor(() => expect(search).toHaveBeenLastCalledWith(expect.objectContaining({ type: 'EXPENSE', keyword: 'deposit' })));
    await userEvent.click(await screen.findByRole('radio', { name: /^Rent, 1 Mar/ }));
    await press('Link to 1 Mar Rent');
    await waitFor(() => expect(link).toHaveBeenCalledWith('c-1', 'dep'));
  });
});

describe('BillPickerModal: who paid a share', () => {
  it('lists money from people two weeks either side of the bill and links the one picked', async () => {
    search.mockResolvedValue(page([
      txn({ id: 'in-1', type: 'INCOME', date: '2026-10-04', counterpartyType: 'PERSON', merchant: 'flatmate a', description: 'UPI FLATMATE A', amount: 9000, categoryName: 'Rent' }),
      txn({ id: 'in-2', type: 'INCOME', date: '2026-10-05', counterpartyType: 'BUSINESS', merchant: 'sample employer', amount: 50000, categoryName: 'Salary' }),
    ]) as never);
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>
        <BillPickerModal mode="credit" bill={{ txnId: 'b-oct', label: 'Sample Owner', amount: 36000, date: '2026-10-01', category: 'Rent' }} onClose={vi.fn()} />
      </QueryClientProvider>,
    );
    expect(screen.getByRole('heading', { name: 'Who paid a share?' })).toBeInTheDocument();
    await waitFor(() => expect(search).toHaveBeenCalledWith(expect.objectContaining({
      type: 'INCOME', dateFrom: '2026-09-17', dateTo: '2026-10-15',
    })));
    const row = await screen.findByRole('radio', { name: /^Rent, 4 Oct/ });
    expect(screen.queryByRole('radio', { name: /^Salary/ })).toBeNull();
    await userEvent.click(row);
    await press('Link 4 Oct ₹9,000');
    await waitFor(() => expect(link).toHaveBeenCalledWith('in-1', 'b-oct'));
  });
});
