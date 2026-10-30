import type { Account, BankInfo, DetectedAccountInfo } from '../types';

/**
 * The bank a new or existing account is filed under, and where that answer came from.
 *
 * Spec: docs/superpowers/specs/2026-10-22-bank-from-profile-and-required-bank-design.md, sections
 * 3, 4, 5 and 7. Sid's rule behind every choice here: a wrong bank is worse than no bank. Nothing in
 * this file ever puts a bank on an account that neither the statement, the statement's layout
 * profile, nor the user supplied.
 */

/** The backend's id for "no recognised bank" (BankRegistry.UNKNOWN_ID). Never returned by
 *  GET /banks, so the picker offers it itself, always last, as "Other bank". */
export const OTHER_BANK_ID = 'OTHER';
export const OTHER_BANK_LABEL = 'Other bank';

/** What the backend names an account whose bank it does not know (StatementValidator and
 *  PdfPreviewGenerator's shared fallback). Used when the user picks "Other bank" before typing a
 *  name, so a name filled from an earlier bank choice does not outlive that choice. */
export const UNKNOWN_BANK_ACCOUNT_NAME = 'Bank Statement Import';

export type BankSource = 'FILE' | 'PROFILE' | 'NONE';

/** One row of the picker. `id` is what the backend stores; `name` is what the person reads. */
export interface BankOption {
  id: string;
  name: string;
}

export const OTHER_BANK_OPTION: BankOption = { id: OTHER_BANK_ID, name: OTHER_BANK_LABEL };

/** The name a person reads for a bank: its official name, else its short name, else "Other bank". */
export function bankDisplayName(bank: Pick<BankInfo, 'id' | 'officialName' | 'shortName'> | null | undefined): string {
  if (!bank || bank.id === OTHER_BANK_ID) return OTHER_BANK_LABEL;
  return bank.officialName || bank.shortName || bank.id;
}

export function toBankOption(bank: Pick<BankInfo, 'id' | 'officialName' | 'shortName'>): BankOption {
  return { id: bank.id, name: bankDisplayName(bank) };
}

/**
 * Where the staged bank came from.
 *
 * `bankSource` is null on sessions staged before the backend started sending it (#2316). For
 * those, a real bank can only have come from the file itself (the profile fill did not exist yet),
 * so it reads as FILE; OTHER reads as NONE. A FILE or PROFILE that still carries OTHER is treated as
 * NONE as well: the screen must never pre-fill "Other bank" as if something had confirmed it.
 */
export function bankSourceOf(detected: DetectedAccountInfo | null | undefined): BankSource {
  if (!detected) return 'NONE';
  const known = !!detected.bank?.id && detected.bank.id !== OTHER_BANK_ID;
  if (!known) return 'NONE';
  return detected.bankSource === 'PROFILE' ? 'PROFILE' : 'FILE';
}

/** The pre-filled bank for a new account: the staged bank when the file or its profile named one,
 *  otherwise nothing, so the person has to choose. */
export function initialBankChoice(detected: DetectedAccountInfo | null | undefined): BankOption | null {
  if (!detected || bankSourceOf(detected) === 'NONE') return null;
  return toBankOption(detected.bank);
}

/** The short line under the import review's Bank field. */
export function importBankHint(source: BankSource, chosen: BankOption | null, prefilled: BankOption | null): string | null {
  if (!chosen) {
    return "This statement doesn't say which bank it's from. Choose the bank, or Other bank, to import it.";
  }
  // Only while the pre-filled bank is still the one shown: once the person picks another, the
  // statement is no longer the source of what is on screen.
  if (prefilled && chosen.id === prefilled.id) {
    if (source === 'FILE') return 'Detected from this statement.';
    if (source === 'PROFILE') return "Recognised from this statement's format.";
  }
  return null;
}

/**
 * The account name after a bank is chosen. The name follows the bank until the person types one of
 * their own, and then it is theirs. "Other bank" goes back to the backend's generic name rather than
 * keeping a bank name filled from an earlier pick.
 */
export function nameAfterBankChoice(currentName: string, nameEdited: boolean, bank: BankOption): string {
  // A name typed and then cleared is not a name of their own any more.
  if (nameEdited && currentName.trim() !== '') return currentName;
  return bank.id === OTHER_BANK_ID ? UNKNOWN_BANK_ACCOUNT_NAME : bank.name;
}

/** The banks the picker lists for a search, "Other bank" always last and always present, so a
 *  person whose bank is not in the list can still finish. `limit` caps the real banks shown. */
export function filterBankOptions(banks: BankOption[], query: string, limit: number): BankOption[] {
  return [...matchingBanks(banks, query).slice(0, limit), OTHER_BANK_OPTION];
}

/** Every real bank a search matches (by name or id, ignoring case), before any limit. */
export function matchingBanks(banks: BankOption[], query: string): BankOption[] {
  const q = query.trim().toLowerCase();
  const real = banks.filter((b) => b.id !== OTHER_BANK_ID);
  return q ? real.filter((b) => b.name.toLowerCase().includes(q) || b.id.toLowerCase().includes(q)) : real;
}

/** The `GET /banks` list as picker rows, sorted by name. Unnamed entries and any OTHER row are
 *  dropped; the picker adds "Other bank" itself. */
export function bankOptionsFrom(banks: BankInfo[]): BankOption[] {
  return banks
    .filter((b) => b.id && b.id !== OTHER_BANK_ID)
    .map(toBankOption)
    .sort((a, b) => a.name.localeCompare(b.name));
}

/**
 * Whether an account should show the one-time "Which bank is this?" prompt (spec section 7): its
 * bank is unknown, it came from a statement import, and it is not synced through Account
 * Aggregator. Accounts carry no "created by import" field, so "a statement has been imported into
 * it" stands in for it: an account added by hand that never had a statement keeps no prompt.
 */
export function shouldAskWhichBank(account: Account): boolean {
  if (account.primarySource === 'ACCOUNT_AGGREGATOR') return false;
  if (account.bank?.id && account.bank.id !== OTHER_BANK_ID) return false;
  return account.lastImportedAt != null || (account.statementsCount ?? 0) > 0;
}

/** SecureStore key for "the prompt on this account was answered or dismissed". SecureStore keys
 *  allow letters, digits, ".", "-" and "_", which a UUID account id stays within. */
export function whichBankPromptKey(accountId: string): string {
  return `which_bank_prompt_done.${accountId}`;
}
