import { describe, expect, it } from 'vitest';
import { buildSpendCalendar, daysInMonth, leadingBlanksFor } from './spendCalendar';

describe('daysInMonth', () => {
  it('knows month lengths, including February in leap and non-leap years', () => {
    expect(daysInMonth('2026-09')).toBe(30);
    expect(daysInMonth('2026-10')).toBe(31);
    expect(daysInMonth('2026-02')).toBe(28);
    expect(daysInMonth('2028-02')).toBe(29);
    expect(daysInMonth('2100-02')).toBe(28); // a century that is not a leap year
  });
});

describe('leadingBlanksFor', () => {
  it('starts the grid on a Monday: Monday needs none, Sunday needs six', () => {
    expect(leadingBlanksFor('2026-06')).toBe(0); // 1 June 2026 is a Monday
    expect(leadingBlanksFor('2026-09')).toBe(1); // Tuesday
    expect(leadingBlanksFor('2026-02')).toBe(6); // Sunday
  });
});

describe('buildSpendCalendar', () => {
  const days = (entries: [string, number][]) => entries.map(([date, amount]) => ({ date, amount }));

  it('has one cell per day of the month, with spend on the days that had it', () => {
    const cal = buildSpendCalendar('2026-09', days([['2026-09-14', 3120], ['2026-09-03', 400]]));
    expect(cal.cells).toHaveLength(30);
    expect(cal.cells[13]).toMatchObject({ day: 14, date: '2026-09-14', amount: 3120 });
    expect(cal.cells[0]).toMatchObject({ day: 1, amount: 0, level: 0 });
    expect(cal.leadingBlanks).toBe(1);
  });

  it('gives intensity relative to the month own biggest day, in quarter steps', () => {
    const cal = buildSpendCalendar('2026-09', days([
      ['2026-09-01', 1000], ['2026-09-02', 760], ['2026-09-03', 510], ['2026-09-04', 260], ['2026-09-05', 250], ['2026-09-06', 10],
    ]));
    const level = (d: number) => cal.cells[d - 1].level;
    expect([level(1), level(2), level(3), level(4), level(5), level(6)]).toEqual([4, 4, 3, 2, 1, 1]);
  });

  it('puts a quarter exactly on the lower step (the bands are exclusive at the bottom)', () => {
    const cal = buildSpendCalendar('2026-09', days([['2026-09-01', 100], ['2026-09-02', 75], ['2026-09-03', 50], ['2026-09-04', 25]]));
    expect(cal.cells.slice(0, 4).map((c) => c.level)).toEqual([4, 3, 2, 1]);
  });

  it('a lone spending day is the darkest, however small', () => {
    const cal = buildSpendCalendar('2026-09', days([['2026-09-09', 5]]));
    expect(cal.cells[8].level).toBe(4);
    expect(cal.biggest?.day).toBe(9);
  });

  it('names the biggest day, and the earlier one on a tie', () => {
    const cal = buildSpendCalendar('2026-09', days([['2026-09-20', 500], ['2026-09-05', 500], ['2026-09-10', 100]]));
    expect(cal.biggest).toMatchObject({ day: 5, amount: 500 });
  });

  it('is empty, with no biggest day, when nothing was spent', () => {
    const cal = buildSpendCalendar('2026-09', []);
    expect(cal.biggest).toBeNull();
    expect(cal.cells.every((c) => c.level === 0 && c.amount === 0)).toBe(true);
  });

  it('ignores entries outside the month, malformed dates, and non-positive or non-finite amounts', () => {
    const cal = buildSpendCalendar('2026-09', days([
      ['2026-08-31', 999], ['2026-10-01', 999], ['2026-09-31', 999], ['2026-09-xx', 999], ['2026-09-00', 999],
      ['2026-09-07', 0], ['2026-09-08', -50], ['2026-09-09', Number.NaN], ['2026-09-10', 120],
    ]));
    expect(cal.cells.filter((c) => c.amount > 0).map((c) => c.day)).toEqual([10]);
    expect(cal.biggest?.amount).toBe(120);
  });

  it('sums two entries for the same day rather than keeping one', () => {
    const cal = buildSpendCalendar('2026-09', days([['2026-09-12', 100], ['2026-09-12', 50]]));
    expect(cal.cells[11].amount).toBe(150);
  });

  it('draws a 28-day February with the right number of cells, and a leap one with 29', () => {
    expect(buildSpendCalendar('2026-02', []).cells).toHaveLength(28);
    expect(buildSpendCalendar('2028-02', []).cells).toHaveLength(29);
  });
});
