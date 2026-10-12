import type { PagedResponse, TransactionFilters } from '../api/endpoints';

/**
 * Paging the Transactions page so that moving to the next page continues from the last row on
 * screen instead of counting rows from the top.
 *
 * A page number counts from the top of the list. A transaction added or deleted elsewhere between
 * two clicks (an import finishing, an edit on a phone) shifts every later page by one row, so the
 * next page began with a row already seen, or stepped over one. The server can instead be told the
 * last row already held (`after`) and answer with the rows that follow it.
 *
 * The page still shows numbered pages. So this remembers, for each page already seen, the row the
 * page after it continues from, and uses it whenever that next page is asked for. A jump to a page
 * whose predecessor was never seen has no such row and is asked for by number, as before.
 */
export interface PageAnchors {
  /** Which list these belong to -- see {@link pagingSignature}. */
  signature: string;
  /** For a page index, the id of the last row of the page before it. */
  afterByPage: Record<number, string>;
}

export const NO_ANCHORS: PageAnchors = { signature: '', afterByPage: {} };

/**
 * Identifies the list a request pages through: every filter, the sort and the page size, but not
 * which page is asked for. A remembered row is only a place in the list it was seen in.
 */
export function pagingSignature(filters: TransactionFilters): string {
  const entries = Object.entries(filters)
    .filter(([key, value]) => key !== 'page' && key !== 'after' && value !== undefined)
    .sort(([a], [b]) => a.localeCompare(b));
  return JSON.stringify(entries);
}

/** Records the last row of a page that has loaded as the place the next page continues from. */
export function rememberAnchors(
  anchors: PageAnchors,
  signature: string,
  page: PagedResponse<{ id: string }>,
): PageAnchors {
  const known = anchors.signature === signature ? anchors.afterByPage : {};
  const lastRow = page.content[page.content.length - 1];
  if (!lastRow) return anchors;
  if (anchors.signature === signature && known[page.page + 1] === lastRow.id) return anchors;
  return { signature, afterByPage: { ...known, [page.page + 1]: lastRow.id } };
}

/**
 * The request to send for these filters. The page number always goes along: a server that does not
 * know `after` pages by it.
 */
export function requestFor(filters: TransactionFilters, anchors: PageAnchors): TransactionFilters {
  const page = filters.page ?? 0;
  const after = page > 0 && anchors.signature === pagingSignature(filters) ? anchors.afterByPage[page] : undefined;
  return after === undefined ? filters : { ...filters, after };
}

/**
 * Continued from a row, the server describes only what is left: page 0 of the remainder, with
 * totals that count the rows after the named one. The Transactions page thinks in pages of the
 * whole list, so this turns such an answer back into one: the page that was asked for, with the
 * pages and rows before it added back on.
 *
 * An answer that already is a page of the whole list is returned untouched. That is what a server
 * that does not know `after` sends, and what any server sends when the named row is not one of the
 * caller's own transactions.
 */
export function asWholeListPage<T>(
  response: PagedResponse<T>,
  requestedPage: number,
  namedARow: boolean,
): PagedResponse<T> {
  if (!namedARow || requestedPage === 0 || response.page === requestedPage) return response;
  return {
    ...response,
    page: requestedPage,
    totalPages: requestedPage + response.totalPages,
    totalElements: requestedPage * response.size + response.totalElements,
  };
}
