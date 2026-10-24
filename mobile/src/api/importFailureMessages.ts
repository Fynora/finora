import {
  PDF_PASSWORD_REQUIRED,
  NO_HEADER_DETECTED,
  NO_TRANSACTIONS_FOUND,
  NO_ACTIVITY_IN_PERIOD,
  SCANNED_OCR_REQUIRED,
  CORRUPT_PDF,
  MALFORMED_CSV,
  PAYMENT_APP_HISTORY,
  MULTI_ACCOUNT_FILE,
  PDF_TOO_LARGE,
  TRUST_REVIEW_REJECTED,
  IMPORT_SYSTEM_BUSY,
  IMPORT_SESSION_HELD_FOR_REVIEW,
  PDF_PASSWORD_INVALID,
  EMPTY_FILE,
  WRONG_FILE_TYPE,
  UPLOAD_MALWARE_DETECTED,
  RATE_LIMITED,
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
  // The wrong kind of document, not something Finora failed to read -- so it names what the file
  // is and what to upload instead, rather than "couldn't find a transaction table".
  [PAYMENT_APP_HISTORY]:
    "This is a payment app history, not a bank statement. Each payment in it was made from one " +
    "of your bank accounts and is already in that bank's own statement, so import those bank " +
    'statements instead.',
  // Also the wrong kind of document: rows from several accounts, while an import fills one.
  [MULTI_ACCOUNT_FILE]:
    "This file lists transactions from more than one account, so it can't be imported into a " +
    'single account. Please upload each bank or card statement on its own.',
  [PDF_TOO_LARGE]:
    'This PDF has too many pages to process. Split it into smaller files (e.g. by date range) ' +
    'and import each one separately.',
  [CORRUPT_PDF]:
    'This file appears to be damaged or incomplete. Downloading it again from your bank usually ' +
    'fixes this.',
  [TRUST_REVIEW_REJECTED]:
    'We checked this statement and could not read it accurately enough to import it. Nothing ' +
    'was added to your accounts.',
  // Gate 2b PR 1: the gaps docs/research/gate2/refusal-audit.md found. Each says what happened and
  // one thing to do. EMPTY_FILE, WRONG_FILE_TYPE and IMPORT_SYSTEM_BUSY match the server's own
  // message, which is what older clients show.
  [EMPTY_FILE]:
    'This file is empty. Download the statement again from your bank and choose the new copy.',
  [WRONG_FILE_TYPE]:
    "This file's contents don't match its name, so Fynora can't read it. Download the statement " +
    'again from your bank and choose the new copy.',
  [UPLOAD_MALWARE_DETECTED]:
    "Our virus check flagged this file, so Fynora didn't open it. Download the statement again " +
    "from your bank's website and choose the new copy.",
  [IMPORT_SYSTEM_BUSY]:
    'Fynora is reading a lot of statements right now. Try again in a moment.',
  [RATE_LIMITED]:
    "You've uploaded several statements in a short time. Wait a few minutes, then try again.",
  // At upload the wrong password is answered inside the password field; this is what the
  // failed-imports lists show for it, which used to fall back to "couldn't complete this import".
  [PDF_PASSWORD_INVALID]:
    "That password didn't open this statement. Choose it again and check the password your bank " +
    'set for it.',
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
  [PAYMENT_APP_HISTORY]: 'This is a payment app history',
  [MULTI_ACCOUNT_FILE]: 'This file mixes several accounts',
  [PDF_TOO_LARGE]: 'This statement is too long',
  [NO_ACTIVITY_IN_PERIOD]: 'Nothing to import',
  [TRUST_REVIEW_REJECTED]: "We couldn't read this accurately",  [EMPTY_FILE]: 'This file is empty',
  [WRONG_FILE_TYPE]: "This file doesn't match its name",
  [UPLOAD_MALWARE_DETECTED]: "We couldn't safely open this file",
  [IMPORT_SYSTEM_BUSY]: 'Fynora is busy right now',
  [RATE_LIMITED]: 'Too many uploads at once',
  [PDF_PASSWORD_INVALID]: "That password didn't work",
};

/** Spec §8.1, verbatim: what the PDF password is, and what it is not. */
export const PDF_PASSWORD_HINT =
  'This is the password your bank set for the statement file, usually a mix of your name, PAN, date ' +
  "of birth or account number. It's not your UPI PIN, card PIN or netbanking password. Check the email " +
  'the statement came in.';

/** A chosen file that is neither .csv nor .pdf, refused before anything is sent. */
export const UNSUPPORTED_FILE_MESSAGE =
  "This isn't a .csv or .pdf file. Choose a .csv or .pdf bank or credit card statement.";

/** A server failure that came back with no words of its own (a proxy's 502, say): not the file's fault. */
export const SERVER_FAILURE_MESSAGE = 'Something went wrong on our end. Try again shortly.';

export function importFailureTitle(code: string | null | undefined): string | undefined {
  return code ? IMPORT_FAILURE_TITLES[code] : undefined;
}

/**
 * A failure that looks like a statement Fynora could not read rather than the wrong file: no
 * transactions read (or no table found), yet the backend recovered at least two lines carrying
 * both a date and an amount (`details.looksLikeAStatement`, ExtractionCheck). That is a parser gap
 * on our side -- the queue holds such a file for the team (ImportJobWorker.holdsForTriage) -- so
 * the user is not told to check their file. Only the synchronous path, which keeps nothing, needs
 * this: it is where a protected PDF goes when its password was not kept.
 */
export function isUnreadStatementLayout(code: string | null | undefined, details: unknown): boolean {
  if (code !== NO_TRANSACTIONS_FOUND && code !== NO_HEADER_DETECTED) return false;
  return (details as { looksLikeAStatement?: unknown } | null)?.looksLikeAStatement === true;
}

/**
 * Whether a failed upload is the statement being refused or left unread -- the moment "What will
 * you do now?" asks about (Gate 2a spec §4.1). Every server error carries an `errorCode`, a crash
 * (INTERNAL_ERROR), a rate limit or an expired sign-in included, so having one is not enough: only
 * an import code is, and not busy or held, where the file was never judged or is with the team.
 */
export function isStatementRefusal(code: string | null | undefined): boolean {
  return !!code && code.startsWith('IMPORT_') && code !== IMPORT_SYSTEM_BUSY && code !== IMPORT_SESSION_HELD_FOR_REVIEW;
}

export const UNREAD_LAYOUT_TITLE = "We'd like to take a closer look";
/** Shown with the offer to keep the password and send the statement through the queue. */
export const UNREAD_LAYOUT_REVIEW_MESSAGE =
  "This statement uses a layout Fynora hasn't learned yet. Keep the password and send it to our " +
  "team. We'll review it and let you know when it's ready to import.";
/** The consent the offer's button gives, said where the button is. */
export const UNREAD_LAYOUT_REVIEW_CONSENT =
  'The password is stored encrypted, only for this statement. Remove it any time in Settings → Data.';
/** Where the offer cannot be made (this deployment keeps no passwords, or has no queue). */
export const UNREAD_LAYOUT_MESSAGE =
  "This statement uses a layout Fynora hasn't learned yet, so its transactions weren't imported.";
