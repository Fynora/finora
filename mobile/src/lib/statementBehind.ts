import type { StatementBehind } from '../types';

/** "2026-09-11" -> "Sep 11". Built from the parts, not `new Date("2026-09-11")`, which is read as UTC
 *  midnight and renders as the 10th anywhere behind UTC. */
function dayLabel(isoDate: string): string {
  const [y, m, d] = isoDate.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}

/** "2026-09" or "2026-09-30" -> "September". */
export function longMonthName(yearMonthOrDate: string): string {
  const [y, m] = yearMonthOrDate.slice(0, 7).split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'long' });
}

/**
 * The line shown with a month's figures when an account's statements stop short of it. It says what
 * is true -- the month is partial so far, and which statement is the reason -- rather than hiding the
 * figures or implying they are final. Null when nothing is behind. Same wording as the web app's
 * lib/statementBehind.ts.
 *
 * `monthName` is the month the figures describe ("September"), passed in so this stays a pure
 * function of the summary and the caller's own label for it.
 */
export function statementBehindNote(behind: StatementBehind | null | undefined, monthName: string): string | null {
  if (!behind) return null;
  const name = behind.accountName.trim() || 'An account';
  const day = dayLabel(behind.statementEnd);
  if (behind.accountCount > 1) {
    return `${monthName} is partial so far: ${behind.accountCount} accounts' latest statements end before month-end, the earliest being ${name} on ${day}.`;
  }
  return `${monthName} is partial so far: ${name}'s latest statement ends ${day}, so its last days aren't in yet.`;
}
