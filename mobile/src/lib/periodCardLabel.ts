// Labels for the dashboard's Income / Expenses / Net Savings cards that name the days they cover.
// Dates are read as plain YYYY-MM-DD parts: `new Date('2026-11-08')` is midnight UTC, which is the
// day before in any time zone west of it. Mirrored in frontend/src/lib/periodCardLabel.ts.

const SHORT = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const LONG = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September',
  'October', 'November', 'December'];

function parts(date: string): [number, number, number] {
  const [y, m, d] = date.split('-').map(Number);
  return [y, m, d];
}

/**
 * "1–8 Nov" while the month is partly covered, "20–30 Nov" when statements only start on the 20th,
 * "8 Nov" for a single day, "November" for the whole month. `withYear` adds the year ("1–20 Aug
 * 2025", "August 2025") for a month that is not the current one, which the old "Aug 25" label also
 * named. `figuresFrom` is the first covered day (the 1st when missing or outside the month).
 * `fallback` when there is no month, no end date, or the end date is not in that month (an older
 * server, or an empty account).
 */
export function monthCardLabel(reportingMonth: string | null | undefined,
  figuresThrough: string | null | undefined, fallback: string, withYear = false,
  figuresFrom?: string | null): string {
  if (!reportingMonth || !figuresThrough || !figuresThrough.startsWith(`${reportingMonth}-`)) return fallback;
  const [y, m, d] = parts(figuresThrough);
  const from = figuresFrom && figuresFrom.startsWith(`${reportingMonth}-`) ? Math.min(parts(figuresFrom)[2], d) : 1;
  const year = withYear ? ` ${y}` : '';
  const daysInMonth = new Date(Date.UTC(y, m, 0)).getUTCDate();
  if (from === 1 && d >= daysInMonth) return `${LONG[m - 1]}${year}`;
  return from === d ? `${d} ${SHORT[m - 1]}${year}` : `${from}–${d} ${SHORT[m - 1]}${year}`;
}

/** "Jun–Nov 2026", "Dec 2025–Feb 2026", or "Nov 2026" for a range inside one month. */
export function rangeCardLabel(startDate: string, endDate: string): string {
  const [sy, sm] = parts(startDate);
  const [ey, em] = parts(endDate);
  if (sy === ey && sm === em) return `${SHORT[em - 1]} ${ey}`;
  if (sy === ey) return `${SHORT[sm - 1]}–${SHORT[em - 1]} ${ey}`;
  return `${SHORT[sm - 1]} ${sy}–${SHORT[em - 1]} ${ey}`;
}
