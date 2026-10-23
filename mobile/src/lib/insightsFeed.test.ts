import type {
  ChangedAmountItem, ExpectedShare, InsightsData, LookAtServerRow, PeopleSuggestion, RecurringItem,
} from '../api/endpoints';
import {
  ackKey, buildToLookAt, comingUp, expectedSharesIn, lookAtRowsFromServer, paidRegularly, withPeople,
} from './insightsFeed';

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

function suggestion(overrides: Partial<PeopleSuggestion> = {}): PeopleSuggestion {
  return {
    kind: 'SHARE_CHECK', ackKey: 't-1', txnId: 't-1', relatedTxnId: null, personKey: 'vpa:flatmate.a',
    label: 'Flatmate A', amount: 9000, date: '2026-10-04', category: 'Rent', bills: [], owed: null,
    ...overrides,
  };
}

describe('buildToLookAt: money with people', () => {
  it('lists the server rows after the card statement and before repeating payments, in server order', () => {
    const rows = buildToLookAt({
      insights: insights({ unlinkedCardBills: { month: '2026-09', total: 1, count: 1, cardName: null, cardLast4: null } }),
      recurring: [recurring({ state: 'NEEDS_ANSWER' })],
      changed: [],
      acks: none,
      people: [suggestion({ ackKey: 'a' }), suggestion({ kind: 'LENT_CHECK', ackKey: 'b', txnId: 'b' })],
    });
    expect(rows.map((r) => r.kind)).toEqual(['card-statement', 'people', 'people', 'new-recurring']);
    const people = rows.filter((r) => r.kind === 'people');
    expect(people.map((r) => r.kind === 'people' && r.item.ackKey)).toEqual(['a', 'b']);
  });

  it('hides a row marked "Got it" under its own kind and key, at once', () => {
    const rows = buildToLookAt({
      recurring: [], changed: [], acks: new Set(['SHARE_CHECK|a']),
      people: [suggestion({ ackKey: 'a' }), suggestion({ kind: 'LENT_CHECK', ackKey: 'a' })],
    });
    expect(rows.map((r) => r.kind === 'people' && r.item.kind)).toEqual(['LENT_CHECK']);
  });

  it('shows none when the server sends none, or an older server has no such endpoint', () => {
    expect(buildToLookAt({ recurring: [], changed: [], acks: none, people: [] })).toEqual([]);
    expect(buildToLookAt({ recurring: [], changed: [], acks: none })).toEqual([]);
  });
});

describe('withPeople', () => {
  it('splits balances into owed to you and you owe, largest first, never a family person or a zero', () => {
    const { owedToYou, youOwe } = withPeople([
      { personKey: 'a', label: 'A', owedToUser: 500, since: '2026-09-03', family: false },
      { personKey: 'b', label: 'B', owedToUser: 1200, since: '2026-09-10', family: false },
      { personKey: 'c', label: 'C', owedToUser: -800, since: '2026-09-12', family: false },
      { personKey: 'd', label: 'D', owedToUser: 3000, since: '2026-09-01', family: true },
      { personKey: 'e', label: 'E', owedToUser: 0, since: null, family: false },
    ]);
    expect(owedToYou.map((p) => p.personKey)).toEqual(['b', 'a']);
    expect(youOwe.map((p) => p.personKey)).toEqual(['c']);
  });
});

describe('expectedSharesIn', () => {
  const share = (overrides: Partial<ExpectedShare> = {}): ExpectedShare => ({
    personKey: 'vpa:flatmate.a', label: 'Flatmate A', usualAmount: 9000, usualDay: 4, late: false, ruleId: 'r1',
    ...overrides,
  });

  it('says whose share, around which day, and marks one already late', () => {
    expect(expectedSharesIn([share(), share({ ruleId: 'r2', label: 'Flatmate B', usualDay: 21, late: true })]))
      .toEqual([
        { key: 'r1', title: "Flatmate A's share", when: 'around the 4th', amount: 9000, late: false },
        { key: 'r2', title: "Flatmate B's share", when: 'late, usually around the 21st', amount: 9000, late: true },
      ]);
  });

  it('names the day with its English ending', () => {
    const when = (day: number) => expectedSharesIn([share({ usualDay: day })])[0].when;
    expect([1, 2, 3, 11, 12, 13, 22, 23, 30].map(when)).toEqual([
      'around the 1st', 'around the 2nd', 'around the 3rd', 'around the 11th', 'around the 12th',
      'around the 13th', 'around the 22nd', 'around the 23rd', 'around the 30th',
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

  it('is empty with nothing repeating', () => {
    expect(comingUp([], today)).toEqual({ rows: [], stillToGoOut: 0 });
  });
});

describe('paidRegularly', () => {
  it('counts the user\'s own share of a bill other people share, never the full amount paid', () => {
    const { rows, monthlyTotal } = paidRegularly([
      recurring({ merchant: 'sample owner', averageAmount: 36000, yourShare: 9000, sharedWith: 3 }),
      recurring({ merchant: 'sample gym', averageAmount: 1500 }),
    ]);
    expect(rows.map((r) => [r.item.merchant, r.monthly])).toEqual([['sample owner', 9000], ['sample gym', 1500]]);
    expect(monthlyTotal).toBe(10500);
  });

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

describe('lookAtRowsFromServer', () => {
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

  it('gives every kind the shape the phone builds, in the server\'s order', () => {
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
        ackKind: 'CATEGORY_HIGH', ackItemKey: 'Food|2026-09', whyAvailable: true,
      },
      {
        kind: 'card-statement', month: '2026-09', total: 21400, count: 2, cardName: 'Sample Card', cardLast4: '0001',
        ackKind: 'CARD_STATEMENT_MISSING', ackItemKey: '2026-09',
      },
      { kind: 'people', item: person },
      { kind: 'new-recurring', item: recurring({ state: 'NEEDS_ANSWER' }) },
      {
        kind: 'amount-changed', merchant: 'Sample TV', category: 'Subscriptions', latestAmount: 649,
        latestDate: '2026-09-12', state: 'AMOUNT_CHANGED', ackKind: 'AMOUNT_CHANGED', ackItemKey: 'sample tv|2026-09-12',
      },
    ]);
  });

  it('builds the same rows from the same data as buildToLookAt', () => {
    // The server port (LookAtService) keeps the phone's rules; a running-high row reads the same either way.
    const local = buildToLookAt({
      insights: insights({ movers: [{ category: 'Food', current: 9400, priorAverage: 6300, pctChange: 49 }] }),
      recurring: [], changed: [], acks: new Set(),
    });
    const server = lookAtRowsFromServer([row({
      kind: 'running-high', key: 'CATEGORY_HIGH|Food|2026-09', ackKind: 'CATEGORY_HIGH', ackItemKey: 'Food|2026-09',
      month: '2026-09', category: 'Food', current: 9400, priorAverage: 6300, whyAvailable: false,
    })]);
    expect(server).toEqual(local.map((r) => ({ ...r, whyAvailable: false })));
  });

  it('drops a row of a kind it does not know, or missing what it needs, rather than drawing it broken', () => {
    const rows = lookAtRowsFromServer([
      row({ kind: 'some-new-kind' as LookAtServerRow['kind'], key: 'X|1' }),
      row({ kind: 'people', key: 'SHARE_CHECK|t-1' }),
      row({ kind: 'running-high', key: 'CATEGORY_HIGH|Food|2026-09', category: 'Food' }),
      row({ kind: 'card-statement', key: 'CARD_STATEMENT_MISSING|2026-09', month: '2026-09' }),
    ]);
    expect(rows).toEqual([]);
  });

  it('keeps an amount-changed row with no category, as the phone does', () => {
    const [only] = lookAtRowsFromServer([row({
      kind: 'amount-changed', key: 'AMOUNT_CHANGED|sample tv|2026-09-12', ackKind: 'AMOUNT_CHANGED',
      ackItemKey: 'sample tv|2026-09-12', merchant: 'Sample TV', latestAmount: 649, latestDate: '2026-09-12',
    })]);
    expect(only).toEqual(expect.objectContaining({ kind: 'amount-changed', category: '' }));
  });
});
