import { doneCountsLine, doneLines, statementMonthOf } from './importDone';
import type { LookAtRow } from './insightsFeed';
import type { PeopleSuggestion, RecurringItem } from '../api/endpoints';

const card: LookAtRow = {
  kind: 'card-statement', month: '2026-09', total: 4200, count: 2, cardName: 'Sample Card', cardLast4: '0002',
  ackKind: 'CARD_STATEMENT_MISSING', ackItemKey: '2026-09',
};
const recurring: LookAtRow = {
  kind: 'new-recurring',
  item: { merchant: 'Sample Gym', label: 'Monthly', averageAmount: 999, occurrences: 3, lastDate: '2026-09-03', nextEstimate: '2026-10-03' } as RecurringItem,
};
const changed: LookAtRow = {
  kind: 'amount-changed', merchant: 'Sample Tv', category: 'Subscriptions', latestAmount: 649, latestDate: '2026-09-05',
  state: 'AMOUNT_CHANGED', ackKind: 'AMOUNT_CHANGED', ackItemKey: 'Sample Tv',
};
const high: LookAtRow = {
  kind: 'running-high', category: 'Food', current: 9400, priorAverage: 6300, month: '2026-09',
  ackKind: 'CATEGORY_HIGH', ackItemKey: 'Food',
};
const people: LookAtRow = { kind: 'people', item: { kind: 'SHARE_OF_BILL' } as unknown as PeopleSuggestion };

describe('doneLines', () => {
  it('words a card bill with no card statement', () => {
    expect(doneLines([card])[0]).toMatchObject({
      kind: 'card-statement',
      title: 'Card statement missing for September 2026',
      detail: 'You paid ₹4,200 toward your Sample Card ending 0002',
    });
  });

  it('words a new repeating payment', () => {
    expect(doneLines([recurring])[0]).toMatchObject({
      title: 'New repeating payment: Sample Gym', detail: 'About ₹999 each time',
    });
  });

  it('words a changed amount', () => {
    expect(doneLines([changed])[0]).toMatchObject({ title: 'Amount changed: Sample Tv', detail: 'Now ₹649' });
  });

  it('words a category running high as Insights does', () => {
    expect(doneLines([high])[0]).toMatchObject({
      title: 'Food is ₹3,100 above your usual month', detail: '₹9,400 vs ₹6,300',
    });
  });

  it('leaves out people rows and keeps three, in order', () => {
    const lines = doneLines([people, high, changed, recurring, card]);
    expect(lines.map((l) => l.kind)).toEqual(['running-high', 'amount-changed', 'new-recurring']);
    expect(new Set(lines.map((l) => l.key)).size).toBe(3);
  });
});

describe('doneCountsLine', () => {
  it('names only the counts that are not zero', () => {
    expect(doneCountsLine(1, 0)).toBe('1 skipped');
    expect(doneCountsLine(2, 1)).toBe('2 skipped, 1 duplicate');
    expect(doneCountsLine(0, 3)).toBe('3 duplicates');
    expect(doneCountsLine(0, 0)).toBeNull();
  });
});

describe('statementMonthOf', () => {
  it('is the month of the statement end', () => {
    expect(statementMonthOf('2026-09-30')).toBe('2026-09');
    expect(statementMonthOf(null)).toBeNull();
  });
});
