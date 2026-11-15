import { describe, expect, it } from 'vitest';
import { payoffLine } from './quickSortPayoff';

const money = (n: number) => '₹' + n;

describe('payoffLine', () => {
  it('names the count, the money and that the payee is remembered', () => {
    expect(payoffLine({ filed: 3, amount: 2400, remembered: true }, 'Food', 'Swiggy', money))
      .toBe('✓ Food · 3 payments filed (₹2400). Fynora will remember this for Swiggy.');
  });

  it('says "1 payment" for one', () => {
    expect(payoffLine({ filed: 1, amount: 450, remembered: true }, 'Food', 'Swiggy', money))
      .toBe('✓ Food · 1 payment filed (₹450). Fynora will remember this for Swiggy.');
  });

  it('promises nothing when the payee was not remembered', () => {
    expect(payoffLine({ filed: 1, amount: 450, remembered: false }, 'Food', 'Swiggy', money))
      .toBe('✓ Filed as Food.');
  });

  it('falls back when an older server sends only the count', () => {
    expect(payoffLine({ filed: 3 }, 'Food', 'Swiggy', money)).toBe('✓ Filed as Food.');
  });
});
