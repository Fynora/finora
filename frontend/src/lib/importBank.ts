import type { BankInfo, DetectedAccountInfo } from '../types';

/**
 * The bank a new account gets on the import review (spec 2026-10-22, "Bank from layout profile, and
 * a required bank at import", section 3).
 *
 * A wrong bank is worse than no bank, so the review never invents one: it pre-fills only what the
 * staging response says came from the file itself or from the layout's profile, and otherwise
 * leaves the field empty and holds the import until the user picks a bank or says "Other bank".
 */

/** The backend's id for "no recognised bank" (BankRegistry.UNKNOWN_ID). `GET /banks` never lists it. */
export const OTHER_BANK_ID = 'OTHER';

/** The account name the backend suggests when no bank is known (StatementValidator.suggestedAccountName). */
export const GENERIC_ACCOUNT_NAME = 'Bank Statement Import';

export type BankSource = 'FILE' | 'PROFILE' | 'NONE';

/**
 * Where the staged bank came from. A session staged before the server sent `bankSource` has it
 * null: read that the way the server itself does (DetectedAccountInfo.sourceOf), FILE when a bank
 * was found and NONE when it was not. An OTHER bank is never treated as known, whatever the
 * field says.
 */
export function bankSourceOf(detected: DetectedAccountInfo): BankSource {
  const known = !!detected.bank?.id && detected.bank.id !== OTHER_BANK_ID;
  if (!known) return 'NONE';
  return detected.bankSource === 'PROFILE' ? 'PROFILE' : 'FILE';
}

/** The picker's starting value: the staged bank when it is known, otherwise empty (nothing chosen). */
export function initialBankChoice(detected: DetectedAccountInfo | null): string {
  if (!detected) return '';
  return bankSourceOf(detected) === 'NONE' ? '' : detected.bank.id;
}

/** The hint under a pre-filled bank, or null when the bank was not pre-filled. */
export function bankHint(source: BankSource): string | null {
  if (source === 'FILE') return 'Detected from this statement';
  if (source === 'PROFILE') return "Recognised from this statement's format";
  return null;
}

/** A bank's display name, as the picker and the account name use it. */
export function bankDisplayName(bank: Pick<BankInfo, 'officialName' | 'shortName'>): string {
  return bank.officialName ?? bank.shortName;
}

/**
 * The account name that follows a bank choice: the bank's own name, or the generic import name for
 * "Other bank". Only applied while the user has not typed a name of their own.
 */
export function accountNameForBank(bankId: string, bank: BankInfo | null): string {
  if (bankId === OTHER_BANK_ID || !bank) return GENERIC_ACCOUNT_NAME;
  return bankDisplayName(bank);
}
