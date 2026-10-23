import { fireEvent, render, screen } from '@testing-library/react-native';
import { TransactionDetailSheet } from './TransactionDetailSheet';
import { MerchantLogo, logoSourceFor } from '../components/MerchantLogo';
import type { Transaction } from '../types';

/** The Logo.dev URL a rendered MerchantLogo would request with a token configured, or null. */
const lookedUp = (p: Record<string, unknown>) =>
  logoSourceFor(String(p.merchant), 64, 'test-token', p.person === true, p.lookup !== false);

// The people part has its own tests; a stand-in that hands back the sheet's callbacks.
jest.mock('../components/people/PeopleTransactionSection', () => {
  const { Pressable: MockPressable } = jest.requireActual('react-native');
  return {
    PeopleTransactionSection: (p: { onShareOfABill: () => void; onSplit: () => void; onOpenPerson: (k: string) => void }) => (
      <>
        <MockPressable accessibilityRole="button" accessibilityLabel="people: share of a bill" onPress={p.onShareOfABill} />
        <MockPressable accessibilityRole="button" accessibilityLabel="people: split" onPress={p.onSplit} />
        <MockPressable accessibilityRole="button" accessibilityLabel="people: person" onPress={() => p.onOpenPerson('vpa:friend.k')} />
      </>
    ),
  };
});

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
  international: false,
  foreignCurrency: null,
  foreignAmount: null,
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
    onShareOfABill: jest.fn(),
    onSplit: jest.fn(),
    onOpenPerson: jest.fn(),
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
  // With no merchant name the sheet is titled by the bank narration, which can hold UPI ids, phone
  // and reference numbers: that text must never be the name looked up on Logo.dev.
  it('never sends a raw narration or a person to Logo.dev, and still looks a business up', () => {
    const looked = (t: Transaction) => {
      const { unmount } = render(
        <TransactionDetailSheet transaction={t} unmarking={false} deleting={false} {...callbacks()} />
      );
      const url = lookedUp(screen.UNSAFE_getByType(MerchantLogo).props);
      unmount();
      return url;
    };
    expect(looked({ ...TXN, merchant: '', description: 'UPI/DR/600000000002/SAMPLE SHOP/YESB', counterpartyType: 'BUSINESS' })).toBeNull();
    expect(looked({ ...TXN, merchant: 'sample person', counterpartyType: 'PERSON' })).toBeNull();
    expect(looked({ ...TXN, merchant: 'Big Bazaar', counterpartyType: 'BUSINESS' })).toContain('img.logo.dev/name/Big%20Bazaar');
  });

  it('hands the people answers to its host', () => {
    const cb = renderSheet();
    fireEvent.press(screen.getByRole('button', { name: 'people: share of a bill' }));
    fireEvent.press(screen.getByRole('button', { name: 'people: split' }));
    fireEvent.press(screen.getByRole('button', { name: 'people: person' }));
    expect(cb.onShareOfABill).toHaveBeenCalledTimes(1);
    expect(cb.onSplit).toHaveBeenCalledTimes(1);
    expect(cb.onOpenPerson).toHaveBeenCalledWith('vpa:friend.k');
  });

  // The list titles a row with rowTitle; the sheet it opens used to lead with the bank's raw
  // narration instead, so the same payment had two different names a tap apart.
  it('is titled as its list row is, with the bank text under the title', () => {
    renderSheet({
      ...TXN, description: 'UPI/DR/100000000001/SAMPLE STORE/YBL/sample@ybl/Pay', merchant: 'sample store', // synthetic-ok
    } as Transaction);

    expect(screen.getByText('Sample Store')).toBeTruthy();
    expect(screen.getByTestId('detail-bank-text-t-1').props.children)
      .toBe('UPI/DR/100000000001/SAMPLE STORE/YBL/sample@ybl/Pay'); // synthetic-ok
  });

  it('does not repeat the bank text when it is the title already', () => {
    renderSheet({ ...TXN, description: 'Grocery run', merchant: '' } as Transaction);

    expect(screen.getByText('Grocery run')).toBeTruthy();
    expect(screen.queryByTestId('detail-bank-text-t-1')).toBeNull();
  });

  it('does not repeat bank text that differs from the title only in its capitals', () => {
    renderSheet({ ...TXN, description: 'NEFT INWARD 0042', merchant: '' } as Transaction);

    expect(screen.getByText('NEFT Inward 0042')).toBeTruthy();
    expect(screen.queryByTestId('detail-bank-text-t-1')).toBeNull();
  });

  // The owner, 2026-10-16: the list rounds to the rupee to be quick to read; the sheet is where an
  // amount is checked against the statement, so it shows the paise, as "Balance after" does.
  it('shows the exact amount, to the paisa, signed as the list signs it', () => {
    renderSheet({ ...TXN, amount: -1240.5 } as Transaction);
    expect(screen.getByText('-₹1,240.50')).toBeTruthy();
  });

  it('shows money in with a plus', () => {
    renderSheet({ ...TXN, amount: 52000, type: 'INCOME' } as Transaction);
    expect(screen.getByText('+₹52,000.00')).toBeTruthy();
  });

  it('shows the transaction summary and category/date info', () => {
    renderSheet();

    expect(screen.getByText('Grocery run')).toBeTruthy();
    expect(screen.getByText('-₹1,250.00')).toBeTruthy();
    expect(screen.getByText('Food')).toBeTruthy();
    expect(screen.getByText('14 Jul 2026')).toBeTruthy();
    expect(screen.getByText('HDFC Credit Card')).toBeTruthy();
  });

  it('shows an international transaction with its original-currency amount', () => {
    renderSheet({ ...TXN, international: true, foreignCurrency: 'USD', foreignAmount: 12.5 } as Transaction);

    expect(screen.getByText('International')).toBeTruthy();
    expect(screen.getByText('USD 12.50')).toBeTruthy();
  });

  it('shows an international transaction with no printed foreign amount as a plain yes', () => {
    renderSheet({ ...TXN, international: true } as Transaction);

    expect(screen.getByText('International')).toBeTruthy();
    expect(screen.getByText('Yes')).toBeTruthy();
  });

  it('omits the international row for a domestic transaction', () => {
    renderSheet();

    expect(screen.queryByText('International')).toBeNull();
  });

  it('marks a card instalment and says in full what the mark means', () => {
    renderSheet({ ...TXN, cardInstalment: true } as Transaction);

    expect(screen.getByText('EMI instalment')).toBeTruthy();
    expect(screen.getByTestId('card-instalment-note-t-1')).toHaveTextContent(
      'A monthly instalment on an earlier card purchase. If that purchase is also in Fynora, your spending totals count both the purchase and its instalments.'
    );
  });

  it('shows neither the mark nor the note for an ordinary transaction', () => {
    renderSheet({ ...TXN, cardInstalment: false } as Transaction);
    expect(screen.queryByText('EMI instalment')).toBeNull();
    expect(screen.queryByTestId('card-instalment-note-t-1')).toBeNull();
  });

  it('shows neither for a row from a server that sends no flag', () => {
    renderSheet();
    expect(screen.queryByText('EMI instalment')).toBeNull();
    expect(screen.queryByText(/monthly instalment/i)).toBeNull();
  });

  it('shows the reference number and running balance the statement printed', () => {
    renderSheet({ ...TXN, referenceNumber: 'REF000000001', balanceAfter: 24361.97 } as Transaction);

    expect(screen.getByText('Reference')).toBeTruthy();
    expect(screen.getByText('REF000000001')).toBeTruthy();
    expect(screen.getByText('Balance after')).toBeTruthy();
    // To the paisa, unlike the rounded amount above: it is checked against the statement.
    expect(screen.getByText('₹24,361.97')).toBeTruthy();
  });

  it('keeps the sign of an overdrawn balance', () => {
    renderSheet({ ...TXN, balanceAfter: -250 } as Transaction);
    expect(screen.getByText('-₹250.00')).toBeTruthy();
  });

  it('shows a zero balance rather than hiding it', () => {
    renderSheet({ ...TXN, balanceAfter: 0 } as Transaction);
    expect(screen.getByText('₹0.00')).toBeTruthy();
  });

  it('omits both rows when the statement printed neither', () => {
    renderSheet({ ...TXN, referenceNumber: null, balanceAfter: null } as Transaction);

    expect(screen.queryByText('Reference')).toBeNull();
    expect(screen.queryByText('Balance after')).toBeNull();
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
