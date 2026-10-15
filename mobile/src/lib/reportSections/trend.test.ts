import type { ReportTrendPoint } from '../../api/endpoints';
import { averageSpend, shortDate, shortMonth, trendCeiling } from './trend';

const point = (month: string, income: number, expense: number, complete = true): ReportTrendPoint =>
  ({ month, income, expense, transactionCount: 10, complete });

describe('averageSpend', () => {
  it('is the mean spending of the whole months', () => {
    expect(averageSpend([point('2026-06', 100, 40), point('2026-07', 100, 60), point('2026-08', 100, 80)])).toBe(60);
  });
  it('leaves a partly-in month out, since it would drag the line down', () => {
    expect(averageSpend([point('2026-07', 100, 60), point('2026-08', 100, 80), point('2026-09', 100, 5, false)])).toBe(70);
  });
  it('is null with fewer than two whole months', () => {
    expect(averageSpend([])).toBeNull();
    expect(averageSpend([point('2026-08', 100, 80)])).toBeNull();
    expect(averageSpend([point('2026-08', 100, 80), point('2026-09', 100, 5, false)])).toBeNull();
  });
  it('counts a month of zero spending as a month, not as missing', () => {
    expect(averageSpend([point('2026-07', 100, 0), point('2026-08', 100, 100)])).toBe(50);
  });
});

describe('trendCeiling', () => {
  it('is the tallest bar across income and expenses, and never zero', () => {
    expect(trendCeiling([point('2026-07', 100, 40), point('2026-08', 70, 250)])).toBe(250);
    expect(trendCeiling([])).toBe(1);
    expect(trendCeiling([point('2026-08', 0, 0)])).toBe(1);
  });
});

describe('shortMonth and shortDate', () => {
  it('name a month or a date by its own value, never the one before', () => {
    expect(shortMonth('2026-09')).toBe('Sep');
    expect(shortMonth('2026-01')).toBe('Jan');
    expect(shortMonth('2026-12')).toBe('Dec');
    expect(shortDate('2026-09-14')).toBe('Sep 14');
    expect(shortDate('2026-01-01')).toBe('Jan 1');
    expect(shortDate('2026-12-31')).toBe('Dec 31');
  });
});
