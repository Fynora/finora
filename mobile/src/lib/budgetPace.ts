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
  if (p.group === 'over') return { main: `${money(p.overBy)} over`, sub: `${money(0)} left to spend` };
  if (clock.daysLeft === 0) return { main: `Last day · ${money(p.remaining)} left`, sub: '' };
  if (p.group === 'heading-over') {
    return { main: `${money(p.safePerDay)} / day safe`, sub: `Heading ${money(p.forecastOverBy)} over by ${clock.monthEndLabel}` };
  }
  return { main: `${money(p.safePerDay)} / day safe`, sub: `About ${money(p.forecast)} by ${clock.monthEndLabel}` };
}

export function paceA11yLabel(name: string, spent: number, limit: number, p: BudgetPace, clock: MonthClock, money: Money): string {
  const head = `${name}: ${money(spent)} spent of ${money(limit)}. `;
  if (p.group === 'over') return `${head}${money(p.overBy)} over budget.`;
  if (clock.daysLeft === 0) return `${head}Last day, ${money(p.remaining)} left.`;
  const daily = `${money(p.safePerDay)} a day safe for ${clock.daysLeft} days.`;
  if (p.group === 'heading-over') return `${head}${daily} Heading ${money(p.forecastOverBy)} over by ${clock.monthEndLabel}.`;
  return `${head}${daily}`;
}
