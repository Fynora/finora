import type { ExpectedShare, PersonBalance } from '../api/endpoints';
import { formatDate } from '../utils/date';

/**
 * Shared payments and loans on the web (docs/superpowers/specs/2026-10-18-shared-payments-and-loans-
 * design.md): the same rules mobile/src/lib/insightsFeed.ts and components/people apply, so the two
 * apps say the same thing about the same money.
 */

/** "4 Oct": a day and a short month, Sep never Sept. */
export function dayMonth(date: string): string {
  return formatDate(date, { day: 'numeric', month: 'short' });
}

/** Whole days from one YYYY-MM-DD to another, counted on the calendar. */
export function daysBetween(from: string, to: string): number {
  const at = (d: string) => {
    const [y, m, day] = d.split('-').map(Number);
    return Date.UTC(y, m - 1, day);
  };
  return Math.round((at(to) - at(from)) / 86_400_000);
}

/** "4th", "22nd": a day of the month with its English ending. */
function ordinal(day: number): string {
  const tens = day % 100;
  if (tens >= 11 && tens <= 13) return `${day}th`;
  return `${day}${({ 1: 'st', 2: 'nd', 3: 'rd' } as Record<number, string>)[day % 10] ?? 'th'}`;
}

/** Shares expected in: each split rule's usual share, around its usual day, marked late once past. */
export function expectedSharesIn(expected: ExpectedShare[]): {
  key: string; title: string; when: string; amount: number; late: boolean;
}[] {
  return expected.map((e) => ({
    key: e.ruleId,
    title: `${e.label}'s share`,
    when: e.late ? `late, usually around the ${ordinal(e.usualDay)}` : `around the ${ordinal(e.usualDay)}`,
    amount: e.usualAmount,
    late: e.late,
  }));
}

/** Who owes the user and whom the user owes, largest first. A family person is never listed (spec
 *  §7), and a settled balance is not a line. */
export function withPeople(balances: PersonBalance[]): { owedToYou: PersonBalance[]; youOwe: PersonBalance[] } {
  const open = balances.filter((p) => !p.family && p.owedToUser !== 0);
  return {
    owedToYou: open.filter((p) => p.owedToUser > 0).sort((a, b) => b.owedToUser - a.owedToUser),
    youOwe: open.filter((p) => p.owedToUser < 0).sort((a, b) => a.owedToUser - b.owedToUser),
  };
}

/** name@bank: a handle, then the bank's short name. The same rule the server applies. */
export const UPI_ID = /^[A-Za-z0-9._-]{2,256}@[A-Za-z]{2,64}$/;

/** A UPI request for what is owed, paid to the user's own UPI id. The NPCI UPI Linking
 *  Specification (1.6) makes the payee's name (pn) mandatory beside their address (pa): the user's
 *  own name, or the handle of their UPI id when the account has none. */
export function upiRequestUrl(ownUpiId: string, name: string | null, owed: number): string {
  const payee = name?.trim() || ownUpiId.split('@')[0];
  return `upi://pay?pa=${ownUpiId}&pn=${encodeURIComponent(payee)}&am=${owed.toFixed(2)}&cu=INR`;
}

/** "₹9,000": whole rupees, as the rest of this site's lists. */
export function rupees(n: number): string {
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

/** The server's own sentence when it refuses an answer (a sender it cannot tell apart, nothing
 *  left to link), else the fallback -- the way the Ledger reports a refused edit. */
export function serverMessage(err: unknown, fallback: string): string {
  const message = (err as { response?: { data?: { message?: unknown } } } | null)?.response?.data?.message;
  return typeof message === 'string' && message.trim() ? message : fallback;
}
