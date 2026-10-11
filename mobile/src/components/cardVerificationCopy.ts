import type { VerificationFinding } from '../types';

/**
 * The wording for the two credit-card checks, CREDIT_CARD_STATEMENT_TOTALS and
 * CREDIT_CARD_FLOW_RECONCILIATION.
 *
 * <b>Our sentences, not the server's.</b> Other rules show the backend's own `reason` and
 * `explanation`. These two cannot: theirs name the parser's reading strategies and call the
 * statement's summary box a "billing-summary panel", which means nothing to the person importing.
 * So each case the validators can report is given fixed wording here, chosen from the outcome and
 * the facts in `details`, the same way CONTENT_INTEGRITY is worded.
 *
 * <b>Both checks run on every import,</b> a savings statement included, where they report
 * NOT_APPLICABLE because there is no card summary to read. That wording has to be true for a
 * savings statement and for a card statement whose summary could not be read.
 *
 * <b>Kept identical in frontend/src/components and mobile/src/components.</b> The two apps share no
 * package, and this is the one place the wording is decided, so a change here is made in both files.
 */
export interface CardCheckCopy {
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
export function cardSummaryCopy(finding: VerificationFinding): CardCheckCopy {
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
export function cardFlowCopy(finding: VerificationFinding): CardCheckCopy {
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
