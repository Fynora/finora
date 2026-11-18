import type { PagedResponse, TransactionFilters } from '../api/endpoints';
import type { Transaction } from '../types';

/** The most rows the server returns for one request (PageBounds.DEFAULT_MAX_SIZE). Asking for more
 *  is not an error: the answer is silently cut to this many. */
export const STATS_PAGE_SIZE = 100;

/** Where the summary stops reading, so one very long history cannot turn opening the page into
 *  hundreds of requests. Past this the page says the figures cover only the newest rows. */
export const STATS_MAX_ROWS = 5000;

export interface LedgerStatsRows {
  content: Transaction[];
  /** How many rows match these filters, from the server: exact even when not all were read. */
  totalElements: number;
  /** False when `content` is not every matching row, so a sum over it is not the whole total. */
  complete: boolean;
}

/**
 * Every row matching these filters, for the summary cards and category chips to add up.
 *
 * Reads on from the last row held rather than by page number (see lib/ledgerPaging): a transaction
 * added or deleted while this runs would otherwise shift the pages and count a row twice or not at
 * all. A continued answer's totalElements counts only the rows still to come.
 */
export async function fetchLedgerStatsRows(
  filters: TransactionFilters,
  search: (filters: TransactionFilters) => Promise<PagedResponse<Transaction>>,
): Promise<LedgerStatsRows> {
  const first = await search({ ...filters, page: 0, size: STATS_PAGE_SIZE });
  const rows = new Map(first.content.map((t) => [t.id, t]));
  let last = first;
  while (last.totalElements > last.content.length && last.content.length > 0 && rows.size < STATS_MAX_ROWS) {
    const after = last.content[last.content.length - 1].id;
    last = await search({ ...filters, page: 0, size: STATS_PAGE_SIZE, after });
    const before = rows.size;
    for (const t of last.content) rows.set(t.id, t);
    // Nothing new means the server did not continue from that row: stop rather than loop.
    if (rows.size === before) break;
  }
  return { content: [...rows.values()], totalElements: first.totalElements, complete: rows.size >= first.totalElements };
}
