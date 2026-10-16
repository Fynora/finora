export interface DaySpend {
  date: string;
  amount: number;
}

/** 0 = no spend; 1-4 = quarter steps of the month's biggest day. */
export type SpendLevel = 0 | 1 | 2 | 3 | 4;

export interface CalendarCell {
  /** Day of the month, 1-based. */
  day: number;
  /** YYYY-MM-DD */
  date: string;
  amount: number;
  level: SpendLevel;
}

export interface SpendCalendar {
  /** Empty cells before the 1st so the grid starts on a Monday. 0-6. */
  leadingBlanks: number;
  cells: CalendarCell[];
  /** The day with the most spend (the earlier day on a tie), or null when nothing was spent. */
  biggest: CalendarCell | null;
}

/** Days in a "YYYY-MM" month, leap years included. */
export function daysInMonth(month: string): number {
  const [y, m] = month.split('-').map(Number);
  return new Date(y, m, 0).getDate();
}

/** How many empty cells precede the 1st in a Monday-first week: Monday 0 ... Sunday 6. */
export function leadingBlanksFor(month: string): number {
  const [y, m] = month.split('-').map(Number);
  const weekday = new Date(y, m - 1, 1).getDay(); // 0 = Sunday
  return (weekday + 6) % 7;
}

function levelFor(amount: number, biggest: number): SpendLevel {
  if (amount <= 0 || biggest <= 0) return 0;
  const share = amount / biggest;
  if (share > 0.75) return 4;
  if (share > 0.5) return 3;
  if (share > 0.25) return 2;
  return 1;
}

/**
 * The month as calendar cells, one per day, with each day's spend and a 0-4 intensity relative to
 * the month's own biggest day. Intensity is relative on purpose: a calendar of absolute rupee
 * bands would be all one colour for a light spender and all dark for a heavy one. A day the server
 * sent nothing for is a day with no spend. Entries outside the month, with a malformed date, or
 * with a non-positive or non-finite amount are ignored rather than drawn.
 */
export function buildSpendCalendar(month: string, days: DaySpend[]): SpendCalendar {
  const total = daysInMonth(month);
  const byDay = new Map<number, number>();
  for (const d of days) {
    if (!d.date.startsWith(`${month}-`) || !Number.isFinite(d.amount) || d.amount <= 0) continue;
    const day = Number(d.date.slice(8, 10));
    if (!Number.isInteger(day) || day < 1 || day > total) continue;
    byDay.set(day, (byDay.get(day) ?? 0) + d.amount);
  }
  const biggestAmount = Math.max(0, ...byDay.values());
  const cells: CalendarCell[] = Array.from({ length: total }, (_, i) => {
    const day = i + 1;
    const amount = byDay.get(day) ?? 0;
    return { day, date: `${month}-${String(day).padStart(2, '0')}`, amount, level: levelFor(amount, biggestAmount) };
  });
  const biggest = biggestAmount > 0 ? cells.find((c) => c.amount === biggestAmount) ?? null : null;
  return { leadingBlanks: leadingBlanksFor(month), cells, biggest };
}
