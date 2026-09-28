/**
 * Plain-English wording for the codes Layout Studio shows.
 *
 * Every explanation here is taken from where the code is produced, not guessed from its name:
 * failure codes from backend ErrorCode (and the "ENGINE_CRASH" literal in AdminAnalysisService),
 * unmatched-line reasons from PdfTableLocator.anchorFailureReason. A code with no entry falls back
 * to its raw value rather than to an invented meaning.
 */

export interface Term {
  label: string;
  meaning: string;
}

/**
 * Keyed by the value the analysis row stores, which is the ErrorCode enum NAME
 * (ErrorCode.failureCodeOf / AdminAnalysisService), not the wire code. The wire codes are
 * accepted as aliases too, so a caller holding either form gets the same answer.
 */
const FAILURE_TERMS: Record<string, Term & { wire?: string }> = {
  IMPORT_NO_HEADER_DETECTED: {
    wire: 'IMPORT_001',
    label: 'No transaction table found',
    meaning: 'The engine could not find a table of transactions anywhere in the file.',
  },
  IMPORT_NO_TRANSACTIONS_FOUND: {
    wire: 'IMPORT_007',
    label: 'Table found, no transactions read',
    meaning: 'A transaction table was found, but not a single transaction could be read from it.',
  },
  IMPORT_PDF_PASSWORD_REQUIRED: {
    wire: 'IMPORT_008',
    label: 'Password needed',
    meaning: 'The PDF is password protected and no password was given.',
  },
  IMPORT_PDF_PASSWORD_INVALID: {
    wire: 'IMPORT_009',
    label: 'Wrong password',
    meaning: 'A password was given but it did not open the PDF.',
  },
  IMPORT_SCANNED_OCR_REQUIRED: {
    wire: 'IMPORT_010',
    label: 'Scanned PDF (images only)',
    meaning: 'Every page is an image, so there is no text for the engine to read.',
  },
  IMPORT_CORRUPT_PDF: {
    wire: 'IMPORT_011',
    label: 'Damaged PDF',
    meaning: 'The PDF could not be opened — it appears damaged or incomplete.',
  },
  IMPORT_PDF_TOO_LARGE: {
    wire: 'IMPORT_013',
    label: 'Too many pages',
    meaning: 'The PDF has more pages than the engine will process in one file.',
  },
  IMPORT_NO_ACTIVITY_IN_PERIOD: {
    wire: 'IMPORT_014',
    label: 'No activity in period',
    meaning: "The statement's own printed summary shows no transactions for its period.",
  },
  IMPORT_TRUST_REVIEW_REJECTED: {
    wire: 'IMPORT_015',
    label: 'Failed accuracy check',
    meaning: 'The statement was read, but not accurately enough to be imported.',
  },
  IMPORT_MALFORMED_CSV: {
    wire: 'IMPORT_017',
    label: 'Damaged CSV',
    meaning: 'The file could not be read as a CSV — it appears damaged or cut short.',
  },
  IMPORT_PAYMENT_APP_HISTORY: {
    wire: 'IMPORT_018',
    label: 'Paytm history, not a statement',
    meaning: 'The upload is a Paytm payment history rather than a bank statement.',
  },
  IMPORT_SYSTEM_BUSY: {
    wire: 'IMPORT_006',
    label: 'System busy',
    meaning: 'Too many imports were running at once; the document itself was never tried.',
  },
  ENGINE_CRASH: {
    label: 'Unexpected error',
    meaning: 'The engine stopped on an unexpected error instead of a known reason. Worth investigating.',
  },
};

const FAILURE_BY_WIRE: Record<string, string> = Object.fromEntries(
  Object.entries(FAILURE_TERMS)
    .filter(([, term]) => term.wire)
    .map(([name, term]) => [term.wire as string, name]),
);

/** The stored name for a code given in either form, so callers can compare one value. */
export function canonicalFailureCode(code: string | null): string | null {
  if (code == null) return null;
  return FAILURE_BY_WIRE[code] ?? code;
}

export function describeFailure(code: string | null): Term {
  const canonical = canonicalFailureCode(code);
  const known = canonical ? FAILURE_TERMS[canonical] : undefined;
  if (known) return { label: known.label, meaning: known.meaning };
  // A failure that carried no ErrorCode is stored as the exception's simple class name
  // (ErrorCode.failureCodeOf), e.g. "NullPointerException" -- the customer-import twin of
  // AdminAnalysisService's ENGINE_CRASH.
  if (code && /^[A-Z][A-Za-z0-9]*(Exception|Error)$/.test(code)) {
    return {
      label: 'Unexpected error',
      meaning: `The engine stopped on an unexpected error (${code}) instead of a known reason. Worth investigating.`,
    };
  }
  return {
    label: 'Other failure',
    meaning: code
      ? `Recorded as "${code}". No plain-English description exists for this code yet.`
      : 'The failure was recorded without a code.',
  };
}

/** Both forms of the password failures -- the stored name is what the API actually returns. */
export function isPasswordFailure(code: string | null): boolean {
  const canonical = canonicalFailureCode(code);
  return canonical === 'IMPORT_PDF_PASSWORD_REQUIRED' || canonical === 'IMPORT_PDF_PASSWORD_INVALID';
}

const UNPARSEABLE_PREFIX = 'UNANCHORED_DATE_UNPARSEABLE:';

/**
 * Why a line could not be matched to a transaction. Reasons come from
 * PdfTableLocator.anchorFailureReason; the unparseable one carries the value's SHAPE (digits → 9,
 * letters → X), never the value itself.
 */
export function describeReason(reason: string): Term {
  if (reason.startsWith(UNPARSEABLE_PREFIX)) {
    const shape = reason.slice(UNPARSEABLE_PREFIX.length);
    return {
      label: `Date not understood: ${shape}`,
      meaning: `Something was in the date column but it is not a date format the engine knows. `
        + `Shown as a shape, not the real text: 9 = a digit, X = a letter (e.g. 99/99/9999).`,
    };
  }
  switch (reason) {
    case 'UNANCHORED_DATE_COLUMN_EMPTY':
      return {
        label: 'Date column empty',
        meaning: 'The table has a date column, but these lines had nothing in it — usually the text '
          + 'landed under a different column than expected.',
      };
    case 'UNANCHORED_NO_DATE_COLUMN':
      return {
        label: 'Table has no date column',
        meaning: 'The table the engine found has no column it recognises as a date.',
      };
    default:
      return {
        label: reason.toLowerCase().replace(/_/g, ' '),
        meaning: 'No plain-English description exists for this reason yet.',
      };
  }
}

/** FinancialProductType names (backend com.finora.imports.product) in plain words. */
const STATEMENT_TYPE_LABELS: Record<string, string> = {
  SAVINGS: 'Savings',
  CURRENT: 'Current account',
  OVERDRAFT: 'Overdraft',
  WALLET: 'Wallet',
  CREDIT_CARD: 'Credit card',
  FIXED_DEPOSIT: 'Fixed deposit',
  RECURRING_DEPOSIT: 'Recurring deposit',
  PPF: 'PPF',
  EPF: 'EPF',
  NPS: 'NPS',
  MUTUAL_FUND: 'Mutual fund',
  DEMAT: 'Demat',
  LOAN: 'Loan',
  INSURANCE: 'Insurance',
  FOREX_CARD: 'Forex card',
};

/** "SAVINGS,FIXED_DEPOSIT" -> "Savings + Fixed deposit"; null when nothing was identified. */
export function describeStatementType(statementType: string | null): string | null {
  if (!statementType) return null;
  const parts = statementType.split(',').map((t) => t.trim()).filter(Boolean)
    .map((t) => STATEMENT_TYPE_LABELS[t] ?? t.toLowerCase().replace(/_/g, ' '));
  return parts.length ? parts.join(' + ') : null;
}

export interface BankCell {
  /** What the cell says. */
  bank: string;
  /** Whether that is a real bank name (styled as a value) or an explanation (styled muted). */
  recognised: boolean;
  /** Hover text explaining a blank. */
  note?: string;
}

/**
 * The bank line for one analysis. Three different answers, kept apart on purpose: a recognised
 * bank; detection ran and recognised none; detection never ran. Showing the last two the same way
 * would send someone looking for a missing bank alias when the file simply never opened.
 */
export function describeBank(a: { identityChecked: boolean; bankName: string | null }): BankCell {
  if (a.bankName) return { bank: a.bankName, recognised: true };
  if (a.identityChecked) {
    return {
      bank: 'Bank not recognised',
      recognised: false,
      note: 'The engine read this file but did not recognise which bank issued it.',
    };
  }
  return {
    bank: '—',
    recognised: false,
    note: 'Not recorded: the file failed before the engine could look for a bank (for example a '
      + 'wrong password), or it was uploaded before bank names were saved.',
  };
}

/** The page glossary: every term the page uses, in the order it appears on screen. */
export const GLOSSARY: Term[] = [
  {
    label: 'Analysis',
    meaning: 'One upload attempt of one statement — by a customer importing, or by an admin using '
      + '"Analyse a statement". Each gets a reference like SA-20260806-0145 that support can quote.',
  },
  {
    label: 'Read / Failed',
    meaning: 'Read means the engine got transactions out of the file. Failed means it stopped; the '
      + 'reason is shown in plain words in the Result column.',
  },
  {
    label: 'Bank & statement type',
    meaning: 'Which bank issued the statement and what kind it is (savings, credit card, fixed '
      + 'deposit…), as the engine detected it from the document. "Bank not recognised" means the '
      + 'engine read the file but could not tell the bank; "—" means it never got that far, or the '
      + 'upload is older than this column.',
  },
  {
    label: 'Statement format (layout / fingerprint)',
    meaning: 'The design of a statement — its columns and structure. Statements from the same bank '
      + 'and product usually share one. The fingerprint (FP-…) is its ID, so repeated uploads of the '
      + 'same design can be grouped. "—" means the file failed before its design could be identified.',
  },
  {
    label: 'Transactions found',
    meaning: 'How many transactions the engine read from the file. "—" means it never got as far as '
      + 'reading (for example a wrong password) — which is different from reading and finding 0.',
  },
  {
    label: 'Unmatched lines (unanchored rows)',
    meaning: 'Lines inside the transaction table that could not be attached to any transaction, '
      + 'because they had no usable date and came after too many date-less lines in a row to be the '
      + 'wrapped description of the transaction above. They are kept aside, not merged. They are not '
      + 'necessarily lost transactions — headers, footers and summary text also end up here — so read '
      + 'them next to Transactions found.',
  },
  {
    label: 'Reasons',
    meaning: 'Why each unmatched line could not be matched. One reason that is large across many '
      + 'uploads points to something the engine cannot do yet; the same reason in one upload points '
      + 'to that one file.',
  },
  {
    label: 'Sections',
    meaning: 'How many separate transaction tables were found in the file (for example a savings '
      + 'account and a fixed deposit in one statement).',
  },
];
