import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { insightsApi, peopleApi, transactionsApi, type BillOption } from '../../api/endpoints';
import { invalidateFinancialData } from '../../lib/invalidateFinancialData';
import type { Transaction } from '../../types';
import { BillPickerSheet } from './BillPickerSheet';

// Hoisted above the imports by babel-jest. Every name and amount here is invented.
jest.mock('../../api/endpoints', () => ({
  peopleApi: { link: jest.fn(), suggestions: jest.fn(), addSplitRule: jest.fn(), addEntry: jest.fn(), settle: jest.fn() },
  insightsApi: { acknowledge: jest.fn() },
  transactionsApi: { search: jest.fn(), updateCategory: jest.fn() },
  inflowApi: { setChoice: jest.fn() },
}));
jest.mock('../../lib/invalidateFinancialData', () => ({ invalidateFinancialData: jest.fn() }));
jest.mock('../../lib/useReduceTransparency', () => ({ useReduceTransparency: jest.fn(() => null) }));

const link = peopleApi.link as jest.Mock;
const suggestions = peopleApi.suggestions as jest.Mock;
const addSplitRule = peopleApi.addSplitRule as jest.Mock;
const acknowledge = insightsApi.acknowledge as jest.Mock;
const search = transactionsApi.search as jest.Mock;

const rentOct: BillOption = { billId: 'b-oct', category: 'Rent', payeeLabel: 'Sample Owner', date: '2026-10-01', amount: 36000, remaining: 36000 };
const rentSep: BillOption = { billId: 'b-sep', category: 'Rent', payeeLabel: 'Sample Owner', date: '2026-09-01', amount: 36000, remaining: 36000 };
const credit = { txnId: 'c-1', label: 'Flatmate A', amount: 9000, date: '2026-10-04' };

function txn(overrides: Partial<Transaction>): Transaction {
  return {
    id: 't', accountId: 'a', categoryId: 'cat', categoryName: 'Food', date: '2026-10-02', description: 'SAMPLE CAFE',
    merchant: 'sample cafe', paymentMethod: 'UPI', amount: 1200, type: 'EXPENSE', tags: [], notes: null,
    reconciliationStatus: 'OK', recurring: false, needsCategoryReview: false, pendingBankCorrection: false,
    categoryManuallySet: false, counterpartyType: 'BUSINESS', international: false,
    ...overrides,
  } as Transaction;
}

let client: QueryClient;

function show(props: { options?: BillOption[] | null } = {}) {
  const onClose = jest.fn();
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(
    <QueryClientProvider client={client}>
      <BillPickerSheet mode="bill" credit={credit} options={[rentOct, rentSep]} onClose={onClose} {...props} />
    </QueryClientProvider>,
  );
  return { onClose };
}

// How the server refuses an answer it cannot keep: an answered error with its own sentence.
const refused = (message: string) => Object.assign(new Error(message), {
  isAxiosError: true, response: { status: 422, data: { message, errorCode: 'TXN_007' } },
});

const press = (name: string) => fireEvent.press(screen.getByRole('button', { name }));

beforeEach(() => {
  link.mockReset().mockResolvedValue({ creditId: 'c-1', billId: 'b-oct', label: 'Flatmate A', date: '2026-10-04', amount: 9000, fromRule: false });
  suggestions.mockReset().mockResolvedValue({ items: [], expected: [] });
  addSplitRule.mockReset().mockResolvedValue({});
  acknowledge.mockReset().mockResolvedValue(undefined);
  search.mockReset().mockResolvedValue({ content: [], page: 0, size: 30, totalElements: 0, totalPages: 0 });
  (invalidateFinancialData as jest.Mock).mockReset();
});

describe('BillPickerSheet: which bill', () => {
  it('lists the bills in the order given, the first one chosen, and links it', async () => {
    show();
    expect(screen.getByText('Share of which bill?')).toBeTruthy();
    expect(screen.getByText('₹9,000 from Flatmate A · 4 Oct')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Rent, 1 Oct, Sample Owner, ₹36,000' }).props.accessibilityState).toMatchObject({ selected: true });
    expect(screen.getByRole('button', { name: 'Rent, 1 Sep, Sample Owner, ₹36,000' }).props.accessibilityState).toMatchObject({ selected: false });
    press('Link to 1 Oct Rent');
    await waitFor(() => expect(link).toHaveBeenCalledWith('c-1', 'b-oct'));
    await waitFor(() => expect(invalidateFinancialData).toHaveBeenCalled());
    expect(await screen.findByText('Linked.')).toBeTruthy();
    expect(screen.getByText('Your share of the 1 Oct Rent is now ₹27,000.')).toBeTruthy();
  });

  it('links the bill the user picks instead', async () => {
    show();
    press('Rent, 1 Sep, Sample Owner, ₹36,000');
    press('Link to 1 Sep Rent');
    await waitFor(() => expect(link).toHaveBeenCalledWith('c-1', 'b-sep'));
  });

  it('asks "every month?" in place when the server now suggests it for this credit', async () => {
    suggestions.mockResolvedValue({
      items: [{
        kind: 'EVERY_MONTH', ackKey: 'vpa:flatmate.a|cat', txnId: 'c-1', relatedTxnId: 'b-oct', personKey: 'vpa:flatmate.a',
        label: 'Flatmate A', amount: 9000, date: '2026-10-04', category: 'Rent', bills: [], owed: null,
      }],
      expected: [],
    });
    const { onClose } = show();
    press('Link to 1 Oct Rent');
    expect(await screen.findByText('Flatmate A sent a share in other months too. Link their Rent shares every month?')).toBeTruthy();
    press('Every month');
    await waitFor(() => expect(addSplitRule).toHaveBeenCalledWith('c-1', 'b-oct'));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it('"Just this one" is remembered, so it is not asked again', async () => {
    suggestions.mockResolvedValue({
      items: [{
        kind: 'EVERY_MONTH', ackKey: 'vpa:flatmate.a|cat', txnId: 'c-1', relatedTxnId: 'b-oct', personKey: 'vpa:flatmate.a',
        label: 'Flatmate A', amount: 9000, date: '2026-10-04', category: 'Rent', bills: [], owed: null,
      }],
      expected: [],
    });
    const { onClose } = show();
    press('Link to 1 Oct Rent');
    const refreshed = jest.spyOn(client, 'invalidateQueries');
    fireEvent.press(await screen.findByRole('button', { name: 'Just this one: Flatmate A' }));
    await waitFor(() => expect(acknowledge).toHaveBeenCalledWith('EVERY_MONTH', 'vpa:flatmate.a|cat'));
    expect(onClose).toHaveBeenCalled();
    // Insights shows the same question: it goes there too, once the server has the answer.
    await waitFor(() => expect(refreshed).toHaveBeenCalledWith({ queryKey: ['insight-acknowledgements'] }));
  });

  it('says why, in the server\'s words, when the server refuses the link', async () => {
    link.mockRejectedValueOnce(refused('Nothing is left of this bill or this payment to link.'));
    show();
    press('Link to 1 Oct Rent');
    expect(await screen.findByText('Nothing is left of this bill or this payment to link.')).toBeTruthy();
  });

  it('says so when linking fails, and stays open to try again', async () => {
    link.mockRejectedValueOnce(new Error('offline'));
    const { onClose } = show();
    press('Link to 1 Oct Rent');
    expect(await screen.findByText("Couldn't link it. Try again.")).toBeTruthy();
    expect(onClose).not.toHaveBeenCalled();
    expect(invalidateFinancialData).not.toHaveBeenCalled();
  });

  it('looks up the expenses two weeks either side when it is given no bills', async () => {
    search.mockResolvedValue({
      content: [txn({ id: 'e-1', date: '2026-10-02' }), txn({ id: 'e-2', date: '2026-10-10', categoryName: 'Travel', merchant: 'sample cabs', description: 'SAMPLE CABS' })],
      page: 0, size: 50, totalElements: 2, totalPages: 1,
    });
    show({ options: null });
    await waitFor(() => expect(search).toHaveBeenCalledWith(expect.objectContaining({
      type: 'EXPENSE', dateFrom: '2026-09-20', dateTo: '2026-10-18',
    })));
    expect(await screen.findByRole('button', { name: /^Food, 2 Oct/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: /^Travel, 10 Oct/ })).toBeTruthy();
  });

  it('"Something else" searches every expense and links the one picked', async () => {
    search.mockResolvedValue({
      content: [txn({ id: 'dep', date: '2026-03-01', categoryName: 'Rent', amount: 72000, merchant: 'sample owner', description: 'DEPOSIT' })],
      page: 0, size: 30, totalElements: 1, totalPages: 1,
    });
    show();
    press('Something else');
    fireEvent.changeText(screen.getByLabelText('Search your payments'), 'deposit');
    await waitFor(() => expect(search).toHaveBeenLastCalledWith(expect.objectContaining({ type: 'EXPENSE', keyword: 'deposit' })));
    fireEvent.press(await screen.findByRole('button', { name: /^Rent, 1 Mar/ }));
    press('Link to 1 Mar Rent');
    await waitFor(() => expect(link).toHaveBeenCalledWith('c-1', 'dep'));
  });
});

describe('BillPickerSheet: who paid a share', () => {
  it('lists money from people two weeks either side of the bill and links the one picked', async () => {
    search.mockResolvedValue({
      content: [
        txn({ id: 'in-1', type: 'INCOME', date: '2026-10-04', counterpartyType: 'PERSON', merchant: 'flatmate a', description: 'UPI FLATMATE A', amount: 9000, categoryName: 'Rent' }),
        txn({ id: 'in-2', type: 'INCOME', date: '2026-10-05', counterpartyType: 'BUSINESS', merchant: 'sample employer', amount: 50000, categoryName: 'Salary' }),
      ],
      page: 0, size: 50, totalElements: 2, totalPages: 1,
    });
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>
        <BillPickerSheet mode="credit" bill={{ txnId: 'b-oct', label: 'Sample Owner', amount: 36000, date: '2026-10-01', category: 'Rent' }} onClose={jest.fn()} />
      </QueryClientProvider>,
    );
    expect(screen.getByText('Who paid a share?')).toBeTruthy();
    await waitFor(() => expect(search).toHaveBeenCalledWith(expect.objectContaining({
      type: 'INCOME', dateFrom: '2026-09-17', dateTo: '2026-10-15',
    })));
    const row = await screen.findByRole('button', { name: /^Rent, 4 Oct/ });
    expect(screen.queryByRole('button', { name: /^Salary/ })).toBeNull();
    fireEvent.press(row);
    press('Link 4 Oct ₹9,000');
    await waitFor(() => expect(link).toHaveBeenCalledWith('in-1', 'b-oct'));
  });
});
