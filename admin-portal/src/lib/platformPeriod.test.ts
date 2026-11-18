import { describe, it, expect } from 'vitest';
import { changePercent, periodChoices, rupees, signed, spanLabel } from './platformPeriod';

describe('periodChoices', () => {
  it('lists the last twelve full months first, crossing a year, then three months and all time', () => {
    const choices = periodChoices(new Date(2026, 0, 15));

    expect(choices).toHaveLength(14);
    expect(choices[0]).toEqual({ key: 'month:2025-12', label: 'December 2025', period: { from: '2025-12', months: 1 } });
    expect(choices[11].period).toEqual({ from: '2025-01', months: 1 });
    expect(choices[12]).toEqual({ key: 'last3', label: 'Last 3 full months', period: { from: '2025-10', months: 3 } });
    expect(choices[13]).toEqual({ key: 'all', label: 'All time' });
  });

  it('leaves the month in progress out, even on its last day', () => {
    expect(periodChoices(new Date(2026, 10, 30))[0].period).toEqual({ from: '2026-10', months: 1 });
    expect(periodChoices(new Date(2026, 10, 1))[0].period).toEqual({ from: '2026-10', months: 1 });
  });
});

describe('spanLabel', () => {
  it('names one month, a span in one year, and a span across years', () => {
    expect(spanLabel('2026-09-01', '2026-09-30')).toBe('September 2026');
    expect(spanLabel('2026-07-01', '2026-09-30')).toBe('July – September 2026');
    expect(spanLabel('2025-11-01', '2026-01-31')).toBe('November 2025 – January 2026');
  });
});

describe('rupees', () => {
  it('rounds to whole rupees with Indian grouping', () => {
    expect(rupees(123456.5)).toBe('₹1,23,457');
    expect(rupees(0.4)).toBe('₹0');
    expect(rupees(-1500)).toBe('-₹1,500');
  });
});

describe('changePercent', () => {
  it('is a whole percent, and null with nothing to compare with', () => {
    expect(changePercent(115, 100)).toBe(15);
    expect(changePercent(50, 100)).toBe(-50);
    expect(changePercent(100, 100)).toBe(0);
    expect(changePercent(100, null)).toBeNull();
    expect(changePercent(100, undefined)).toBeNull();
    expect(changePercent(100, 0)).toBeNull();
  });
});

describe('signed', () => {
  it('marks a rise with + and a fall with a minus sign', () => {
    expect(signed(6)).toBe('+6');
    expect(signed(-3)).toBe('−3');
    expect(signed(0)).toBe('0');
  });
});
