/** Periods the Platform Analytics page offers, and how it labels them and their changes. */

export interface PeriodChoice {
  key: string;
  label: string;
  /** The API's period: first month as YYYY-MM and how many months. Absent for all time. */
  period?: { from: string; months: number };
}

const MONTHS_LISTED = 12;

function yyyyMm(year: number, monthIndex: number): string {
  const d = new Date(year, monthIndex, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

function monthName(year: number, monthIndex: number): string {
  return new Date(year, monthIndex, 1).toLocaleDateString('en-IN', { month: 'long', year: 'numeric' });
}

/**
 * The last twelve full months (most recent first), the last three full months, and all time. The
 * month in progress is left out: compared with a whole month it would always look like a drop.
 * `today` is the admin's own calendar day.
 */
export function periodChoices(today: Date): PeriodChoice[] {
  const year = today.getFullYear();
  const month = today.getMonth();
  const months: PeriodChoice[] = [];
  for (let back = 1; back <= MONTHS_LISTED; back++) {
    const from = yyyyMm(year, month - back);
    months.push({ key: `month:${from}`, label: monthName(year, month - back), period: { from, months: 1 } });
  }
  return [
    ...months,
    { key: 'last3', label: 'Last 3 full months', period: { from: yyyyMm(year, month - 3), months: 3 } },
    { key: 'all', label: 'All time' },
  ];
}

/** "September 2026", or "July – September 2026" / "November 2025 – January 2026" for a span. */
export function spanLabel(fromIso: string, toIso: string): string {
  const [fy, fm] = fromIso.split('-').map(Number);
  const [ty, tm] = toIso.split('-').map(Number);
  if (fy === ty && fm === tm) return monthName(fy, fm - 1);
  const start = new Date(fy, fm - 1, 1).toLocaleDateString('en-IN', fy === ty ? { month: 'long' } : { month: 'long', year: 'numeric' });
  return `${start} – ${monthName(ty, tm - 1)}`;
}

/** Whole rupees, en-IN grouping: aggregates across customers, so paise are noise here. */
export function rupees(amount: number): string {
  const whole = Math.round(amount);
  return (whole < 0 ? '-₹' : '₹') + Math.abs(whole).toLocaleString('en-IN');
}

/** Percentage change from previous to current, whole percent with a sign; null when there is no
 *  previous figure to compare with. */
export function changePercent(current: number, previous: number | null | undefined): number | null {
  if (previous == null || previous <= 0) return null;
  return Math.round(((current - previous) / previous) * 100);
}

export function signed(n: number): string {
  return n > 0 ? `+${n}` : n < 0 ? `−${Math.abs(n)}` : '0';
}
