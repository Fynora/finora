import fs from 'fs';
import path from 'path';
import {
  assetShare,
  comingUp,
  groupKeyFor,
  hasDepositTerms,
  holdingTerms,
  holdingValueProblem,
  moneyInPeriod,
  parseHoldingValue,
  shareLabel,
  summarizeHoldings,
  summarizeMoneyIn,
  valueChangeLine,
} from './investmentSummary';

const TODAY = new Date(2026, 10, 18);
const money = (n: number) => '₹' + Math.round(n).toLocaleString('en-IN');

// The six holdings drawn in Figma, ₹14,00,000 in all.
const SAMPLE = [
  { id: 'ppf', name: 'PPF', balance: 202000, investmentKind: 'PPF/NPS' },
  { id: 'idx', name: 'Index fund', balance: 260000, investmentKind: 'Mutual Fund' },
  { id: 'fd', name: 'Bank FD', balance: 300000, investmentKind: 'FD' },
  { id: 'flx', name: 'Flexi cap fund', balance: 380000, investmentKind: 'Mutual Fund' },
  { id: 'rd', name: 'Recurring deposit', balance: 48000, investmentKind: 'RD' },
  { id: 'brk', name: 'Broker account', balance: 210000, investmentKind: 'Stocks' },
];

describe('groupKeyFor', () => {
  it.each([
    ['Mutual Fund', 'mutual-funds'], [' fd ', 'deposits'], ['RD', 'deposits'], ['Demat', 'stocks'], ['Stocks', 'stocks'],
    ['PPF/NPS', 'retirement'], ['PPF', 'retirement'], ['EPF', 'retirement'], ['NPS', 'retirement'],
    ['Other', 'other'], [null, 'other'], [undefined, 'other'], ['Gold', 'other'],
  ])('groups %j as %s', (kind, key) => {
    expect(groupKeyFor(kind)).toBe(key);
  });
});

describe('summarizeHoldings', () => {
  it('orders groups, sorts holdings by value and works out shares', () => {
    const s = summarizeHoldings(SAMPLE);
    expect(s.total).toBe(1400000);
    expect(s.count).toBe(6);
    expect(s.groups.map((g) => g.key)).toEqual(['mutual-funds', 'deposits', 'stocks', 'retirement']);
    expect(s.groups[0].label).toBe('Mutual funds');
    expect(s.groups[3].label).toBe('PPF, EPF and NPS');
    expect(s.groups[0].total).toBe(640000);
    expect(s.groups[0].items.map((i) => i.holding.name)).toEqual(['Flexi cap fund', 'Index fund']);
    expect(shareLabel(s.groups[0].share)).toBe('46%');
    expect(shareLabel(s.groups[0].items[0].share)).toBe('27%');
  });

  it('stays finite when holdings total zero or less', () => {
    const s = summarizeHoldings([{ id: 'a', name: 'A', balance: 0 }, { id: 'b', name: 'B', balance: -5 }]);
    expect(s.total).toBe(-5);
    expect(s.mix).toEqual([]);
    expect(s.groups[0].share).toBe(0);
    expect(s.groups[0].items[0].share).toBe(0);
  });

  it('leaves a group with nothing positive out of the mix bar', () => {
    const s = summarizeHoldings([
      { id: 'a', name: 'A', balance: 100, investmentKind: 'Stocks' },
      { id: 'b', name: 'B', balance: 0, investmentKind: 'FD' },
    ]);
    expect(s.mix.map((m) => m.key)).toEqual(['stocks']);
    expect(s.groups.map((g) => g.key)).toEqual(['deposits', 'stocks']);
  });

  it('has nothing to say about no holdings', () => {
    expect(summarizeHoldings([])).toEqual({ total: 0, count: 0, groups: [], mix: [] });
  });
});

describe('shareLabel', () => {
  it.each([[0.46, '46%'], [0.001, '<1%'], [0, '0%'], [-1, '0%'], [1, '100%']])('reads %d as %s', (share, label) => {
    expect(shareLabel(share)).toBe(label);
  });
});

describe('assetShare', () => {
  it('caps and hides the asset share', () => {
    expect(assetShare(1400000, 1840000)).toBeCloseTo(0.76, 2);
    expect(assetShare(200, 100)).toBe(1);
    expect(assetShare(-10, 100)).toBe(0);
    expect(assetShare(100, 0)).toBeNull();
    expect(assetShare(100, null)).toBeNull();
    expect(assetShare(100, undefined)).toBeNull();
  });
});

describe('comingUp', () => {
  const fd = (id: string, maturityDate: string) => ({ id, name: id, balance: 1, maturityDate });

  it('lists maturities soonest first, then instalments, at most three', () => {
    const rows = comingUp([
      { id: 'rd', name: 'rd', balance: 1, installmentsPaid: 12, installmentsTotal: 24 },
      fd('late', '2027-06-01'), fd('soon', '2026-12-01'), fd('mid', '2027-01-12'), fd('last', '2027-09-01'),
    ], TODAY);
    expect(rows.map((r) => r.holding.id)).toEqual(['soon', 'mid', 'late']);

    const mixed = comingUp([{ id: 'rd', name: 'rd', balance: 1, installmentsPaid: 12, installmentsTotal: 24 }, fd('soon', '2026-12-01')], TODAY);
    expect(mixed.map((r) => r.holding.id)).toEqual(['soon', 'rd']);
    expect(mixed[1]).toMatchObject({ dateLabel: null, whenLabel: null, paid: 12, instalments: 24, progress: 0.5 });
  });

  it.each([['2026-11-18', 'today'], ['2026-11-19', 'tomorrow'], ['2027-01-12', 'in 55 days']])('says when %s matures', (maturityDate, whenLabel) => {
    expect(comingUp([fd('a', maturityDate)], TODAY)[0].whenLabel).toBe(whenLabel);
  });

  it('carries the date, the maturity amount and the paid count on one row', () => {
    const [row] = comingUp([{ id: 'a', name: 'a', balance: 1, maturityDate: '2027-01-12', maturityAmount: 321600, installmentsPaid: 6, installmentsTotal: 24 }], TODAY);
    expect(row).toMatchObject({ dateLabel: '12 Jan 2027', amount: 321600, paid: 6, instalments: 24, progress: 0.25 });
  });

  it('leaves a matured deposit out of Coming up and says so on its row', () => {
    const matured = { id: 'a', name: 'FD', balance: 1, maturityDate: '2026-01-03', installmentsPaid: 2, installmentsTotal: 24 };
    expect(comingUp([matured], TODAY)).toEqual([]);
    expect(holdingTerms({ id: 'a', name: 'FD', balance: 1, maturityDate: '2026-01-03' }, TODAY, money)).toBe('Matured 3 Jan 2026');
  });

  it('ignores a date that is not a date', () => {
    expect(comingUp([fd('a', 'soon')], TODAY)).toEqual([]);
  });

  it('clamps instalment progress and drops a finished or empty plan', () => {
    const row = (paid: number, total: number) => comingUp([{ id: 'a', name: 'a', balance: 1, installmentsPaid: paid, installmentsTotal: total }], TODAY);
    expect(row(30, 24)).toEqual([]);
    expect(row(24, 24)).toEqual([]);
    expect(row(0, 0)).toEqual([]);
    expect(row(-1, 24)[0]).toMatchObject({ paid: 0, progress: 0 });
  });

  it('counts days by the calendar late at night', () => {
    expect(comingUp([fd('a', '2026-11-19')], new Date(2026, 10, 18, 23, 59))[0].whenLabel).toBe('tomorrow');
  });
});

describe('holdingTerms', () => {
  it('joins what a deposit knows', () => {
    expect(holdingTerms({
      id: 'a', name: 'FD', balance: 1, principalAmount: 300000, interestRate: 7.2, maturityDate: '2027-01-12', maturityAmount: 321600,
    }, TODAY, money)).toBe('Principal ₹3,00,000 · 7.2% a year · matures 12 Jan 2027 · ₹3,21,600 at maturity');
    expect(holdingTerms({ id: 'a', name: 'RD', balance: 1, installmentAmount: 4000, installmentsPaid: 12, installmentsTotal: 24 }, TODAY, money))
      .toBe('₹4,000 a month · 12 of 24 paid');
  });

  it('starts with a capital when the maturity comes first', () => {
    expect(holdingTerms({ id: 'a', name: 'FD', balance: 1, maturityDate: '2027-01-12' }, TODAY, money)).toBe('Matures 12 Jan 2027');
  });

  it.each([['Mutual Fund', 'Mutual fund'], ['FD', 'Deposit'], ['Demat', 'Stocks'], ['EPF', 'PPF, EPF or NPS'], [null, 'Other']])(
    'names the type of a holding with no terms: %j', (investmentKind, label) => {
      expect(holdingTerms({ id: 'a', name: 'A', balance: 1, investmentKind }, TODAY, money)).toBe(label);
    },
  );
});

describe('hasDepositTerms', () => {
  it('tells a deposit from a holding with only a name and a value', () => {
    expect(hasDepositTerms({ id: 'a', name: 'A', balance: 1, investmentKind: 'FD' })).toBe(false);
    expect(hasDepositTerms({ id: 'a', name: 'A', balance: 1, interestRate: 7 })).toBe(true);
    expect(hasDepositTerms({ id: 'a', name: 'A', balance: 1, maturityDate: '2027-01-12' })).toBe(true);
    expect(hasDepositTerms({ id: 'a', name: 'A', balance: 1, installmentsPaid: 1, installmentsTotal: 12 })).toBe(true);
  });
});

describe('moneyInPeriod', () => {
  it('covers this month and the 11 before it', () => {
    expect(moneyInPeriod(TODAY)).toEqual({ dateFrom: '2025-12-01', dateTo: '2026-11-18' });
  });

  it('survives the end of a long month and the turn of a year', () => {
    expect(moneyInPeriod(new Date(2026, 2, 31))).toEqual({ dateFrom: '2025-04-01', dateTo: '2026-03-31' });
    expect(moneyInPeriod(new Date(2027, 0, 5))).toEqual({ dateFrom: '2026-02-01', dateTo: '2027-01-05' });
  });
});

describe('summarizeMoneyIn', () => {
  it('buckets payments by month and ignores replaced copies', () => {
    const s = summarizeMoneyIn([
      { date: '2026-11-05', amount: 12000 },
      { date: '2026-11-06', amount: 6000 },
      { date: '2026-02-05', amount: 9000, reconciliationStatus: 'INVESTMENT_TRANSFER' },
      { date: '2026-02-05', amount: 9000, reconciliationStatus: 'DUPLICATE' },
      { date: '2026-03-05', amount: 9000, reconciliationStatus: 'SUPERSEDED' },
      { date: '2025-11-30', amount: 500000 },
    ], TODAY);
    expect(s).toMatchObject({ total: 27000, count: 3, monthlyAverage: 2700 });
    expect(s.months).toHaveLength(12);
    expect(s.months[0]).toMatchObject({ key: '2025-12', letter: 'D', total: 0, heightFrac: 0 });
    expect(s.months[11]).toMatchObject({ key: '2026-11', letter: 'N', total: 18000, heightFrac: 1 });
    expect(s.months[2]).toMatchObject({ key: '2026-02', total: 9000, heightFrac: 0.5 });
  });

  // Dividing by 12 made two months of ₹10,000 SIPs read as about ₹1,667 a month.
  it.each([
    ['two recent months', [['2026-10-05', 10000], ['2026-11-05', 10000]], 10000],
    ['a first payment this month', [['2026-11-05', 5000]], 5000],
    ['a gap, which still counts as a month', [['2026-08-05', 15000], ['2026-11-05', 15000]], 7500],
    ['the whole period', [['2025-12-05', 12000], ['2026-11-05', 132000]], 12000],
  ] as [string, [string, number][], number][])('averages from the first payment: %s', (_name, rows, average) => {
    const s = summarizeMoneyIn(rows.map(([date, amount]) => ({ date, amount })), TODAY);
    expect(s.monthlyAverage).toBe(average);
  });

  it('does not start the average at a payment that was a duplicate', () => {
    const s = summarizeMoneyIn([
      { date: '2026-01-05', amount: 9000, reconciliationStatus: 'DUPLICATE' },
      { date: '2026-11-05', amount: 6000 },
    ], TODAY);
    expect(s.monthlyAverage).toBe(6000);
  });

  it('has no NaN with no payments', () => {
    const s = summarizeMoneyIn([], TODAY);
    expect(s).toMatchObject({ total: 0, count: 0, monthlyAverage: 0 });
    expect(s.months.every((m) => m.heightFrac === 0)).toBe(true);
  });
});

describe('parseHoldingValue', () => {
  it.each([['2,60,000', 260000], ['260,000', 260000], ['12,34,567', 1234567], [' 5000 ', 5000], ['99.50', 99.5], ['5.', 5], ['999999999999.99', 999999999999.99]])(
    'reads %j', (typed, value) => { expect(parseHoldingValue(typed)).toBe(value); },
  );

  it.each(['', '   ', '0', '-5', 'abc', '12abc', '1e5', '0x10', '12,5', '12,50', '1,00', '1,000,00', '100.555', '1000000000000'])('refuses %j', (typed) => {
    expect(parseHoldingValue(typed)).toBeNull();
  });
});

describe('holdingValueProblem', () => {
  it('explains decimals and size', () => {
    expect(holdingValueProblem('100.555')).toBe('Current value can have at most 2 decimal places.');
    expect(holdingValueProblem('1000000000000')).toBe('Current value is too large.');
  });

  it.each(['12,50', '1,00', '1,000,00', '12,5.50'])('says what is wrong with the commas in %j', (typed) => {
    expect(holdingValueProblem(typed)).toBe('Check the commas in this amount. For paise, use a point: 12.50.');
  });

  it.each(['', '0', 'abc', '1e99', '5000', '2,60,000'])('has nothing specific to say about %j', (typed) => {
    expect(holdingValueProblem(typed)).toBeNull();
  });
});

describe('dates', () => {
  // Intl writes September as "Sept" for en-IN; every other date in the app says "Sep".
  it('writes September the way the rest of the app does', () => {
    const deposit = { id: 'fd', name: 'Bank FD', balance: 1000, maturityDate: '2027-09-12' };

    expect(holdingTerms(deposit, TODAY, money)).toBe('Matures 12 Sep 2027');
    expect(comingUp([deposit], TODAY)[0].dateLabel).toBe('12 Sep 2027');
  });
});

describe('valueChangeLine', () => {
  it('describes the change', () => {
    expect(valueChangeLine(380000, 395000, money)).toBe('Up ₹15,000 from the last value you saved.');
    expect(valueChangeLine(380000, 370000, money)).toBe('Down ₹10,000 from the last value you saved.');
  });

  it('says nothing when there is nothing to compare', () => {
    expect(valueChangeLine(380000, 380000, money)).toBeNull();
    expect(valueChangeLine(380000, 380000.2, money)).toBeNull();
    expect(valueChangeLine(380000, null, money)).toBeNull();
  });
});

describe('parity with the web app', () => {
  it('is a byte-for-byte copy of frontend/src/lib/investmentSummary.ts', () => {
    const web = fs.readFileSync(path.join(__dirname, '../../../frontend/src/lib/investmentSummary.ts'), 'utf8');
    const phone = fs.readFileSync(path.join(__dirname, 'investmentSummary.ts'), 'utf8');
    expect(phone).toBe(web);
  });
});
