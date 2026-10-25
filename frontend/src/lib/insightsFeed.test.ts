import { describe, it, expect } from 'vitest';
import type { LookAtServerRow, PeopleSuggestion, RecurringItem, ReportTrendPoint } from '../api/endpoints';
import { ackEntry, comingUp, isAcked, lookAtRowsFromServer, monthLine, paidRegularly, rowLabel } from './insightsFeed';

// Ported from mobile/src/lib/insightsFeed.test.ts: the two apps apply the same rules. Every name and
// amount here is invented.

function recurring(overrides: Partial<RecurringItem> = {}): RecurringItem {
  return {
    merchant: 'sample gym', label: 'Monthly', averageAmount: 1500, occurrences: 3, lastDate: '2026-09-05',
    nextEstimate: '2026-10-05', state: 'NONE', ...overrides,
  };
}

/** A server row with every field its kind does not use left null. */
function row(fields: Partial<LookAtServerRow> & Pick<LookAtServerRow, 'kind' | 'key'>): LookAtServerRow {
  return {
    ackKind: null, ackItemKey: null, month: null, total: null, count: null, cardName: null, cardLast4: null,
    people: null, recurring: null, merchant: null, category: null, latestAmount: null, latestDate: null,
    current: null, priorAverage: null, whyAvailable: null, ...fields,
  };
}

const person: PeopleSuggestion = {
  kind: 'SHARE_CHECK', ackKey: 't-1', txnId: 't-1', relatedTxnId: null, personKey: 'vpa:flatmate.a',
  label: 'Flatmate A', amount: 9000, date: '2026-10-04', category: 'Rent', bills: [], owed: null,
};

describe('lookAtRowsFromServer', () => {
  it("keeps every kind, in the server's order", () => {
    const rows = lookAtRowsFromServer([
      row({
        kind: 'running-high', key: 'CATEGORY_HIGH|Food|2026-09', ackKind: 'CATEGORY_HIGH', ackItemKey: 'Food|2026-09',
        month: '2026-09', category: 'Food', current: 9400, priorAverage: 6300, whyAvailable: true,
      }),
      row({
        kind: 'card-statement', key: 'CARD_STATEMENT_MISSING|2026-09', ackKind: 'CARD_STATEMENT_MISSING',
        ackItemKey: '2026-09', month: '2026-09', total: 21400, count: 2, cardName: 'Sample Card', cardLast4: '0001',
      }),
      row({ kind: 'people', key: 'SHARE_CHECK|t-1', ackKind: 'SHARE_CHECK', ackItemKey: 't-1', people: person }),
      row({ kind: 'new-recurring', key: 'NEW_RECURRING|sample gym', recurring: recurring({ state: 'NEEDS_ANSWER' }) }),
      row({
        kind: 'amount-changed', key: 'AMOUNT_CHANGED|sample tv|2026-09-12', ackKind: 'AMOUNT_CHANGED',
        ackItemKey: 'sample tv|2026-09-12', merchant: 'Sample TV', category: 'Subscriptions', latestAmount: 649,
        latestDate: '2026-09-12',
      }),
    ]);
    expect(rows).toEqual([
      {
        kind: 'running-high', category: 'Food', current: 9400, priorAverage: 6300, month: '2026-09',
        ackKind: 'CATEGORY_HIGH', ackItemKey: 'Food|2026-09',
      },
      {
        kind: 'card-statement', month: '2026-09', total: 21400, count: 2, cardName: 'Sample Card', cardLast4: '0001',
        ackKind: 'CARD_STATEMENT_MISSING', ackItemKey: '2026-09',
      },
      { kind: 'people', item: person },
      { kind: 'new-recurring', item: recurring({ state: 'NEEDS_ANSWER' }) },
      {
        kind: 'amount-changed', merchant: 'Sample TV', category: 'Subscriptions', latestAmount: 649,
        latestDate: '2026-09-12', ackKind: 'AMOUNT_CHANGED', ackItemKey: 'sample tv|2026-09-12',
      },
    ]);
  });

  it('drops a row of a kind it does not know, or missing what it needs, rather than drawing it broken', () => {
    expect(lookAtRowsFromServer([
      row({ kind: 'some-new-kind' as LookAtServerRow['kind'], key: 'X|1' }),
      row({ kind: 'people', key: 'SHARE_CHECK|t-1' }),
      row({ kind: 'running-high', key: 'CATEGORY_HIGH|Food|2026-09', category: 'Food' }),
      row({ kind: 'card-statement', key: 'CARD_STATEMENT_MISSING|2026-09', month: '2026-09' }),
      row({ kind: 'new-recurring', key: 'NEW_RECURRING|x' }),
    ])).toEqual([]);
  });

  it('keeps an amount-changed row with no category', () => {
    const [only] = lookAtRowsFromServer([row({
      kind: 'amount-changed', key: 'AMOUNT_CHANGED|sample tv|2026-09-12', ackKind: 'AMOUNT_CHANGED',
      ackItemKey: 'sample tv|2026-09-12', merchant: 'Sample TV', latestAmount: 649, latestDate: '2026-09-12',
    })]);
    expect(only).toEqual(expect.objectContaining({ kind: 'amount-changed', category: '' }));
  });
});

describe('isAcked', () => {
  const [high, people, fresh] = lookAtRowsFromServer([
    row({
      kind: 'running-high', key: 'k1', ackKind: 'CATEGORY_HIGH', ackItemKey: 'Food|2026-09', month: '2026-09',
      category: 'Food', current: 9400, priorAverage: 6300,
    }),
    row({ kind: 'people', key: 'k2', people: person }),
    row({ kind: 'new-recurring', key: 'k3', recurring: recurring({ state: 'NEEDS_ANSWER' }) }),
  ]);

  it('reads each row by its own "Got it" kind and key; a people row by its suggestion', () => {
    const acks = new Set([ackEntry('CATEGORY_HIGH', 'Food|2026-09'), ackEntry('SHARE_CHECK', 't-1')]);
    expect(isAcked(high, acks)).toBe(true);
    expect(isAcked(people, acks)).toBe(true);
    expect(isAcked(high, new Set([ackEntry('CATEGORY_HIGH', 'Food|2026-08')]))).toBe(false);
  });

  it('never hides a new repeating payment: it is answered, not dismissed', () => {
    expect(isAcked(fresh, new Set(['anything']))).toBe(false);
  });
});

describe('rowLabel', () => {
  it('names each row in a few words, for "Hidden: ..." and screen readers', () => {
    const rows = lookAtRowsFromServer([
      row({
        kind: 'running-high', key: 'k1', ackKind: 'CATEGORY_HIGH', ackItemKey: 'Food|2026-09', month: '2026-09',
        category: 'Food', current: 9400, priorAverage: 6300,
      }),
      row({
        kind: 'card-statement', key: 'k2', ackKind: 'CARD_STATEMENT_MISSING', ackItemKey: '2026-09', month: '2026-09',
        total: 21400, count: 2,
      }),
      row({ kind: 'people', key: 'k3', people: person }),
      row({
        kind: 'amount-changed', key: 'k4', ackKind: 'AMOUNT_CHANGED', ackItemKey: 'sample tv|2026-09-12',
        merchant: 'Sample TV', latestAmount: 649, latestDate: '2026-09-12',
      }),
    ]);
    expect(rows.map(rowLabel)).toEqual([
      'Food above your usual month', 'Card statement for September 2026', 'Flatmate A', 'Amount changed: Sample TV',
    ]);
  });
});

describe('comingUp', () => {
  const today = '2026-10-16';

  it('lists soonest first, flags dates already past, and counts only what is still ahead this month', () => {
    const past = recurring({ merchant: 'rent', averageAmount: 15000, nextEstimate: '2026-10-05' });
    const ahead = recurring({ merchant: 'tv', averageAmount: 649, nextEstimate: '2026-10-20' });
    const nextMonth = recurring({ merchant: 'insurance', averageAmount: 2000, nextEstimate: '2026-11-02' });
    const result = comingUp([nextMonth, ahead, past], today);
    expect(result.rows).toEqual([
      { item: past, past: true },
      { item: ahead, past: false },
      { item: nextMonth, past: false },
    ]);
    expect(result.stillToGoOut).toBe(649);
  });

  it('counts a payment due today as still to go out', () => {
    expect(comingUp([recurring({ averageAmount: 300, nextEstimate: today })], today).stillToGoOut).toBe(300);
  });

  // The whole payment leaves the account; the others' shares come in separately (expected shares).
  it('counts a shared bill at the full amount that goes out', () => {
    expect(comingUp([recurring({ averageAmount: 36000, yourShare: 9000, sharedWith: 3, nextEstimate: '2026-10-20' })], today)
      .stillToGoOut).toBe(36000);
  });

  it('is empty with nothing repeating', () => {
    expect(comingUp([], today)).toEqual({ rows: [], stillToGoOut: 0 });
  });
});

describe('paidRegularly', () => {
  it("counts the user's own share of a bill other people share, never the full amount paid", () => {
    const { rows, monthlyTotal } = paidRegularly([
      recurring({ merchant: 'sample owner', averageAmount: 36000, yourShare: 9000, sharedWith: 3 }),
      recurring({ merchant: 'sample gym', averageAmount: 1500 }),
    ]);
    expect(rows.map((r) => [r.item.merchant, r.monthly])).toEqual([['sample owner', 9000], ['sample gym', 1500]]);
    expect(monthlyTotal).toBe(10500);
  });

  it('turns each cadence into a monthly amount and totals the month and the year', () => {
    const result = paidRegularly([
      recurring({ merchant: 'milk', label: 'Weekly', averageAmount: 100 }),
      recurring({ merchant: 'insurance', label: 'Quarterly', averageAmount: 900 }),
      recurring({ merchant: 'tv', label: 'Monthly', averageAmount: 649 }),
      recurring({ merchant: 'cleaner', label: 'Biweekly', averageAmount: 600 }),
    ]);
    const monthlyOf = (m: string) => result.rows.find((r) => r.item.merchant === m)!.monthly;
    expect(monthlyOf('milk')).toBeCloseTo(433.33, 2);
    expect(monthlyOf('insurance')).toBeCloseTo(300, 2);
    expect(monthlyOf('tv')).toBeCloseTo(649, 2);
    expect(monthlyOf('cleaner')).toBeCloseTo(1300, 2);
    expect(result.yearlyTotal).toBeCloseTo(result.monthlyTotal * 12, 6);
  });

  it('lists the largest monthly amount first and marks one whose latest payment went up', () => {
    const result = paidRegularly([
      recurring({ merchant: 'tv', averageAmount: 549, latestAmount: 649 }),
      recurring({ merchant: 'rent', averageAmount: 15000, latestAmount: 15000 }),
      recurring({ merchant: 'gym', averageAmount: 1500 }),
    ]);
    expect(result.rows.map((r) => [r.item.merchant, r.rising])).toEqual([['rent', false], ['gym', false], ['tv', true]]);
  });

  it('is empty with nothing repeating', () => {
    expect(paidRegularly([])).toEqual({ rows: [], monthlyTotal: 0, yearlyTotal: 0 });
  });
});

describe('monthLine', () => {
  const point = (month: string, expense: number, complete = true): ReportTrendPoint => ({
    month, income: 0, expense, transactionCount: 1, complete,
  });

  it('says what was spent and the change from the month before, as the phone does', () => {
    expect(monthLine('2026-09', [point('2026-08', 40000), point('2026-09', 48200)]))
      .toBe('You spent ₹48,200 in September 2026, 21% more than August.');
    expect(monthLine('2026-09', [point('2026-08', 50000), point('2026-09', 40000)]))
      .toBe('You spent ₹40,000 in September 2026, 20% less than August.');
    expect(monthLine('2026-09', [point('2026-08', 40000), point('2026-09', 40100)]))
      .toBe('You spent ₹40,100 in September 2026, the same as August.');
  });

  it('gives no comparison without a month before, and says "so far" for a month still coming in', () => {
    expect(monthLine('2026-09', [point('2026-09', 48200)])).toBe('You spent ₹48,200 in September 2026.');
    expect(monthLine('2026-09', [point('2026-08', 0), point('2026-09', 48200)])).toBe('You spent ₹48,200 in September 2026.');
    expect(monthLine('2026-10', [point('2026-09', 40000), point('2026-10', 9000, false)]))
      .toBe("You've spent ₹9,000 in October 2026 so far.");
  });

  it('compares January with the December before it', () => {
    expect(monthLine('2027-01', [point('2026-12', 10000), point('2027-01', 15000)]))
      .toBe('You spent ₹15,000 in January 2027, 50% more than December.');
  });

  it('is nothing without the month in the trend', () => {
    expect(monthLine('2026-09', [point('2026-08', 40000)])).toBeNull();
    expect(monthLine(null, [point('2026-09', 40000)])).toBeNull();
  });
});
