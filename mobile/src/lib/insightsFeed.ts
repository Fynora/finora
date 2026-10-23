import type {
  AckKind, ChangedAmountItem, ExpectedShare, InsightsData, LookAtServerRow, PeopleSuggestion, PeopleSuggestionKind,
  PersonBalance, RecurringItem, UnlinkedCardBills,
} from '../api/endpoints';

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
  // Shared payments and loans: the server picks and orders these (PeopleSuggestions); its "Got it"
  // kind is the row's own kind and its key comes with it.
  | { kind: 'people'; item: PeopleSuggestion }
  | ({
      kind: 'amount-changed';
      merchant: string;
      category: string;
      latestAmount: number;
      latestDate: string;
      state: 'AMOUNT_CHANGED';
    } & Dismissible)
  | ({
      kind: 'running-high'; category: string; current: number; priorAverage: number; month: string;
      /** The server can explain it ("Why?"): its usual month holds at least 2 months. Never set on
       *  rows built on the phone. */
      whyAvailable?: boolean;
    } & Dismissible);

/** The key an acknowledgement is stored under for each kind, exactly as V277 documents it. The
 *  people kinds (V281) carry their key from the server instead. */
export function ackKey(
  kind: Exclude<AckKind, PeopleSuggestionKind>,
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
 * "To look at": a card statement to upload, questions about money with people, new repeating
 * payments to answer, repeating payments whose amount moved, and categories running high -- in that
 * order, less what was marked "Got it".
 */
export function buildToLookAt(input: {
  insights?: InsightsData;
  recurring: RecurringItem[];
  changed: ChangedAmountItem[];
  acks: Set<string>;
  /** The server's people rows; absent on an older server. */
  people?: PeopleSuggestion[];
}): LookAtRow[] {
  const { insights, recurring, changed, acks, people = [] } = input;
  const rows: LookAtRow[] = [];
  const acked = (kind: AckKind, itemKey: string) => acks.has(ackEntry(kind, itemKey));

  const bills = insights?.unlinkedCardBills;
  if (bills) {
    const itemKey = ackKey('CARD_STATEMENT_MISSING', { month: bills.month });
    if (!acked('CARD_STATEMENT_MISSING', itemKey)) {
      rows.push({ kind: 'card-statement', ...bills, ackKind: 'CARD_STATEMENT_MISSING', ackItemKey: itemKey });
    }
  }

  // The server already leaves out what was marked "Got it"; this also hides one the moment it is tapped.
  for (const item of people) {
    if (!acked(item.kind, item.ackKey)) rows.push({ kind: 'people', item });
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
 * The server's "To look at" rows (AI insights spec §8.2), in its order, as the rows buildToLookAt
 * makes. A row of a kind this app does not know, or missing a field its kind needs, is left out
 * rather than drawn broken -- a newer server may add kinds.
 */
export function lookAtRowsFromServer(rows: LookAtServerRow[]): LookAtRow[] {
  const out: LookAtRow[] = [];
  for (const r of rows) {
    switch (r.kind) {
      case 'card-statement':
        if (r.month && r.total != null && r.count != null && r.ackKind && r.ackItemKey) {
          out.push({
            kind: 'card-statement', month: r.month, total: r.total, count: r.count, cardName: r.cardName,
            cardLast4: r.cardLast4, ackKind: r.ackKind, ackItemKey: r.ackItemKey,
          });
        }
        break;
      case 'people':
        if (r.people) out.push({ kind: 'people', item: r.people });
        break;
      case 'new-recurring':
        if (r.recurring) out.push({ kind: 'new-recurring', item: r.recurring });
        break;
      case 'amount-changed':
        if (r.merchant && r.latestAmount != null && r.latestDate && r.ackKind && r.ackItemKey) {
          out.push({
            kind: 'amount-changed', merchant: r.merchant, category: r.category ?? '', latestAmount: r.latestAmount,
            latestDate: r.latestDate, state: 'AMOUNT_CHANGED', ackKind: r.ackKind, ackItemKey: r.ackItemKey,
          });
        }
        break;
      case 'running-high':
        if (r.category && r.current != null && r.priorAverage != null && r.month && r.ackKind && r.ackItemKey) {
          out.push({
            kind: 'running-high', category: r.category, current: r.current, priorAverage: r.priorAverage,
            month: r.month, ackKind: r.ackKind, ackItemKey: r.ackItemKey, whyAvailable: r.whyAvailable === true,
          });
        }
        break;
      default:
        break;
    }
  }
  return out;
}

/** Whether a row was marked "Got it": by its kind's key, as the server stores it. */
export function isAcked(row: LookAtRow, acks: Set<string>): boolean {
  if (row.kind === 'new-recurring') return false;
  if (row.kind === 'people') return acks.has(ackEntry(row.item.kind, row.item.ackKey));
  return acks.has(ackEntry(row.ackKind, row.ackItemKey));
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

/** "4th", "22nd": a day of the month with its English ending. */
function ordinal(day: number): string {
  const tens = day % 100;
  if (tens >= 11 && tens <= 13) return `${day}th`;
  return `${day}${({ 1: 'st', 2: 'nd', 3: 'rd' } as Record<number, string>)[day % 10] ?? 'th'}`;
}

/** "Coming up", money in: each split rule's usual share, around its usual day, marked late once past. */
export function expectedSharesIn(expected: ExpectedShare[]): {
  key: string; title: string; when: string; amount: number; late: boolean;
}[] {
  return expected.map((e) => ({
    key: e.ruleId,
    title: `${e.label}'s share`,
    when: e.late ? `late, usually around the ${ordinal(e.usualDay)}` : `around the ${ordinal(e.usualDay)}`,
    amount: e.usualAmount,
    late: e.late,
  }));
}

/** "With people": who owes the user and whom the user owes, largest first. A family person is never
 *  listed here (spec §7), and a settled balance is not a line. */
export function withPeople(balances: PersonBalance[]): { owedToYou: PersonBalance[]; youOwe: PersonBalance[] } {
  const open = balances.filter((p) => !p.family && p.owedToUser !== 0);
  return {
    owedToYou: open.filter((p) => p.owedToUser > 0).sort((a, b) => b.owedToUser - a.owedToUser),
    youOwe: open.filter((p) => p.owedToUser < 0).sort((a, b) => a.owedToUser - b.owedToUser),
  };
}

/** "Paid regularly": every repeating payment as a monthly amount, largest first, with the totals. A
 *  bill other people share counts at the user's share, as every other figure does. */
export function paidRegularly(recurring: RecurringItem[]): {
  rows: { item: RecurringItem; monthly: number; rising: boolean }[];
  monthlyTotal: number;
  yearlyTotal: number;
} {
  const rows = recurring
    .map((item) => ({
      item,
      monthly: (item.yourShare ?? item.averageAmount) * (MONTHLY_FACTOR[item.label] ?? 1),
      rising: item.latestAmount != null && item.latestAmount > item.averageAmount,
    }))
    .sort((a, b) => b.monthly - a.monthly);
  const monthlyTotal = rows.reduce((sum, r) => sum + r.monthly, 0);
  return { rows, monthlyTotal, yearlyTotal: monthlyTotal * 12 };
}
