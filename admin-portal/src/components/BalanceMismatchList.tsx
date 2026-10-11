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
export const BALANCE_MISMATCH_KEYS: readonly string[] = ['discrepancyShapes', 'gapsCancel'];

const SHAPES_KEY = 'discrepancyShapes';
const GAPS_CANCEL_KEY = 'gapsCancel';
const LIST_LABEL = 'Rows that disagreed with the running balance';

/** Read defensively: a finding written by a later version may hold a shape this one does not expect,
 *  and that must cost one line rather than the page. */
export function balanceMismatchShapes(details: Record<string, unknown>): BalanceDiscrepancyShape[] {
  const raw = details[SHAPES_KEY];
  if (!Array.isArray(raw)) return [];
  return raw.filter((shape): shape is BalanceDiscrepancyShape =>
    shape !== null && typeof shape === 'object'
    && Number.isInteger((shape as { rowIndex?: unknown }).rowIndex));
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
  return (
    <div className="mt-2 text-xs text-muted">
      <ul className="list-disc pl-4 space-y-0.5 text-ink" aria-label={LIST_LABEL}>
        {shapes.map((shape) => <li key={shape.rowIndex}>{describeShape(shape)}</li>)}
      </ul>
      <p className="mt-1.5">
        {mismatches > shapes.length && `Showing the first ${shapes.length} of ${mismatches} rows that disagreed. `}
        {mismatches > 1 && details[GAPS_CANCEL_KEY] === true && 'The gaps cancel each other out. '}
        {mismatches > 1 && details[GAPS_CANCEL_KEY] === false && 'The gaps do not cancel each other out. '}
        {rowNumbersNote}
      </p>
    </div>
  );
}
