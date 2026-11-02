import type { FlaggedRows } from '../components/verificationFlags';
import type { StagedRow } from '../types';
import type { ImportGateState } from './importGate';
import { isUnconfirmedGuess, isUnderReview, type DuplicateDecision } from './importReview';

/**
 * The Import check step's questions (spec 2026-11-02, Step 2): which rows need the person, and what
 * the pinned Import bar says. Nothing here decides what may be imported -- that stays in
 * canConfirmImport (importGate.ts); importBarMessage only names the reason it already refuses for.
 */

function flaggedSet(flagged: FlaggedRows): Set<number> {
  return new Set([...(flagged.balance ?? []), ...(flagged.description ?? [])]);
}

/** Rows that need an answer, in statement order: unresolved possible duplicates, and rows the
 *  statement checks marked that nobody has yet said look right. Each row once. */
export function questionRows(
  rows: StagedRow[],
  decisions: DuplicateDecision[],
  flagged: FlaggedRows,
  dismissed: ReadonlySet<number>,
): number[] {
  const marked = flaggedSet(flagged);
  const out: number[] = [];
  rows.forEach((row, i) => {
    const unresolved = isUnderReview(row) && decisions[i] === 'unresolved';
    if (unresolved || (marked.has(i) && !dismissed.has(i))) out.push(i);
  });
  return out;
}

/** Marked rows nobody has said look right yet. */
export function undismissedFlagCount(flagged: FlaggedRows, dismissed: ReadonlySet<number>): number {
  return [...flaggedSet(flagged)].filter((i) => !dismissed.has(i)).length;
}

/** Included rows whose category is only a guess ("Needs a look"). */
export function categoryGuessCount(rows: StagedRow[], included: boolean[]): number {
  return rows.filter((row, i) => included[i] && isUnconfirmedGuess(row.categorySource)).length;
}

/** "File into" folds to one line when nothing in its form stands between the person and Import. */
export function fileIntoCollapsed(s: {
  isReimport: boolean;
  accountChoice: 'existing' | 'new';
  selectedAccountId: string;
  amountOwedInvalid: boolean;
  newAccountBankMissing: boolean;
}): boolean {
  if (s.isReimport) return true;
  return s.accountChoice === 'existing' && !!s.selectedAccountId && !s.amountOwedInvalid && !s.newAccountBankMissing;
}

function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

/**
 * The pinned bar's line: the first reason Import is refused, in the order canConfirmImport's
 * conditions are listed in the plan, or -- when nothing blocks -- how many marked rows are left to
 * look at. Null when there is nothing to say.
 */
export function importBarMessage(
  gate: ImportGateState,
  flagsToCheck: number,
): { kind: 'blocked' | 'check'; text: string } | null {
  const blocked = (text: string) => ({ kind: 'blocked' as const, text });
  if (gate.outstanding > 0) return blocked(`Answer ${plural(gate.outstanding, 'question', 'questions')} to import`);
  if (!gate.isReimport && gate.accountChoice === 'existing' && !gate.selectedAccountId) {
    return blocked('Choose an account to import into');
  }
  if (!gate.isReimport && gate.accountChoice === 'new' && gate.newAccountBankMissing) {
    return blocked("Choose the new account's bank to import");
  }
  if (gate.amountOwedInvalid) return blocked('Fix the amount owed to import');
  if (!gate.isReimport && gate.freePlanLimitBlocks) return blocked("This statement is over the Free plan's one-month limit");
  if (gate.includedCount === 0) return blocked('Choose at least one transaction to import');
  if (flagsToCheck > 0) return { kind: 'check', text: `${plural(flagsToCheck, 'row', 'rows')} to check` };
  return null;
}
