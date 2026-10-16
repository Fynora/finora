import type { ReportTrendPoint } from '../../api/endpoints';

/** The fewest fully-in months an average is drawn for. One month is not an average. */
export const MIN_MONTHS_FOR_AVERAGE = 2;

/** Mean spending over the months that are whole. A partly-in month understates spending and would
 *  drag the line down, so it is left out. Null when fewer than {@link MIN_MONTHS_FOR_AVERAGE} are
 *  whole: the line is then not drawn rather than drawn from too little. */
export function averageSpend(points: ReportTrendPoint[]): number | null {
  const whole = points.filter((p) => p.complete && Number.isFinite(p.expense));
  if (whole.length < MIN_MONTHS_FOR_AVERAGE) return null;
  return whole.reduce((sum, p) => sum + p.expense, 0) / whole.length;
}

/** The tallest bar's value, never zero, so a scale built from it cannot divide by zero. */
export function trendCeiling(points: ReportTrendPoint[]): number {
  const tallest = points.reduce((max, p) => Math.max(max, p.income, p.expense), 0);
  return tallest > 0 ? tallest : 1;
}

/** "2026-09" -> "Sep". Explicit y/m/1 construction, not `new Date("2026-09")`, which parses as UTC
 *  midnight and names the previous month anywhere behind UTC. */
export function shortMonth(month: string): string {
  const [y, m] = month.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'short' });
}
