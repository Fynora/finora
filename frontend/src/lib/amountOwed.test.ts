import { describe, expect, it } from 'vitest';
import { amountOwedToSend, isAmountOwedUnknown, readAmountOwed, shouldAskAmountOwed, unknownCardsNote } from './amountOwed';

describe('isAmountOwedUnknown', () => {
  it('is true only for a card the server says is not known', () => {
    expect(isAmountOwedUnknown({ accountType: 'CREDIT_CARD', amountOwedKnown: false })).toBe(true);
    expect(isAmountOwedUnknown({ accountType: 'CREDIT_CARD', amountOwedKnown: true })).toBe(false);
    expect(isAmountOwedUnknown({ accountType: 'CREDIT_CARD' })).toBe(false);
    expect(isAmountOwedUnknown({ accountType: 'SAVINGS', amountOwedKnown: false })).toBe(false);
  });
});

describe('unknownCardsNote', () => {
  it('says how many cards a total leaves out, and nothing when none', () => {
    expect(unknownCardsNote(1)).toBe("Leaves out 1 card whose amount owed isn't known.");
    expect(unknownCardsNote(2)).toBe("Leaves out 2 cards whose amount owed isn't known.");
    expect(unknownCardsNote(0)).toBeNull();
    expect(unknownCardsNote(undefined)).toBeNull();
  });
});

describe('shouldAskAmountOwed', () => {
  const base = { closingBalance: null, existing: null, newType: null, newOpeningBalance: null };

  it('asks for an existing card nothing has stated a figure for', () => {
    expect(shouldAskAmountOwed({ ...base, existing: { accountType: 'CREDIT_CARD', balanceStated: false } })).toBe(true);
  });

  it('does not ask for an existing card with a stated figure', () => {
    expect(shouldAskAmountOwed({ ...base, existing: { accountType: 'CREDIT_CARD', balanceStated: true } })).toBe(false);
  });

  it('does not ask when the server did not say (an older server)', () => {
    expect(shouldAskAmountOwed({ ...base, existing: { accountType: 'CREDIT_CARD' } })).toBe(false);
  });

  it('asks for a new card with no opening balance entered', () => {
    expect(shouldAskAmountOwed({ ...base, newType: 'CREDIT_CARD', newOpeningBalance: '' })).toBe(true);
    expect(shouldAskAmountOwed({ ...base, newType: 'CREDIT_CARD', newOpeningBalance: '   ' })).toBe(true);
    expect(shouldAskAmountOwed({ ...base, newType: 'CREDIT_CARD', newOpeningBalance: null })).toBe(true);
  });

  it('does not ask for a new card with an opening balance entered', () => {
    expect(shouldAskAmountOwed({ ...base, newType: 'CREDIT_CARD', newOpeningBalance: '5000' })).toBe(false);
  });

  it('never asks when the statement gives a closing balance', () => {
    expect(shouldAskAmountOwed({ ...base, closingBalance: 8000, existing: { accountType: 'CREDIT_CARD', balanceStated: false } })).toBe(false);
    expect(shouldAskAmountOwed({ ...base, closingBalance: 0, newType: 'CREDIT_CARD', newOpeningBalance: '' })).toBe(false);
  });

  it('never asks for anything but a card', () => {
    expect(shouldAskAmountOwed({ ...base, existing: { accountType: 'SAVINGS', balanceStated: false } })).toBe(false);
    expect(shouldAskAmountOwed({ ...base, newType: 'SAVINGS', newOpeningBalance: '' })).toBe(false);
  });
});

describe('readAmountOwed', () => {
  it('reads blank, a valid amount, zero and invalid input', () => {
    expect(readAmountOwed('')).toEqual({ state: 'blank' });
    expect(readAmountOwed('  ')).toEqual({ state: 'blank' });
    expect(readAmountOwed('12345.67')).toEqual({ state: 'valid', value: 12345.67 });
    expect(readAmountOwed('1,00,396.53')).toEqual({ state: 'valid', value: 100396.53 });
    expect(readAmountOwed('0')).toEqual({ state: 'valid', value: 0 });
    expect(readAmountOwed('-1')).toEqual({ state: 'invalid' });
    expect(readAmountOwed('12abc')).toEqual({ state: 'invalid' });
    expect(readAmountOwed('10.001')).toEqual({ state: 'invalid' });
    expect(readAmountOwed('1000000000000')).toEqual({ state: 'invalid' });
  });
});

describe('amountOwedToSend', () => {
  it('sends the amount only when asked, answered and not skipped', () => {
    expect(amountOwedToSend(true, '12345.67', false)).toBe(12345.67);
    expect(amountOwedToSend(true, '0', false)).toBe(0);
    expect(amountOwedToSend(true, '12345.67', true)).toBeUndefined();
    expect(amountOwedToSend(true, '', false)).toBeUndefined();
    expect(amountOwedToSend(false, '12345.67', false)).toBeUndefined();
    expect(amountOwedToSend(true, 'abc', false)).toBeUndefined();
  });
});
