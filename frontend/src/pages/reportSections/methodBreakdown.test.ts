import { describe, expect, it } from 'vitest';
import { MIN_NAMED_SHARE, formatShare, methodBreakdown } from './methodBreakdown';

const m = (method: string, amount: number) => ({ method, amount });

describe('methodBreakdown', () => {
  it('ranks the named methods largest first, with each one\'s share of the whole', () => {
    const out = methodBreakdown([m('CARD', 4000), m('UPI', 5000), m('CASH', 1000)])!;
    expect(out.named.map((r) => [r.method, r.label, r.amount, Math.round(r.share)])).toEqual([
      ['UPI', 'UPI', 5000, 50],
      ['CARD', 'Card', 4000, 40],
      ['CASH', 'Cash', 1000, 10],
    ]);
    expect(out.total).toBe(10000);
    expect(out.unidentified).toBeNull();
    expect(out.namedShare).toBe(100);
  });

  it('keeps what no method was named for as its own row, counted in the shares', () => {
    const out = methodBreakdown([m('UPI', 7000), m('UNIDENTIFIED', 3000)])!;
    expect(out.unidentified).toMatchObject({ label: 'Not identified', amount: 3000 });
    expect(Math.round(out.unidentified!.share)).toBe(30);
    expect(Math.round(out.namedShare)).toBe(70);
    expect(Math.round(out.named[0].share + out.unidentified!.share)).toBe(100);
  });

  it('is left out when too little of the spending has a named method, and drawn right at the line', () => {
    expect(methodBreakdown([m('UPI', 400), m('UNIDENTIFIED', 600)])).toBeNull();
    expect(MIN_NAMED_SHARE).toBe(50);
    expect(methodBreakdown([m('UPI', 500), m('UNIDENTIFIED', 500)])).not.toBeNull();
    expect(methodBreakdown([m('UPI', 499), m('UNIDENTIFIED', 501)])).toBeNull();
  });

  it('is left out when nothing is named, or nothing was spent, or the server sent none', () => {
    expect(methodBreakdown([m('UNIDENTIFIED', 900)])).toBeNull();
    expect(methodBreakdown([])).toBeNull();
    expect(methodBreakdown(undefined)).toBeNull();
    expect(methodBreakdown([m('UPI', 0)])).toBeNull();
  });

  it('drops a method with no positive amount instead of drawing a zero-width row', () => {
    const out = methodBreakdown([m('UPI', 900), m('CASH', 0), m('CARD', -50), m('CHEQUE', Number.NaN)])!;
    expect(out.named.map((r) => r.method)).toEqual(['UPI']);
    expect(out.total).toBe(900);
  });

  it('reads a code it does not know as words, never the raw code', () => {
    const out = methodBreakdown([m('UPI', 900), m('GIFT_VOUCHER', 100)])!;
    expect(out.named.map((r) => r.label)).toEqual(['UPI', 'Gift voucher']);
    expect(methodBreakdown([m('UPI', 900), m('', 100)])!.named.map((r) => r.label)).toEqual(['UPI', 'Other']);
  });

  it('never reads a method with real money in it as 0%', () => {
    expect(formatShare(0.2)).toBe('<1%');
    expect(formatShare(0.99)).toBe('<1%');
    expect(formatShare(1)).toBe('1%');
    expect(formatShare(59.6)).toBe('60%');
  });

  it('labels every code the server sends', () => {
    const out = methodBreakdown([
      m('UPI', 8), m('CARD', 7), m('BANK_TRANSFER', 6), m('CASH', 5), m('CHEQUE', 4), m('AUTO_DEBIT', 3), m('WALLET', 2),
    ])!;
    expect(out.named.map((r) => r.label)).toEqual(['UPI', 'Card', 'Bank transfer', 'Cash', 'Cheque', 'Auto-debit', 'Wallet']);
  });
});
