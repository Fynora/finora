/**
 * Budget pace: every number and status line the Budgets screen shows, worked out from the five
 * fields GET /api/v1/budgets returns plus today's date. No React and no imports, on purpose: this
 * file is copied byte for byte to mobile/src/lib/budgetPace.ts, and a mobile test fails if the two
 * ever differ, so web and phone always say the same thing.
 *
 * Design: docs/superpowers/specs/2026-11-17-budgets-redesign-design.md ("The numbers").
 *
 * The forecast is a straight line (spent so far / days gone x days in month). It overstates a month
 * with one big early payment, which is why every sentence built from it says "at this pace".
 */

export type BudgetGroup = 'over' | 'heading-over' | 'on-track';

export interface MonthClock {
  dayOfMonth: number;
  daysInMonth: number;
  /** Days after today. Today counts as time already spent, so the last day has 0 left. */
  daysLeft: number;
  /** dayOfMonth / daysInMonth: where the pace marker sits on each bar. */
  monthFrac: number;
  /** "30 Nov" */
  monthEndLabel: string;
  /** "November 2026" */
  monthLabel: string;
}

export interface BudgetPace {
  spentFrac: number;
  remaining: number;
  /** Rounded down, so following it never tips a budget over. 0 when over or on the last day. */
  safePerDay: number;
  forecast: number;
  overBy: number;
  forecastOverBy: number;
  group: BudgetGroup;
}

export interface PaceCopy {
  main: string;
  sub: string;
}

type Money = (n: number) => string;

interface SpendAndLimit {
  spentThisMonth: number;
  monthlyLimit: number;
}

export const GROUP_LABEL: Record<BudgetGroup, string> = {
  over: 'Over',
  'heading-over': 'Heading over at this pace',
  'on-track': 'On track',
};

const GROUP_ORDER: BudgetGroup[] = ['over', 'heading-over', 'on-track'];

/** "1 day", "13 days". */
export function dayCount(n: number): string {
  return `${n} ${n === 1 ? 'day' : 'days'}`;
}

/** budgets.monthly_limit is NUMERIC(14,2); the API refuses anything larger or finer. */
const MAX_LIMIT = 999_999_999_999.99;
const LIMIT_FORMAT = /^(\d+|\d{1,3}(,\d{2,3})+)?(\.\d*)?$/;

/**
 * The limit someone typed, or null if it isn't one. Digit grouping is fine ("10,000", "1,25,000");
 * "12,5" is refused rather than read as 125, and so are forms Number() would quietly accept ("1e5",
 * "0x10"). Anything the API would refuse is refused here, where limitProblem can explain it.
 */
export function parseLimit(text: string): number | null {
  const trimmed = text.trim();
  if (!LIMIT_FORMAT.test(trimmed) || limitProblem(trimmed)) return null;
  const value = Number(trimmed.replace(/,/g, ''));
  return Number.isFinite(value) && value > 0 ? value : null;
}

/** Why a well-formed number can't be a limit, in words; null when there is nothing specific to say. */
export function limitProblem(text: string): string | null {
  const trimmed = text.trim();
  if (!LIMIT_FORMAT.test(trimmed)) return null;
  if (/\.\d{3,}$/.test(trimmed)) return 'Monthly limit can have at most 2 decimal places.';
  return Number(trimmed.replace(/,/g, '')) > MAX_LIMIT ? 'Monthly limit is too large.' : null;
}

/** "78%". Capped, because a tiny limit against a large spend would otherwise overflow the ring. */
export function spentPercent(spentFrac: number): string {
  const pct = Math.round(spentFrac * 100);
  return pct > 999 ? '999%+' : `${pct}%`;
}

/** The list with a just-saved budget in place of its old copy, or added if it is new. */
export function withSavedBudget<T extends { id: string }>(budgets: T[], saved: T): T[] {
  return budgets.some((b) => b.id === saved.id)
    ? budgets.map((b) => (b.id === saved.id ? saved : b))
    : [...budgets, saved];
}

/** Saving a budget for a category that has one changes its limit, so New budget says so first. */
export function replaceLimitNote(categoryName: string, currentLimit: number, money: Money): string {
  return `${categoryName} already has a ${money(currentLimit)} budget. Saving replaces that limit.`;
}

/** Spent the whole limit, to the rupee shown: "₹0 over" would read like a glitch. */
function atLimit(p: BudgetPace): boolean {
  return Math.round(p.overBy) === 0;
}

export function monthClock(today: Date): MonthClock {
  const year = today.getFullYear();
  const month = today.getMonth();
  const daysInMonth = new Date(year, month + 1, 0).getDate();
  const dayOfMonth = today.getDate();
  const monthEnd = new Date(year, month, daysInMonth);
  return {
    dayOfMonth,
    daysInMonth,
    daysLeft: daysInMonth - dayOfMonth,
    monthFrac: dayOfMonth / daysInMonth,
    monthEndLabel: new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short' }).format(monthEnd),
    monthLabel: new Intl.DateTimeFormat('en-IN', { month: 'long', year: 'numeric' }).format(monthEnd),
  };
}

export function budgetPace(spent: number, limit: number, clock: MonthClock): BudgetPace {
  const remaining = limit - spent;
  const forecast = Math.round((spent / clock.dayOfMonth) * clock.daysInMonth);
  const group: BudgetGroup = spent >= limit ? 'over' : forecast > limit ? 'heading-over' : 'on-track';
  return {
    spentFrac: limit > 0 ? spent / limit : 0,
    remaining,
    safePerDay: remaining > 0 && clock.daysLeft > 0 ? Math.floor(remaining / clock.daysLeft) : 0,
    forecast,
    overBy: Math.max(0, spent - limit),
    forecastOverBy: Math.max(0, forecast - limit),
    group,
  };
}

export function overallPace(budgets: SpendAndLimit[], clock: MonthClock): BudgetPace {
  const spent = budgets.reduce((sum, b) => sum + b.spentThisMonth, 0);
  const limit = budgets.reduce((sum, b) => sum + b.monthlyLimit, 0);
  if (limit <= 0) {
    // No budgets: nothing to be over. budgetPace would call 0-of-0 "over" (spent >= limit).
    return { spentFrac: 0, remaining: 0, safePerDay: 0, forecast: 0, overBy: 0, forecastOverBy: 0, group: 'on-track' };
  }
  return budgetPace(spent, limit, clock);
}

export function groupBudgets<T extends SpendAndLimit>(
  budgets: T[],
  clock: MonthClock,
): { group: BudgetGroup; items: { budget: T; pace: BudgetPace }[] }[] {
  const withPace = budgets.map((budget) => ({ budget, pace: budgetPace(budget.spentThisMonth, budget.monthlyLimit, clock) }));
  const sortKey: Record<BudgetGroup, (p: BudgetPace) => number> = {
    over: (p) => p.overBy,
    'heading-over': (p) => p.forecastOverBy,
    'on-track': (p) => p.spentFrac,
  };
  return GROUP_ORDER.map((group) => ({
    group,
    items: withPace.filter((x) => x.pace.group === group).sort((a, b) => sortKey[group](b.pace) - sortKey[group](a.pace)),
  })).filter((g) => g.items.length > 0);
}

export function describePace(p: BudgetPace, clock: MonthClock, money: Money): PaceCopy {
  if (p.group === 'over') {
    return { main: atLimit(p) ? 'Limit reached' : `${money(p.overBy)} over`, sub: `${money(0)} left to spend` };
  }
  if (clock.daysLeft === 0) return { main: `Last day · ${money(p.remaining)} left`, sub: '' };
  if (p.group === 'heading-over') {
    return { main: `${money(p.safePerDay)} / day safe`, sub: `Heading ${money(p.forecastOverBy)} over by ${clock.monthEndLabel}` };
  }
  // "About ₹0 by 30 Nov" is true but reads like a glitch; say what it means.
  const sub = p.spentFrac === 0 ? 'Nothing spent yet' : `About ${money(p.forecast)} by ${clock.monthEndLabel}`;
  return { main: `${money(p.safePerDay)} / day safe`, sub };
}

export function paceA11yLabel(name: string, spent: number, limit: number, p: BudgetPace, clock: MonthClock, money: Money): string {
  const head = `${name}: ${money(spent)} spent of ${money(limit)}. `;
  if (p.group === 'over') return atLimit(p) ? `${head}Limit reached.` : `${head}${money(p.overBy)} over budget.`;
  if (clock.daysLeft === 0) return `${head}Last day, ${money(p.remaining)} left.`;
  const daily = `${money(p.safePerDay)} a day safe for ${dayCount(clock.daysLeft)}.`;
  if (p.group === 'heading-over') return `${head}${daily} Heading ${money(p.forecastOverBy)} over by ${clock.monthEndLabel}.`;
  return `${head}${daily}`;
}

/** The hero card as one sentence for a screen reader. */
export function overallSummary(spent: number, limit: number, p: BudgetPace, clock: MonthClock, money: Money): string {
  let daily = `${money(p.safePerDay)} a day safe for ${dayCount(clock.daysLeft)}.`;
  if (p.group === 'over') daily = atLimit(p) ? 'All of your budgets used.' : `${money(p.overBy)} over your budgets.`;
  else if (clock.daysLeft === 0) daily = `Last day, ${money(p.remaining)} left.`;
  return `${money(spent)} spent of ${money(limit)}. ${daily} At this pace ${money(p.forecast)} by ${clock.monthEndLabel}.`;
}
