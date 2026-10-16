import { describe, expect, it } from 'vitest';
import { categoryRows, changeVsAverage, donutSlices } from './categoryShare';

describe('categoryRows', () => {
  it('divides by the rows own total, so shares add to 100 and none passes it', () => {
    const { rows, total } = categoryRows([
      { category: 'Rent', amount: 500 }, { category: 'Food', amount: 300 }, { category: 'Investments', amount: 200 },
    ]);
    expect(total).toBe(1000);
    expect(rows.map((r) => r.share)).toEqual([50, 30, 20]);
    expect(rows.reduce((s, r) => s + r.share, 0)).toBeCloseTo(100);
  });

  it('keeps every share under 100 even when the report Expense would be smaller than the rows', () => {
    // Expense (investment transfers excluded) could be 800 while the rows hold 1000: dividing by
    // Expense would give 125% for the biggest row. Dividing by the rows cannot.
    const { rows } = categoryRows([{ category: 'Rent', amount: 500 }, { category: 'Investments', amount: 500 }]);
    expect(Math.max(...rows.map((r) => r.share))).toBeLessThanOrEqual(100);
  });

  it('sorts largest first regardless of the order it arrives in', () => {
    expect(categoryRows([{ category: 'A', amount: 1 }, { category: 'B', amount: 9 }]).rows.map((r) => r.category)).toEqual(['B', 'A']);
  });

  it('drops zero, negative and non-finite rows rather than drawing an empty slice', () => {
    const { rows, total } = categoryRows([
      { category: 'A', amount: 10 }, { category: 'B', amount: 0 }, { category: 'C', amount: -5 }, { category: 'D', amount: Number.NaN },
    ]);
    expect(rows.map((r) => r.category)).toEqual(['A']);
    expect(total).toBe(10);
  });

  it('is empty, with a zero total, for no spending', () => {
    expect(categoryRows([])).toEqual({ rows: [], total: 0 });
    expect(categoryRows([{ category: 'A', amount: 0 }])).toEqual({ rows: [], total: 0 });
  });

  it('gives a single category all of it', () => {
    expect(categoryRows([{ category: 'A', amount: 42 }]).rows[0].share).toBe(100);
  });
});

describe('donutSlices', () => {
  const rows = (n: number) => categoryRows(Array.from({ length: n }, (_, i) => ({ category: `C${i}`, amount: 100 - i }))).rows;

  it('keeps every category when there are few enough', () => {
    expect(donutSlices(rows(4)).map((s) => s.category)).toEqual(['C0', 'C1', 'C2', 'C3']);
  });

  it('keeps exactly maxSlices when the count equals it, with no Other', () => {
    const slices = donutSlices(rows(6), 6);
    expect(slices).toHaveLength(6);
    expect(slices.some((s) => s.category === null)).toBe(false);
  });

  it('groups the smallest into one Other slice past maxSlices, and the slices still add up', () => {
    const all = rows(9);
    const slices = donutSlices(all, 6);
    expect(slices).toHaveLength(6);
    expect(slices[5].category).toBeNull();
    expect(slices.reduce((s, x) => s + x.amount, 0)).toBeCloseTo(all.reduce((s, x) => s + x.amount, 0));
    expect(slices.reduce((s, x) => s + x.share, 0)).toBeCloseTo(100);
  });

  it('is empty for no rows', () => {
    expect(donutSlices([])).toEqual([]);
  });
});

describe('changeVsAverage', () => {
  it('points up for more spending and down for less', () => {
    expect(changeVsAverage(25.4)).toEqual({ text: '▲ 25%', tone: 'up' });
    expect(changeVsAverage(-12.2)).toEqual({ text: '▼ 12%', tone: 'down' });
  });
  it('reads as in line when it rounds to nothing, either way', () => {
    expect(changeVsAverage(0)).toEqual({ text: 'In line', tone: 'flat' });
    expect(changeVsAverage(0.4)).toEqual({ text: 'In line', tone: 'flat' });
    expect(changeVsAverage(-0.4)).toEqual({ text: 'In line', tone: 'flat' });
  });
  it('is null with nothing to compare against (a new category) or a bad number', () => {
    expect(changeVsAverage(null)).toBeNull();
    expect(changeVsAverage(undefined)).toBeNull();
    expect(changeVsAverage(Number.NaN)).toBeNull();
  });
});
