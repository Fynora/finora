import type { QuickSortQuestion } from '../types';

/**
 * The line a Quick sort question shows under the payee ("14 payments since Aug · usually ₹150–₹400"),
 * built from numbers the server already worked out (PayeePattern). Spec:
 * docs/superpowers/specs/2026-11-14-smarter-person-question-design.md. Mobile keeps an identical copy in
 * mobile/src/lib/payeePattern.ts; the apps share no code.
 */

// Fixed names rather than a locale's short month: en-IN prints "Sept", and the line must read the same
// on every device.
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function rupees(n: number): string {
  return '₹' + Math.round(n).toLocaleString('en-IN');
}

/** "Aug", or "Dec 2025" when the year differs from the newest payment's. Dates are ISO yyyy-MM-dd. */
function monthLabel(iso: string, newestIso: string | undefined): string {
  const [year, month] = iso.split('-');
  const name = MONTHS[Number(month) - 1] ?? month;
  return newestIso && newestIso.slice(0, 4) !== year ? `${name} ${year}` : name;
}

/** The pattern line, or null when the question carries no pattern (older server, or a one-row question). */
export function patternLine(q: QuickSortQuestion): string | null {
  if (q.allPayments == null || !q.firstDate) return null;
  const month = monthLabel(q.firstDate, q.recent?.[0]?.date);
  if (q.allPayments === 1) return `1 payment in ${month}`;
  const head = `${q.allPayments} payments since ${month}`;
  if (q.typicalLow == null || q.typicalHigh == null) return head;
  const low = Math.round(q.typicalLow);
  const high = Math.round(q.typicalHigh);
  return low === high ? `${head} · usually ${rupees(low)}` : `${head} · usually ${rupees(low)}–${rupees(high)}`;
}

/** The Transactions page, searched for this payee. */
export function seeAllHref(payee: string): string {
  return `/app/transactions?q=${encodeURIComponent(payee)}`;
}
