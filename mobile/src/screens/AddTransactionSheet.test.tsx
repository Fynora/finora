import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AddTransactionSheet } from './AddTransactionSheet';
import { accountsApi, categoriesApi, transactionsApi } from '../api/endpoints';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';

jest.mock('../api/endpoints', () => ({
  transactionsApi: { create: jest.fn() },
  accountsApi: { list: jest.fn() },
  categoriesApi: { list: jest.fn(), options: jest.fn() },
}));

jest.mock('../lib/invalidateFinancialData', () => ({
  invalidateFinancialData: jest.fn(),
}));

jest.mock('../lib/idempotencyKey', () => ({
  newIdempotencyKey: jest.fn(() => 'key-1'),
}));

const transactions = transactionsApi as jest.Mocked<typeof transactionsApi>;
const accounts = accountsApi as jest.Mocked<typeof accountsApi>;
const categories = categoriesApi as jest.Mocked<typeof categoriesApi>;

const ACCOUNTS = [
  { id: 'a-1', name: 'HDFC Savings' },
  { id: 'a-2', name: 'ICICI Credit Card' },
] as never;

const onClose = jest.fn();
const onSaved = jest.fn();

function renderSheet() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AddTransactionSheet onClose={onClose} onSaved={onSaved} />
    </QueryClientProvider>
  );
}

async function settle() {
  await act(async () => {});
}

beforeEach(() => {
  onClose.mockReset();
  onSaved.mockReset();
  transactions.create.mockReset();
  accounts.list.mockReset().mockResolvedValue(ACCOUNTS);
  categories.list.mockReset().mockResolvedValue([{ id: 'c-1', name: 'Food', isSystem: true, icon: 'utensils', color: 'orange' }]);
  categories.options.mockReset().mockResolvedValue({ icons: [], colors: [] });
  (invalidateFinancialData as jest.Mock).mockReset();
});

describe('AddTransactionSheet', () => {
  it('tells the user to import a statement or add an account when there is none to attach to', async () => {
    accounts.list.mockResolvedValue([]);
    renderSheet();
    await settle();

    expect(await screen.findByText(/Import a statement or add an account first/)).toBeTruthy();
    expect(screen.queryByLabelText('Description')).toBeNull();
  });

  it('defaults to the first account, today, and Expense', async () => {
    renderSheet();
    await settle();

    expect(await screen.findByText('HDFC Savings')).toBeTruthy();
    expect(screen.getByRole('button', { name: /^Expense$/ }).props.accessibilityState.selected).toBe(true);
  });

  it('defaults the date to today in the device timezone, not a UTC-shifted day', async () => {
    // Regression test for the bug fixed alongside LedgerScreen's day grouping (commit
    // f33fa4aa, "fix(mobile): correct timezone bug and non-adjacent same-date splitting in day
    // grouping"): `new Date().toISOString().slice(0, 10)` converts to UTC before slicing, so for
    // anyone east of UTC (IST included) the window between local midnight and UTC catching up to
    // the same calendar day silently defaults to yesterday. Pin TZ ahead of UTC and the clock
    // inside that window so this fails against the old code regardless of the machine running it.
    const originalTz = process.env.TZ;
    process.env.TZ = 'Asia/Kolkata';
    jest.useFakeTimers();
    jest.setSystemTime(new Date('2026-09-12T01:00:00'));
    try {
      renderSheet();
      await settle();
      await screen.findByText('HDFC Savings');

      expect(screen.getByLabelText('Date: 12 Sept 2026. Change')).toBeTruthy();
    } finally {
      jest.useRealTimers();
      process.env.TZ = originalTz;
    }
  });

  it('creates the transaction with a minted idempotency key and no category by default', async () => {
    transactions.create.mockResolvedValue({} as never);
    renderSheet();
    await settle();
    await screen.findByText('HDFC Savings');

    fireEvent.changeText(screen.getByLabelText('Description'), 'Groceries at the market');
    fireEvent.changeText(screen.getByLabelText('Amount'), '500');
    fireEvent.press(screen.getByRole('button', { name: /^Add Transaction$/ }));
    await settle();

    await waitFor(() => expect(transactions.create).toHaveBeenCalledWith(expect.objectContaining({
      accountId: 'a-1',
      idempotencyKey: 'key-1',
      description: 'Groceries at the market',
      amount: 500,
      type: 'EXPENSE',
      categoryName: null,
      tags: [],
    })));
    expect(invalidateFinancialData).toHaveBeenCalled();
    expect(onSaved).toHaveBeenCalled();
  });

  it('reuses the same idempotency key on a retry after a failure', async () => {
    transactions.create
      .mockRejectedValueOnce(new Error('timeout'))
      .mockResolvedValueOnce({} as never);
    renderSheet();
    await settle();
    await screen.findByText('HDFC Savings');

    fireEvent.changeText(screen.getByLabelText('Description'), 'Groceries');
    fireEvent.changeText(screen.getByLabelText('Amount'), '500');
    fireEvent.press(screen.getByRole('button', { name: /^Add Transaction$/ }));
    await settle();
    expect(await screen.findByText('Could not add this transaction.')).toBeTruthy();

    fireEvent.press(screen.getByRole('button', { name: /^Add Transaction$/ }));
    await settle();

    expect(transactions.create).toHaveBeenNthCalledWith(1, expect.objectContaining({ idempotencyKey: 'key-1' }));
    expect(transactions.create).toHaveBeenNthCalledWith(2, expect.objectContaining({ idempotencyKey: 'key-1' }));
  });

  it('switches the selected account via the account picker', async () => {
    renderSheet();
    await settle();
    await screen.findByText('HDFC Savings');

    fireEvent.press(screen.getByLabelText('Account'));
    await settle();
    fireEvent.press(screen.getByText('ICICI Credit Card'));
    await settle();

    expect(screen.getByLabelText('Account')).toHaveTextContent('ICICI Credit Card');
  });

  it('picks a category via the category picker', async () => {
    renderSheet();
    await settle();
    await screen.findByText('HDFC Savings');

    fireEvent.press(screen.getByLabelText('Category'));
    await settle();
    fireEvent.press(await screen.findByTestId('category-Food'));
    await settle();

    expect(screen.getByText('Food')).toBeTruthy();
  });

  it('disables Add Transaction until description and a positive amount are both present', async () => {
    renderSheet();
    await settle();
    await screen.findByText('HDFC Savings');

    expect(screen.getByRole('button', { name: /^Add Transaction$/ }).props.accessibilityState.disabled).toBe(true);

    fireEvent.changeText(screen.getByLabelText('Description'), 'Coffee');
    expect(screen.getByRole('button', { name: /^Add Transaction$/ }).props.accessibilityState.disabled).toBe(true);

    fireEvent.changeText(screen.getByLabelText('Amount'), '150');
    expect(screen.getByRole('button', { name: /^Add Transaction$/ }).props.accessibilityState.disabled).toBe(false);
  });
});
