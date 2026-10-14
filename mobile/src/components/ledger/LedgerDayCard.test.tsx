import { Platform, StyleSheet } from 'react-native';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { LedgerDayCard } from './LedgerDayCard';
import { ThemeProvider } from '../../theme';
import { light } from '../../theme/palette';
import type { DayPiece } from '../../lib/ledgerRows';
import type { Transaction } from '../../types';

function txn(over: Partial<Transaction> = {}): Transaction {
  return {
    id: 't-1', accountId: 'a-1', categoryId: 'c-1', categoryName: 'Food', date: '2026-07-14',
    description: 'UPI/DR/600000000001/RAHUL SH/YESB', merchant: 'rahul sh', paymentMethod: 'UPI', amount: -1250,
    type: 'EXPENSE', tags: [], notes: null, reconciliationStatus: 'OK', recurring: false,
    needsCategoryReview: false, pendingBankCorrection: false, categoryManuallySet: false,
    counterpartyType: 'PERSON', international: false, foreignCurrency: null, foreignAmount: null,
    ...over,
  };
}
function piece(over: Partial<DayPiece> = {}): DayPiece {
  // The total differs from the one row's amount so each can be found on its own.
  return { key: '2026-07-14#0', date: '2026-07-14', heading: 'Tue, 14 Jul', subtotal: -2000, rows: [txn()], first: true, last: true, ...over };
}
const onOpen = jest.fn();
const onDelete = jest.fn();
function show(p: DayPiece, over: { deletingId?: string | null; largeText?: boolean } = {}) {
  return render(
    <ThemeProvider>
      <LedgerDayCard piece={p} deletingId={over.deletingId ?? null} largeText={over.largeText ?? false} onOpen={onOpen} onDelete={onDelete} />
    </ThemeProvider>
  );
}
beforeEach(() => jest.clearAllMocks());

describe('LedgerDayCard', () => {
  it('shows the day heading as a heading, and the day total signed and coloured', () => {
    show(piece());
    expect(screen.getByRole('header', { name: 'Tue, 14 Jul' })).toBeTruthy();
    expect(screen.getByText('-₹2,000')).toHaveStyle({ color: light.dangerInk });
  });

  it('shows no heading on a later piece of the same day', () => {
    show(piece({ key: '2026-07-14#1', heading: null, first: false }));
    expect(screen.queryByRole('header')).toBeNull();
  });

  it('titles a row by the softened payee name, with category and counterparty under it, and no date', () => {
    show(piece());
    expect(screen.getByText('Rahul Sh')).toBeTruthy();
    expect(screen.getByText('Food · Person')).toBeTruthy();
    expect(screen.queryByText(/2026-07-14/)).toBeNull();
    expect(screen.queryByText(/UPI\/DR/)).toBeNull();
  });

  it('shows only the category when the counterparty is unknown', () => {
    show(piece({ rows: [txn({ counterpartyType: 'UNKNOWN' })] }));
    expect(screen.getByText('Food')).toBeTruthy();
  });

  it('shows no tag line for an ordinary row', () => {
    show(piece());
    expect(screen.queryByText('Categorized')).toBeNull();
    expect(screen.queryByText('Reviewed')).toBeNull();
    expect(screen.queryByTestId('reconciliation-badge-t-1')).toBeNull();
  });

  it('shows the tags a row has, under their test ids, in their colours', () => {
    show(piece({ rows: [txn({ reconciliationStatus: 'TRANSFER', needsCategoryReview: true, international: true })] }));
    expect(screen.getByTestId('reconciliation-badge-t-1')).toHaveTextContent('Transfer');
    expect(screen.getByTestId('reconciliation-badge-t-1')).toHaveStyle({ backgroundColor: light.border, color: light.ink });
    expect(screen.getByTestId('row-tag-needs-review-t-1')).toHaveTextContent('Needs Review');
    expect(screen.getByTestId('row-tag-needs-review-t-1')).toHaveStyle({ backgroundColor: light.warningBg, color: light.warningInk });
    expect(screen.getByTestId('international-badge-t-1')).toHaveTextContent('International');
  });

  it('is one button per row that opens details on press and asks to delete on long press', () => {
    const t = txn();
    show(piece({ rows: [t] }));
    const row = screen.getByRole('button', { name: /^Rahul Sh, expense ₹1,250, Food, 2026-07-14, Sent to a person$/ });
    fireEvent.press(row);
    expect(onOpen).toHaveBeenCalledWith(t);
    fireEvent(row, 'longPress');
    expect(onDelete).toHaveBeenCalledWith(t);
  });

  it('replaces the amount with a spinner while that row is being deleted', () => {
    show(piece(), { deletingId: 't-1' });
    expect(screen.getByTestId('row-deleting-t-1')).toBeTruthy();
    expect(screen.queryByText('-₹1,250')).toBeNull();
    expect(screen.getByText('-₹2,000')).toBeTruthy(); // the day total stays
  });

  it('colours income green and expense red', () => {
    show(piece({ rows: [txn({ id: 'in', type: 'INCOME', amount: 500 }), txn({ id: 'out', amount: -70 })], subtotal: 430 }));
    expect(screen.getByText('+₹500')).toHaveStyle({ color: light.successInk });
    expect(screen.getByText('-₹70')).toHaveStyle({ color: light.dangerInk });
    expect(screen.getByText('+₹430')).toHaveStyle({ color: light.successInk });
  });

  it('at large text hides the avatar, lets the title wrap and moves the amount under the text', () => {
    show(piece(), { largeText: true });
    expect(screen.queryByLabelText('rahul sh')).toBeNull();
    expect(screen.getByText('Rahul Sh').props.numberOfLines).toBeUndefined();
    expect(screen.getByTestId('row-amount-below-t-1')).toBeTruthy();
  });

  it('keeps the avatar and a one-line title at ordinary text sizes', () => {
    show(piece());
    expect(screen.getByLabelText('rahul sh')).toBeTruthy();
    expect(screen.getByText('Rahul Sh').props.numberOfLines).toBe(1);
    expect(screen.queryByTestId('row-amount-below-t-1')).toBeNull();
  });

  it('rounds and shadows a whole card', () => {
    show(piece());
    const card = StyleSheet.flatten(screen.getByTestId('day-card-2026-07-14#0').props.style);
    expect(card).toMatchObject({ borderTopLeftRadius: 20, borderBottomLeftRadius: 20 });
    expect(card.boxShadow).toBeDefined();
  });

  it('gives a middle piece of a cut day no corners, no shadow and no edge above or below', () => {
    show(piece({ key: 'mid', heading: null, first: false, last: false }));
    const mid = StyleSheet.flatten(screen.getByTestId('day-card-mid').props.style);
    expect(mid.borderTopLeftRadius).toBeUndefined();
    expect(mid.borderBottomLeftRadius).toBeUndefined();
    expect(mid).toMatchObject({ borderTopWidth: 0, borderBottomWidth: 0 });
    expect(mid.boxShadow).toBeUndefined();
  });

  it('gives the last piece of a cut day the rounded bottom and the shadow only', () => {
    show(piece({ key: 'end', heading: null, first: false, last: true }));
    const end = StyleSheet.flatten(screen.getByTestId('day-card-end').props.style);
    expect(end.borderTopLeftRadius).toBeUndefined();
    expect(end).toMatchObject({ borderBottomLeftRadius: 20, borderTopWidth: 0 });
    expect(end.boxShadow).toBeDefined();
  });

  it('uses elevation instead of a box shadow on Android', () => {
    const original = Platform.OS;
    (Platform as { OS: string }).OS = 'android';
    try {
      show(piece());
      const card = StyleSheet.flatten(screen.getByTestId('day-card-2026-07-14#0').props.style);
      expect(card.elevation).toBe(2);
      expect(card.boxShadow).toBeUndefined();
    } finally {
      (Platform as { OS: string }).OS = original;
    }
  });
});
