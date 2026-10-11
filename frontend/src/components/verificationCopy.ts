import type { VerificationFinding } from '../types';

/**
 * The wording for four checks: the two credit-card ones (CREDIT_CARD_STATEMENT_TOTALS,
 * CREDIT_CARD_FLOW_RECONCILIATION), ROW_ACCOUNTING and DESCRIPTION_CORRUPTION.
 *
 * <b>Our sentences, not the server's.</b> Other rules show the backend's own `reason` and
 * `explanation`. These cannot: theirs name the parser's reading strategies, call a statement's
 * summary box a "billing-summary panel" and a line of the page a "transaction candidate", which
 * mean nothing to the person importing. So each case the validators can report is given fixed
 * wording here, chosen from the outcome and the facts in `details`, the same way
 * CONTENT_INTEGRITY is worded.
 *
 * <b>All four run on every import.</b> The card checks report NOT_APPLICABLE on a savings
 * statement, because there is no card summary to read, so that wording has to be true for a
 * savings statement and for a card statement whose summary could not be read.
 *
 * <b>Kept identical in frontend/src/components and mobile/src/components.</b> The two apps share no
 * package, and this is the one place the wording is decided, so a change here is made in both files.
 */
export interface CheckCopy {
  /** What was compared and how it came out. */
  body: string;
  /** What it means for the person importing. */
  note?: string;
}

type Details = Record<string, unknown>;

const detailsOf = (finding: VerificationFinding): Details => (finding.details ?? {}) as Details;

const SUMMARY_ONLY = "This check looks only at the statement's summary, not at your transactions.";
const COULD_NOT_RUN = 'This check could not be run on this statement.';

/** The summary figures the validator can name as read two ways, in the words a statement uses. */
const SUMMARY_FIGURES: Record<string, string> = {
  previousBalance: 'previous balance',
  purchases: 'purchases',
  cashAdvances: 'cash advances',
  fees: 'fees',
  paymentsAndCredits: 'payments and credits',
  totalAmountDue: 'total amount due',
};

/** CREDIT_CARD_STATEMENT_TOTALS: does the statement's summary add up to its own total? */
export function cardSummaryCopy(finding: VerificationFinding): CheckCopy {
  const d = detailsOf(finding);
  // Some card statements total to the outstanding balance, the rest to the amount due.
  const total = 'totalOutstanding' in d ? 'total outstanding' : 'total amount due';

  if (finding.outcome === 'VERIFIED') {
    const rounded = d.totalRoundedToRupee === true || d.totalAmountDueRoundedToRupee === true;
    return {
      body: `The figures in this statement's summary add up to its ${total}.`
        + (rounded
          ? ' The statement prints that total in whole rupees, so a difference of less than one rupee was allowed.'
          : ''),
    };
  }

  if (finding.outcome === 'NOT_APPLICABLE') {
    return {
      body: 'No card summary with a previous balance, purchases, payments and a total was found on '
        + 'this statement, so there was nothing to check here.',
    };
  }

  // The validator only ever warns. A FAILED from a later backend would still be a disagreement,
  // and is worded as one rather than as a check that never ran.
  if (finding.outcome === 'WARNING' || finding.outcome === 'FAILED') {
    const conflicting = Array.isArray(d.conflictingFields) ? d.conflictingFields : [];
    if (conflicting.length > 0) {
      const named = conflicting.map((field) => SUMMARY_FIGURES[String(field)]).filter(Boolean);
      return {
        body: 'The summary on this statement could be read in two ways that give different figures, '
          + 'so it was not used for this check.'
          + (named.length > 0 ? ` The two readings differ on: ${named.join(', ')}.` : ''),
        note: SUMMARY_ONLY,
      };
    }
    return {
      body: `The figures read from this statement's summary do not add up to its ${total}.`,
      note: `This usually means one of the summary figures was read incorrectly. ${SUMMARY_ONLY}`,
    };
  }

  return { body: COULD_NOT_RUN };
}

/** CREDIT_CARD_FLOW_RECONCILIATION: do the transactions read add up to the summary's totals? */
export function cardFlowCopy(finding: VerificationFinding): CheckCopy {
  const d = detailsOf(finding);

  if (finding.outcome === 'VERIFIED') {
    const instalments = typeof d.instalmentRowsSetAside === 'number' ? d.instalmentRowsSetAside : 0;
    if (instalments > 0) {
      return {
        body: "Payments and credits match this statement's summary. Purchases match too once "
          + `${instalments} instalment (EMI) ${instalments === 1 ? 'transaction is' : 'transactions are'} `
          + 'set aside: the statement lists them as transactions but leaves them out of its summary totals.',
      };
    }
    // Fees shown apart from purchases, refunds taken off the purchases figure, or both: the
    // details do not say which, so the sentence names them as examples and claims neither.
    if ('creditsNettedIntoPurchases' in d) {
      return {
        body: "This statement's summary groups some amounts differently from its list of transactions, "
          + 'for example fees shown apart from purchases, or refunds taken off the purchases total. '
          + 'Allowing for that, the transactions read from it add up to its totals.',
      };
    }
    return {
      body: 'The purchases and the payments read from this statement add up to the totals in its summary.',
    };
  }

  if (finding.outcome === 'NOT_APPLICABLE') {
    switch (d.evidenceLevel) {
      case 'NO_SUMMARY':
        return {
          body: 'No card summary was found on this statement, so there were no printed totals to '
            + 'compare the transactions with.',
        };
      case 'PARTIAL_SUMMARY_ONLY':
        return {
          body: "This statement's summary does not print both a purchases total and a payments total, "
            + 'so the transactions could not be compared with it.',
        };
      case 'NO_CLASSIFIED_TRANSACTIONS':
        return {
          body: "This statement's summary prints purchases and payments totals, but no transaction "
            + 'on it was read as a purchase or a payment, so there was nothing to compare.',
        };
      default:
        return { body: COULD_NOT_RUN };
    }
  }

  // The validator only ever warns. A FAILED from a later backend would still be a disagreement,
  // and is worded as one rather than as a check that never ran.
  if (finding.outcome === 'WARNING' || finding.outcome === 'FAILED') {
    const note = 'This check cannot tell which side is off. Compare the transactions with your '
      + 'statement before you import.';
    // Payments proven, purchases over by an amount instalments do not account for exactly. An
    // unproven side is never passed off as checked: the backend warns, and holds the import for
    // a person to review, the same as any other disagreement.
    if (typeof d.instalmentRowsRecognised === 'number') {
      const found = d.instalmentRowsRecognised;
      return {
        body: "Payments and credits match this statement's summary, but the purchases and charges "
          + 'read from it add up to more than the summary counts.'
          + (found > 0
            ? ` The ${found === 1 ? 'one instalment (EMI) transaction' : `${found} instalment (EMI) transactions`} `
              + `found on it ${found === 1 ? 'does' : 'do'} not account for the difference exactly.`
            : ''),
        note,
      };
    }
    return {
      body: 'The transactions read from this statement do not add up to the purchases and '
        + 'payments totals in its summary.',
      note,
    };
  }

  return { body: COULD_NOT_RUN };
}

const countOf = (value: unknown): number | null =>
  typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null;

/**
 * ROW_ACCOUNTING: was any line that looks like a transaction left behind on the way in?
 *
 * No count of the lines left out. The details count every dropped line, explained ones included,
 * and which reasons count as explained is the backend's to decide: a figure worked out here would
 * be wrong the day that list grows.
 */
export function rowAccountingCopy(finding: VerificationFinding): CheckCopy {
  const d = detailsOf(finding);

  if (finding.outcome === 'NOT_APPLICABLE') {
    return { body: 'No transaction table was found, so there were no rows to account for.' };
  }

  const read = countOf(d.stagedTransactionCount);
  const unreadable = countOf(d.unparseableRowCount) ?? 0;
  const opening = (read === null ? '' : `${read} ${read === 1 ? 'transaction was' : 'transactions were'} read. `)
    // The screen already says these will not be imported. Repeated here because this check passes
    // with them present, and "verified" beside a silence about them would read as none skipped.
    + (unreadable > 0
      ? `${unreadable} ${unreadable === 1 ? 'row' : 'rows'} in the table could not be read as `
        + `${unreadable === 1 ? 'a transaction' : 'transactions'} and will not be imported. `
      : '');

  // Says what this check found, not that nothing was missed: it sees only lines the reader
  // dropped after noticing a date and an amount on them, never a line it did not notice at all.
  if (finding.outcome === 'VERIFIED') {
    return {
      body: `${opening}This check found no line outside the transaction table that looks like a missed transaction.`,
    };
  }

  // The validator only ever warns; a FAILED from a later backend is the same finding, said louder.
  if (finding.outcome === 'WARNING' || finding.outcome === 'FAILED') {
    return {
      body: `${opening}At least one line outside the transaction table has both a date and an amount, `
        + 'and was left out. It may not be a transaction, but nothing confirmed that.',
      note: 'Compare this import with your statement for any transaction that is missing.',
    };
  }

  return { body: COULD_NOT_RUN };
}

/** How many row numbers a sentence names before it says "and N more". */
const ROWS_NAMED = 8;

/** "3", "3 and 14", "3, 14 and 20", "3, 14, 20 and 2 more". */
function listRows(rows: number[]): string {
  const named = rows.slice(0, ROWS_NAMED).map(String);
  const rest = rows.length - named.length;
  if (rest > 0) return `${named.join(', ')} and ${rest} more`;
  if (named.length === 1) return named[0];
  return `${named.slice(0, -1).join(', ')} and ${named[named.length - 1]}`;
}

/** DESCRIPTION_CORRUPTION: did a transaction's description pick up text that was never part of it? */
export function descriptionCopy(finding: VerificationFinding): CheckCopy {
  const d = detailsOf(finding);
  const pickedUp = 'picked up text that is not part of the transaction, such as a page footer';

  if (finding.outcome === 'NOT_APPLICABLE') {
    return {
      body: 'There are too few transactions here to tell what a normal description looks like, '
        + 'so this check was skipped.',
    };
  }

  if (finding.outcome === 'VERIFIED') {
    const checked = countOf(d.rowsChecked);
    return {
      body: (checked === null
        ? "Every transaction's description was checked. "
        : `The ${checked === 1 ? 'description of 1 transaction was' : `descriptions of ${checked} transactions were`} checked. `)
        + 'None looks like it picked up text from elsewhere on the page, such as a footer.',
    };
  }

  if (finding.outcome === 'WARNING' || finding.outcome === 'FAILED') {
    const outliers = Array.isArray(d.outliers) ? d.outliers : [];
    // +1 so it matches the row a person counts down to in the list, not the index.
    const rows = outliers
      .map((o) => countOf((o as { rowIndex?: unknown } | null)?.rowIndex))
      .filter((index): index is number => index !== null)
      .map((index) => index + 1);
    if (rows.length === 1) {
      return {
        body: `Row ${rows[0]} in the list has a description much longer than the others, and it reads `
          + `like more than one sentence. It may have ${pickedUp}.`,
        note: 'Read that description before you import.',
      };
    }
    if (rows.length > 1) {
      return {
        body: `Rows ${listRows(rows)} in the list have descriptions much longer than the others, and they `
          + `read like more than one sentence. They may have ${pickedUp}.`,
        note: 'Read those descriptions before you import.',
      };
    }
    return {
      body: 'At least one transaction has a description much longer than the others, and it reads '
        + `like more than one sentence. It may have ${pickedUp}.`,
      note: 'Read the longest descriptions before you import.',
    };
  }

  return { body: COULD_NOT_RUN };
}
