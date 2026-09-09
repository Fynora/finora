import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { EditTransactionSheet } from './EditTransactionSheet';
import { categoriesApi, transactionsApi } from '../api/endpoints';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import type { Transaction } from '../types';

jest.mock('../api/endpoints', () => ({
  transactionsApi: { update: jest.fn() },
  categoriesApi: { list: jest.fn(), options: jest.fn() },
}));

jest.mock('../lib/invalidateFinancialData', () => ({
  invalidateFinancialData: jest.fn(),
}));

const transactions = transactionsApi as jest.Mocked<typeof transactionsApi>;
const categories = categoriesApi as jest.Mocked<typeof categoriesApi>;

const TXN: Transaction = {
  id: 't-1',
  accountId: 'a-1',
  categoryId: 'c-1',
  categoryName: 'Food',
  date: '2026-07-14',
  description: 'Grocery run',
  merchant: 'Big Bazaar',
  paymentMethod: 'CARD',
  // Signed, matching the real read model (negative for an EXPENSE) -- see LedgerScreen's own
  // Math.abs()+sign-prefix rendering. Deliberately NOT a positive number here: that would hide
  // exactly the bug this fixture exists to catch (the Amount field must show and submit the
  // positive 1250 a human -- and the backend's requireAmountWithinBounds -- both expect).
  amount: -1250,
  type: 'EXPENSE',
  tags: ['shared'],
  notes: 'Split with roommate',
  reconciliationStatus: 'OK',
  recurring: false,
  needsCategoryReview: false,
  categoryManuallySet: false,
} as Transaction;

const onClose = jest.fn();
const onSaved = jest.fn();

function renderSheet(transaction: Transaction = TXN) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <EditTransactionSheet transaction={transaction} onClose={onClose} onSaved={onSaved} />
    </QueryClientProvider>
  );
}

async function settle() {
  await act(async () => {});
}

beforeEach(() => {
  onClose.mockReset();
  onSaved.mockReset();
  transactions.update.mockReset();
  categories.list.mockReset().mockResolvedValue([
    { id: 'c-1', name: 'Food', isSystem: true, icon: 'utensils', color: 'orange' },
    { id: 'c-2', name: 'Travel', isSystem: false, icon: 'plane', color: 'blue' },
  ]);
  categories.options.mockReset().mockResolvedValue({ icons: [], colors: [] });
  (invalidateFinancialData as jest.Mock).mockReset();
});

describe('EditTransactionSheet', () => {
  it('seeds every field from the transaction', () => {
    renderSheet();

    expect(screen.getByLabelText('Description').props.value).toBe('Grocery run');
    expect(screen.getByLabelText('Merchant').props.value).toBe('Big Bazaar');
    // Positive, even though TXN.amount is -1250 -- the field shows what a human means by
    // "amount"; the sign is carried by the Type toggle instead, not this field.
    expect(screen.getByLabelText('Amount').props.value).toBe('1250');
    expect(screen.getByLabelText('Notes').props.value).toBe('Split with roommate');
    expect(screen.getByLabelText('Tags (comma-separated)').props.value).toBe('shared');
    expect(screen.getByText('Food')).toBeTruthy();
    expect(
      screen.getByRole('button', { name: /Expense/ }).props.accessibilityState.selected
    ).toBe(true);
  });

  it('saves every field, including notes and tags at their literal current value', async () => {
    transactions.update.mockResolvedValue({ ...TXN, description: 'Grocery run (updated)' });
    renderSheet();

    fireEvent.changeText(screen.getByLabelText('Description'), 'Grocery run (updated)');
    fireEvent.changeText(screen.getByLabelText('Tags (comma-separated)'), 'shared, recurring');
    fireEvent.press(screen.getByRole('button', { name: /^Save Changes$/ }));
    await settle();

    await waitFor(() => expect(transactions.update).toHaveBeenCalledWith('t-1', {
      date: '2026-07-14',
      description: 'Grocery run (updated)',
      merchant: 'Big Bazaar',
      // Positive, even though TXN.amount above is -1250 -- this is the fix under test: the
      // backend's requireAmountWithinBounds rejects anything <= 0 regardless of type.
      amount: 1250,
      type: 'EXPENSE',
      categoryName: 'Food',
      notes: 'Split with roommate',
      tags: ['shared', 'recurring'],
    }));
    expect(invalidateFinancialData).toHaveBeenCalled();
    expect(onSaved).toHaveBeenCalled();
  });

  it('sends an explicit empty string for notes, not omitting the field, when cleared', async () => {
    transactions.update.mockResolvedValue(TXN);
    renderSheet();

    fireEvent.changeText(screen.getByLabelText('Notes'), '');
    fireEvent.press(screen.getByRole('button', { name: /^Save Changes$/ }));
    await settle();

    await waitFor(() => expect(transactions.update).toHaveBeenCalledWith('t-1', expect.objectContaining({ notes: '' })));
  });

  it('switches type between Expense and Income', () => {
    renderSheet();

    fireEvent.press(screen.getByRole('button', { name: /^Income$/ }));

    expect(screen.getByRole('button', { name: /^Income$/ }).props.accessibilityState.selected).toBe(true);
    expect(screen.getByRole('button', { name: /^Expense$/ }).props.accessibilityState.selected).toBe(false);
  });

  it('disables Save when the amount is zero or blank', () => {
    renderSheet();

    fireEvent.changeText(screen.getByLabelText('Amount'), '0');
    expect(screen.getByRole('button', { name: /^Save Changes$/ }).props.accessibilityState.disabled).toBe(true);
    expect(screen.getByText('Amount must be greater than zero.')).toBeTruthy();

    fireEvent.changeText(screen.getByLabelText('Amount'), '');
    expect(screen.getByRole('button', { name: /^Save Changes$/ }).props.accessibilityState.disabled).toBe(true);
  });

  it('disables Save when the description is blank', () => {
    renderSheet();

    fireEvent.changeText(screen.getByLabelText('Description'), '   ');

    expect(screen.getByRole('button', { name: /^Save Changes$/ }).props.accessibilityState.disabled).toBe(true);
  });

  it('shows an error and does not close when the update request fails', async () => {
    transactions.update.mockRejectedValue(
      Object.assign(new Error('bad'), { isAxiosError: true, response: { status: 400, data: { message: 'Amount must be a valid money value.' } } })
    );
    renderSheet();

    fireEvent.press(screen.getByRole('button', { name: /^Save Changes$/ }));
    await settle();

    expect(await screen.findByText('Amount must be a valid money value.')).toBeTruthy();
    expect(onSaved).not.toHaveBeenCalled();
  });

  it('updates the category once one is picked from the category picker', async () => {
    renderSheet();

    fireEvent.press(screen.getByLabelText('Category'));
    await settle();
    fireEvent.press(await screen.findByTestId('category-Travel'));
    await settle();

    expect(screen.getByText('Travel')).toBeTruthy();
  });
});
