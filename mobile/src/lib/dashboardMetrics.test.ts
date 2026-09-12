import { averageMonthlySavings, deriveNetSavingsSeries } from './dashboardMetrics';

describe('deriveNetSavingsSeries', () => {
  it('maps each point to label + income-minus-expense', () => {
    const result = deriveNetSavingsSeries([
      { label: 'Jan', income: 50000, expense: 30000 },
      { label: 'Feb', income: 40000, expense: 45000 },
    ]);
    expect(result).toEqual([
      { label: 'Jan', net: 20000 },
      { label: 'Feb', net: -5000 },
    ]);
  });

  it('returns an empty array for no points', () => {
    expect(deriveNetSavingsSeries([])).toEqual([]);
  });
});

describe('averageMonthlySavings', () => {
  it('averages net savings across every point', () => {
    expect(averageMonthlySavings([
      { label: 'Jan', income: 50000, expense: 30000 },
      { label: 'Feb', income: 40000, expense: 45000 },
    ])).toBe(7500);
  });

  it('returns 0 for no points, not NaN', () => {
    expect(averageMonthlySavings([])).toBe(0);
  });
});
