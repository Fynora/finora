import type { CashFlowPoint } from '../components/charts/CashFlowChart';

export interface NetSavingsPoint {
  label: string;
  net: number;
}

/** Cash Flow Mini card's sparkline series -- income minus expense, per month, client-derived
 *  from the same per-month report queries the full Cash Flow chart already fetches. No new
 *  backend field: the two numbers this subtracts are already on CashFlowPoint. */
export function deriveNetSavingsSeries(points: CashFlowPoint[]): NetSavingsPoint[] {
  return points.map((p) => ({ label: p.label, net: p.income - p.expense }));
}

/** Cash Flow Mini card's "Average Monthly Savings" figure -- mean net savings over the same
 *  range the sparkline draws. 0 (not NaN) for an empty range: there is nothing to average, and a
 *  currency figure rendering as "NaN" is worse than a genuine zero. */
export function averageMonthlySavings(points: CashFlowPoint[]): number {
  if (points.length === 0) return 0;
  const total = points.reduce((sum, p) => sum + (p.income - p.expense), 0);
  return total / points.length;
}
