/** Below this many signups a row's import rate is shown but marked too small to judge. */
export const MIN_SIGNUPS_TO_JUDGE = 5;

/** One row of a source or landing-page table, reduced to what the rates need. */
export interface RateRow {
  key: string;
  label: string;
  signups: number;
  gotDataIn: number;
  /** Null when the backend did not send it (deployed before the field). */
  cameBackLaterDay: number | null;
  /** Kept at the bottom, in the given order, whatever the sort: "Other pages", "No landing page recorded". */
  pinned?: boolean;
}

export type RateSortKey = 'signups' | 'gotDataIn' | 'rate' | 'cameBackLaterDay';
export interface RateSort { key: RateSortKey; descending: boolean }

/** Got data in, of signups, as a fraction; null with no signups. */
export function importRate(row: RateRow): number | null {
  return row.signups === 0 ? null : row.gotDataIn / row.signups;
}

/** Some signups, but too few for the rate to mean much. */
export function tooSmall(row: RateRow): boolean {
  return row.signups > 0 && row.signups < MIN_SIGNUPS_TO_JUDGE;
}

function value(row: RateRow, key: RateSortKey): number {
  switch (key) {
    case 'signups': return row.signups;
    case 'gotDataIn': return row.gotDataIn;
    case 'rate': return importRate(row) ?? -1;
    case 'cameBackLaterDay': return row.cameBackLaterDay ?? -1;
  }
}

/**
 * Rows in the chosen order. Pinned rows stay last. By rate, rows too small to judge and rows with no
 * signups go after the rest whichever way, so a 1-of-1 never heads the list. Ties keep the given order.
 */
export function sortRates(rows: RateRow[], sort: RateSort): RateRow[] {
  const rank = (row: RateRow) => {
    if (row.pinned) return 2;
    if (sort.key === 'rate' && (tooSmall(row) || row.signups === 0)) return 1;
    return 0;
  };
  return rows
    .map((row, index) => ({ row, index }))
    .sort((a, b) => {
      const byRank = rank(a.row) - rank(b.row);
      if (byRank !== 0) return byRank;
      if (a.row.pinned) return a.index - b.index;
      const diff = value(a.row, sort.key) - value(b.row, sort.key);
      if (diff !== 0) return sort.descending ? -diff : diff;
      return a.index - b.index;
    })
    .map(({ row }) => row);
}
