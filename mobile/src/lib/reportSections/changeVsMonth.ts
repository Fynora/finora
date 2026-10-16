import { categoryRows } from './categoryShare';

export interface CategoryChange {
  /** The category's name as the report names it. */
  category: string;
  /** Spent this month; 0 for a category that had spend only in the earlier month. */
  now: number;
  /** Spent in the earlier month; 0 for a category that is new this month. */
  before: number;
  /** now - before, in rupees (positive: more spent). Never 0 in a returned change. */
  delta: number;
  kind: 'up' | 'down' | 'new' | 'gone';
}

/** How many changes the list shows. */
export const MAX_CHANGES = 5;

const keyOf = (name: string) => name.trim().toLowerCase();

/** The month's category rows keyed by name. Two rows that differ only by case or spacing are one
 *  category here, so their amounts add up rather than the later row replacing the earlier. */
function byName(categories: { category: string; amount: number }[]): Map<string, { category: string; amount: number }> {
  const out = new Map<string, { category: string; amount: number }>();
  for (const r of categoryRows(categories).rows) {
    const key = keyOf(r.category);
    const seen = out.get(key);
    out.set(key, seen ? { category: seen.category, amount: seen.amount + r.amount } : { category: r.category, amount: r.amount });
  }
  return out;
}

/**
 * The categories whose spend moved most between the earlier month and this one, largest rupee move
 * first. Both lists are the reports' own category rows (same rules for both months, refunds netted,
 * non-positive rows dropped), so the difference is the difference between two figures the user can
 * see in their own reports, not a computation of the app's own.
 *
 * Amounts, not percentages: a percentage of a small figure makes a small move look large, and the
 * rupee amount is what the user can check. A category that has spend in only one of the months is
 * `new` or `gone`. The catch-all "Uncategorized" bucket is left out: a move in it is more or less
 * unsorted money, not something the user spent differently. A move that rounds to zero rupees is
 * not a change. Matching is by name, ignoring case and surrounding space. Mirrors
 * frontend/src/pages/reportSections/changeVsMonth.ts.
 */
export function categoryChanges(
  current: { category: string; amount: number }[],
  prior: { category: string; amount: number }[],
  max: number = MAX_CHANGES,
): CategoryChange[] {
  const now = byName(current);
  const before = byName(prior);
  const keys = new Set([...now.keys(), ...before.keys()]);
  const changes: CategoryChange[] = [];
  for (const key of keys) {
    if (key === 'uncategorized') continue;
    const n = now.get(key);
    const b = before.get(key);
    const nowAmount = n?.amount ?? 0;
    const beforeAmount = b?.amount ?? 0;
    const delta = Math.round(nowAmount - beforeAmount);
    if (delta === 0) continue;
    changes.push({
      category: (n ?? b)!.category,
      now: nowAmount,
      before: beforeAmount,
      delta,
      kind: !b ? 'new' : !n ? 'gone' : delta > 0 ? 'up' : 'down',
    });
  }
  return changes
    .sort((a, b) => Math.abs(b.delta) - Math.abs(a.delta) || a.category.localeCompare(b.category))
    .slice(0, max);
}
