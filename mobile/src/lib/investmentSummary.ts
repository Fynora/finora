/**
 * Investments: every figure and status line the screen shows, worked out from the holdings, the
 * Investments-category payments and today's date. No React and no imports, on purpose: this file is
 * copied byte for byte to mobile/src/lib/investmentSummary.ts, and a mobile test fails if the two
 * ever differ, so web and phone always say the same thing.
 *
 * Design: docs/superpowers/specs/2026-11-18-investments-redesign-design.md ("The numbers").
 *
 * A holding's value is what the person last typed or a statement last stated. Nothing here is a
 * return or a gain: no purchase cost is stored, so none can be worked out.
 */

export type HoldingGroupKey = 'mutual-funds' | 'deposits' | 'stocks' | 'retirement' | 'other';

export const GROUP_LABEL: Record<HoldingGroupKey, string> = {
  'mutual-funds': 'Mutual funds',
  deposits: 'Deposits',
  stocks: 'Stocks',
  retirement: 'PPF, EPF and NPS',
  other: 'Other',
};

const GROUP_SINGULAR: Record<HoldingGroupKey, string> = {
  'mutual-funds': 'Mutual fund',
  deposits: 'Deposit',
  stocks: 'Stocks',
  retirement: 'PPF, EPF or NPS',
  other: 'Other',
};

const GROUP_ORDER: HoldingGroupKey[] = ['mutual-funds', 'deposits', 'stocks', 'retirement', 'other'];

const KIND_GROUP: Record<string, HoldingGroupKey> = {
  'mutual fund': 'mutual-funds',
  fd: 'deposits',
  rd: 'deposits',
  stocks: 'stocks',
  demat: 'stocks',
  'ppf/nps': 'retirement',
  ppf: 'retirement',
  epf: 'retirement',
  nps: 'retirement',
};

export interface HoldingLike {
  id: string;
  name: string;
  balance: number;
  investmentKind?: string | null;
  principalAmount?: number | null;
  interestRate?: number | null;
  maturityDate?: string | null;
  maturityAmount?: number | null;
  installmentAmount?: number | null;
  installmentsPaid?: number | null;
  installmentsTotal?: number | null;
}

export interface PaymentLike {
  date: string;
  amount: number;
  reconciliationStatus?: string | null;
}

export interface HoldingGroup<T> {
  key: HoldingGroupKey;
  label: string;
  total: number;
  share: number;
  items: { holding: T; share: number }[];
}

export interface PortfolioSummary<T> {
  total: number;
  count: number;
  groups: HoldingGroup<T>[];
  mix: { key: HoldingGroupKey; label: string; total: number; share: number }[];
}

export interface ComingUpRow<T> {
  holding: T;
  /** "12 Jan 2027", or null for a plan with no maturity date. */
  dateLabel: string | null;
  /** "today", "tomorrow" or "in 55 days". */
  whenLabel: string | null;
  amount: number | null;
  paid: number | null;
  instalments: number | null;
  /** paid / instalments, from 0 to 1. */
  progress: number | null;
}

export interface MoneyInSummary {
  total: number;
  count: number;
  monthlyAverage: number;
  months: { key: string; letter: string; total: number; heightFrac: number }[];
}

type Money = (n: number) => string;

const COMING_UP_ROWS = 3;
const PERIOD_MONTHS = 12;
const MONTH_LETTERS = 'JFMAMJJASOND';
// A row the reconciliation engine judged to be a second copy of another, or that a re-uploaded
// statement replaced, is not a second payment.
const NOT_REAL_PAYMENT = ['DUPLICATE', 'SUPERSEDED'];
// accounts.balance is NUMERIC(14,2); the API refuses anything larger or finer.
const MAX_VALUE = 999_999_999_999.99;
// Grouped digits always end in three: "12,50" is a decimal comma or a typo, not 1,250.
const VALUE_FORMAT = /^(\d+|\d{1,3}(,\d{2,3})*,\d{3})?(\.\d*)?$/;

export function groupKeyFor(kind?: string | null): HoldingGroupKey {
  return KIND_GROUP[(kind ?? '').trim().toLowerCase()] ?? 'other';
}

/** "46%". A real holding too small to round to 1% says so instead of reading as nothing. */
export function shareLabel(share: number): string {
  if (!(share > 0)) return '0%';
  const pct = Math.round(share * 100);
  return pct === 0 ? '<1%' : `${pct}%`;
}

export function summarizeHoldings<T extends HoldingLike>(holdings: T[]): PortfolioSummary<T> {
  const total = holdings.reduce((sum, h) => sum + h.balance, 0);
  const shareOf = (value: number) => (total > 0 ? value / total : 0);
  const groups = GROUP_ORDER.flatMap((key) => {
    const members = holdings.filter((h) => groupKeyFor(h.investmentKind) === key);
    if (members.length === 0) return [];
    const groupTotal = members.reduce((sum, h) => sum + h.balance, 0);
    return [{
      key,
      label: GROUP_LABEL[key],
      total: groupTotal,
      share: shareOf(groupTotal),
      items: [...members].sort((a, b) => b.balance - a.balance).map((holding) => ({ holding, share: shareOf(holding.balance) })),
    }];
  });
  const mix = total > 0
    ? groups.filter((g) => g.total > 0).map(({ key, label, total: groupTotal, share }) => ({ key, label, total: groupTotal, share }))
    : [];
  return { total, count: holdings.length, groups, mix };
}

/** How much of everything the person owns is invested; null when that can't be known. */
export function assetShare(total: number, totalAssets?: number | null): number | null {
  if (totalAssets == null || !(totalAssets > 0)) return null;
  return Math.max(0, Math.min(1, total / totalAssets));
}

/** A calendar date's parts, read from the text: `new Date("2027-01-12")` is midnight UTC, which is the day before in some zones. */
function dateParts(iso?: string | null): { year: number; month: number; day: number } | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso ?? '');
  if (!match) return null;
  return { year: Number(match[1]), month: Number(match[2]) - 1, day: Number(match[3]) };
}

function daysUntil(parts: { year: number; month: number; day: number }, today: Date): number {
  const from = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate());
  return Math.round((Date.UTC(parts.year, parts.month, parts.day) - from) / 86_400_000);
}

/** "12 Sep 2027". Intl writes "Sept" for en-IN; every other date in the app has three letters. */
function dateLabel(parts: { year: number; month: number; day: number }): string {
  return new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })
    .format(new Date(parts.year, parts.month, parts.day))
    .replace(/\bSept\b/, 'Sep');
}

function whenLabel(days: number): string {
  if (days === 0) return 'today';
  return days === 1 ? 'tomorrow' : `in ${days} days`;
}

/** The instalments still to pay on a plan, or null when it has none running. */
function runningPlan(h: HoldingLike): { paid: number; instalments: number; progress: number } | null {
  const instalments = h.installmentsTotal;
  if (h.installmentsPaid == null || instalments == null || !(instalments > 0) || h.installmentsPaid >= instalments) return null;
  const paid = Math.max(0, h.installmentsPaid);
  return { paid, instalments, progress: paid / instalments };
}

export function comingUp<T extends HoldingLike>(holdings: T[], today: Date): ComingUpRow<T>[] {
  const dated = holdings
    .map((holding) => ({ holding, parts: dateParts(holding.maturityDate) }))
    .map((x) => ({ ...x, days: x.parts ? daysUntil(x.parts, today) : null }));
  const maturing = dated
    .filter((x) => x.days !== null && x.days >= 0)
    .sort((a, b) => (a.days as number) - (b.days as number))
    .map(({ holding, parts, days }): ComingUpRow<T> => {
      const plan = runningPlan(holding);
      return {
        holding,
        dateLabel: dateLabel(parts as NonNullable<typeof parts>),
        whenLabel: whenLabel(days as number),
        amount: holding.maturityAmount ?? null,
        paid: plan?.paid ?? null,
        instalments: plan?.instalments ?? null,
        progress: plan?.progress ?? null,
      };
    });
  const plans = dated.flatMap(({ holding, days }): ComingUpRow<T>[] => {
    const plan = runningPlan(holding);
    // A dated holding is either listed above already, or past its maturity and finished.
    if (!plan || days !== null) return [];
    return [{ holding, dateLabel: null, whenLabel: null, amount: null, ...plan }];
  });
  return [...maturing, ...plans].slice(0, COMING_UP_ROWS);
}

/** Whether a statement gave this holding deposit terms; a hand-added one has only a name and a value. */
export function hasDepositTerms(holding: HoldingLike): boolean {
  return [
    holding.principalAmount, holding.interestRate, holding.installmentAmount, holding.maturityAmount,
    holding.maturityDate, holding.installmentsPaid != null && holding.installmentsTotal != null ? 1 : null,
  ].some((term) => term != null);
}

/** The line under a holding's name: its deposit terms, or its type when it has none. */
export function holdingTerms(holding: HoldingLike, today: Date, money: Money): string {
  const terms: string[] = [];
  if (holding.principalAmount != null) terms.push(`Principal ${money(holding.principalAmount)}`);
  if (holding.interestRate != null) terms.push(`${holding.interestRate}% a year`);
  if (holding.installmentAmount != null) terms.push(`${money(holding.installmentAmount)} a month`);
  if (holding.installmentsPaid != null && holding.installmentsTotal != null) {
    terms.push(`${holding.installmentsPaid} of ${holding.installmentsTotal} paid`);
  }
  const maturity = dateParts(holding.maturityDate);
  if (maturity) {
    terms.push(`${daysUntil(maturity, today) < 0 ? 'Matured' : 'matures'} ${dateLabel(maturity)}`);
  }
  if (holding.maturityAmount != null) terms.push(`${money(holding.maturityAmount)} at maturity`);
  if (terms.length === 0) return GROUP_SINGULAR[groupKeyFor(holding.investmentKind)];
  const line = terms.join(' · ');
  return line.charAt(0).toUpperCase() + line.slice(1);
}

function pad(n: number): string {
  return String(n).padStart(2, '0');
}

/** This calendar month and the 11 before it, so every bar but the last is a whole month. */
export function moneyInPeriod(today: Date): { dateFrom: string; dateTo: string } {
  const start = new Date(today.getFullYear(), today.getMonth() - (PERIOD_MONTHS - 1), 1);
  return {
    dateFrom: `${start.getFullYear()}-${pad(start.getMonth() + 1)}-01`,
    dateTo: `${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}`,
  };
}

export function summarizeMoneyIn(payments: PaymentLike[], today: Date): MoneyInSummary {
  const totals = Array.from({ length: PERIOD_MONTHS }, (_, i) => {
    const month = new Date(today.getFullYear(), today.getMonth() - (PERIOD_MONTHS - 1 - i), 1);
    return { key: `${month.getFullYear()}-${pad(month.getMonth() + 1)}`, letter: MONTH_LETTERS[month.getMonth()], total: 0 };
  });
  let total = 0;
  let count = 0;
  for (const payment of payments) {
    if (NOT_REAL_PAYMENT.includes(payment.reconciliationStatus ?? '')) continue;
    const bucket = totals.find((m) => m.key === payment.date.slice(0, 7));
    if (!bucket) continue;
    bucket.total += payment.amount;
    total += payment.amount;
    count += 1;
  }
  const tallest = Math.max(...totals.map((m) => m.total));
  // From the first month with a payment: earlier months may simply have no statement imported yet.
  const firstPaid = totals.findIndex((m) => m.total > 0);
  return {
    total,
    count,
    monthlyAverage: firstPaid < 0 ? 0 : Math.round(total / (PERIOD_MONTHS - firstPaid)),
    months: totals.map((m) => ({ ...m, heightFrac: tallest > 0 ? m.total / tallest : 0 })),
  };
}

/** Why a well-formed number can't be saved, in words; null when there is nothing specific to say. */
export function holdingValueProblem(text: string): string | null {
  const trimmed = text.trim();
  if (!VALUE_FORMAT.test(trimmed)) {
    return /^[\d,]*,[\d,]*(\.\d*)?$/.test(trimmed) ? 'Check the commas in this amount. For paise, use a point: 12.50.' : null;
  }
  if (/\.\d{3,}$/.test(trimmed)) return 'Current value can have at most 2 decimal places.';
  return Number(trimmed.replace(/,/g, '')) > MAX_VALUE ? 'Current value is too large.' : null;
}

/**
 * The value someone typed, or null if it isn't one. Digit grouping is fine ("2,60,000"); "12,5" is
 * refused rather than read as 125, and so are forms Number() would quietly accept ("1e5", "0x10").
 */
export function parseHoldingValue(text: string): number | null {
  const trimmed = text.trim();
  if (!VALUE_FORMAT.test(trimmed) || holdingValueProblem(trimmed)) return null;
  const value = Number(trimmed.replace(/,/g, ''));
  return Number.isFinite(value) && value > 0 ? value : null;
}

/** What the typed value changes, while the person is still typing it. */
export function valueChangeLine(previous: number, next: number | null, money: Money): string | null {
  if (next === null) return null;
  const change = Math.round(next - previous);
  if (change === 0) return null;
  return `${change > 0 ? 'Up' : 'Down'} ${money(Math.abs(change))} from the last value you saved.`;
}
