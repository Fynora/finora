/**
 * "How much do you owe on this card today?" -- asked at import review when a card statement gives
 * no closing balance and nothing has stated the card's figure. Without an answer the card's balance
 * is summed from zero off the statement's rows, which is almost never what is owed.
 * Design: docs/superpowers/specs/2026-10-26-card-amount-owed-prompt-design.md.
 *
 * The same rules live in frontend/src/lib/amountOwed.ts; keep the two in step.
 */

export interface AmountOwedTarget {
  /** The statement's printed closing balance, if it has one. A closing balance states the figure. */
  closingBalance: number | null | undefined;
  /** The existing account the statement goes into, or null when a new account is being created. */
  existing: { accountType: string; balanceStated?: boolean } | null;
  /** The new account's type, when one is being created. */
  newType: string | null;
  /** The new account's opening balance as typed, when one is being created. */
  newOpeningBalance: string | null;
}

export function shouldAskAmountOwed(target: AmountOwedTarget): boolean {
  if (target.closingBalance != null) return false;
  if (target.existing) {
    // balanceStated comes from the server. When it is missing (an older server) nothing is asked:
    // only a server that knows the field also knows what to do with the answer.
    return target.existing.accountType === 'CREDIT_CARD' && target.existing.balanceStated === false;
  }
  return target.newType === 'CREDIT_CARD' && (target.newOpeningBalance ?? '').trim() === '';
}

export type AmountOwedAnswer = { state: 'blank' } | { state: 'invalid' } | { state: 'valid'; value: number };

/** The most the server stores: 12 digits before the decimal point (accounts.balance is NUMERIC(14,2)). */
const MAX_RUPEES = 999_999_999_999.99;

const PLAIN = /^\d+(\.\d{1,2})?$/;
const INDIAN_GROUPING = /^\d{1,2}(,\d{2})*,\d{3}(\.\d{1,2})?$/;
const INTERNATIONAL_GROUPING = /^\d{1,3}(,\d{3})+(\.\d{1,2})?$/;

/**
 * Reads what was typed. Indian digit grouping ("1,00,396.53") is accepted; a negative figure, more
 * than two decimals or anything that is not a number is invalid -- the server refuses those too.
 */
export function readAmountOwed(raw: string): AmountOwedAnswer {
  const typed = raw.trim();
  if (!typed) return { state: 'blank' };
  // Commas only as digit grouping -- Indian (1,00,396.53) or international (100,396.53) -- never
  // as a decimal mark: a keypad that types "," for the point would otherwise turn 12,50 into 1,250.
  if (!PLAIN.test(typed) && !INDIAN_GROUPING.test(typed) && !INTERNATIONAL_GROUPING.test(typed)) {
    return { state: 'invalid' };
  }
  const value = Number(typed.replace(/,/g, ''));
  if (!Number.isFinite(value) || value > MAX_RUPEES) return { state: 'invalid' };
  return { state: 'valid', value };
}

/** What goes in the confirm request's amountOwedToday: only an answer to a question that was asked. */
export function amountOwedToSend(asked: boolean, raw: string, skipped: boolean): number | undefined {
  if (!asked || skipped) return undefined;
  const answer = readAmountOwed(raw);
  return answer.state === 'valid' ? answer.value : undefined;
}

/** A card whose balance was summed from zero (the server's AmountOwedRule): show no figure for it. */
export function isAmountOwedUnknown(account: { accountType: string; amountOwedKnown?: boolean }): boolean {
  return account.accountType === 'CREDIT_CARD' && account.amountOwedKnown === false;
}

/** Said under a total that leaves such cards out; null when it leaves none out. */
export function unknownCardsNote(count: number | null | undefined): string | null {
  if (!count || count <= 0) return null;
  return `Leaves out ${count} card${count === 1 ? '' : 's'} whose amount owed isn't known.`;
}
