import type { VerificationFinding, VerificationReport } from '../types';

/**
 * Which rows of the review list a verification report points at.
 *
 * Two checks are about single transactions: BALANCE_CHAIN (a row's balance does not follow from
 * the row before it) and DESCRIPTION_CORRUPTION (a description that may have picked up a page
 * footer). The review list marks those rows, so nobody has to count down to one.
 *
 * <b>Only `displayIndex` is read, never `rowIndex`.</b> `rowIndex` has counted three different
 * lists: the document's own sequence, the balance check's walk along it, and the list on screen.
 * For a PDF those differ, because the rows are checked in the order the document printed them and
 * shown sorted by date. `displayIndex` is sent only by a backend that numbers the list on screen,
 * so its presence is what says the number can be trusted. A report without it (one staged earlier
 * and opened again, or an older backend) marks nothing and prints no row number.
 *
 * <b>All of a check's rows or none.</b> If any one position is missing or outside the list, that
 * check marks nothing: the panel says "marked in the list", and that has to be true of every row
 * it counted.
 *
 * <b>Kept identical in frontend/src/components and mobile/src/components</b>, like
 * verificationCopy.ts: the two apps share no package.
 */
export interface FlaggedRows {
  /** Positions in the list shown whose balance did not match. Null when there are none, or when
   *  the report does not say where they are. */
  balance: readonly number[] | null;
  /** Positions in the list shown whose description looks too long. Null on the same terms. */
  description: readonly number[] | null;
  /**
   * Positions whose balance did not match only because same-day rows are listed in a different
   * order from the one the bank applied them (the server's `sameDayOrderOnly`, spec 2026-11-09).
   * Kept apart from `balance`, which is then null, so nothing that counts or asks about rows to
   * check treats them as a problem. Null on the same terms as the others. Optional so a caller
   * building one by hand need not name it.
   */
  sameDayOrder?: readonly number[] | null;
}

export const NO_FLAGGED_ROWS: FlaggedRows = { balance: null, description: null };

/** Whether this finding is a BALANCE_CHAIN warning the server found wholly explained by same-day
 *  order. Read from the server's own flag, never worked out here, so both apps say the same. */
export function isSameDayOrderOnly(finding: VerificationFinding | undefined): boolean {
  return finding?.rule === 'BALANCE_CHAIN'
    && finding.outcome === 'WARNING'
    && (finding.details as Record<string, unknown> | null | undefined)?.sameDayOrderOnly === true;
}

const disagrees = (finding: VerificationFinding) => finding.outcome === 'WARNING' || finding.outcome === 'FAILED';

/** The positions under `key`, top to bottom, or null unless every entry has one inside the list. */
function positionsOf(finding: VerificationFinding | undefined, key: string, rowCount: number): number[] | null {
  if (!finding || !disagrees(finding)) return null;
  const entries = (finding.details as Record<string, unknown> | null | undefined)?.[key];
  if (!Array.isArray(entries) || entries.length === 0) return null;

  const positions: number[] = [];
  for (const entry of entries) {
    const position = (entry as { displayIndex?: unknown } | null)?.displayIndex;
    if (typeof position !== 'number' || !Number.isInteger(position) || position < 0 || position >= rowCount) {
      return null;
    }
    positions.push(position);
  }
  return [...new Set(positions)].sort((a, b) => a - b);
}

/**
 * @param rowCount how many rows the list shows. A position at or past it points at no row.
 */
export function flaggedRows(verification: VerificationReport | null | undefined, rowCount: number): FlaggedRows {
  const findings = verification?.findings ?? [];
  const chain = findings.find((f) => f.rule === 'BALANCE_CHAIN');
  const chainRows = positionsOf(chain, 'discrepancies', rowCount);
  const sameDay = isSameDayOrderOnly(chain);
  const balance = sameDay ? null : chainRows;
  const sameDayOrder = sameDay ? chainRows : null;
  const description = positionsOf(findings.find((f) => f.rule === 'DESCRIPTION_CORRUPTION'), 'outliers', rowCount);
  if (balance === null && description === null && sameDayOrder === null) return NO_FLAGGED_ROWS;
  // Named only when it applies, so a report with no same-day order reads exactly as before.
  return sameDayOrder === null ? { balance, description } : { balance, description, sameDayOrder };
}

/** The label a marked row carries in the list. The panel quotes these, so they are decided once. */
export const ROW_FLAG_LABEL = {
  balance: "Balance didn't match",
  description: 'Check description',
  sameDayOrder: 'Same-day order',
} as const;
