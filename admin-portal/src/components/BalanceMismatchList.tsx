import type { BalanceDiscrepancyShape } from '../types';

/**
 * A balance-chain mismatch, row by row, in words.
 *
 * <h2>Why these get sentences when every other detail is `key=value`</h2>
 * A count says two rows disagreed. Whether that is a balance column that slipped a row or a credit
 * read as a debit decides what happens next, and telling them apart used to mean querying the
 * customer's staged rows by hand before they were deleted. The backend now records the part of that
 * answer which is not statement content — where each row is and how its numbers relate, never the
 * numbers — and this is where an operator reads it.
 *
 * <h2>Facts, not a diagnosis</h2>
 * Each line states what was measured. It does not name a cause, for the reason the trace page
 * carries no verdict: "balance did not move" is consistent with a slipped column and with a row
 * extracted twice, and choosing between them is the operator's call.
 *
 * <h2>One component, two pages</h2>
 * Import Trace and Held Statement Detail both show a finding's details, and each had its own way of
 * printing a value it did not expect. A second copy of these sentences would drift from the first
 * the day a pattern was added, and an operator moving between the pages would read the same
 * mismatch described two ways.
 */

/** The detail keys this component renders. A page showing the rest of a finding's details leaves
 *  these out of its own listing so nothing is said twice. */
export const BALANCE_MISMATCH_KEYS: readonly string[] = ['discrepancyShapes', 'gapsCancel', 'reorderHypothesis'];

const SHAPES_KEY = 'discrepancyShapes';
const GAPS_CANCEL_KEY = 'gapsCancel';
const REORDER_KEY = 'reorderHypothesis';
const LIST_LABEL = 'Rows that disagreed with the running balance';

const NOT_TESTED_TEXT: Record<string, string> = {
  NOT_TESTED_TOO_MANY_ROWS: 'too many rows in a row to try re-ordering them',
  NOT_TESTED_DIFFERENT_DAYS: 'the rows are on different days, so re-ordering was not tried',
  NOT_TESTED_ROW_INCOMPLETE: 'a row has no amount or balance, so re-ordering was not tried',
};

/** Read defensively: a finding written by a later version may hold a shape this one does not expect,
 *  and that must cost one line rather than the page. */
export function balanceMismatchShapes(details: Record<string, unknown>): BalanceDiscrepancyShape[] {
  const raw = details[SHAPES_KEY];
  if (!Array.isArray(raw)) return [];
  return raw.filter((shape): shape is BalanceDiscrepancyShape =>
    shape !== null && typeof shape === 'object'
    && Number.isInteger((shape as { rowIndex?: unknown }).rowIndex));
}

const isRowList = (value: unknown): value is number[] =>
  Array.isArray(value) && value.every((row) => Number.isInteger(row));

/**
 * What the backend found when it tried re-ordering the disagreeing rows, one sentence per run of rows.
 * A fact about the arithmetic, not a cause: "add up if taken in this order" says the printed balances
 * agree with that order, and why the statement lists them otherwise is the operator's to judge.
 * Read defensively -- anything unexpected costs its own line, never the page.
 */
export function reorderHypothesisLines(details: Record<string, unknown>): string[] {
  const hypothesis = details[REORDER_KEY];
  if (hypothesis === null || typeof hypothesis !== 'object') return [];
  const windows = (hypothesis as { windows?: unknown }).windows;
  if (!Array.isArray(windows)) return [];
  const lines: string[] = [];
  for (const window of windows) {
    if (window === null || typeof window !== 'object') continue;
    const { rows, tested, reason, reconciles, orderings, order } = window as Record<string, unknown>;
    if (!isRowList(rows) || rows.length === 0) continue;
    const label = `Rows ${[...rows].sort((a, b) => a - b).map(rowNumber).join(', ')}`;
    if (tested === true && reconciles === true && isRowList(order)) {
      lines.push(`${label} add up if taken in this order: ${order.map(rowNumber).join(', ')}.`);
    } else if (tested === true && reconciles === true && typeof orderings === 'number') {
      lines.push(`${label} add up in ${orderings} different orders, so no single order is named.`);
    } else if (tested === true && reconciles === false) {
      lines.push(`${label}: no re-ordering of these rows makes them add up.`);
    } else if (tested === false && typeof reason === 'string' && NOT_TESTED_TEXT[reason]) {
      lines.push(`${label}: ${NOT_TESTED_TEXT[reason]}.`);
    }
  }
  return lines;
}

/** Rows are counted from one, down the rows as they are listed for the customer — so "row 13" in a
 *  support conversation and "Row 13" here are the same row. */
function rowNumber(index: number): number {
  return index + 1;
}

function describeShape(shape: BalanceDiscrepancyShape): string {
  const facts: string[] = [];
  if (shape.balanceDidNotMove === true) facts.push('balance did not move');
  if (shape.directionLooksFlipped === true) facts.push('gap is twice this row’s own amount');

  const matches = Array.isArray(shape.gapMatchesAmountOfRows)
    ? shape.gapMatchesAmountOfRows.filter((row) => Number.isInteger(row))
    : [];
  if (matches.length > 0) {
    const rows = [...matches].sort((a, b) => a - b).map(rowNumber).join(', ');
    facts.push(`gap equals the amount on ${matches.length === 1 ? 'row' : 'rows'} ${rows}`);
  }
  // Said rather than left blank: a row with nothing after its number reads as a rendering fault.
  if (facts.length === 0) facts.push('gap matches no amount in the rows');

  if (shape.sameDayAsPrevious === true && shape.sameDayAsNext === true) {
    facts.push('same day as the rows before and after');
  } else if (shape.sameDayAsPrevious === true) {
    facts.push('same day as the row before');
  } else if (shape.sameDayAsNext === true) {
    facts.push('same day as the row after');
  }
  return `Row ${rowNumber(shape.rowIndex)}: ${facts.join('; ')}`;
}

/**
 * Renders nothing for a finding with no mismatch to describe, so a page can place it
 * unconditionally.
 *
 * @param rowNumbersNote one sentence saying what the row numbers count, which differs by page: the
 *                       trace has no rows on it, the held-statement page lists them.
 */
export function BalanceMismatchList({
  details, rowNumbersNote,
}: {
  details: Record<string, unknown>;
  rowNumbersNote: string;
}) {
  const shapes = balanceMismatchShapes(details);
  if (shapes.length === 0) return null;
  const mismatches = typeof details.discrepanciesCount === 'number' ? details.discrepanciesCount : shapes.length;
  const reorderLines = reorderHypothesisLines(details);
  return (
    <div className="mt-2 text-xs text-muted">
      <ul className="list-disc pl-4 space-y-0.5 text-ink" aria-label={LIST_LABEL}>
        {shapes.map((shape) => <li key={shape.rowIndex}>{describeShape(shape)}</li>)}
      </ul>
      {reorderLines.length > 0 && (
        <ul className="mt-1.5 list-disc pl-4 space-y-0.5 text-ink" aria-label="Re-ordering test">
          {reorderLines.map((line) => <li key={line}>{line}</li>)}
        </ul>
      )}
      <p className="mt-1.5">
        {mismatches > shapes.length && `Showing the first ${shapes.length} of ${mismatches} rows that disagreed. `}
        {mismatches > 1 && details[GAPS_CANCEL_KEY] === true && 'The gaps cancel each other out. '}
        {mismatches > 1 && details[GAPS_CANCEL_KEY] === false && 'The gaps do not cancel each other out. '}
        {rowNumbersNote}
      </p>
    </div>
  );
}
