import type { AdvancedMonth } from '../../api/endpoints';
import { flowDomain, flowTicks, monthlyPoints, niceCeiling, yearlyPoints } from './cashFlow';

const month = (m: string, income: number, expense: number, complete = true): AdvancedMonth => ({
  month: m, income, expense, net: income - expense, savingsRatePct: null, complete, transactionCount: 3,
});

describe('monthlyPoints', () => {
  it('keeps each month, its net and whether it is whole', () => {
    const [p] = monthlyPoints([month('2026-03', 1000, 400, false)]);
    expect(p).toMatchObject({ key: '2026-03', label: 'Mar', name: 'Mar 2026', income: 1000, expense: 400, net: 600, complete: false });
  });
});

describe('yearlyPoints', () => {
  const twelve = (year: number, complete = true) =>
    Array.from({ length: 12 }, (_, i) => month(`${year}-${String(i + 1).padStart(2, '0')}`, 100, 60, complete));

  it('adds the months of each calendar year and takes the net of the sums', () => {
    const points = yearlyPoints([...twelve(2025), ...twelve(2026)]);
    expect(points.map((p) => p.key)).toEqual(['2025', '2026']);
    expect(points[0]).toMatchObject({ income: 1200, expense: 720, net: 480, complete: true });
  });

  it('marks a year the period only partly covers, however whole its months are', () => {
    const points = yearlyPoints([...twelve(2025), ...twelve(2026).slice(0, 5)]);
    expect(points[0].complete).toBe(true);
    expect(points[1]).toMatchObject({ income: 500, expense: 300, complete: false });
  });

  it('marks a year with any month that is only part of a month', () => {
    const months = twelve(2025);
    months[3] = month('2025-04', 100, 60, false);
    expect(yearlyPoints(months)[0].complete).toBe(false);
  });
});

describe('the axis', () => {
  it('rounds up to 1, 2, 2.5, 5 or 10 times a power of ten', () => {
    expect(niceCeiling(0)).toBe(0);
    expect(niceCeiling(0.9)).toBe(1);
    expect(niceCeiling(1)).toBe(1);
    expect(niceCeiling(1.01)).toBe(2);
    expect(niceCeiling(2.2)).toBe(2.5);
    expect(niceCeiling(4000)).toBe(5000);
    expect(niceCeiling(137000)).toBe(200000);
    expect(niceCeiling(1000)).toBe(1000);
  });

  it('covers the largest bar, and goes below zero only when a month spent more than it earned', () => {
    const up = flowDomain(monthlyPoints([month('2026-01', 120000, 70000), month('2026-02', 100000, 90000)]));
    expect(up).toEqual({ lo: 0, hi: 200000 });
    const down = flowDomain(monthlyPoints([month('2026-01', 50000, 80000)]));
    expect(down.lo).toBe(-50000);
    expect(down.hi).toBe(100000);
  });

  it('still draws an axis for a period of nothing', () => {
    expect(flowDomain(monthlyPoints([month('2026-01', 0, 0)]))).toEqual({ lo: 0, hi: 1 });
    expect(flowDomain([])).toEqual({ lo: 0, hi: 1 });
  });

  it('spaces the gridlines evenly across the domain', () => {
    expect(flowTicks({ lo: 0, hi: 200000 })).toEqual([0, 50000, 100000, 150000, 200000]);
    expect(flowTicks({ lo: -50000, hi: 100000 }, 3)).toEqual([-50000, 0, 50000, 100000]);
  });
});
