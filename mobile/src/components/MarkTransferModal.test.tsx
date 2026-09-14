import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MarkTransferModal } from './MarkTransferModal';
import { transactionsApi } from '../api/endpoints';
import type { Transaction } from '../types';

jest.mock('../api/endpoints', () => ({
  transactionsApi: { search: jest.fn(), markTransfer: jest.fn() },
}));

const transactions = transactionsApi as jest.Mocked<typeof transactionsApi>;

function txn(over: Partial<Transaction> = {}): Transaction {
  return {
    id: 't-1',
    accountId: 'a-1',
    categoryId: 'c-1',
    categoryName: 'Shopping',
    date: '2026-09-10',
    description: 'AMAZON PAY',
    merchant: 'Amazon',
    paymentMethod: 'Card',
    amount: 700,
    type: 'EXPENSE',
    tags: [],
    notes: null,
    reconciliationStatus: 'OK',
    recurring: false,
    needsCategoryReview: false,
    categoryManuallySet: false,
    counterpartyType: 'UNKNOWN',
    ...over,
  };
}

function page(content: Transaction[]) {
  return {
    content, page: 0, size: 10, totalElements: content.length, totalPages: content.length ? 1 : 0,
  };
}

const SEARCH_PLACEHOLDER = 'Search description, merchant, or bank…';

// Deliberately does NOT key this by transaction id, unlike LedgerScreen.tsx's own (fixed
// alongside this) usage -- keying the test harness would remount on every reopen and mask the
// exact bug under test: whether the component's OWN state resets correctly when reused for a
// different transaction, independent of whether the caller remembers a key.
function renderModal(transaction: Transaction | null, onClose = jest.fn(), onMarked = jest.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <MarkTransferModal transaction={transaction} onClose={onClose} onMarked={onMarked} />
    </QueryClientProvider>
  );
  return {
    onClose,
    onMarked,
    ...utils,
    reopenFor: (next: Transaction | null) => utils.rerender(
      <QueryClientProvider client={queryClient}>
        <MarkTransferModal transaction={next} onClose={onClose} onMarked={onMarked} />
      </QueryClientProvider>
    ),
  };
}

beforeEach(() => {
  jest.clearAllMocks();
});

describe('MarkTransferModal (Phase 6)', () => {
  it('renders nothing when no transaction is being marked', () => {
    const { toJSON } = renderModal(null);
    expect(toJSON()).toBeNull();
    expect(transactions.search).not.toHaveBeenCalled();
  });

  it('excludes the transaction itself and existing transfers from the candidate list', async () => {
    transactions.search.mockResolvedValue(page([
      txn({ id: 't-1', merchant: 'Self' }),
      txn({ id: 't-2', merchant: 'Already a transfer', reconciliationStatus: 'TRANSFER' }),
      txn({ id: 't-3', merchant: 'Landlord' }),
    ]));

    renderModal(txn({ id: 't-1' }));
    fireEvent.changeText(screen.getByPlaceholderText(SEARCH_PLACEHOLDER), 'rent');

    expect(await screen.findByTestId('transfer-candidate-t-3')).toBeTruthy();
    expect(screen.queryByTestId('transfer-candidate-t-1')).toBeNull();
    expect(screen.queryByTestId('transfer-candidate-t-2')).toBeNull();
  });

  it('marks the pair and calls onMarked on success', async () => {
    transactions.search.mockResolvedValue(page([txn({ id: 'c-1', merchant: 'Landlord' })]));
    transactions.markTransfer.mockResolvedValue(txn({ id: 't-1', reconciliationStatus: 'TRANSFER' }));
    const { onMarked } = renderModal(txn({ id: 't-1' }));

    fireEvent.changeText(screen.getByPlaceholderText(SEARCH_PLACEHOLDER), 'rent');
    fireEvent.press(await screen.findByTestId('transfer-candidate-c-1'));

    await waitFor(() => expect(transactions.markTransfer).toHaveBeenCalledWith('t-1', 'c-1'));
    await waitFor(() => expect(onMarked).toHaveBeenCalled());
  });

  it('shows an error and does not call onMarked when marking fails', async () => {
    transactions.search.mockResolvedValue(page([txn({ id: 'c-1', merchant: 'Landlord' })]));
    transactions.markTransfer.mockRejectedValue(new Error('network down'));
    const { onMarked } = renderModal(txn({ id: 't-1' }));

    fireEvent.changeText(screen.getByPlaceholderText(SEARCH_PLACEHOLDER), 'rent');
    fireEvent.press(await screen.findByTestId('transfer-candidate-c-1'));

    expect(await screen.findByText('Could not mark these as a transfer.')).toBeTruthy();
    expect(onMarked).not.toHaveBeenCalled();
  });

  // Bug found in review: LedgerScreen.tsx renders this component with no `key`, so it is a single
  // persistent instance reused across every transaction -- it never unmounts between opens. `pick`
  // only reset `marking` back to false in the catch block, never after a successful
  // `markTransfer` call, so every OTHER candidate row (gated by `disabled={marking}`) stayed
  // disabled forever the next time this same instance was reused for a different transaction.
  it('does not leave candidates stuck disabled when reused for a different transaction after a successful mark', async () => {
    const candidateA = txn({ id: 'c-a', merchant: 'Landlord' });
    const candidateB = txn({ id: 'c-b', merchant: 'Freelance client' });
    transactions.search.mockResolvedValueOnce(page([candidateA])).mockResolvedValueOnce(page([candidateB]));
    transactions.markTransfer.mockResolvedValue(txn({ id: 't-1', reconciliationStatus: 'TRANSFER' }));
    const { onMarked, reopenFor } = renderModal(txn({ id: 't-1' }));

    fireEvent.changeText(screen.getByPlaceholderText(SEARCH_PLACEHOLDER), 'rent');
    fireEvent.press(await screen.findByTestId('transfer-candidate-c-a'));
    await waitFor(() => expect(transactions.markTransfer).toHaveBeenCalledWith('t-1', 'c-a'));
    await waitFor(() => expect(onMarked).toHaveBeenCalled());

    // The parent would normally close the modal on onMarked -- simulate this same instance being
    // handed a different transaction right away, the scenario that exposes a `marking` flag stuck
    // true from the previous, already-successful pick.
    reopenFor(txn({ id: 't-2' }));

    fireEvent.changeText(screen.getByPlaceholderText(SEARCH_PLACEHOLDER), 'invoice');
    fireEvent.press(await screen.findByTestId('transfer-candidate-c-b'));

    await waitFor(() => expect(transactions.markTransfer).toHaveBeenCalledWith('t-2', 'c-b'));
  });
});
