import type { Account } from '../types';

/**
 * Which calendar months one account's statements cover, for the Import screen's "Your statements"
 * card (spec docs/superpowers/specs/2026-11-02-mobile-import-redesign-design.md, "Month chips").
 *
 * Read only from the server's coverage report (GET /accounts/{id}/coverage): the statement periods
 * it holds and the gaps between them. Nothing is guessed from transactions. All dates stay as
 * YYYY-MM-DD strings, compared as text, so no time zone can move a day.
 */

/** The parts of the coverage report this rule reads. */
export interface CoverageInput {
  segments: { periodStart: string; periodEnd: string }[];
  gaps: { gapStart: string; gapEnd: string }[];
}

export type MonthState = 'covered' | 'missing' | 'next';

export interface MonthChip {
  /** YYYY-MM */
  month: string;
  state: MonthState;
}

export type MonthsLine =
  | { kind: 'next'; month: string }
  | { kind: 'up-to-date' }
  /** Newest first. */
  | { kind: 'missing'; months: string[] };

const MAX_PAST_CHIPS = 5;

function monthOf(date: string): string {
  return date.slice(0, 7);
}

function addMonths(month: string, n: number): string {
  const [y, m] = month.split('-').map(Number);
  const index = y * 12 + (m - 1) + n;
  return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, '0')}`;
}

function daysIn(month: string): number {
  const [y, m] = month.split('-').map(Number);
  return new Date(y, m, 0).getDate();
}

function firstDay(month: string): string {
  return `${month}-01`;
}

function lastDay(month: string): string {
  return `${month}-${String(daysIn(month)).padStart(2, '0')}`;
}

/** The same day one calendar month later, clamped to that month's last day (31 Jan → 28/29 Feb). */
function oneMonthAfter(date: string): string {
  const month = addMonths(monthOf(date), 1);
  const day = Math.min(Number(date.slice(8, 10)), daysIn(month));
  return `${month}-${String(day).padStart(2, '0')}`;
}

export function statementMonths(coverage: CoverageInput, today: string): { chips: MonthChip[]; line: MonthsLine } {
  const { segments, gaps } = coverage;
  if (segments.length === 0) return { chips: [], line: { kind: 'up-to-date' } };

  const ends = segments.map((s) => s.periodEnd).sort();
  const latestEnd = ends[ends.length - 1];
  const earliestEndMonth = monthOf(ends[0]);
  const latestEndMonth = monthOf(latestEnd);
  const firstMonth = segments.map((s) => monthOf(s.periodStart)).sort()[0];

  const isCovered = (m: string) =>
    segments.some((s) => monthOf(s.periodEnd) === m || (s.periodStart <= firstDay(m) && s.periodEnd >= lastDay(m)));
  const gapTouches = (m: string) => gaps.some((g) => g.gapStart <= lastDay(m) && g.gapEnd >= firstDay(m));

  const past: MonthChip[] = [];
  for (let m = firstMonth; m <= latestEndMonth; m = addMonths(m, 1)) {
    if (isCovered(m)) past.push({ month: m, state: 'covered' });
    else if (gapTouches(m) && m >= earliestEndMonth) past.push({ month: m, state: 'missing' });
  }

  const missing = past.filter((c) => c.state === 'missing').map((c) => c.month).reverse();
  const nextMonth = oneMonthAfter(latestEnd) <= today ? addMonths(latestEndMonth, 1) : null;

  const chips = past.slice(-MAX_PAST_CHIPS);
  if (nextMonth) chips.push({ month: nextMonth, state: 'next' });

  const line: MonthsLine = missing.length > 0
    ? { kind: 'missing', months: missing }
    : nextMonth
      ? { kind: 'next', month: nextMonth }
      : { kind: 'up-to-date' };
  return { chips, line };
}

/** "Oct" / "October": the month alone, no year. */
export function monthName(month: string, style: 'short' | 'long'): string {
  const [y, m] = month.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: style });
}

export function monthsLineText(line: MonthsLine): string {
  switch (line.kind) {
    case 'next':
      return `Next: ${monthName(line.month, 'long')}'s statement`;
    case 'up-to-date':
      return 'Up to date';
    case 'missing':
      return line.months.length === 1
        ? `${monthName(line.months[0], 'long')}'s statement is missing`
        : `${line.months.length} statements are missing`;
  }
}

/**
 * Accounts whose months the server can report: imported from statements, with at least one. An
 * account linked through the Account Aggregator, or kept by hand, has no statement months.
 */
export function qualifiesForMonths(a: Pick<Account, 'primarySource' | 'statementsCount'>): boolean {
  return a.primarySource === 'MANUAL' && a.statementsCount > 0;
}
