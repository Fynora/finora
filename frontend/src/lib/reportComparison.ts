import type { MonthComparison } from '../api/endpoints';

/** "2026-08" -> "August", or "August 2025" when it is not in the same year as `relativeTo`. Explicit
 *  y/m/1 construction: a bare "YYYY-MM" parses as UTC midnight, which is the prior month in any
 *  timezone behind UTC. */
export function comparisonMonthName(month: string, relativeTo: string): string {
  const [y, m] = month.split('-').map(Number);
  const sameYear = relativeTo.startsWith(`${y}-`);
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', sameYear ? { month: 'long' } : { month: 'long', year: 'numeric' });
}

/** "↑ 42% vs August". Null when there is no percent to show (not reliable, or the prior was zero).
 *  A change that rounds to 0% reads "No change", not "↑ 0%". The arrow carries direction on its own,
 *  so the text never relies on colour. */
export function deltaPhrase(pct: number | null | undefined, priorName: string): string | null {
  if (pct == null || !Number.isFinite(pct)) return null;
  const rounded = Math.round(Math.abs(pct));
  if (rounded === 0) return `No change vs ${priorName}`;
  return `${pct > 0 ? '↑' : '↓'} ${rounded}% vs ${priorName}`;
}

/** "₹34,469 less than August" / "₹1,200 more than August" / "Same as August". Null when the
 *  comparison is not reliable. Net is compared as an amount, never a percent: a percent of a
 *  negative or near-zero net misleads. `fmt` formats a non-negative amount. */
export function netChangePhrase(
  cmp: MonthComparison | null | undefined,
  priorName: string,
  fmt: (n: number) => string,
): string | null {
  if (!cmp || !cmp.reliable || cmp.netDelta == null) return null;
  const rounded = Math.round(cmp.netDelta);
  if (rounded === 0) return `Same as ${priorName}`;
  return `${fmt(Math.abs(rounded))} ${rounded > 0 ? 'more' : 'less'} than ${priorName}`;
}

/** One quiet sentence saying why there is no comparison. Null when the comparison is reliable or
 *  absent (no live account: nothing to explain). An unknown reason from a newer server falls back
 *  to a generic sentence rather than showing the raw code. */
export function comparisonGateNote(
  cmp: MonthComparison | null | undefined,
  monthName: string,
  priorName: string,
): string | null {
  if (!cmp || cmp.reliable) return null;
  switch (cmp.gateReason) {
    case 'NO_TRANSACTION_HISTORY':
    case 'NO_TRANSACTIONS_IN_MONTH':
      return null; // The page already says there is nothing to compare; a second line adds nothing.
    case 'PRIOR_BEFORE_HISTORY':
      return `Your history starts after ${priorName}, so there is nothing to compare ${monthName} with.`;
    case 'SELECTED_MONTH_PARTIAL':
      return `${monthName} is only partly in your history, so it is not compared with ${priorName}.`;
    case 'PARTIAL_PRIOR_MONTH':
      return `${priorName} is only partly in your history, so it is not compared with ${monthName}.`;
    case 'COVERAGE_GAP':
      return `A statement is missing around ${monthName} or ${priorName}, so they are not compared.`;
    case 'ACCOUNT_STATEMENT_BEHIND':
      return `An account's latest data stops short of ${monthName}, so it is not compared with ${priorName} yet.`;
    case 'TOO_FEW_PRIOR_TRANSACTIONS':
      return `${priorName} has too few transactions to compare ${monthName} with.`;
    default:
      return `${monthName} is not compared with ${priorName}.`;
  }
}
