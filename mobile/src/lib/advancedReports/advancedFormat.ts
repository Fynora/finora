import type {
  AdvancedComparison, AdvancedCompare, AdvancedDrill, AdvancedPeriodInfo, AdvancedRange,
} from '../../api/endpoints';

export const RANGE_OPTIONS: { value: AdvancedRange; label: string }[] = [
  { value: 'LAST_3_MONTHS', label: 'Last 3 months' },
  { value: 'LAST_6_MONTHS', label: 'Last 6 months' },
  { value: 'LAST_12_MONTHS', label: 'Last 12 months' },
  { value: 'THIS_YEAR', label: 'This year' },
  { value: 'LAST_YEAR', label: 'Last year' },
  { value: 'ALL_TIME', label: 'All time' },
  { value: 'CUSTOM', label: 'Custom range' },
];

/** "2026-09" -> "Sep". Explicit y/m/1 construction: a bare "YYYY-MM" parses as UTC midnight, which
 *  is the previous month in any timezone behind UTC. */
export function monthShort(month: string): string {
  const [y, m] = month.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'short' });
}

/** "2026-09" -> "Sep 2026". */
export function monthYear(month: string): string {
  const [y, m] = month.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'short', year: 'numeric' });
}

/** "Oct 2025 - Sep 2026", or just "Sep 2026" for a single month. Null when the period has no months. */
export function periodSpan(p: AdvancedPeriodInfo): string | null {
  if (!p.startMonth || !p.endMonth) return null;
  return p.startMonth === p.endMonth ? monthYear(p.startMonth) : `${monthYear(p.startMonth)} – ${monthYear(p.endMonth)}`;
}

/** The earlier period in words, for use after "than in" or "vs": "the previous 6 months". */
export function againstName(p: AdvancedPeriodInfo): string {
  if (p.compare === 'SAME_PERIOD_LAST_YEAR') return 'the same months a year earlier';
  return p.monthCount === 1 ? 'the month before' : `the previous ${p.monthCount} months`;
}

/** What a compare option is called in the picker. */
export function compareOptionLabel(option: AdvancedCompare, p: AdvancedPeriodInfo): string {
  if (option === 'SAME_PERIOD_LAST_YEAR') return 'Same months a year earlier';
  return p.monthCount === 1 ? 'Month before' : `Previous ${p.monthCount} months`;
}

/** One line saying how much of what was asked for exists, or null when the history covers it all. */
export function coverageNote(p: AdvancedPeriodInfo): string | null {
  if (!p.hasData || p.requestedMonths <= p.monthCount) return null;
  if (p.range === 'THIS_YEAR' || p.range === 'LAST_YEAR' || p.range === 'CUSTOM') {
    return `Your history covers ${p.monthCount} of the ${p.requestedMonths} months asked for.`;
  }
  return `Your history covers ${p.monthCount} of the last ${p.requestedMonths} months.`;
}

/** Why the newest month is not in the period, or null when nothing was left out. */
export function leftOutNote(p: AdvancedPeriodInfo): string | null {
  if (!p.leftOutMonth) return null;
  const name = monthYear(p.leftOutMonth);
  return p.leftOutReason === 'STATEMENT_BEHIND'
    ? `${name} is left out: an account's latest statement does not reach the end of it yet.`
    : `${name} is left out: it is still in progress, so only part of it is in your data.`;
}

/** Months inside the period that are only part of a month, or null when there are none. */
export function incompleteNote(p: AdvancedPeriodInfo): string | null {
  if (p.incompleteMonths.length === 0) return null;
  const names = p.incompleteMonths.map(monthYear).join(', ');
  return p.incompleteMonths.length === 1
    ? `${names} is only part of a month (a statement is missing or the history starts mid-month), so its bars run lower than the real figures.`
    : `${names} are only part of a month (a statement is missing or the history starts mid-month), so their bars run lower than the real figures.`;
}

/** One quiet sentence saying why there is no comparison, or null when there is one (or nothing to explain). */
export function gateNote(cmp: AdvancedComparison, p: AdvancedPeriodInfo): string | null {
  if (cmp.reliable || !p.hasData) return null;
  switch (cmp.gateReason) {
    case 'ALL_TIME':
      return null; // Nothing comes before all time; saying so adds nothing.
    case 'NO_TRANSACTION_HISTORY':
      return null;
    case 'PRIOR_PERIOD_BEFORE_HISTORY':
      return 'Your history does not reach back far enough to set this period beside an earlier one.';
    case 'TOO_FEW_PRIOR_TRANSACTIONS':
      return 'The earlier period has too few transactions to compare with.';
    case 'COVERAGE_GAP':
      return 'A statement is missing in this period or the one it is compared with, so they are not compared.';
    case 'INCOMPLETE_MONTHS':
      return 'A month in this period or the one it is compared with is only part of a month, so they are not compared.';
    default:
      return 'This period cannot be compared with an earlier one yet.';
  }
}

/** What the page says when there is nothing to draw. */
export function noDataCopy(p: AdvancedPeriodInfo): { title: string; desc: string } {
  switch (p.noDataReason) {
    case 'ONLY_PARTIAL_MONTH':
      return {
        title: 'Your first month is still in progress',
        desc: 'Advanced Reports looks at whole months. Once a month is complete in your data it appears here.',
      };
    case 'NO_MONTHS_IN_PERIOD':
      return {
        title: 'No data in this period',
        desc: 'Your history does not cover these months. Try All time, or another range.',
      };
    default:
      return {
        title: 'No transactions yet',
        desc: 'Import a statement or add transactions to see how your finances have changed over time.',
      };
  }
}

/** "+₹2,000" / "-₹1,200": the sign is part of the text, so direction never rests on colour alone. */
export function signedAmount(n: number, fmt: (n: number) => string): string {
  const rounded = Math.round(n);
  return (rounded > 0 ? '+' : '') + fmt(rounded);
}

/** "↑ 12%" / "↓ 3%" / "No change". Null when there is no percentage (no earlier figure, or it was zero). */
export function percentChange(pct: number | null | undefined): string | null {
  if (pct == null || !Number.isFinite(pct)) return null;
  const rounded = Math.round(Math.abs(pct));
  if (rounded === 0) return 'No change';
  return `${pct > 0 ? '↑' : '↓'} ${rounded}%`;
}

/** "↑ 6 pp" / "↓ 2 pp" for a change in a rate, in percentage points. */
export function pointsChange(pp: number | null | undefined): string | null {
  if (pp == null || !Number.isFinite(pp)) return null;
  const rounded = Math.round(Math.abs(pp));
  if (rounded === 0) return 'No change';
  return `${pp > 0 ? '↑' : '↓'} ${rounded} pp`;
}

/** An axis label: ₹1.5L, ₹25K, ₹2Cr. Indian grouping words, never more than one decimal. */
export function compactAmount(n: number): string {
  const abs = Math.abs(n);
  const sign = n < 0 ? '-' : '';
  const trim = (v: number) => (Math.round(v * 10) / 10).toString();
  if (abs >= 1e7) return `${sign}₹${trim(abs / 1e7)}Cr`;
  if (abs >= 1e5) return `${sign}₹${trim(abs / 1e5)}L`;
  if (abs >= 1e3) return `${sign}₹${trim(abs / 1e3)}K`;
  return `${sign}₹${Math.round(abs)}`;
}

/** What the Transactions tab needs to open the rows behind a drill: its date range and the category or
 *  search word it names, with a label for the filter chip. */
export interface TransactionsFilter {
  dateFrom: string;
  dateTo: string;
  categoryName?: string;
  keyword?: string;
  label: string;
}

/** The Transactions-tab filter for a drill. "Uncategorized" is no category a filter can match, so it
 *  asks for the period alone. */
export function drillFilter(drill: AdvancedDrill, label: string): TransactionsFilter {
  const filter: TransactionsFilter = { dateFrom: drill.from, dateTo: drill.to, label };
  if (drill.categoryName && drill.categoryName !== 'Uncategorized') filter.categoryName = drill.categoryName;
  if (drill.keyword) filter.keyword = drill.keyword;
  return filter;
}

/** First and last day of a "YYYY-MM" month, for linking to a whole month. */
export function monthBounds(month: string): { from: string; to: string } {
  const [y, m] = month.split('-').map(Number);
  const last = new Date(y, m, 0).getDate();
  return { from: `${month}-01`, to: `${month}-${String(last).padStart(2, '0')}` };
}
