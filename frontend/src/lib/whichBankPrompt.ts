import type { Account } from '../types';
import { safeStorage } from './safeStorage';
import { OTHER_BANK_ID } from './importBank';

/**
 * "Which bank is this?" -- asked once on an imported account that has no bank (spec 2026-10-22 §7):
 * accounts created before the import review asked, and accounts where the user chose "Other bank".
 *
 * Not asked for an Account Aggregator account (Bank Sync sets its bank, and it is not editable), or
 * for an account with no statements imported into it, which is how a hand-made account looks: the
 * account carries no record of how it was created, so having statements is the sign it came from
 * an import.
 *
 * Dismissing it, or answering it (choosing "Other bank" again counts), is remembered per account in
 * this browser's storage. Device-local, like the open-in-app banner's dismissal: another browser
 * or the mobile app may ask once more.
 */
const KEY_PREFIX = 'fynora.whichBankAnswered.';

export function whichBankKey(accountId: string): string {
  return KEY_PREFIX + accountId;
}

export function shouldAskWhichBank(account: Account): boolean {
  if ((account.bank?.id ?? OTHER_BANK_ID) !== OTHER_BANK_ID) return false;
  if (account.primarySource === 'ACCOUNT_AGGREGATOR') return false;
  const imported = account.statementsCount > 0 || account.lastImportedAt != null;
  if (!imported) return false;
  return safeStorage.getItem(whichBankKey(account.id)) !== '1';
}

export function rememberWhichBankAnswered(accountId: string): void {
  safeStorage.setItem(whichBankKey(accountId), '1');
}
