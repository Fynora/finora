import { describe, expect, it } from 'vitest';
import type { ReportTrendPoint } from '../../api/endpoints';
import { averageSpend, shortMonth, trendCeiling } from './trend';

const point = (month: string, income: number, expense: number, complete = true): ReportTrendPoint =>
  ({ month, income, expense, transactionCount: 10, complete });

describe('averageSpend', () => {
  it('is the mean spending of the whole months', () => {
    expect(averageSpend([point('2026-06', 100, 40), point('2026-07', 100, 60), point('2026-08', 100, 80)])).toBe(60);
  });

  it('leaves a partly-in month out, since it would drag the line down', () => {
    expect(averageSpend([point('2026-07', 100, 60), point('2026-08', 100, 80), point('2026-09', 100, 5, false)])).toBe(70);
  });

  it('is null with fewer than two whole months, rather than drawn from too little', () => {
    expect(averageSpend([])).toBeNull();
    expect(averageSpend([point('2026-08', 100, 80)])).toBeNull();
    expect(averageSpend([point('2026-08', 100, 80), point('2026-09', 100, 5, false)])).toBeNull();
  });

  it('counts a month of zero spending as a month, not as missing', () => {
    expect(averageSpend([point('2026-07', 100, 0), point('2026-08', 100, 100)])).toBe(50);
  });
});

describe('trendCeiling', () => {
  it('is the tallest bar across income and expenses', () => {
    expect(trendCeiling([point('2026-07', 100, 40), point('2026-08', 70, 250)])).toBe(250);
  });
  it('is never zero, so a scale built from it cannot divide by zero', () => {
    expect(trendCeiling([])).toBe(1);
    expect(trendCeiling([point('2026-08', 0, 0)])).toBe(1);
  });
});

describe('shortMonth', () => {
  it('names the month, including the first and last of the year', () => {
    expect(shortMonth('2026-09')).toBe('Sep');
    expect(shortMonth('2026-01')).toBe('Jan');
    expect(shortMonth('2026-12')).toBe('Dec');
  });
});
