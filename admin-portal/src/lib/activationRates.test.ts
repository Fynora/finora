import { describe, expect, it } from 'vitest';
import { MIN_SIGNUPS_TO_JUDGE, importRate, sortRates, tooSmall, type RateRow } from './activationRates';

function row(key: string, signups: number, gotDataIn: number, cameBack: number | null = 0, pinned = false): RateRow {
  return { key, label: key, signups, gotDataIn, cameBackLaterDay: cameBack, pinned };
}

describe('importRate and tooSmall', () => {
  it('is got data in over signups, null with no signups', () => {
    expect(importRate(row('a', 8, 2))).toBe(0.25);
    expect(importRate(row('a', 0, 0))).toBeNull();
  });

  it('marks rows below the minimum, not at it, and not empty ones', () => {
    expect(MIN_SIGNUPS_TO_JUDGE).toBe(5);
    expect(tooSmall(row('a', 4, 1))).toBe(true);
    expect(tooSmall(row('a', 5, 1))).toBe(false);
    expect(tooSmall(row('a', 0, 0))).toBe(false);
  });
});

describe('sortRates', () => {
  const rows = [
    row('small-perfect', 1, 1, 1),
    row('big-half', 10, 5, 2),
    row('empty', 0, 0, 0),
    row('mid-low', 6, 1, 0),
    row('other', 20, 0, 0, true),
  ];

  it('sorts by signups, most first, with pinned rows last', () => {
    expect(sortRates(rows, { key: 'signups', descending: true }).map((r) => r.key))
      .toEqual(['big-half', 'mid-low', 'small-perfect', 'empty', 'other']);
  });

  it('sorts by rate with rows too small to judge and empty rows after the rest, either way', () => {
    expect(sortRates(rows, { key: 'rate', descending: true }).map((r) => r.key))
      .toEqual(['big-half', 'mid-low', 'small-perfect', 'empty', 'other']);
    expect(sortRates(rows, { key: 'rate', descending: false }).map((r) => r.key))
      .toEqual(['mid-low', 'big-half', 'empty', 'small-perfect', 'other']);
  });

  it('keeps the given order on ties and puts a missing came-back count last when descending', () => {
    const tied = [row('x', 3, 1, null), row('y', 3, 1, 1), row('z', 3, 1, 1)];
    expect(sortRates(tied, { key: 'signups', descending: true }).map((r) => r.key)).toEqual(['x', 'y', 'z']);
    expect(sortRates(tied, { key: 'cameBackLaterDay', descending: true }).map((r) => r.key)).toEqual(['y', 'z', 'x']);
  });

  it('keeps pinned rows in their given order, whatever their counts', () => {
    const withPinned = [row('none', 9, 1, 0, true), row('page', 2, 1), row('other', 1, 1, 0, true)];
    expect(sortRates(withPinned, { key: 'signups', descending: false }).map((r) => r.key)).toEqual(['page', 'none', 'other']);
    expect(sortRates(withPinned, { key: 'signups', descending: true }).map((r) => r.key)).toEqual(['page', 'none', 'other']);
  });

  it('has nothing to sort with no rows', () => {
    expect(sortRates([], { key: 'rate', descending: true })).toEqual([]);
  });
});
