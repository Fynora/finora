import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BankCorrectionModal } from './BankCorrectionModal';
import { transactionsApi } from '../api/endpoints';
import type { BankCorrectionHistoryEntry, Transaction } from '../types';

jest.mock('../api/endpoints', () => ({
  transactionsApi: { correctionHistory: jest.fn(), acknowledgeBankCorrection: jest.fn() },
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
    pendingBankCorrection: true,
    categoryManuallySet: false,
    counterpartyType: 'UNKNOWN',
    ...over,
  };
}

function historyEntry(over: Partial<BankCorrectionHistoryEntry> = {}): BankCorrectionHistoryEntry {
  return {
    action: 'ACCOUNT_AGGREGATOR_TRANSACTION_CORRECTED',
    metadata: { previousAmount: 500, newAmount: 700 },
    createdAt: '2026-09-14T00:00:00Z',
    ...over,
  };
}

function renderModal(transaction: Transaction | null, onClose = jest.fn(), onAcknowledged = jest.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  // Keyed by transaction id, exactly like LedgerScreen.tsx's own usage -- see that file's own
  // comment on why: without the key, this component is a single persistent instance across every
  // open, and a bug found in review (state set for ONE transaction still showing for the next)
  // depends on the caller keying it correctly, not on anything internal to this component. A test
  // that omitted the key would misrepresent how this component is actually used in production.
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <BankCorrectionModal key={transaction?.id ?? 'none'} transaction={transaction} onClose={onClose} onAcknowledged={onAcknowledged} />
    </QueryClientProvider>
  );
  return {
    onClose,
    onAcknowledged,
    ...utils,
    reopenFor: (next: Transaction | null) => utils.rerender(
      <QueryClientProvider client={queryClient}>
        <BankCorrectionModal key={next?.id ?? 'none'} transaction={next} onClose={onClose} onAcknowledged={onAcknowledged} />
      </QueryClientProvider>
    ),
  };
}

beforeEach(() => {
  jest.clearAllMocks();
});

describe('BankCorrectionModal (Plan 6, Track B mobile parity)', () => {
  it('renders nothing when no transaction is being viewed', () => {
    const { toJSON } = renderModal(null);
    expect(toJSON()).toBeNull();
    expect(transactions.correctionHistory).not.toHaveBeenCalled();
  });

  it('shows the corrected-value history entry with old vs new amount', async () => {
    transactions.correctionHistory.mockResolvedValue([historyEntry()]);

    renderModal(txn());

    expect(await screen.findByText('Bank reported a different value')).toBeTruthy();
    expect(transactions.correctionHistory).toHaveBeenCalledWith('t-1');
  });

  it('shows the missing-transaction history entry', async () => {
    transactions.correctionHistory.mockResolvedValue([historyEntry({
      action: 'ACCOUNT_AGGREGATOR_TRANSACTION_MISSING',
      metadata: { amount: 250, narration: 'Pre-auth hold', txnDate: '2026-09-10' },
    })]);

    renderModal(txn());

    expect(await screen.findByText('No longer reported by the bank')).toBeTruthy();
  });

  it('says so, rather than nothing, when the request fails', async () => {
    transactions.correctionHistory.mockRejectedValue(new Error('network down'));

    renderModal(txn());

    expect(await screen.findByText("Couldn't load this correction's history.")).toBeTruthy();
  });

  it('acknowledges the correction and calls onAcknowledged', async () => {
    transactions.correctionHistory.mockResolvedValue([historyEntry()]);
    transactions.acknowledgeBankCorrection.mockResolvedValue(txn({ pendingBankCorrection: false }));
    const { onAcknowledged } = renderModal(txn());

    fireEvent.press(await screen.findByText('Acknowledge'));

    await waitFor(() => expect(transactions.acknowledgeBankCorrection).toHaveBeenCalledWith('t-1'));
    await waitFor(() => expect(onAcknowledged).toHaveBeenCalled());
  });

  it('shows an error and does not call onAcknowledged when acknowledging fails', async () => {
    transactions.correctionHistory.mockResolvedValue([historyEntry()]);
    transactions.acknowledgeBankCorrection.mockRejectedValue(new Error('network down'));
    const { onAcknowledged } = renderModal(txn());

    fireEvent.press(await screen.findByText('Acknowledge'));

    expect(await screen.findByText('Could not acknowledge this correction.')).toBeTruthy();
    expect(onAcknowledged).not.toHaveBeenCalled();
  });

  it('calls onClose when Close is pressed', async () => {
    transactions.correctionHistory.mockResolvedValue([]);
    const { onClose } = renderModal(txn());

    fireEvent.press(await screen.findByText('Close'));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  // Bug found in review: this component (like MarkTransferModal, the closest existing precedent)
  // is rendered once by LedgerScreen.tsx and reused for every transaction -- it never unmounts
  // between opens, since it's given no `key`. A component that resets its loading/error state only
  // at submit time, not whenever which transaction is being viewed changes, leaves stale state
  // from transaction A visible the next time transaction B's correction is opened.
  it('does not show as still acknowledging when reopened for a different transaction after a successful acknowledge', async () => {
    transactions.correctionHistory.mockResolvedValue([historyEntry()]);
    transactions.acknowledgeBankCorrection.mockResolvedValue(txn({ pendingBankCorrection: false }));
    const { onAcknowledged, reopenFor } = renderModal(txn({ id: 't-1' }));

    fireEvent.press(await screen.findByText('Acknowledge'));
    await waitFor(() => expect(onAcknowledged).toHaveBeenCalled());

    // The parent would normally close the modal on onAcknowledged (transaction becomes null) --
    // simulate it being reopened right away for a DIFFERENT transaction, the scenario that
    // exposes a stuck `acknowledging` flag.
    reopenFor(txn({ id: 't-2' }));

    expect(await screen.findByText('Acknowledge')).toBeTruthy();
  });

  it('does not carry a stale error into a newly opened transaction', async () => {
    transactions.correctionHistory.mockResolvedValue([historyEntry()]);
    transactions.acknowledgeBankCorrection.mockRejectedValue(new Error('network down'));
    const { reopenFor } = renderModal(txn({ id: 't-1' }));

    fireEvent.press(await screen.findByText('Acknowledge'));
    await screen.findByText('Could not acknowledge this correction.');

    reopenFor(txn({ id: 't-2' }));

    await screen.findByText('Bank reported a different value');
    expect(screen.queryByText('Could not acknowledge this correction.')).toBeNull();
  });
});
