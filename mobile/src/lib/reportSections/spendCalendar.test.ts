import { buildSpendCalendar, daysInMonth, leadingBlanksFor } from './spendCalendar';

describe('daysInMonth', () => {
  it('knows month lengths, including February in leap and non-leap years', () => {
    expect(daysInMonth('2026-09')).toBe(30);
    expect(daysInMonth('2026-10')).toBe(31);
    expect(daysInMonth('2026-02')).toBe(28);
    expect(daysInMonth('2028-02')).toBe(29);
    expect(daysInMonth('2100-02')).toBe(28);
  });
});

describe('leadingBlanksFor', () => {
  it('starts the grid on a Monday: Monday needs none, Sunday needs six', () => {
    expect(leadingBlanksFor('2026-06')).toBe(0);
    expect(leadingBlanksFor('2026-09')).toBe(1);
    expect(leadingBlanksFor('2026-02')).toBe(6);
  });
});

describe('buildSpendCalendar', () => {
  const days = (entries: [string, number][]) => entries.map(([date, amount]) => ({ date, amount }));

  it('has one cell per day, with spend on the days that had it', () => {
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
    expect(cal.cells.slice(0, 6).map((c) => c.level)).toEqual([4, 4, 3, 2, 1, 1]);
  });

  it('puts a quarter exactly on the lower step', () => {
    const cal = buildSpendCalendar('2026-09', days([['2026-09-01', 100], ['2026-09-02', 75], ['2026-09-03', 50], ['2026-09-04', 25]]));
    expect(cal.cells.slice(0, 4).map((c) => c.level)).toEqual([4, 3, 2, 1]);
  });

  it('a lone spending day is the darkest, however small, and the earlier day wins a tie', () => {
    expect(buildSpendCalendar('2026-09', days([['2026-09-09', 5]])).cells[8].level).toBe(4);
    expect(buildSpendCalendar('2026-09', days([['2026-09-20', 500], ['2026-09-05', 500]])).biggest).toMatchObject({ day: 5 });
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
  });

  it('sums two entries for the same day', () => {
    expect(buildSpendCalendar('2026-09', days([['2026-09-12', 100], ['2026-09-12', 50]])).cells[11].amount).toBe(150);
  });

  it('draws 28 or 29 cells for February', () => {
    expect(buildSpendCalendar('2026-02', []).cells).toHaveLength(28);
    expect(buildSpendCalendar('2028-02', []).cells).toHaveLength(29);
  });
});
