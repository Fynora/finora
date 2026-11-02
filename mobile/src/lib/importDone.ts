import { fmtCurrency, monthLabelLong } from './format';
import { cardPhrase, type LookAtRow } from './insightsFeed';

/**
 * The Import Done step's "{Month} in Fynora" lines (spec 2026-11-02, Step 3): what Insights' "To
 * look at" now says about the statement's month, one short line each. Rows about other people
 * (shared payments, loans) are left to Insights, where their own buttons live.
 */

export interface DoneLine {
  key: string;
  kind: LookAtRow['kind'];
  title: string;
  detail: string;
}

const MAX_LINES = 3;

/** "2026-09-30" → "2026-09". */
export function statementMonthOf(periodEnd: string | null): string | null {
  return periodEnd ? periodEnd.slice(0, 7) : null;
}

function lineFor(row: LookAtRow): DoneLine | null {
  switch (row.kind) {
    case 'card-statement':
      return {
        key: `card-${row.month}`,
        kind: row.kind,
        title: `Card statement missing for ${monthLabelLong(row.month)}`,
        detail: `You paid ${fmtCurrency(row.total)} toward ${cardPhrase(row)}`,
      };
    case 'new-recurring':
      return {
        key: `new-${row.item.merchant}`,
        kind: row.kind,
        title: `New repeating payment: ${row.item.merchant}`,
        detail: `About ${fmtCurrency(row.item.averageAmount)} each time`,
      };
    case 'amount-changed':
      return {
        key: `changed-${row.merchant}`,
        kind: row.kind,
        title: `Amount changed: ${row.merchant}`,
        detail: `Now ${fmtCurrency(row.latestAmount)}`,
      };
    case 'running-high':
      // The same words as Insights' own row (LookAtSection), so the two never disagree.
      return {
        key: `high-${row.category}`,
        kind: row.kind,
        title: `${row.category} is ${fmtCurrency(row.current - row.priorAverage)} above your usual month`,
        detail: `${fmtCurrency(row.current)} vs ${fmtCurrency(row.priorAverage)}`,
      };
    case 'people':
      return null;
  }
}

/** Up to three lines, in the order given (the server's order). */
export function doneLines(rows: LookAtRow[]): DoneLine[] {
  return rows.map(lineFor).filter((l): l is DoneLine => l !== null).slice(0, MAX_LINES);
}

/** "2 skipped, 1 duplicate": each count only when it is not zero. The two are never added: whether
 *  duplicates are already among the skipped is not established. */
export function doneCountsLine(skipped: number, duplicates: number): string | null {
  const parts = [
    skipped > 0 ? `${skipped} skipped` : null,
    duplicates > 0 ? `${duplicates} duplicate${duplicates === 1 ? '' : 's'}` : null,
  ].filter((p): p is string => p !== null);
  return parts.length > 0 ? parts.join(', ') : null;
}
