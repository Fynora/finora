import { fireEvent, render, screen } from '@testing-library/react-native';
import { LookAtSection } from './LookAtSection';
import type { LookAtRow } from '../../lib/insightsFeed';

// The question has its own tests; a stand-in that shows what it was given is enough here.
jest.mock('../RecurringQuestion', () => {
  const { Text: MockText } = jest.requireActual('react-native');
  return {
    RecurringQuestion: ({ merchant, state, amount }: { merchant: string; state: string; amount: number }) => (
      <MockText>{`question ${merchant} ${state} ${amount}`}</MockText>
    ),
  };
});

const card: LookAtRow = {
  kind: 'card-statement', month: '2026-09', total: 21400, count: 2, cardName: 'Sample Card', cardLast4: '0001',
  ackKind: 'CARD_STATEMENT_MISSING', ackItemKey: '2026-09',
};
const unknownCard: LookAtRow = { ...card, cardName: null, cardLast4: null };
const high: LookAtRow = {
  kind: 'running-high', category: 'Food', current: 9400, priorAverage: 6300, month: '2026-09',
  ackKind: 'CATEGORY_HIGH', ackItemKey: 'Food|2026-09',
};
const changed: LookAtRow = {
  kind: 'amount-changed', merchant: 'Sample TV', category: 'Subscriptions', latestAmount: 649,
  latestDate: '2026-09-12', state: 'AMOUNT_CHANGED', ackKind: 'AMOUNT_CHANGED', ackItemKey: 'sample tv|2026-09-12',
};
const newOne: LookAtRow = {
  kind: 'new-recurring',
  item: { merchant: 'sample gym', label: 'Monthly', averageAmount: 1500, occurrences: 3, lastDate: '2026-09-05', nextEstimate: '2026-10-05', state: 'NEEDS_ANSWER' },
};

function show(rows: LookAtRow[]) {
  const handlers = { onOpenRow: jest.fn(), onAck: jest.fn(), onUpload: jest.fn() };
  render(<LookAtSection rows={rows} monthLong="September 2026" {...handlers} />);
  return handlers;
}

describe('LookAtSection', () => {
  it('names the card when it is known, and offers the upload', () => {
    const { onUpload, onAck } = show([card]);
    expect(screen.getByText('You paid ₹21,400 toward your Sample Card ending 0001 in September 2026 with no card statement uploaded for it. Upload it to see what you bought.')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Upload statement' }));
    expect(onUpload).toHaveBeenCalledTimes(1);
    fireEvent.press(screen.getByRole('button', { name: 'Got it: card statement for September 2026' }));
    expect(onAck).toHaveBeenCalledWith(card);
  });

  it('says "a credit card" when the card is not known', () => {
    show([unknownCard]);
    expect(screen.getByText('You paid ₹21,400 toward a credit card in September 2026 with no card statement uploaded for it. Upload it to see what you bought.')).toBeTruthy();
  });

  it('names the card without digits when only its name is known', () => {
    show([{ ...card, cardLast4: null } as LookAtRow]);
    expect(screen.getByText(/toward your Sample Card in September 2026/)).toBeTruthy();
  });

  it('says how far a category ran above its usual month, in rupees, and opens it', () => {
    const { onOpenRow, onAck } = show([high]);
    expect(screen.getByText('Food is ₹3,100 above your usual month')).toBeTruthy();
    expect(screen.getByText('₹9,400 vs ₹6,300')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Food is ₹3,100 above your usual month, ₹9,400 vs ₹6,300. Shows the payments' }));
    expect(onOpenRow).toHaveBeenCalledWith(high);
    fireEvent.press(screen.getByRole('button', { name: 'Got it: Food' }));
    expect(onAck).toHaveBeenCalledWith(high);
  });

  it('asks about a new repeating payment in place, with no "Got it"', () => {
    show([newOne]);
    expect(screen.getByText('New repeating payment: sample gym')).toBeTruthy();
    expect(screen.getByText('question sample gym NEEDS_ANSWER 1500')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /^Got it/ })).toBeNull();
  });

  it('asks again about an amount that changed, and can be marked "Got it"', () => {
    const { onAck, onOpenRow } = show([changed]);
    expect(screen.getByText('Amount changed: Sample TV')).toBeTruthy();
    expect(screen.getByText('question Sample TV AMOUNT_CHANGED 649')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Show the payments to Sample TV' }));
    expect(onOpenRow).toHaveBeenCalledWith(changed);
    fireEvent.press(screen.getByRole('button', { name: 'Got it: Sample TV' }));
    expect(onAck).toHaveBeenCalledWith(changed);
  });

  it('says plainly when there is nothing to look at', () => {
    show([]);
    expect(screen.getByText('Nothing new to look at for September 2026.')).toBeTruthy();
  });

  it('heads the section with its count', () => {
    show([card, high]);
    expect(screen.getByText('To look at')).toBeTruthy();
    expect(screen.getByText('2')).toBeTruthy();
  });
});
