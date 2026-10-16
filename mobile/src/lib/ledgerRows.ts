import { counterpartyLabel } from './counterpartyLabel';
import { fmtCurrency, fmtDate, fromLocalDateString, toLocalDateString } from './format';
import { reconciliationBadge } from './reconciliationBadge';
import { CARD_INSTALMENT_LABEL, CARD_INSTALMENT_NOTE } from './cardInstalment';
import type { ReconciliationStatus, Transaction } from '../types';

/**
 * The Transactions list's row logic, kept apart from the screen so it can be tested as plain
 * functions: how transactions are grouped into days, what a row is titled and tagged, what a
 * screen reader says for it, and how the fetched pages become the list's items.
 */

export type GroupedRow =
  | { kind: 'header'; date: string; label: string; subtotal: number }
  | { kind: 'row'; transaction: Transaction };

// Bug fix. DUPLICATE and SUPERSEDED rows are still rendered inline (with their own badge --
// LedgerScreen shows everything, unlike the Dashboard/Reports screens, which is exactly why the
// default statusFilter is 'ALL' and these rows ordinarily sit in `txns` right alongside everything
// else), but they are not a second real transaction: DUPLICATE means the app itself believes this
// is the same purchase recorded twice, and SUPERSEDED means this row is history a later re-upload
// of the exact same statement period replaced. Summing either into the day's subtotal double-counts
// money that never actually moved twice -- the identical defect RefundNetting.reportable() exists
// to keep out of every backend total (Dashboard, Reports), just recreated here in a client-side
// aggregate the backend never computes. TRANSFER/REFUND/REVERSAL/INVESTMENT_TRANSFER stay included
// on purpose -- unlike these two, they represent real money that genuinely moved on this account
// today, the same convention Account.balance itself uses (see RefundNetting's own class doc).
const SUBTOTAL_EXCLUDED_STATUSES: ReadonlySet<ReconciliationStatus> = new Set(['DUPLICATE', 'SUPERSEDED']);

/**
 * Groups a merged list of transactions into day sections with a per-day net subtotal (income
 * minus expense for that day, signed the same way a single row's own amount is -- positive shows
 * in c.success, negative in c.danger, same convention as every other signed figure in this app).
 *
 * Runs on the FULLY MERGED txns array (every fetched page flattened), not per-page -- grouping
 * a day that happens to straddle two 20-row server pages still produces one header for that day,
 * since by the time this runs both pages are already concatenated.
 *
 * Groups by date VALUE (a Map, not "does this row's date differ from the one right before it"),
 * deliberately not assuming same-date rows stay contiguous. This was written when the backend
 * sorted by txnDate alone, so two rows sharing a date had no guaranteed relative order across
 * separate page fetches. The backend now breaks ties on createdAt and then id
 * (TransactionService.search), so a day's rows arrive together and in a fixed order; the
 * value-keyed group is kept because it costs nothing and still merges a day into one header if
 * this app is ever pointed at a server that predates that fix.
 *
 * `today` is a parameter, not read internally, so the caller controls how fresh "Today"/
 * "Yesterday" are; see this function's call site for why that matters (a memoized call needs the
 * current date as an explicit dependency, or it goes stale across a long-lived, otherwise-idle
 * mount -- the exact class of bug scripts/check-reporting-period-labels.py exists to catch
 * elsewhere in this app).
 */
export function groupTransactionsByDay(txns: Transaction[], today: Date = new Date()): GroupedRow[] {
  const yesterday = new Date(today);
  yesterday.setDate(yesterday.getDate() - 1);
  // toLocalDateString, NOT `.toISOString().slice(0, 10)` -- toISOString converts to UTC, which
  // silently shifts the key by the local UTC offset. In any timezone ahead of UTC (IST included --
  // this app's default locale is en-IN) that made "today" compare as YESTERDAY's date and
  // "yesterday" as TODAY's: a row actually dated today fell through to the full formatted-date
  // branch, and yesterday's row was mislabeled "Today" instead. Caught by this function's own
  // tests once they asserted on `label` against a fixed clock rather than only on date/subtotal.
  const todayKey = toLocalDateString(today);
  const yesterdayKey = toLocalDateString(yesterday);

  function labelFor(dateStr: string): string {
    if (dateStr === todayKey) return 'Today';
    if (dateStr === yesterdayKey) return 'Yesterday';
    const d = fromLocalDateString(dateStr);
    return d.toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'short', year: 'numeric' });
  }

  const order: string[] = [];
  const byDate = new Map<string, Transaction[]>();
  for (const t of txns) {
    let bucket = byDate.get(t.date);
    if (!bucket) {
      bucket = [];
      byDate.set(t.date, bucket);
      order.push(t.date);
    }
    bucket.push(t);
  }

  const result: GroupedRow[] = [];
  for (const date of order) {
    const rows = byDate.get(date)!;
    const subtotal = rows.reduce(
      (sum, t) => SUBTOTAL_EXCLUDED_STATUSES.has(t.reconciliationStatus)
        ? sum
        : sum + (t.type === 'INCOME' ? t.amount : -Math.abs(t.amount)),
      0
    );
    result.push({ kind: 'header', date, label: labelFor(date), subtotal });
    for (const t of rows) result.push({ kind: 'row', transaction: t });
  }
  return result;
}

// A word is a run of the letters A to Z; an apostrophe between letters stays inside it, so
// "DOMINO'S" is one word. Letters outside A to Z are left alone: statements print these names in
// plain capitals, and a pattern that names other alphabets is one more thing to get wrong.
const WORD = /[A-Za-z]+(?:'[A-Za-z]+)*/g;

// Payment rails and banking terms that stay in capitals (the owner, 2026-10-16): "Upi" and "Neft"
// read as typos. A fixed list, not a guess at what is an abbreviation; a whole word only, so
// "POSTAL" is still softened.
const KEPT_IN_CAPITALS: ReadonlySet<string> = new Set([
  'UPI', 'NEFT', 'IMPS', 'RTGS', 'ATM', 'POS', 'EMI', 'ECS', 'NACH', 'GST', 'TDS', 'SIP',
]);

/**
 * The owner's choice for row titles (design, 2026-10-12): a word printed entirely in capitals is
 * shown with only its first letter capital. A word that already has a small letter, a single
 * letter, digits and punctuation are left exactly as printed. An abbreviation therefore reads as a
 * word ("IRCTC" as "Irctc"); that cost was accepted with the choice, except for the banking terms
 * in KEPT_IN_CAPITALS above.
 */
export function softenCapitals(text: string): string {
  return text.replace(WORD, (word) => {
    const letters = word.replace(/'/g, '');
    if (letters.length < 2 || word !== word.toUpperCase() || KEPT_IN_CAPITALS.has(word)) return word;
    return word[0] + word.slice(1).toLowerCase();
  });
}

/** Lower case, with every run of anything that is not a letter or digit as one space: the same
 *  reduction the server's merchant label has already been through. */
function plain(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
}

function capitaliseIfLower(text: string): string {
  if (text !== text.toLowerCase()) return text;
  return text.replace(/(^|\s)([a-z])/g, (_, gap: string, letter: string) => gap + letter.toUpperCase())
    // The casing here is made up, so a banking term gets its capitals back (KEPT_IN_CAPITALS).
    .replace(WORD, (word) => (KEPT_IN_CAPITALS.has(word.toUpperCase()) ? word.toUpperCase() : word));
}

function printedTitle(description: string, merchant: string): string {
  const words = plain(merchant).split(' ').filter(Boolean);
  if (words.length === 0) return description || 'Transaction';
  const key = words.join(' ');

  // A description without a slash that begins with the merchant is plain words already (a
  // hand-entered transaction, an unstructured bank line): it is the title, in full. The slash
  // test keeps out the layouts that print the payee ahead of a structured narration.
  if (description && !description.includes('/')) {
    const reduced = plain(description);
    if (reduced === key || reduced.startsWith(`${key} `)) return description;
  }

  // Otherwise the merchant's words are looked for inside the description, whatever punctuation
  // sits between them, as whole words. `words` holds only a-z and 0-9, so nothing in it can act
  // as a pattern character.
  const found = new RegExp(`(?:^|[^A-Za-z0-9])(${words.join('[^A-Za-z0-9]+')})(?![A-Za-z0-9])`, 'i').exec(description);
  if (found) return found[1];

  return capitaliseIfLower(merchant);
}

/**
 * Who a transaction was with, as the row shows it. The server's `merchant` cannot be shown as it
 * is: it is lower case, stripped of punctuation and cut to four words, for hand-entered
 * transactions too. See the design document for the order of the rules and what was measured
 * (docs/superpowers/specs/2026-10-11-mobile-transactions-redesign-design.md, "Row title").
 */
export function rowTitle(t: Pick<Transaction, 'description' | 'merchant'>): string {
  const description = (t.description ?? '').trim();
  const merchant = (t.merchant ?? '').trim();
  return softenCapitals(merchant ? printedTitle(description, merchant) : description || 'Transaction');
}

export type RowTagTone = 'warning' | 'danger' | 'success' | 'neutral';
export interface RowTag {
  key: 'match' | 'bank-correction' | 'needs-review' | 'recurring' | 'international' | 'card-instalment';
  label: string;
  tone: RowTagTone;
}

const MATCH_TONE: Record<Exclude<ReconciliationStatus, 'OK'>, RowTagTone> = {
  DUPLICATE: 'danger',
  REVERSAL: 'warning',
  REFUND: 'success',
  TRANSFER: 'neutral',
  INVESTMENT_TRANSFER: 'neutral',
  SUPERSEDED: 'neutral',
};

/**
 * The tags a row shows, in order. "Reviewed" and "Categorized" are the normal state of a row and
 * are not tags; they stay in the details sheet (statusBadges in LedgerScreen.tsx still makes them
 * for that sheet).
 */
export function rowTags(t: Transaction): RowTag[] {
  const tags: RowTag[] = [];
  const match = reconciliationBadge(t.reconciliationStatus);
  if (match && t.reconciliationStatus !== 'OK') {
    tags.push({ key: 'match', label: match.label, tone: MATCH_TONE[t.reconciliationStatus] });
  }
  if (t.pendingBankCorrection) tags.push({ key: 'bank-correction', label: 'Bank Correction', tone: 'danger' });
  if (t.needsCategoryReview) tags.push({ key: 'needs-review', label: 'Needs Review', tone: 'warning' });
  if (t.recurring) tags.push({ key: 'recurring', label: 'Recurring', tone: 'neutral' });
  if (t.international) tags.push({ key: 'international', label: 'International', tone: 'neutral' });
  // A fact about the row, like international. What it means is in the details sheet, and spoken
  // in full below.
  if (t.cardInstalment) tags.push({ key: 'card-instalment', label: CARD_INSTALMENT_LABEL, tone: 'neutral' });
  return tags;
}

/** How a tag is spoken: international in lower case, mid-sentence; the instalment mark as its full
 *  note, since its two words alone do not say why it matters and a listener has no tooltip. */
function spokenTag(tag: RowTag): string {
  if (tag.key === 'international') return 'international';
  if (tag.key === 'card-instalment') return CARD_INSTALMENT_NOTE;
  return tag.label;
}

/**
 * What a screen reader says for a row: the title as shown, direction and amount, category, the
 * date (the day heading is a separate element), who was on the other side in full, what a match
 * means, then the tags on the row.
 */
export function rowSpokenLabel(t: Transaction): string {
  const cp = counterpartyLabel(t.counterpartyType, t.type);
  const match = reconciliationBadge(t.reconciliationStatus);
  const parts = [
    rowTitle(t),
    `${t.type === 'INCOME' ? 'income' : 'expense'} ${fmtCurrency(Math.abs(t.amount))}`,
    t.categoryName,
    // Read out as a date ("14 Jul 2026"), not as the digits and dashes of "2026-07-14".
    fmtDate(t.date) ?? t.date,
    cp?.full,
    match?.hint,
    ...rowTags(t).filter((tag) => tag.key !== 'match').map(spokenTag),
  ];
  return parts.filter(Boolean).join(', ');
}

// Written out, not asked of the device: the short month for September differs between the
// engines this runs on ("Sep" and "Sept"), and a heading must read the same in a test as on a phone.
const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "Today", "Yesterday", or "Wed, 30 Sep"; the year is added when it is not `today`'s year. */
export function dayHeading(date: string, today: Date): string {
  const yesterday = new Date(today);
  yesterday.setDate(yesterday.getDate() - 1);
  if (date === toLocalDateString(today)) return 'Today';
  if (date === toLocalDateString(yesterday)) return 'Yesterday';
  const d = fromLocalDateString(date);
  const short = `${WEEKDAYS[d.getDay()]}, ${d.getDate()} ${MONTHS[d.getMonth()]}`;
  return d.getFullYear() === today.getFullYear() ? short : `${short} ${d.getFullYear()}`;
}

/**
 * Every fetched page as one list with each transaction once. The server's search has returned the
 * same row on two pages (its sort had no tie-break, and rows can move between two requests); the
 * first copy keeps its place and later copies are dropped, so a row is neither listed nor counted
 * twice.
 */
export function mergePagesById(pages: readonly { content: Transaction[] }[]): Transaction[] {
  const seen = new Set<string>();
  const merged: Transaction[] = [];
  for (const page of pages) {
    for (const t of page.content) {
      if (seen.has(t.id)) continue;
      seen.add(t.id);
      merged.push(t);
    }
  }
  return merged;
}

/** Drawing one very long card in a single pass is what measured as expensive, so a day is cut. */
export const DAY_PIECE_SIZE = 25;

export interface DayPiece {
  key: string;
  date: string;
  /** The day's heading, on its first piece only. */
  heading: string | null;
  /** The whole day's total, on every piece. */
  subtotal: number;
  rows: Transaction[];
  first: boolean;
  last: boolean;
}

/** The list's items: each day as one card, cut into pieces of at most `pieceSize` rows. */
export function buildDayPieces(txns: Transaction[], today: Date = new Date(), pieceSize: number = DAY_PIECE_SIZE): DayPiece[] {
  const pieces: DayPiece[] = [];
  let day: { date: string; subtotal: number; rows: Transaction[] } | null = null;

  function close() {
    if (!day) return;
    const count = Math.ceil(day.rows.length / pieceSize);
    for (let i = 0; i < count; i++) {
      pieces.push({
        key: `${day.date}#${i}`,
        date: day.date,
        heading: i === 0 ? dayHeading(day.date, today) : null,
        subtotal: day.subtotal,
        rows: day.rows.slice(i * pieceSize, (i + 1) * pieceSize),
        first: i === 0,
        last: i === count - 1,
      });
    }
  }

  for (const item of groupTransactionsByDay(txns, today)) {
    if (item.kind === 'header') {
      close();
      day = { date: item.date, subtotal: item.subtotal, rows: [] };
    } else {
      day!.rows.push(item.transaction);
    }
  }
  close();
  return pieces;
}
