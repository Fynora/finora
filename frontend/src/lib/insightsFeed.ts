import type { AckKind, LookAtServerRow, PeopleSuggestion, RecurringItem, ReportTrendPoint } from '../api/endpoints';
import { rupees } from './people';

/**
 * The website Insights page's rows (docs/superpowers/specs/2026-10-24-web-insights-redesign-design.md):
 * what needs a look, what is coming up and what is paid regularly. The same rules as
 * mobile/src/lib/insightsFeed.ts, so the two apps say the same thing about the same money. Pure, so
 * every rule is tested on its own.
 */

/** The newest month's "To look at" rows: the Insights page and the sidebar dot share this key, so
 *  the dot costs no second request while the page is open. */
export const LOOK_AT_KEY = ['ai-insights', 'look-at', null] as const;

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
  | ({
      kind: 'card-statement'; month: string; total: number; count: number;
      cardName: string | null; cardLast4: string | null;
    } & Dismissible)
  | { kind: 'people'; item: PeopleSuggestion }
  | { kind: 'new-recurring'; item: RecurringItem }
  | ({ kind: 'amount-changed'; merchant: string; category: string; latestAmount: number; latestDate: string } & Dismissible)
  | ({ kind: 'running-high'; category: string; current: number; priorAverage: number; month: string } & Dismissible);

/** One entry in the acknowledgement set: `${kind}|${itemKey}`. */
export function ackEntry(kind: AckKind, itemKey: string): string {
  return `${kind}|${itemKey}`;
}

/** "September 2026". */
export function monthLong(month: string): string {
  const [y, m] = month.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
}

function monthName(month: string): string {
  const [y, m] = month.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'long' });
}

function previousMonth(month: string): string {
  const [y, m] = month.split('-').map(Number);
  return m === 1 ? `${y - 1}-12` : `${y}-${String(m - 1).padStart(2, '0')}`;
}

/**
 * The server's "To look at" rows (AI insights spec §8.2), in its order. A row of a kind this page does
 * not know, or missing a field its kind needs, is left out rather than drawn broken -- a newer server
 * may add kinds. The website has no "Why?" (AI insights spec §11.8), so whyAvailable is not kept.
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
            latestDate: r.latestDate, ackKind: r.ackKind, ackItemKey: r.ackItemKey,
          });
        }
        break;
      case 'running-high':
        if (r.category && r.current != null && r.priorAverage != null && r.month && r.ackKind && r.ackItemKey) {
          out.push({
            kind: 'running-high', category: r.category, current: r.current, priorAverage: r.priorAverage,
            month: r.month, ackKind: r.ackKind, ackItemKey: r.ackItemKey,
          });
        }
        break;
      default:
        break;
    }
  }
  return out;
}

/** The "Got it" kind and key a row is stored under; null for a new repeating payment, which is
 *  answered, never dismissed. */
export function rowAck(row: LookAtRow): { kind: AckKind; itemKey: string } | null {
  if (row.kind === 'new-recurring') return null;
  if (row.kind === 'people') return { kind: row.item.kind, itemKey: row.item.ackKey };
  return { kind: row.ackKind, itemKey: row.ackItemKey };
}

/** Whether a row was marked "Got it": by its kind's key, as the server stores it. */
export function isAcked(row: LookAtRow, acks: Set<string>): boolean {
  const ack = rowAck(row);
  return ack !== null && acks.has(ackEntry(ack.kind, ack.itemKey));
}

/** A row in a few words: for "Hidden: ..." after "Got it", and for screen-reader labels. */
export function rowLabel(row: LookAtRow): string {
  switch (row.kind) {
    case 'card-statement': return `Card statement for ${monthLong(row.month)}`;
    case 'people': return row.item.label;
    case 'new-recurring': return `New repeating payment: ${row.item.merchant}`;
    case 'amount-changed': return `Amount changed: ${row.merchant}`;
    case 'running-high': return `${row.category} above your usual month`;
  }
}

/** A stable key per row, for React and for the pending "Got it" set. */
export function rowKey(row: LookAtRow): string {
  if (row.kind === 'new-recurring') return `new|${row.item.merchant}`;
  const ack = rowAck(row)!;
  return ackEntry(ack.kind, ack.itemKey);
}

/**
 * "Coming up": repeating payments by their next expected date, soonest first. A date before today
 * is flagged `past` -- the data stops at the latest statement, so it may well have been paid -- and
 * never counted in what is still to go out this month. A shared bill counts at the whole payment:
 * that is what leaves the account; the others' shares come in separately.
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

/** The month line: what was spent in the reporting month and the change from the month before, from
 *  Reports' own trend, so the two pages can never disagree. The same sentence the phone writes. */
export function monthLine(month: string | null, points: ReportTrendPoint[]): string | null {
  if (!month) return null;
  const current = points.find((p) => p.month === month);
  if (!current) return null;
  const name = monthLong(month);
  if (current.complete === false) return `You've spent ${rupees(current.expense)} in ${name} so far.`;
  const before = points.find((p) => p.month === previousMonth(month));
  if (!before || before.expense <= 0) return `You spent ${rupees(current.expense)} in ${name}.`;
  const pct = Math.round(((current.expense - before.expense) / before.expense) * 100);
  const beforeName = monthName(before.month);
  const comparison = pct === 0 ? `the same as ${beforeName}` : `${Math.abs(pct)}% ${pct > 0 ? 'more' : 'less'} than ${beforeName}`;
  return `You spent ${rupees(current.expense)} in ${name}, ${comparison}.`;
}
