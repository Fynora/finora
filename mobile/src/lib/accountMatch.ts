import type { Account, DetectedAccountInfo } from '../types';
import { OTHER_BANK_ID } from './bankChoice';

/**
 * Which existing account, if any, a freshly-staged statement belongs to.
 *
 * <b>Why this exists.</b> Import.tsx defaulted the "which account is this statement for?" choice
 * like this:
 *
 *     if (existingAccounts.length > 0) {
 *       setAccountChoice('existing');
 *       setSelectedAccountId(existingAccounts[0].id);
 *     }
 *
 * with a comment above it describing something else entirely -- "default to the account the file's
 * own signals most plausibly matches, falling back to the first existing account." No signal was
 * ever consulted. Whoever had imported a Kotak statement first was then shown "use an existing
 * account: Kotak" for every subsequent statement, from any bank, and had to notice and override it.
 * The multi-section path was worse: a composite statement defaulted every one of its sections to
 * the same first account.
 *
 * <b>The two mistakes are not equally bad, and this leans accordingly.</b> Wrongly defaulting to an
 * EXISTING account merges one institution's transactions into another's -- silently wrong balances,
 * a wrong net worth, and reconciliation running across two unrelated ledgers. Wrongly defaulting to
 * NEW creates a duplicate account, which is visible in the account list and can be deleted. So
 * anything short of a real match returns null, and the caller shows "create a new account".
 *
 * Deliberately client-side. Every field compared here is already on the staging response and the
 * account list, so this needs no new endpoint and no new server round trip -- it is a defaulting
 * decision about data the page is already holding. The stronger server-side identity signal that
 * exists for deposits (`productIdentityHash`, a one-way hash of institution + product number
 * computed where the full number is still available) is deliberately not reimplemented here: it
 * solves confirm-time deduplication, which is a different question from which radio button to
 * preselect.
 *
 * <b>Ported verbatim from frontend/src/lib/accountMatch.ts</b> (A3, two-pass mobile audit
 * 2026-09-01 -- see docs/project-management/plans/mobile-correctness-trust-roadmap.md, Track A).
 * ImportScreen.tsx carried the identical `existingAccounts[0]` defect quoted above, fixed on web
 * but never ported. Kept as a verbatim copy rather than a mobile-specific reimplementation
 * precisely because two divergent answers to "which account is this statement for?" is itself a
 * defect generator: the first draft of this fix reimplemented it from scratch and lost the bank
 * filter, the four-digit minimum, the suffix match and the ambiguity guard, every one of which is
 * load-bearing. `Account` and `DetectedAccountInfo` carry the same fields in both clients' type
 * definitions, so this drops in unchanged. Any future fix here belongs in both copies.
 */
export function matchExistingAccount(
  detected: DetectedAccountInfo,
  accounts: Account[],
): Account | null {
  if (accounts.length === 0) return null;

  // Bug fix (ported from frontend/src/lib/accountMatch.ts, see this file's own comment on why the
  // two copies must stay in lockstep): an AA-linked account must never be a candidate here,
  // confident match or not. ImportScreen's account picker disables the option for one, but that
  // only helps if the user actually looks at the list -- a confident match (same bank + number, or
  // the only account of that type) bypasses the list entirely by preselecting "use an existing
  // account" for them, so the user would only discover the account is blocked after confirming and
  // hitting AccountAggregatorGuard's 409. Filtered out before any matching logic runs, not after,
  // so it can never win any of the rules below.
  const eligibleAccounts = accounts.filter((a) => a.primarySource !== 'ACCOUNT_AGGREGATOR');
  if (eligibleAccounts.length === 0) return null;

  // "Other bank" is not a bank. Two accounts that are both OTHER share nothing but the absence of a
  // bank, so treating them as the same bank preselected every unknown-bank statement into the
  // person's one "Other bank" account, whatever bank it really came from. The backend refuses the
  // same thing (ProductIdentity: OTHER matching OTHER "would make every unidentified product the
  // same product"). Spec 2026-10-22-bank-from-profile-and-required-bank-design.md, section 5.
  const detectedBank = detected.bank?.id;
  if (!detectedBank || detectedBank === OTHER_BANK_ID) return null;
  const sameBank = eligibleAccounts.filter((a) => a.bank?.id === detectedBank);
  if (sameBank.length === 0) return null;

  const detectedDigits = trailingDigits(detected.accountNumberMasked);

  // 1. Same bank, same account number. The only genuinely conclusive signal available here.
  if (detectedDigits) {
    const byNumber = sameBank.filter((a) => digitsMatch(detectedDigits, trailingDigits(a.accountNumberMasked)));
    if (byNumber.length === 1) return byNumber[0];
    // More than one existing account reporting the same number is a data problem, not a match --
    // picking either would be a guess about which. Fall through to returning null.
    if (byNumber.length > 1) return null;
  }

  // 2. No number to compare, or none matched. A single account of the same type at the same bank
  //    is the only remaining case confident enough to preselect.
  //
  //    The number check above is a VETO as well as a match: if this statement carries an account
  //    number and no existing account shares it, the candidates below are all known to be a
  //    different account, however few of them there are.
  if (detectedDigits) {
    const anyExistingHasANumber = sameBank.some((a) => trailingDigits(a.accountNumberMasked) !== null);
    if (anyExistingHasANumber) return null;
  }

  const sameType = sameBank.filter((a) => a.accountType === detected.suggestedAccountType);
  return sameType.length === 1 ? sameType[0] : null;
}

/**
 * Accounts the person already holds at the bank they just chose for a new account, of the same
 * type, that this statement could be imported into instead. Feeds a one-line hint on the import
 * review -- never a preselection (spec 2026-10-22-bank-from-profile-and-required-bank-design.md,
 * section 5): the bank here is the person's own pick, not something the statement said.
 *
 * Left out: "Other bank" (not a bank, so nothing is "the same bank"); Account Aggregator accounts
 * (a statement can't be imported into one); and an account whose number is known to differ from the
 * statement's, by the same veto matchExistingAccount applies -- offering that one would point at an
 * account the statement already says it is not.
 */
export function sameBankAccounts(
  bankId: string | null,
  accountType: Account['accountType'],
  statementNumberMasked: string | null | undefined,
  accounts: Account[],
): Account[] {
  if (!bankId || bankId === OTHER_BANK_ID) return [];
  const statementDigits = trailingDigits(statementNumberMasked);
  return accounts.filter((a) => {
    if (a.bank?.id !== bankId || a.accountType !== accountType) return false;
    if (a.primarySource === 'ACCOUNT_AGGREGATOR') return false;
    const accountDigits = trailingDigits(a.accountNumberMasked);
    if (statementDigits && accountDigits && !digitsMatch(statementDigits, accountDigits)) return false;
    return true;
  });
}

/**
 * The digit run at the end of a masked account number, or null when there isn't a usable one.
 *
 * Masking is not consistent between statements -- the same account appears as "XXXXXX4587" on one
 * and "XX4587" on another -- so only the digits can be compared, never the whole string. Fewer
 * than four is not enough to identify anything and is treated as absent rather than matched
 * loosely.
 */
function trailingDigits(masked: string | null | undefined): string | null {
  if (!masked) return null;
  const match = masked.match(/(\d+)\s*$/);
  if (!match) return null;
  return match[1].length >= 4 ? match[1] : null;
}

/** True when two trailing digit runs identify the same account. One being a suffix of the other
 *  covers the case where one statement masks more aggressively than the other. */
function digitsMatch(a: string, b: string | null): boolean {
  if (!b) return false;
  return a === b || a.endsWith(b) || b.endsWith(a);
}
