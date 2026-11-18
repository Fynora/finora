import type { PagedResponse, TransactionFilters } from '../api/endpoints';
import type { Transaction } from '../types';
import { SUBTOTAL_EXCLUDED_STATUSES } from './ledgerRows';

/** The most rows the server returns for one request (PageBounds.DEFAULT_MAX_SIZE). Asking for more
 *  is not an error: the answer is silently cut to this many. */
export const FILTER_TOTALS_PAGE_SIZE = 100;

/** Where the adding up stops, so one very long history cannot turn a filter into hundreds of
 *  requests. Past this the card says its figures cover only the newest rows. */
export const FILTER_TOTALS_MAX_ROWS = 5000;

export interface LedgerRowSums {
  moneyIn: number;
  moneyOut: number;
  /** Rows matching the filters that are left out of both sums: duplicates and replaced rows. */
  notCounted: number;
}

export interface LedgerFilterTotals extends LedgerRowSums {
  rowsRead: number;
  /** How many rows match these filters, from the server: exact even when not all were read. */
  totalElements: number;
  /** False when not every matching row was read, so the sums are not the whole total. */
  complete: boolean;
}

/** Whether these filters leave out any row, which is when the month's figures stop describing the list. */
export function isNarrowedLedger(f: TransactionFilters): boolean {
  return !!(f.categoryId || f.accountId || f.type || f.status || f.dateFrom || f.dateTo || f.keyword || f.payeeOf)
    || f.international !== undefined;
}

/**
 * What the rows add up to, by the rule the list's day subtotals use, so the card and the days under
 * it agree. These are not the dashboard's Income and Expenses: the server decides those from what
 * a row does not carry (which credits are earned, which refunds net off which purchase).
 */
export function addUpLedgerRows(rows: Transaction[]): LedgerRowSums {
  // In paise: adding rupee fractions drifts (0.1 + 0.2).
  let inPaise = 0;
  let outPaise = 0;
  let notCounted = 0;
  for (const t of rows) {
    if (SUBTOTAL_EXCLUDED_STATUSES.has(t.reconciliationStatus)) notCounted += 1;
    else if (t.type === 'INCOME') inPaise += Math.round(Math.abs(t.amount) * 100);
    else outPaise += Math.round(Math.abs(t.amount) * 100);
  }
  return { moneyIn: inPaise / 100, moneyOut: outPaise / 100, notCounted };
}

/**
 * The totals of every row matching these filters.
 *
 * Reads on from the last row held rather than by page number (see LedgerPageParam): a transaction
 * added or deleted while this runs would otherwise shift the pages and count a row twice or not at
 * all. A continued answer's totalElements counts only the rows still to come. Mirrors
 * frontend/src/lib/ledgerStats.ts.
 */
export async function fetchLedgerFilterTotals(
  filters: TransactionFilters,
  search: (filters: TransactionFilters) => Promise<PagedResponse<Transaction>>,
): Promise<LedgerFilterTotals> {
  const first = await search({ ...filters, page: 0, size: FILTER_TOTALS_PAGE_SIZE });
  const rows = new Map(first.content.map((t) => [t.id, t]));
  let last = first;
  while (last.totalElements > last.content.length && last.content.length > 0 && rows.size < FILTER_TOTALS_MAX_ROWS) {
    const after = last.content[last.content.length - 1].id;
    last = await search({ ...filters, page: 0, size: FILTER_TOTALS_PAGE_SIZE, after });
    const before = rows.size;
    for (const t of last.content) rows.set(t.id, t);
    // Nothing new means the server did not continue from that row: stop rather than loop.
    if (rows.size === before) break;
  }
  return {
    ...addUpLedgerRows([...rows.values()]),
    rowsRead: rows.size,
    totalElements: first.totalElements,
    complete: rows.size >= first.totalElements,
  };
}
