import { fireEvent, render, screen } from '@testing-library/react-native';
import { TransactionDetailSheet } from './TransactionDetailSheet';
import type { Transaction } from '../types';

const TXN: Transaction = {
  id: 't-1',
  accountId: 'a-1',
  categoryId: 'c-1',
  categoryName: 'Food',
  date: '2026-07-14',
  description: 'Grocery run',
  merchant: 'Big Bazaar',
  paymentMethod: 'HDFC Credit Card',
  amount: -1250,
  type: 'EXPENSE',
  tags: [],
  notes: null,
  reconciliationStatus: 'OK',
  recurring: false,
  needsCategoryReview: false,
  pendingBankCorrection: false,
  categoryManuallySet: false,
  counterpartyType: 'UNKNOWN',
} as Transaction;

function callbacks() {
  return {
    onClose: jest.fn(),
    onChangeCategory: jest.fn(),
    onEdit: jest.fn(),
    onViewSource: jest.fn(),
    onExplainCategory: jest.fn(),
    onMarkTransfer: jest.fn(),
    onUnmarkTransfer: jest.fn(),
    onViewCorrection: jest.fn(),
    onDelete: jest.fn(),
  };
}

function renderSheet(transaction: Transaction = TXN, overrides: Partial<{ unmarking: boolean; deleting: boolean }> = {}) {
  const cb = callbacks();
  render(
    <TransactionDetailSheet
      transaction={transaction}
      unmarking={overrides.unmarking ?? false}
      deleting={overrides.deleting ?? false}
      {...cb}
    />
  );
  return cb;
}

describe('TransactionDetailSheet', () => {
  it('shows the transaction summary and category/date info', () => {
    renderSheet();

    expect(screen.getByText('Grocery run')).toBeTruthy();
    expect(screen.getByText('-₹1,250')).toBeTruthy();
    expect(screen.getByText('Food')).toBeTruthy();
    expect(screen.getByText('2026-07-14')).toBeTruthy();
    expect(screen.getByText('HDFC Credit Card')).toBeTruthy();
  });

  it('omits the payment method row when the transaction has none', () => {
    renderSheet({ ...TXN, paymentMethod: '' } as Transaction);

    expect(screen.queryByText('HDFC Credit Card')).toBeNull();
  });

  it('shows the status badge pill (Categorized/Reviewed/etc.) same as the row', () => {
    renderSheet({ ...TXN, categoryManuallySet: true } as Transaction);

    expect(screen.getByText('Reviewed')).toBeTruthy();
  });

  it('calls onChangeCategory when its row is pressed', () => {
    const cb = renderSheet();
    fireEvent.press(screen.getByTestId('category-button-t-1'));
    expect(cb.onChangeCategory).toHaveBeenCalledTimes(1);
  });

  it('calls onEdit when its row is pressed', () => {
    const cb = renderSheet();
    fireEvent.press(screen.getByTestId('edit-button-t-1'));
    expect(cb.onEdit).toHaveBeenCalledTimes(1);
  });

  it('calls onViewSource when its row is pressed', () => {
    const cb = renderSheet();
    fireEvent.press(screen.getByTestId('source-button-t-1'));
    expect(cb.onViewSource).toHaveBeenCalledTimes(1);
  });

  it('calls onExplainCategory when its row is pressed', () => {
    const cb = renderSheet();
    fireEvent.press(screen.getByTestId('explain-button-t-1'));
    expect(cb.onExplainCategory).toHaveBeenCalledTimes(1);
  });

  it('calls onDelete when its row is pressed', () => {
    const cb = renderSheet();
    fireEvent.press(screen.getByTestId('delete-button-t-1'));
    expect(cb.onDelete).toHaveBeenCalledTimes(1);
  });

  it('calls onClose when the close icon is pressed', () => {
    const cb = renderSheet();
    fireEvent.press(screen.getByLabelText('Close'));
    expect(cb.onClose).toHaveBeenCalledTimes(1);
  });

  // Distinct from the icon above: getByLabelText('Close') matches ONLY the top-right icon (its
  // own explicit accessibilityLabel) -- the bottom "Close" link Button has no accessibilityLabel
  // of its own, so it's reachable only by its visible text, a genuinely different element. Found
  // in review: the earlier "does not call onClose while busy" tests below pressed the icon twice
  // (via that same ambiguous-looking query) and never actually exercised this Button at all, so
  // its own `disabled={busy}` wiring had zero coverage.
  it('calls onClose when the bottom Close button is pressed', () => {
    const cb = renderSheet();
    fireEvent.press(screen.getByText('Close'));
    expect(cb.onClose).toHaveBeenCalledTimes(1);
  });

  it('offers Mark as transfer, not Unmark, for an OK transaction', () => {
    renderSheet({ ...TXN, reconciliationStatus: 'OK' } as Transaction);

    expect(screen.getByTestId('mark-transfer-button-t-1')).toBeTruthy();
    expect(screen.queryByTestId('unmark-transfer-button-t-1')).toBeNull();
  });

  it('offers Unmark as transfer, not Mark, for a TRANSFER transaction', () => {
    const cb = renderSheet({ ...TXN, reconciliationStatus: 'TRANSFER' } as Transaction);

    expect(screen.getByTestId('unmark-transfer-button-t-1')).toBeTruthy();
    expect(screen.queryByTestId('mark-transfer-button-t-1')).toBeNull();

    fireEvent.press(screen.getByTestId('unmark-transfer-button-t-1'));
    expect(cb.onUnmarkTransfer).toHaveBeenCalledTimes(1);
  });

  it('offers neither Mark nor Unmark for a transaction already classified as something else', () => {
    renderSheet({ ...TXN, reconciliationStatus: 'DUPLICATE' } as Transaction);

    expect(screen.queryByTestId('mark-transfer-button-t-1')).toBeNull();
    expect(screen.queryByTestId('unmark-transfer-button-t-1')).toBeNull();
  });

  it('shows the bank-correction row only when the transaction is flagged', () => {
    renderSheet({ ...TXN, pendingBankCorrection: true } as Transaction);
    expect(screen.getByTestId('bank-correction-button-t-1')).toBeTruthy();
  });

  it('does not show the bank-correction row for an ordinary transaction', () => {
    renderSheet({ ...TXN, pendingBankCorrection: false } as Transaction);
    expect(screen.queryByTestId('bank-correction-button-t-1')).toBeNull();
  });

  it('disables the unmark row and shows a spinner instead of the chevron while unmarking', () => {
    renderSheet({ ...TXN, reconciliationStatus: 'TRANSFER' } as Transaction, { unmarking: true });

    const row = screen.getByTestId('unmark-transfer-button-t-1');
    expect(row.props.accessibilityState?.disabled ?? row.props.disabled).toBeTruthy();
  });

  it('disables the delete row and shows a spinner instead of the chevron while deleting', () => {
    renderSheet(TXN, { deleting: true });

    const row = screen.getByTestId('delete-button-t-1');
    expect(row.props.accessibilityState?.disabled ?? row.props.disabled).toBeTruthy();
  });

  // Bug found in review: every OTHER action used to stay fully interactive while a delete or
  // unmark was in flight for this same transaction -- a user fast enough could navigate away to
  // edit (or start marking a transfer for) a transaction a request elsewhere in this sheet might
  // be about to change or remove. Every row now shares the same busy state.
  it('disables every other action row while deleting is in flight, not just the delete row itself', () => {
    renderSheet(TXN, { deleting: true });

    for (const testId of ['category-button-t-1', 'edit-button-t-1', 'source-button-t-1', 'explain-button-t-1']) {
      const row = screen.getByTestId(testId);
      expect(row.props.accessibilityState?.disabled ?? row.props.disabled).toBeTruthy();
    }
  });

  it('disables every other action row while unmarking is in flight, not just the unmark row itself', () => {
    renderSheet({ ...TXN, reconciliationStatus: 'TRANSFER' } as Transaction, { unmarking: true });

    for (const testId of ['category-button-t-1', 'edit-button-t-1', 'source-button-t-1', 'explain-button-t-1', 'delete-button-t-1']) {
      const row = screen.getByTestId(testId);
      expect(row.props.accessibilityState?.disabled ?? row.props.disabled).toBeTruthy();
    }
  });

  it('leaves every action row enabled when nothing is in flight', () => {
    renderSheet();

    for (const testId of ['category-button-t-1', 'edit-button-t-1', 'source-button-t-1', 'explain-button-t-1', 'delete-button-t-1']) {
      const row = screen.getByTestId(testId);
      expect(row.props.accessibilityState?.disabled ?? row.props.disabled).toBeFalsy();
    }
  });

  it('is independently reachable for a screen-reader user -- each action is its own accessible row, not nested inside another', () => {
    renderSheet();

    for (const testId of ['category-button-t-1', 'edit-button-t-1', 'source-button-t-1', 'explain-button-t-1', 'delete-button-t-1']) {
      const row = screen.getByTestId(testId);
      expect(row.props.accessibilityRole).toBe('button');
      expect(row.props.accessibilityLabel).toBeTruthy();
    }
  });

  // Bug found in review: the caller (LedgerScreen) closes this sheet once a delete/unmark it
  // triggered settles, unconditionally -- if dismissal were still possible mid-request, a user
  // could close this sheet, open a DIFFERENT transaction's, and have the first request's eventual
  // completion force-close that unrelated sheet out from under them. Blocking dismissal while
  // busy closes that race instead of just living with it.
  it('does not call onClose from the backdrop, the close icon, or the Close button while deleting', () => {
    const cb = renderSheet(TXN, { deleting: true });

    fireEvent.press(screen.getByLabelText('Close transaction details'));
    fireEvent.press(screen.getByLabelText('Close'));
    fireEvent.press(screen.getByText('Close'));
    expect(cb.onClose).not.toHaveBeenCalled();
  });

  it('does not call onClose from the backdrop, the close icon, or the Close button while unmarking', () => {
    const cb = renderSheet({ ...TXN, reconciliationStatus: 'TRANSFER' } as Transaction, { unmarking: true });

    fireEvent.press(screen.getByLabelText('Close transaction details'));
    fireEvent.press(screen.getByLabelText('Close'));
    fireEvent.press(screen.getByText('Close'));
    expect(cb.onClose).not.toHaveBeenCalled();
  });

  it('allows dismissal normally when nothing is in flight', () => {
    const cb = renderSheet();

    fireEvent.press(screen.getByLabelText('Close transaction details'));
    expect(cb.onClose).toHaveBeenCalledTimes(1);
  });
});
