import type { AckKind, ChangedAmountItem, InsightsData, RecurringItem, UnlinkedCardBills } from '../api/endpoints';

/**
 * The Insights screen's rows, built from what the server already sends: what needs a look, what is
 * coming up, and what is paid regularly. Pure, so every rule below is tested on its own.
 * Design: docs/superpowers/specs/2026-10-16-mobile-insights-redesign-design.md.
 */

/** A category counts as running high from this rise over its usual month: the server's own
 *  significance threshold for its mover sentences (InsightsService.MOVER_SIGNIFICANCE_THRESHOLD_PCT). */
const RUNNING_HIGH_MIN_PCT = 15;
/** ...and by at least this many rupees, so a small category's swing does not fill the list. */
const RUNNING_HIGH_MIN_RUPEES = 500;
const RUNNING_HIGH_MAX_ROWS = 3;

/** How many months one payment of each cadence covers. RecurringService's labels. */
const MONTHLY_FACTOR: Record<string, number> = {
  Weekly: 52 / 12,
  Biweekly: 26 / 12,
  Monthly: 1,
  Quarterly: 1 / 3,
};

interface Dismissible {
  ackKind: AckKind;
  ackItemKey: string;
}

export type LookAtRow =
  | ({ kind: 'card-statement' } & UnlinkedCardBills & Dismissible)
  | { kind: 'new-recurring'; item: RecurringItem }
  | ({
      kind: 'amount-changed';
      merchant: string;
      category: string;
      latestAmount: number;
      latestDate: string;
      state: 'AMOUNT_CHANGED';
    } & Dismissible)
  | ({ kind: 'running-high'; category: string; current: number; priorAverage: number; month: string } & Dismissible);

/** The key an acknowledgement is stored under for each kind, exactly as V277 documents it. */
export function ackKey(
  kind: AckKind,
  parts: { category?: string; month?: string; merchant?: string; date?: string },
): string {
  switch (kind) {
    case 'CATEGORY_HIGH':
      return `${parts.category}|${parts.month}`;
    case 'AMOUNT_CHANGED':
      return `${normalizeMerchant(parts.merchant ?? '')}|${parts.date}`;
    case 'CARD_STATEMENT_MISSING':
      return `${parts.month}`;
  }
}

/** One entry in the acknowledgement set: `${kind}|${itemKey}`. */
export function ackEntry(kind: AckKind, itemKey: string): string {
  return `${kind}|${itemKey}`;
}

function normalizeMerchant(merchant: string): string {
  return merchant.trim().toLowerCase();
}

/**
 * "To look at": a card statement to upload, new repeating payments to answer, repeating payments
 * whose amount moved, and categories running high -- in that order, less what was marked "Got it".
 */
export function buildToLookAt(input: {
  insights?: InsightsData;
  recurring: RecurringItem[];
  changed: ChangedAmountItem[];
  acks: Set<string>;
}): LookAtRow[] {
  const { insights, recurring, changed, acks } = input;
  const rows: LookAtRow[] = [];
  const acked = (kind: AckKind, itemKey: string) => acks.has(ackEntry(kind, itemKey));

  const bills = insights?.unlinkedCardBills;
  if (bills) {
    const itemKey = ackKey('CARD_STATEMENT_MISSING', { month: bills.month });
    if (!acked('CARD_STATEMENT_MISSING', itemKey)) {
      rows.push({ kind: 'card-statement', ...bills, ackKind: 'CARD_STATEMENT_MISSING', ackItemKey: itemKey });
    }
  }

  for (const item of recurring) {
    if (item.state === 'NEEDS_ANSWER') rows.push({ kind: 'new-recurring', item });
  }

  // A payee can arrive both as a detected group in AMOUNT_CHANGED state and as a changed-amounts
  // entry (a saved answer whose payee is no longer detected); show it once.
  const seen = new Set<string>();
  const amountChanges: { merchant: string; category: string; latestAmount: number; latestDate: string }[] = [
    ...recurring
      .filter((r) => r.state === 'AMOUNT_CHANGED')
      .map((r) => ({
        merchant: r.merchant,
        category: r.answer ?? r.category ?? '',
        latestAmount: r.latestAmount ?? r.averageAmount,
        latestDate: r.lastDate,
      })),
    ...changed,
  ];
  for (const c of amountChanges) {
    const merchantKey = normalizeMerchant(c.merchant);
    if (seen.has(merchantKey)) continue;
    seen.add(merchantKey);
    const itemKey = ackKey('AMOUNT_CHANGED', { merchant: c.merchant, date: c.latestDate });
    if (acked('AMOUNT_CHANGED', itemKey)) continue;
    rows.push({
      kind: 'amount-changed', merchant: c.merchant, category: c.category, latestAmount: c.latestAmount,
      latestDate: c.latestDate, state: 'AMOUNT_CHANGED', ackKind: 'AMOUNT_CHANGED', ackItemKey: itemKey,
    });
  }

  const month = insights?.reportingMonth;
  if (month) {
    const high = (insights?.movers ?? [])
      .filter((m) => m.pctChange !== null && m.pctChange >= RUNNING_HIGH_MIN_PCT
        && m.current - m.priorAverage >= RUNNING_HIGH_MIN_RUPEES)
      .filter((m) => !acked('CATEGORY_HIGH', ackKey('CATEGORY_HIGH', { category: m.category, month })))
      .sort((a, b) => (b.current - b.priorAverage) - (a.current - a.priorAverage))
      .slice(0, RUNNING_HIGH_MAX_ROWS);
    for (const m of high) {
      rows.push({
        kind: 'running-high', category: m.category, current: m.current, priorAverage: m.priorAverage, month,
        ackKind: 'CATEGORY_HIGH', ackItemKey: ackKey('CATEGORY_HIGH', { category: m.category, month }),
      });
    }
  }
  return rows;
}

/**
 * "Coming up": repeating payments by their next expected date, soonest first. A date before today
 * is flagged `past` -- the data stops at the latest statement, so it may well have been paid -- and
 * never counted in what is still to go out this month.
 */
export function comingUp(recurring: RecurringItem[], today: string): {
  rows: { item: RecurringItem; past: boolean }[];
  stillToGoOut: number;
} {
  const thisMonth = today.slice(0, 7);
  const rows = [...recurring]
    .sort((a, b) => a.nextEstimate.localeCompare(b.nextEstimate))
    .map((item) => ({ item, past: item.nextEstimate < today }));
  const stillToGoOut = rows
    .filter((r) => !r.past && r.item.nextEstimate.slice(0, 7) === thisMonth)
    .reduce((sum, r) => sum + r.item.averageAmount, 0);
  return { rows, stillToGoOut };
}

/** "Paid regularly": every repeating payment as a monthly amount, largest first, with the totals. */
export function paidRegularly(recurring: RecurringItem[]): {
  rows: { item: RecurringItem; monthly: number; rising: boolean }[];
  monthlyTotal: number;
  yearlyTotal: number;
} {
  const rows = recurring
    .map((item) => ({
      item,
      monthly: item.averageAmount * (MONTHLY_FACTOR[item.label] ?? 1),
      rising: item.latestAmount != null && item.latestAmount > item.averageAmount,
    }))
    .sort((a, b) => b.monthly - a.monthly);
  const monthlyTotal = rows.reduce((sum, r) => sum + r.monthly, 0);
  return { rows, monthlyTotal, yearlyTotal: monthlyTotal * 12 };
}
