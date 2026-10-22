import type { AdvancedMonth } from '../../api/endpoints';
import { monthShort, monthYear } from './advancedFormat';

/** One bar pair on the income-versus-expenses chart: a month, or a calendar year of the period. */
export interface FlowPoint {
  key: string;
  label: string;
  /** Spoken name, e.g. "Mar 2026" or "2026". */
  name: string;
  income: number;
  expense: number;
  net: number;
  /** False when the point is only part of what its label says. */
  complete: boolean;
}

export function monthlyPoints(months: AdvancedMonth[]): FlowPoint[] {
  return months.map((m) => ({
    key: m.month,
    label: monthShort(m.month),
    name: monthYear(m.month),
    income: m.income,
    expense: m.expense,
    net: m.net,
    complete: m.complete,
  }));
}

/**
 * The months of the period added up by calendar year. A year is whole only when all twelve of its
 * months are in the period and each is whole: a year the period starts or ends inside is drawn as
 * the part it has, and marked, never as if it were a full year. Net is income minus expense of the
 * added-up figures.
 */
export function yearlyPoints(months: AdvancedMonth[]): FlowPoint[] {
  const byYear = new Map<string, AdvancedMonth[]>();
  for (const m of months) {
    const year = m.month.slice(0, 4);
    byYear.set(year, [...(byYear.get(year) ?? []), m]);
  }
  return [...byYear.entries()].map(([year, list]) => {
    const income = list.reduce((s, m) => s + m.income, 0);
    const expense = list.reduce((s, m) => s + m.expense, 0);
    return {
      key: year, label: year, name: year, income, expense, net: income - expense,
      complete: list.length === 12 && list.every((m) => m.complete),
    };
  });
}

/** More than two years of bars stops being readable one month at a time. */
export const YEARLY_ABOVE_MONTHS = 24;

/** The smallest 1, 2, 2.5, 5 or 10 times a power of ten at or above `value` (0 for 0). */
export function niceCeiling(value: number): number {
  if (value <= 0) return 0;
  const power = Math.pow(10, Math.floor(Math.log10(value)));
  for (const step of [1, 2, 2.5, 5, 10]) {
    if (step * power >= value) return step * power;
  }
  return 10 * power;
}

export interface FlowDomain {
  lo: number;
  hi: number;
}

/** The value range the chart's vertical axis covers: from the most negative net (or zero) to a round
 *  number above the largest bar. Never empty, so a period of all zeros still draws an axis. */
export function flowDomain(points: FlowPoint[]): FlowDomain {
  const hi = niceCeiling(Math.max(0, ...points.map((p) => Math.max(p.income, p.expense, p.net))));
  const below = niceCeiling(Math.max(0, ...points.map((p) => -p.net)));
  const lo = below === 0 ? 0 : -below; // never -0, which would print as a minus sign
  return hi === 0 && lo === 0 ? { lo: 0, hi: 1 } : { lo, hi };
}

/** Evenly spaced gridline values across the domain, each a round number. */
export function flowTicks(domain: FlowDomain, count = 4): number[] {
  const step = (domain.hi - domain.lo) / count;
  return Array.from({ length: count + 1 }, (_, i) => domain.lo + step * i);
}
