import { patternLine, seeAllFilters } from './payeePattern';
import type { QuickSortQuestion, QuickSortSample } from '../types';

function sample(date: string): QuickSortSample {
  return { id: date, date, description: 'UPI/SAMPLE PERSON/REF', amount: 200, type: 'EXPENSE' };
}

function q(over: Partial<QuickSortQuestion>): QuickSortQuestion {
  return {
    id: 'vpa:sample|EXPENSE', anchorTransactionId: 't1', kind: 'PERSON_PAID', payee: 'SAMPLE PERSON', payments: 3,
    total: 600, latestDate: '2026-11-12', largeOneOff: false, currentCategory: 'Personal Transfer', answers: [],
    samples: [], ...over,
  };
}

describe('patternLine', () => {
  it('formats the count, month and usual range', () => {
    expect(patternLine(q({
      allPayments: 14, firstDate: '2026-08-03', typicalLow: 150, typicalHigh: 400, recent: [sample('2026-11-12')],
    }))).toBe('14 payments since Aug · usually ₹150–₹400');
  });

  it('shows the year when the first payment is in another year', () => {
    expect(patternLine(q({
      allPayments: 5, firstDate: '2025-12-20', typicalLow: 100, typicalHigh: 300, recent: [sample('2026-02-01')],
    }))).toBe('5 payments since Dec 2025 · usually ₹100–₹300');
  });

  it('shows one value when both ends round the same', () => {
    expect(patternLine(q({
      allPayments: 4, firstDate: '2026-09-01', typicalLow: 200, typicalHigh: 200, recent: [sample('2026-10-01')],
    }))).toBe('4 payments since Sep · usually ₹200');
  });

  it('describes a single payment without a range', () => {
    expect(patternLine(q({
      allPayments: 1, firstDate: '2026-11-04', typicalLow: null, typicalHigh: null, recent: [sample('2026-11-04')],
    }))).toBe('1 payment in Nov');
  });

  it('groups large amounts the Indian way', () => {
    expect(patternLine(q({
      allPayments: 6, firstDate: '2026-06-01', typicalLow: 1500, typicalHigh: 120000, recent: [sample('2026-11-01')],
    }))).toBe('6 payments since Jun · usually ₹1,500–₹1,20,000');
  });

  it('is null without a pattern', () => {
    expect(patternLine(q({}))).toBeNull();
    expect(patternLine(q({ allPayments: null }))).toBeNull();
  });
});

describe('seeAllFilters', () => {
  it("narrows Transactions to the question's payee and names who was paid", () => {
    expect(seeAllFilters({ anchorTransactionId: 'txn-1', payee: 'RAVI K', kind: 'PERSON_PAID' }, 7))
      .toEqual({ payeeOf: 'txn-1', label: 'Payments to RAVI K', nonce: 7 });
  });

  it('says "from" for money that came in', () => {
    expect(seeAllFilters({ anchorTransactionId: 'txn-2', payee: 'ASHA', kind: 'MONEY_IN' }, 1).label)
      .toBe('Payments from ASHA');
  });
});
