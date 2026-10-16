import { describe, expect, it } from 'vitest';
import { categoryChanges } from './changeVsMonth';

const row = (category: string, amount: number) => ({ category, amount });

describe('categoryChanges', () => {
  it('lists the biggest rupee moves first, each with its direction', () => {
    const out = categoryChanges(
      [row('Rent', 25000), row('Food', 9000), row('Travel', 4000)],
      [row('Rent', 23000), row('Food', 12000), row('Travel', 3900)],
    );
    expect(out.map((c) => [c.category, c.delta, c.kind])).toEqual([
      ['Food', -3000, 'down'],
      ['Rent', 2000, 'up'],
      ['Travel', 100, 'up'],
    ]);
  });

  it('calls a category with spend in only one of the months new or gone', () => {
    const out = categoryChanges([row('Gifts', 380)], [row('Gym', 1200)]);
    expect(out).toEqual([
      { category: 'Gym', now: 0, before: 1200, delta: -1200, kind: 'gone' },
      { category: 'Gifts', now: 380, before: 0, delta: 380, kind: 'new' },
    ]);
  });

  it('leaves out a category that did not move, or moved by under half a rupee', () => {
    expect(categoryChanges([row('Rent', 25000), row('Food', 100.3)], [row('Rent', 25000), row('Food', 100)])).toEqual([]);
  });

  it('leaves out the catch-all Uncategorized bucket, which is unsorted money rather than a choice', () => {
    expect(categoryChanges([row('Uncategorized', 9000)], [row('Uncategorized', 100)])).toEqual([]);
  });

  it('matches names ignoring case and surrounding space, and keeps this month\'s spelling', () => {
    const out = categoryChanges([row('Food & Dining', 500)], [row(' food & dining ', 300)]);
    expect(out).toEqual([{ category: 'Food & Dining', now: 500, before: 300, delta: 200, kind: 'up' }]);
  });

  it('ignores a refund-netted row that is not a positive amount, as the category list does', () => {
    const out = categoryChanges([row('Shopping', -50)], [row('Shopping', 400)]);
    expect(out).toEqual([{ category: 'Shopping', now: 0, before: 400, delta: -400, kind: 'gone' }]);
  });

  it('keeps only the biggest few, and breaks a tie by name so the order is stable', () => {
    const now = ['A', 'B', 'C', 'D', 'E', 'F', 'G'].map((n) => row(n, 1000));
    const out = categoryChanges(now, []);
    expect(out.map((c) => c.category)).toEqual(['A', 'B', 'C', 'D', 'E']);
    expect(categoryChanges(now, [], 2)).toHaveLength(2);
  });

  it('is empty when there is nothing in either month', () => {
    expect(categoryChanges([], [])).toEqual([]);
  });
});
