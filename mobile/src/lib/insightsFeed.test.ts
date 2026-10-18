import type { ChangedAmountItem, InsightsData, RecurringItem } from '../api/endpoints';
import { ackKey, buildToLookAt, comingUp, paidRegularly } from './insightsFeed';

// Every name and amount here is invented.

function insights(overrides: Partial<InsightsData> = {}): InsightsData {
  return {
    sentences: [],
    movers: [],
    coverageCaveat: null,
    biggestCategory: null,
    topMerchant: null,
    reportingMonth: '2026-09',
    reportingMonthIsCurrent: false,
    unlinkedCardBills: null,
    ...overrides,
  };
}

function recurring(overrides: Partial<RecurringItem> = {}): RecurringItem {
  return {
    merchant: 'sample gym',
    label: 'Monthly',
    averageAmount: 1500,
    occurrences: 3,
    lastDate: '2026-09-05',
    nextEstimate: '2026-10-05',
    state: 'NONE',
    ...overrides,
  };
}

const none = new Set<string>();

describe('ackKey', () => {
  it('builds each kind key as the server stores it', () => {
    expect(ackKey('CATEGORY_HIGH', { category: 'Food', month: '2026-09' })).toBe('Food|2026-09');
    expect(ackKey('AMOUNT_CHANGED', { merchant: '  Sample TV ', date: '2026-09-12' })).toBe('sample tv|2026-09-12');
    expect(ackKey('CARD_STATEMENT_MISSING', { month: '2026-09' })).toBe('2026-09');
  });
});

describe('buildToLookAt: running high', () => {
  it('keeps a category at least 15% and at least ₹500 above its usual month', () => {
    const rows = buildToLookAt({
      insights: insights({ movers: [
        { category: 'Food', current: 9400, priorAverage: 6300, pctChange: 49 },
        { category: 'Fuel', current: 800, priorAverage: 400, pctChange: 100 },
        { category: 'Rent', current: 20000, priorAverage: 18000, pctChange: 11 },
        { category: 'New', current: 3000, priorAverage: 0, pctChange: null },
      ] }),
      recurring: [], changed: [], acks: none,
    });
    expect(rows).toEqual([expect.objectContaining({
      kind: 'running-high', category: 'Food', current: 9400, priorAverage: 6300, month: '2026-09',
      ackKind: 'CATEGORY_HIGH', ackItemKey: 'Food|2026-09',
    })]);
  });

  it('shows at most three, the largest rupee gap first', () => {
    const rows = buildToLookAt({
      insights: insights({ movers: [
        { category: 'A', current: 2000, priorAverage: 1000, pctChange: 100 },
        { category: 'B', current: 9000, priorAverage: 3000, pctChange: 200 },
        { category: 'C', current: 5000, priorAverage: 2000, pctChange: 150 },
        { category: 'D', current: 4000, priorAverage: 2500, pctChange: 60 },
      ] }),
      recurring: [], changed: [], acks: none,
    });
    expect(rows.map((r) => r.kind === 'running-high' && r.category)).toEqual(['B', 'C', 'D']);
  });

  it('is exactly at the thresholds: 15% and ₹500 are enough', () => {
    const rows = buildToLookAt({
      insights: insights({ movers: [{ category: 'Edge', current: 3833.34, priorAverage: 3333.34, pctChange: 15 }] }),
      recurring: [], changed: [], acks: none,
    });
    expect(rows).toHaveLength(1);
  });

  it('hides a category marked "Got it" for that month only', () => {
    const movers = [{ category: 'Food', current: 9400, priorAverage: 6300, pctChange: 49 }];
    const acks = new Set(['CATEGORY_HIGH|Food|2026-09']);
    expect(buildToLookAt({ insights: insights({ movers }), recurring: [], changed: [], acks })).toEqual([]);
    expect(buildToLookAt({ insights: insights({ movers, reportingMonth: '2026-10' }), recurring: [], changed: [], acks }))
      .toHaveLength(1);
  });

  it('says nothing about a month it cannot name', () => {
    const rows = buildToLookAt({
      insights: insights({ reportingMonth: null, movers: [{ category: 'Food', current: 9400, priorAverage: 6300, pctChange: 49 }] }),
      recurring: [], changed: [], acks: none,
    });
    expect(rows).toEqual([]);
  });
});

describe('buildToLookAt: repeating payments', () => {
  it('asks about a new repeating payment, which is answered, never dismissed', () => {
    const item = recurring({ state: 'NEEDS_ANSWER' });
    const rows = buildToLookAt({ insights: insights(), recurring: [item, recurring({ merchant: 'other', state: 'ANSWERED' })], changed: [], acks: none });
    expect(rows).toEqual([{ kind: 'new-recurring', item }]);
  });

  it('shows an amount change once when both lists report the same payee', () => {
    const fromList = recurring({ merchant: 'Sample TV', state: 'AMOUNT_CHANGED', category: 'Subscriptions', latestAmount: 649, lastDate: '2026-09-12' });
    const changed: ChangedAmountItem[] = [
      { merchant: ' sample tv ', category: 'Subscriptions', latestAmount: 649, latestDate: '2026-09-12', amountMin: 400, amountMax: 500 },
      { merchant: 'Sample Music', category: 'Subscriptions', latestAmount: 199, latestDate: '2026-09-02', amountMin: 100, amountMax: 120 },
    ];
    const rows = buildToLookAt({ insights: insights(), recurring: [fromList], changed, acks: none });
    expect(rows).toEqual([
      expect.objectContaining({ kind: 'amount-changed', merchant: 'Sample TV', latestAmount: 649, latestDate: '2026-09-12', ackItemKey: 'sample tv|2026-09-12' }),
      expect.objectContaining({ kind: 'amount-changed', merchant: 'Sample Music', ackKind: 'AMOUNT_CHANGED', ackItemKey: 'sample music|2026-09-02' }),
    ]);
  });

  it('hides an amount change marked "Got it", until a later payment moves again', () => {
    const changed: ChangedAmountItem[] = [{ merchant: 'Sample TV', category: 'Subscriptions', latestAmount: 649, latestDate: '2026-09-12', amountMin: 400, amountMax: 500 }];
    const acks = new Set(['AMOUNT_CHANGED|sample tv|2026-09-12']);
    expect(buildToLookAt({ insights: insights(), recurring: [], changed, acks })).toEqual([]);
    const later = [{ ...changed[0], latestDate: '2026-10-12' }];
    expect(buildToLookAt({ insights: insights(), recurring: [], changed: later, acks })).toHaveLength(1);
  });
});

describe('buildToLookAt: card statement missing', () => {
  const bills = { month: '2026-09', total: 21400, count: 2, cardName: 'Sample Card', cardLast4: '0001' };

  it('appears first, with the card when known', () => {
    const rows = buildToLookAt({
      insights: insights({ unlinkedCardBills: bills, movers: [{ category: 'Food', current: 9400, priorAverage: 6300, pctChange: 49 }] }),
      recurring: [recurring({ state: 'NEEDS_ANSWER' })], changed: [], acks: none,
    });
    expect(rows.map((r) => r.kind)).toEqual(['card-statement', 'new-recurring', 'running-high']);
    expect(rows[0]).toEqual(expect.objectContaining({
      kind: 'card-statement', month: '2026-09', total: 21400, count: 2, cardName: 'Sample Card', cardLast4: '0001',
      ackKind: 'CARD_STATEMENT_MISSING', ackItemKey: '2026-09',
    }));
  });

  it('is absent when the server sends none, or an older server sends no field', () => {
    expect(buildToLookAt({ insights: insights({ unlinkedCardBills: null }), recurring: [], changed: [], acks: none })).toEqual([]);
    const older = insights();
    delete (older as Partial<InsightsData>).unlinkedCardBills;
    expect(buildToLookAt({ insights: older, recurring: [], changed: [], acks: none })).toEqual([]);
    expect(buildToLookAt({ insights: undefined, recurring: [], changed: [], acks: none })).toEqual([]);
  });

  it('is hidden when marked "Got it"', () => {
    const acks = new Set(['CARD_STATEMENT_MISSING|2026-09']);
    expect(buildToLookAt({ insights: insights({ unlinkedCardBills: bills }), recurring: [], changed: [], acks })).toEqual([]);
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

  it('is empty with nothing repeating', () => {
    expect(comingUp([], today)).toEqual({ rows: [], stillToGoOut: 0 });
  });
});

describe('paidRegularly', () => {
  it('turns each cadence into a monthly amount and totals the month and the year', () => {
    const weekly = recurring({ merchant: 'milk', label: 'Weekly', averageAmount: 100 });
    const quarterly = recurring({ merchant: 'insurance', label: 'Quarterly', averageAmount: 900 });
    const monthly = recurring({ merchant: 'tv', label: 'Monthly', averageAmount: 649 });
    const biweekly = recurring({ merchant: 'cleaner', label: 'Biweekly', averageAmount: 600 });
    const result = paidRegularly([weekly, quarterly, monthly, biweekly]);
    const monthlyOf = (m: string) => result.rows.find((r) => r.item.merchant === m)!.monthly;
    expect(monthlyOf('milk')).toBeCloseTo(433.33, 2);
    expect(monthlyOf('insurance')).toBeCloseTo(300, 2);
    expect(monthlyOf('tv')).toBeCloseTo(649, 2);
    expect(monthlyOf('cleaner')).toBeCloseTo(1300, 2);
    expect(result.monthlyTotal).toBeCloseTo(433.33 + 300 + 649 + 1300, 1);
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
