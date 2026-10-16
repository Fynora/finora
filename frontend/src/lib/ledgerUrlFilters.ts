/** What a link into the Ledger can ask for besides a search term: a date range and one category.
 *  Reports rows link here to show the transactions in that category for that month. It is the
 *  Ledger's own filter, not a replay of the report's sum: the report nets refunds against purchases
 *  and leaves out transfers, so the Ledger's rows and total for a category can differ from the
 *  report row's amount. Wording around the link must not promise they match. */
export interface LedgerUrlFilters {
  dateFrom?: string;
  dateTo?: string;
  /** A category NAME, because that is all a report row carries; resolved to an id by
   *  {@link categoryIdByName} once the user's categories have loaded. */
  categoryName?: string;
}

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

/** A real calendar date, not just the right shape: "2026-02-31" matches the pattern but is not a day. */
function isRealDate(value: string): boolean {
  if (!ISO_DATE.test(value)) return false;
  const [y, m, d] = value.split('-').map(Number);
  const date = new Date(Date.UTC(y, m - 1, d));
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d;
}

/** Reads `from`, `to` and `category` from the query string. Anything malformed is ignored rather
 *  than passed on: a hand-edited or stale link must not send the server a garbage date. A range
 *  whose end is before its start is dropped whole, since it would match nothing and read as
 *  "you have no transactions". */
export function parseLedgerUrlFilters(params: URLSearchParams): LedgerUrlFilters {
  const from = params.get('from');
  const to = params.get('to');
  const category = params.get('category')?.trim();
  const out: LedgerUrlFilters = {};
  const fromOk = from !== null && isRealDate(from);
  const toOk = to !== null && isRealDate(to);
  if (fromOk && toOk && from > to) {
    // Backwards range: neither end is trustworthy.
  } else {
    if (fromOk) out.dateFrom = from;
    if (toOk) out.dateTo = to;
  }
  if (category) out.categoryName = category;
  return out;
}

/** The id of the category with this name (case-insensitive, exact), or undefined when there is none.
 *  Undefined means "apply no category filter", never "filter to nothing": a report's catch-all
 *  "Uncategorized" bucket has no category row to match. */
export function categoryIdByName(categories: { id: string; name: string }[] | undefined, name: string): string | undefined {
  const wanted = name.trim().toLowerCase();
  return categories?.find((c) => c.name.trim().toLowerCase() === wanted)?.id;
}

/** A link from Reports to the transactions in a category for a month, bounded by the month's own
 *  first and last day (the same range the report asked for). */
export function ledgerLinkForCategory(category: string, month: string, lastDay: string): string {
  const params = new URLSearchParams({ category, from: `${month}-01`, to: lastDay });
  return `/app/transactions?${params.toString()}`;
}
