import {
  NO_HEADER_DETECTED,
  NO_TRANSACTIONS_FOUND,
  NO_ACTIVITY_IN_PERIOD,
  SCANNED_OCR_REQUIRED,
  CORRUPT_PDF,
  TRUST_REVIEW_REJECTED,
} from './errorCodes';

/**
 * Phase 4 (Medium-Tier Parity). Verbatim port of frontend/src/api/importFailureMessages.ts's
 * curated copy -- see that file's own doc comment for why this is the single source of truth for
 * user-facing import-failure text, and why it deliberately does not also carry
 * `userActionRequired`-ness (that comes off the wire instead).
 */
export const IMPORT_FAILURE_MESSAGES: Record<string, string> = {
  [NO_HEADER_DETECTED]:
    "We couldn't find a transaction table in this file. Please check that you've uploaded the " +
    'transaction statement PDF from your bank, not a summary, terms document, or other export.',
  [NO_TRANSACTIONS_FOUND]:
    'We found a table in this statement but could not read any transactions from it. Please ' +
    'double-check this is the transaction statement PDF from your bank -- some other exports use ' +
    'a similar layout.',
  [NO_ACTIVITY_IN_PERIOD]:
    "This statement's own summary shows no transactions for the period it covers, so there's " +
    'nothing to import from it.',
  [SCANNED_OCR_REQUIRED]:
    'This PDF appears to be a scanned image rather than text. Statements exported directly from ' +
    "your bank's website usually work best.",
  [CORRUPT_PDF]:
    'This file appears to be damaged or incomplete. Downloading it again from your bank usually ' +
    'fixes this.',
  [TRUST_REVIEW_REJECTED]:
    'We checked this statement and could not read it accurately enough to import it. Nothing ' +
    'was added to your accounts.',
};

/** See the web file's own doc comment on why this deliberately takes no fallback -- each call
 *  site decides its own fallback from whatever `undefined` means to it. */
export function importFailureMessage(code: string | null | undefined): string | undefined {
  return code ? IMPORT_FAILURE_MESSAGES[code] : undefined;
}
