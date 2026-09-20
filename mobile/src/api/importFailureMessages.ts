import {
  PDF_PASSWORD_REQUIRED,
  NO_HEADER_DETECTED,
  NO_TRANSACTIONS_FOUND,
  NO_ACTIVITY_IN_PERIOD,
  SCANNED_OCR_REQUIRED,
  CORRUPT_PDF,
  MALFORMED_CSV,
  PDF_TOO_LARGE,
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
  // A queued job can only reach this if it was accepted before the upload started refusing
  // protected PDFs (the worker has no password to try). Without an entry the failed card
  // showed no reason at all -- prod, 2026-09-19. Says what to DO, because "choose a different
  // file" alone would send the user away from a file that works once its password is given.
  [PDF_PASSWORD_REQUIRED]:
    'This statement is password protected. Choose it again and enter the password your bank ' +
    'uses for it.',
  // Reuses the backend's own already-approved wording (ErrorCode.IMPORT_PDF_TOO_LARGE). A queued
  // job that hit the page ceiling had no entry here and failed with no reason shown at all.
  [MALFORMED_CSV]:
    'This file could not be read as a CSV. It may be damaged or cut short -- downloading it again from ' +
    'your bank usually fixes this.',
  [PDF_TOO_LARGE]:
    'This PDF has too many pages to process. Split it into smaller files (e.g. by date range) ' +
    'and import each one separately.',
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

/**
 * The plain headline a failed import leads with, one per code that has a message above. A user
 * reads the headline first, and for a failure they can act on it must say what is wrong in ordinary
 * words -- not a generic "Couldn't finish" with the real explanation in small print beneath. Kept
 * as a separate table rather than folded into the messages so each stays one sentence-shaped value;
 * the test pins that every code with a message also has a headline.
 */
export const IMPORT_FAILURE_TITLES: Record<string, string> = {
  [NO_HEADER_DETECTED]: "This doesn't look like a statement",
  [NO_TRANSACTIONS_FOUND]: "We couldn't read any transactions",
  [PDF_PASSWORD_REQUIRED]: 'This statement is password protected',
  [SCANNED_OCR_REQUIRED]: 'This looks like a scanned copy',
  [CORRUPT_PDF]: 'This file looks damaged',
  [MALFORMED_CSV]: 'This file looks damaged',
  [PDF_TOO_LARGE]: 'This statement is too long',
  [NO_ACTIVITY_IN_PERIOD]: 'Nothing to import',
  [TRUST_REVIEW_REJECTED]: "We couldn't read this accurately",
};

export function importFailureTitle(code: string | null | undefined): string | undefined {
  return code ? IMPORT_FAILURE_TITLES[code] : undefined;
}
