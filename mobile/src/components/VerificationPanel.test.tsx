import { render, screen, fireEvent } from '@testing-library/react-native';
import { VerificationPanel } from './VerificationPanel';
import { flaggedRows } from './verificationFlags';
import type { VerificationFinding, VerificationReport } from '../types';

function report(overrides: Partial<VerificationReport> = {}): VerificationReport {
  return {
    findings: [],
    headerReconstructionUncertain: false,
    textSource: 'NATIVE_PDF',
    reliabilityStatus: null,
    ...overrides,
  };
}

function finding(overrides: Partial<VerificationFinding> = {}): VerificationFinding {
  return { rule: 'BALANCE_CHAIN', outcome: 'VERIFIED', details: {}, ...overrides };
}

describe('VerificationPanel', () => {
  it('renders nothing when there is no verification report', () => {
    render(<VerificationPanel verification={null} />);
    expect(screen.queryByText('Statement verification')).toBeNull();
  });

  it('renders nothing when the report carries zero findings', () => {
    render(<VerificationPanel verification={report({ findings: [] })} />);
    expect(screen.queryByText('Statement verification')).toBeNull();
  });

  it('shows the server-computed CLEAN verdict and starts collapsed', () => {
    render(<VerificationPanel verification={report({ reliabilityStatus: 'CLEAN', findings: [finding()] })} />);

    expect(screen.getByText('Imported successfully')).toBeTruthy();
    expect(screen.queryByText(/transaction\(s\) checked/)).toBeNull();
  });

  it('shows a NEEDS_ATTENTION verdict distinctly from REVIEW_RECOMMENDED', () => {
    render(<VerificationPanel verification={report({ reliabilityStatus: 'NEEDS_ATTENTION', findings: [finding({ outcome: 'FAILED' })] })} />);
    expect(screen.getByText('Import needs attention')).toBeTruthy();
  });

  it('softens REVIEW_RECOMMENDED to "Imported with notes" rather than an alarming label', () => {
    render(<VerificationPanel verification={report({ reliabilityStatus: 'REVIEW_RECOMMENDED', findings: [finding({ outcome: 'WARNING' })] })} />);
    expect(screen.getByText('Imported with notes')).toBeTruthy();
  });

  it('falls back to counting findings when the server sends no reliabilityStatus', () => {
    render(<VerificationPanel verification={report({
      reliabilityStatus: null,
      findings: [finding({ outcome: 'VERIFIED' }), finding({ rule: 'COLUMN_AMBIGUITY', outcome: 'WARNING' })],
    })} />);
    expect(screen.getByText('1 finding')).toBeTruthy();
  });

  it('expands to show each finding\'s rule label, outcome, and summary sentence', () => {
    render(<VerificationPanel verification={report({
      reliabilityStatus: 'CLEAN',
      findings: [finding({
        details: { rowsChecked: 8, rowsWithBalance: 8, anchoredOnOpeningBalance: false, discrepancies: [] },
      })],
    })} />);

    fireEvent.press(screen.getByText('Statement verification'));

    expect(screen.getByText(/Running balance.*verified/)).toBeTruthy();
    expect(screen.getByText(/8 transaction\(s\) checked/)).toBeTruthy();
    expect(screen.getByText(/no opening balance/)).toBeTruthy();
  });

  it('names a discrepancy count on the balance-chain summary when rows disagree', () => {
    render(<VerificationPanel verification={report({
      reliabilityStatus: 'NEEDS_ATTENTION',
      findings: [finding({
        outcome: 'FAILED',
        details: {
          rowsChecked: 10, rowsWithBalance: 10, anchoredOnOpeningBalance: true,
          discrepancies: [{ rowIndex: 3, expectedBalance: 100, actualBalance: 90, difference: 10 }],
        },
      })],
    })} />);

    fireEvent.press(screen.getByText('Statement verification'));

    expect(screen.getByText(/1 row didn't match/)).toBeTruthy();
    // rowIndex alone does not say where the row is on this screen, so nothing points at the list.
    expect(screen.queryByText(/in the list below/)).toBeNull();
  });

  describe('the rows the balance check points at', () => {
    const chain = (discrepancies: Record<string, number>[]) => report({
      reliabilityStatus: 'REVIEW_RECOMMENDED',
      findings: [finding({
        outcome: 'WARNING',
        details: { rowsChecked: 10, rowsWithBalance: 10, anchoredOnOpeningBalance: true, discrepancies },
      })],
    });
    const amounts = { expectedBalance: 100, actualBalance: 90, difference: 10 };
    const expand = (verification: VerificationReport, rowCount: number) => {
      render(<VerificationPanel verification={verification} flagged={flaggedRows(verification, rowCount)} />);
      fireEvent.press(screen.getByText('Statement verification'));
    };

    it('says the row is marked in the list, and names no number', () => {
      expand(chain([{ rowIndex: 2, displayIndex: 4, ...amounts }]), 10);

      expect(screen.getByText('That row is marked "Balance didn\'t match" in the list below.')).toBeTruthy();
      expect(screen.queryByText(/\brows? \d/i)).toBeNull();
    });

    it('says rows, for several', () => {
      expand(chain([{ rowIndex: 4, displayIndex: 4, ...amounts }, { rowIndex: 9, displayIndex: 9, ...amounts }]), 10);

      expect(screen.getByText(/2 rows didn't match/)).toBeTruthy();
      expect(screen.getByText('Those rows are marked "Balance didn\'t match" in the list below.')).toBeTruthy();
    });

    it('does not point at the list when a position is past its end', () => {
      expand(chain([{ rowIndex: 16, displayIndex: 16, ...amounts }]), 10);

      expect(screen.getByText(/1 row didn't match/)).toBeTruthy();
      expect(screen.queryByText(/in the list below/)).toBeNull();
    });
  });

  it('shows the OCR provenance note only when the text source involved OCR', () => {
    render(<VerificationPanel verification={report({ reliabilityStatus: 'REVIEW_RECOMMENDED', textSource: 'OCR', findings: [finding({ outcome: 'WARNING' })] })} />);
    fireEvent.press(screen.getByText('Statement verification'));
    expect(screen.getByText(/read using OCR/)).toBeTruthy();
  });

  it('shows no OCR note for a purely native-text read', () => {
    render(<VerificationPanel verification={report({ reliabilityStatus: 'CLEAN', textSource: 'NATIVE_PDF', findings: [finding()] })} />);
    fireEvent.press(screen.getByText('Statement verification'));
    expect(screen.queryByText(/read using OCR/)).toBeNull();
  });

  it('names an unrecognized rule rather than silently dropping it', () => {
    render(<VerificationPanel verification={report({
      reliabilityStatus: null,
      findings: [finding({ rule: 'SOME_FUTURE_RULE', outcome: 'WARNING' })],
    })} />);
    fireEvent.press(screen.getByText('Statement verification'));
    expect(screen.getByText('SOME_FUTURE_RULE', { exact: false })).toBeTruthy();
    expect(screen.getByText(/doesn't know how to display yet/)).toBeTruthy();
  });

  // A partially unreadable file is the one finding that must not hide behind a tap: rows may be missing
  // from what the user is about to confirm. Prod-adjacent, 2026-09-20: a file damaged part-way imported
  // 1 of 6 rows as "Imported successfully".
  describe('content integrity (a damaged file)', () => {
    const damaged = () => report({
      reliabilityStatus: 'NEEDS_ATTENTION',
      findings: [finding({
        rule: 'CONTENT_INTEGRITY', outcome: 'FAILED',
        details: { failedTextOperators: 0, corruptContentStreams: 1, damagedPages: [2], explanation: 'server text' },
      })],
    });

    it('opens by itself, so the warning is read without a tap', () => {
      render(<VerificationPanel verification={damaged()} />);

      expect(screen.getByText(/could not be read/i)).toBeTruthy();
    });

    it('says which page and what to do, in plain words', () => {
      render(<VerificationPanel verification={damaged()} />);

      // The label shares a Text node with its outcome ("File integrity · did not reconcile").
      expect(screen.getByText(/File integrity/)).toBeTruthy();
      expect(screen.getByText(/some transactions may be missing/i)).toBeTruthy();
      expect(screen.getByText(/Download the statement again from your bank/i)).toBeTruthy();
      expect(screen.getByText(/page 2/i)).toBeTruthy();
    });

    it('names several pages', () => {
      const r = damaged();
      r.findings[0].details = { corruptContentStreams: 2, damagedPages: [1, 3] };
      render(<VerificationPanel verification={r} />);

      expect(screen.getByText(/pages 1, 3/i)).toBeTruthy();
    });

    it('leaves every other report collapsed', () => {
      render(<VerificationPanel verification={report({ reliabilityStatus: 'NEEDS_ATTENTION', findings: [finding({ outcome: 'FAILED' })] })} />);

      expect(screen.queryByText(/transaction\(s\) checked/)).toBeNull();
    });
  });

  // Both card checks reach every import, and until they had renderers each showed its rule id over
  // "doesn't know how to display yet". Same cases as the web panel's tests, with the details each
  // backend validator sends for that outcome, the server's own sentences included, so a test can
  // prove those stay unseen.
  describe('credit card checks', () => {
    const SUMMARY = 'CREDIT_CARD_STATEMENT_TOTALS';
    const FLOW = 'CREDIT_CARD_FLOW_RECONCILIATION';

    const amountDue = {
      extractionMethod: 'GRID', previousBalance: 12000, purchases: 8500.5, cashAdvances: 0, fees: 0,
      paymentsAndCredits: 12000, totalAmountDue: 8500.5, expectedTotalAmountDue: 8500.5, difference: 0,
    };
    const outstanding = {
      extractionMethod: 'STACKED_LABEL_COLUMNS', previousBalance: 40000, purchases: 9000.4, cashAdvances: 0,
      fees: 590, paymentsAndCredits: 15000, totalOutstanding: 34590.4, expectedTotalOutstanding: 34590.4,
      difference: 0,
    };
    const summaryMisread = 'The previous balance, purchases, cash advances, fees, and payments/credits this '
      + 'statement prints about itself do not add up to its own printed total amount due. This does not '
      + 'implicate any parsed transaction -- no transaction row was read to produce this finding -- it means '
      + 'this extraction misread one of the statement\'s own summary figures.';
    const flowAmounts = {
      evidenceLevel: 'FULL_SUMMARY_RECONCILIATION',
      expectedExpenseAmount: 8500.5, observedExpenseAmount: 8500.5, differenceExpenseAmount: 0,
      expectedIncomeAmount: 12000, observedIncomeAmount: 12000, differenceIncomeAmount: 0,
    };
    const flowDisagrees = 'The extracted transactions, summed by direction, do not match this statement\'s own '
      + 'printed purchases and/or payments/credits totals. This does not identify which side is wrong '
      + '-- only that they disagree.';

    interface Case {
      name: string; finding: VerificationFinding; label: string; tag: string; body: RegExp; note?: RegExp;
    }

    const cases: Case[] = [
      {
        name: 'summary adds up to the amount due',
        finding: { rule: SUMMARY, outcome: 'VERIFIED', details: amountDue },
        label: 'Card statement summary', tag: 'verified',
        body: /figures in this statement's summary add up to its total amount due\.$/,
      },
      {
        name: 'summary adds up to an amount due printed in whole rupees',
        finding: {
          rule: SUMMARY, outcome: 'VERIFIED',
          details: { ...amountDue, totalAmountDue: 8500, difference: -0.5, totalAmountDueRoundedToRupee: true },
        },
        label: 'Card statement summary', tag: 'verified',
        body: /add up to its total amount due\. The statement prints that total in whole rupees/,
      },
      {
        name: 'summary adds up to the outstanding balance',
        finding: { rule: SUMMARY, outcome: 'VERIFIED', details: outstanding },
        label: 'Card statement summary', tag: 'verified',
        body: /figures in this statement's summary add up to its total outstanding\.$/,
      },
      {
        name: 'summary adds up to an outstanding balance printed in whole rupees',
        finding: {
          rule: SUMMARY, outcome: 'VERIFIED',
          details: { ...outstanding, totalOutstanding: 34590, difference: -0.4, totalRoundedToRupee: true },
        },
        label: 'Card statement summary', tag: 'verified',
        body: /add up to its total outstanding\. The statement prints that total in whole rupees/,
      },
      {
        name: 'summary does not add up to the amount due',
        finding: {
          rule: SUMMARY, outcome: 'WARNING',
          details: { ...amountDue, totalAmountDue: 9100.5, difference: 600, explanation: summaryMisread },
        },
        label: 'Card statement summary', tag: 'needs review',
        body: /figures read from this statement's summary do not add up to its total amount due/,
        note: /one of the summary figures was read incorrectly.*not at your transactions/,
      },
      {
        name: 'summary does not add up to the outstanding balance',
        finding: {
          rule: SUMMARY, outcome: 'WARNING',
          details: { ...outstanding, totalOutstanding: 35000, difference: 409.6, explanation: summaryMisread },
        },
        label: 'Card statement summary', tag: 'needs review',
        body: /do not add up to its total outstanding/,
        note: /not at your transactions/,
      },
      {
        name: 'summary was read two ways',
        finding: {
          rule: SUMMARY, outcome: 'WARNING',
          details: {
            conflictingFields: ['previousBalance', 'fees'],
            reason: 'SUMMARY_EXTRACTION_CONFLICT: GRID and INLINE_LABEL_VALUE read different values for the '
              + 'same field(s) on this statement\'s billing-summary panel. At least one of the two readings '
              + 'is wrong, so neither is trusted here.',
          },
        },
        label: 'Card statement summary', tag: 'needs review',
        body: /read in two ways that give different figures.*differ on: previous balance, fees\./,
        note: /not at your transactions/,
      },
      {
        name: 'no summary to add up',
        finding: {
          rule: SUMMARY, outcome: 'NOT_APPLICABLE',
          details: {
            reason: 'This statement\'s billing-summary panel did not print enough of previous balance, '
              + 'purchases, and payments/credits, alongside the total amount due, to check them against each other.',
          },
        },
        label: 'Card statement summary', tag: 'not applicable',
        body: /No card summary with a previous balance, purchases, payments and a total was found/,
      },
      {
        name: 'transactions add up to the summary',
        finding: { rule: FLOW, outcome: 'VERIFIED', details: flowAmounts },
        label: 'Card purchases and payments', tag: 'verified',
        body: /purchases and the payments read from this statement add up to the totals in its summary/,
      },
      {
        name: 'transactions add up once refunds and fees are allowed for',
        finding: {
          rule: FLOW, outcome: 'VERIFIED',
          details: {
            ...flowAmounts, observedExpenseAmount: 9590.5, differenceExpenseAmount: 1090,
            observedIncomeAmount: 12500, differenceIncomeAmount: 500,
            expectedChargesAmount: 9090.5, creditsNettedIntoPurchases: 500,
          },
        },
        label: 'Card purchases and payments', tag: 'verified',
        body: /groups some amounts differently.*for example fees.*or refunds.*transactions read from it add up to its totals/,
      },
      {
        name: 'transactions add up once one instalment is set aside',
        finding: {
          rule: FLOW, outcome: 'VERIFIED',
          details: {
            ...flowAmounts, observedExpenseAmount: 10500.5, differenceExpenseAmount: 2000,
            expectedChargesAmount: 8500.5, instalmentRowsSetAside: 1,
          },
        },
        label: 'Card purchases and payments', tag: 'verified',
        body: /Purchases match too once 1 instalment \(EMI\) transaction is set aside/,
      },
      {
        name: 'transactions add up once several instalments are set aside',
        finding: {
          rule: FLOW, outcome: 'VERIFIED',
          details: {
            ...flowAmounts, observedExpenseAmount: 14500.5, differenceExpenseAmount: 6000,
            expectedChargesAmount: 8500.5, instalmentRowsSetAside: 3,
          },
        },
        label: 'Card purchases and payments', tag: 'verified',
        body: /Purchases match too once 3 instalment \(EMI\) transactions are set aside/,
      },
      // Payments proven, purchases over by more than instalments explain. The backend holds this
      // for a person rather than passing it with a softer label, so it reads as any other warning.
      {
        name: 'payments match but purchases are over, with instalments that do not explain it',
        finding: {
          rule: FLOW, outcome: 'WARNING',
          details: {
            ...flowAmounts, observedExpenseAmount: 11750.5, differenceExpenseAmount: 3250,
            expectedChargesAmount: 8500.5, instalmentRowsRecognised: 2, explanation: flowDisagrees,
          },
        },
        label: 'Card purchases and payments', tag: 'needs review',
        body: /Payments and credits match this statement's summary, but the purchases and charges read from it add up to more than the summary counts\. The 2 instalment \(EMI\) transactions found on it do not account for the difference exactly\./,
        note: /cannot tell which side is off\. Compare the transactions with your statement/,
      },
      {
        name: 'payments match but purchases are over, with one instalment that does not explain it',
        finding: {
          rule: FLOW, outcome: 'WARNING',
          details: {
            ...flowAmounts, observedExpenseAmount: 11750.5, differenceExpenseAmount: 3250,
            expectedChargesAmount: 8500.5, instalmentRowsRecognised: 1, explanation: flowDisagrees,
          },
        },
        label: 'Card purchases and payments', tag: 'needs review',
        body: /The one instalment \(EMI\) transaction found on it does not account for the difference exactly\./,
        note: /cannot tell which side is off/,
      },
      {
        name: 'payments match but purchases are over, with no instalment found',
        finding: {
          rule: FLOW, outcome: 'WARNING',
          details: {
            ...flowAmounts, observedExpenseAmount: 11750.5, differenceExpenseAmount: 3250,
            expectedChargesAmount: 8500.5, instalmentRowsRecognised: 0, explanation: flowDisagrees,
          },
        },
        label: 'Card purchases and payments', tag: 'needs review',
        body: /purchases and charges read from it add up to more than the summary counts\.$/,
        note: /cannot tell which side is off/,
      },
      {
        name: 'no card summary on the statement',
        finding: {
          rule: FLOW, outcome: 'NOT_APPLICABLE',
          details: {
            evidenceLevel: 'NO_SUMMARY',
            reason: 'No billing-summary panel evidence was extracted for this statement.',
          },
        },
        label: 'Card purchases and payments', tag: 'not applicable',
        body: /No card summary was found on this statement/,
      },
      {
        name: 'summary prints only one of the two totals',
        finding: {
          rule: FLOW, outcome: 'NOT_APPLICABLE',
          details: {
            evidenceLevel: 'PARTIAL_SUMMARY_ONLY',
            reason: 'The billing-summary panel did not print both purchases and payments/credits, so the '
              + 'extracted transactions could not be reconciled against it.',
          },
        },
        label: 'Card purchases and payments', tag: 'not applicable',
        body: /^Only part of this statement's summary could be read\. A purchases total and a payments total were not both found, so the transactions could not be compared with it\.$/,
      },
      {
        name: 'no transaction read as a purchase or a payment',
        finding: {
          rule: FLOW, outcome: 'NOT_APPLICABLE',
          details: {
            evidenceLevel: 'NO_CLASSIFIED_TRANSACTIONS',
            reason: 'This statement\'s billing-summary panel printed both purchases and payments/credits, but '
              + 'no extracted row was classified as either an expense or an income -- there is nothing to '
              + 'reconcile the panel against.',
          },
        },
        label: 'Card purchases and payments', tag: 'not applicable',
        body: /no transaction on it was read as a purchase or a payment/,
      },
      {
        name: 'a reason for not checking that this build has not heard of',
        finding: { rule: FLOW, outcome: 'NOT_APPLICABLE', details: { evidenceLevel: 'SOME_FUTURE_LEVEL' } },
        label: 'Card purchases and payments', tag: 'not applicable',
        body: /This check could not be run on this statement\./,
      },
      {
        name: 'transactions do not add up to the summary',
        finding: {
          rule: FLOW, outcome: 'WARNING',
          details: {
            ...flowAmounts, observedExpenseAmount: 7300.5, differenceExpenseAmount: -1200,
            explanation: flowDisagrees,
          },
        },
        label: 'Card purchases and payments', tag: 'needs review',
        body: /do not add up to the purchases and payments totals in its summary/,
        note: /cannot tell which side is off\. Compare the transactions with your statement/,
      },
      // A summary printed as its own equation that leaves a refund out of its payments and credits
      // (CardSummaryEquationReader, a real Axis card): the rows are checked against the balances.
      {
        name: 'transactions verified against the balances, the summary leaving a credit out',
        finding: {
          rule: FLOW, outcome: 'VERIFIED',
          details: {
            ...flowAmounts, observedIncomeAmount: 12500, differenceIncomeAmount: 500,
            expectedChargesAmount: 8500.5, creditsNotCountedInSummary: 500, rowsCarryPreviousBalanceToTotalDue: true,
          },
        },
        label: 'Card purchases and payments', tag: 'verified',
        body: /^Purchases match this statement's summary\. Its payments and credits figure leaves out a credit, such as a refund, that its total due includes\. Counting that credit, the transactions read from it take the previous balance exactly to the total due\.$/,
      },
      // The summary rule re-read in light of the row above: its gap is that credit, not a misread.
      {
        name: 'a summary that leaves a credit out, explained by the rows',
        finding: {
          rule: SUMMARY, outcome: 'NOT_APPLICABLE',
          details: {
            ...amountDue, extractionMethod: 'EQUATION_ROW', totalAmountDue: 8000.5, difference: -500,
            reason: 'SUMMARY_LEAVES_OUT_A_CREDIT: the summary\'s payments and credits leave out a credit its own '
              + 'total includes. The rows carry the previous balance to that total exactly.',
          },
        },
        label: 'Card statement summary', tag: 'not applicable',
        body: /^The figures in this statement's summary do not add up to its total amount due, because its payments and credits leave out a credit, such as a refund, that the total includes\. The transactions read from it account for that credit exactly, so nothing was misread\.$/,
      },
      {
        // Set aside for a reason this build does not know, with the summary's figures still on it.
        name: 'a summary that was read, set aside for an unknown reason',
        finding: { rule: SUMMARY, outcome: 'NOT_APPLICABLE', details: { ...amountDue, reason: 'SOME_FUTURE_LEVEL: x' } },
        label: 'Card statement summary', tag: 'not applicable',
        body: /^This statement's summary was read, but this check could not be completed on it\.$/,
      },
      // Neither validator reports FAILED today. One that did would still mean a disagreement, so
      // it must not fall through to "could not be run".
      {
        name: 'a failed summary check from a later backend',
        finding: { rule: SUMMARY, outcome: 'FAILED', details: { ...amountDue, totalAmountDue: 9100.5, difference: 600 } },
        label: 'Card statement summary', tag: 'did not reconcile',
        body: /figures read from this statement's summary do not add up to its total amount due/,
        note: /not at your transactions/,
      },
      {
        name: 'a failed transactions check from a later backend',
        finding: {
          rule: FLOW, outcome: 'FAILED',
          details: { ...flowAmounts, observedExpenseAmount: 7300.5, differenceExpenseAmount: -1200 },
        },
        label: 'Card purchases and payments', tag: 'did not reconcile',
        body: /do not add up to the purchases and payments totals in its summary/,
        note: /cannot tell which side is off/,
      },
    ];

    const expand = (...findings: VerificationFinding[]) => {
      render(<VerificationPanel verification={report({ findings })} />);
      fireEvent.press(screen.getByText('Statement verification'));
    };

    it.each(cases)('$name', ({ finding, label, tag, body, note }) => {
      expand(finding);

      // The label shares a Text node with its outcome.
      expect(screen.getByText(`${label} · ${tag}`)).toBeTruthy();
      expect(screen.getByText(body)).toBeTruthy();
      if (note) expect(screen.getByText(note)).toBeTruthy();

      // Nothing of the machinery: not the rule id, not the fallback line, not a details key or
      // value, and not the server's own sentence about panels and extraction.
      expect(screen.queryByText(
        /CREDIT_CARD|doesn't know how to display|billing-summary|GRID|INLINE_LABEL|extract|evidenceLevel|_ONLY|NO_SUMMARY|NO_CLASSIFIED|FULL_SUMMARY|FUTURE_LEVEL|undefined|NaN/,
      )).toBeNull();
    });

    it('never tells someone a statement with one side unproven can go ahead', () => {
      // The owner's rule (2026-10-11): an import goes ahead only once its amounts are confirmed,
      // by a check or by a person. A softer label here would say the opposite.
      expand(cases.find((c) => c.name.startsWith('payments match but purchases are over, with instalments'))!.finding);

      expect(screen.queryByText(/not applicable|not fully checked|verified|go ahead/i)).toBeNull();
    });

    it('survives a finding that arrives with no details at all', () => {
      const bare = (rule: string): VerificationFinding =>
        ({ rule, outcome: 'VERIFIED', details: null } as unknown as VerificationFinding);
      expand(bare(SUMMARY), bare(FLOW));

      expect(screen.getByText('Card statement summary · verified')).toBeTruthy();
      expect(screen.getByText('Card purchases and payments · verified')).toBeTruthy();
    });
  });

  // The other two checks every report carries that had no renderer. Same cases as the web panel's
  // tests, with what RowAccountingValidator and DescriptionCorruptionValidator send, their own
  // sentences included.
  describe('row accounting and description checks', () => {
    const ROWS = 'ROW_ACCOUNTING';
    const DESCRIPTIONS = 'DESCRIPTION_CORRUPTION';

    const counts = {
      stagedTransactionCount: 42, locatedRowCount: 42, unparseableRowCount: 0, droppedTransactionCandidateCount: 0,
    };
    const allAccounted = 'Every row this parser located has an accounted-for fate.';
    const outlier = (rowIndex: number, length: number | null = 412) =>
      ({ rowIndex, length, sectionMedianLength: 47, sentenceBreaks: 3 });
    const absorbed = 'One transaction\'s description is far longer than this statement\'s own typical narration and '
      + 'reads like more than one sentence -- it may have absorbed text that was never part of it, such as a '
      + 'page footer or disclaimer.';

    interface Case {
      name: string; finding: VerificationFinding; label: string; tag: string; body: RegExp; note?: RegExp;
    }

    const cases: Case[] = [
      {
        name: 'every row accounted for',
        finding: { rule: ROWS, outcome: 'VERIFIED', details: { ...counts, explanation: allAccounted } },
        label: 'Rows accounted for', tag: 'verified',
        body: /^42 transactions were read\. This check found no line outside the transaction table that looks like a missed transaction\.$/,
      },
      {
        name: 'a statement of one transaction',
        finding: {
          rule: ROWS, outcome: 'VERIFIED',
          details: { ...counts, stagedTransactionCount: 1, locatedRowCount: 1, explanation: allAccounted },
        },
        label: 'Rows accounted for', tag: 'verified',
        body: /^1 transaction was read\. This check found no line/,
      },
      {
        // Passes with rows it could not read, and with a line dropped for a known reason.
        name: 'accounted for, with rows that could not be read',
        finding: {
          rule: ROWS, outcome: 'VERIFIED',
          details: {
            ...counts, locatedRowCount: 44, unparseableRowCount: 2, droppedTransactionCandidateCount: 1,
            droppedTransactionCandidateReasons: { PAGE_LEGEND_BLOCK_SUPPRESSED: 1 }, explanation: allAccounted,
          },
        },
        label: 'Rows accounted for', tag: 'verified',
        body: /^42 transactions were read\. Rows in the table that could not be read are counted separately on this screen and will not be imported\. This check found no line/,
      },
      {
        name: 'a line that looks like a transaction was left out',
        finding: {
          rule: ROWS, outcome: 'WARNING',
          details: {
            ...counts, droppedTransactionCandidateCount: 3,
            droppedTransactionCandidateReasons: { PRE_HEADER_ACTIVITY_CANDIDATE: 2, PAGE_LEGEND_BLOCK_SUPPRESSED: 1 },
            explanation: '2 rows outside the recognized transaction table had the shape of a transaction candidate '
              + '(a date and an amount on the same line) and were discarded. Nothing here says they were '
              + 'transactions -- only that they were never explained, and require review.',
          },
        },
        label: 'Rows accounted for', tag: 'needs review',
        body: /^42 transactions were read\. At least one line outside the transaction table has both a date and an amount, and was left out\. It may not be a transaction, but nothing confirmed that\.$/,
        note: /Compare this import with your statement for any transaction that is missing\./,
      },
      {
        name: 'no table to account for',
        finding: {
          rule: ROWS, outcome: 'NOT_APPLICABLE',
          details: {
            ...counts, stagedTransactionCount: 0, locatedRowCount: 0,
            reason: 'No transaction table was located, so there is nothing to account for.',
          },
        },
        label: 'Rows accounted for', tag: 'not applicable',
        body: /^No transaction table was found, so there were no rows to account for\.$/,
      },
      {
        name: 'a failed row check from a later backend',
        finding: { rule: ROWS, outcome: 'FAILED', details: { ...counts, droppedTransactionCandidateCount: 1 } },
        label: 'Rows accounted for', tag: 'did not reconcile',
        body: /At least one line outside the transaction table/,
        note: /Compare this import with your statement/,
      },
      {
        name: 'descriptions all look like descriptions',
        finding: { rule: DESCRIPTIONS, outcome: 'VERIFIED', details: { rowsChecked: 42, sectionMedianLength: 47 } },
        label: 'Transaction descriptions', tag: 'verified',
        body: /^The descriptions of 42 transactions were checked\. None looks like it picked up text from elsewhere on the page, such as a footer\.$/,
      },
      {
        name: 'too few transactions to judge descriptions',
        finding: {
          rule: DESCRIPTIONS, outcome: 'NOT_APPLICABLE',
          details: {
            reason: 'Fewer than 5 non-blank descriptions -- too small a sample to know what this section\'s own '
              + 'normal length looks like.',
          },
        },
        label: 'Transaction descriptions', tag: 'not applicable',
        body: /^There are too few transactions here to tell what a normal description looks like, so this check was skipped\.$/,
      },
      {
        name: 'one description picked up extra text',
        finding: {
          rule: DESCRIPTIONS, outcome: 'WARNING',
          details: { rowsChecked: 42, sectionMedianLength: 47, outliers: [outlier(13)], explanation: absorbed },
        },
        label: 'Transaction descriptions', tag: 'needs review',
        body: /^One transaction has a description much longer than the others here, and it reads like more than one sentence\. It may have picked up text that is not part of the transaction, such as a page footer\. It is 412 characters long, where a typical one here is about 47\.$/,
        note: /Find that description in the list and read it before you import\./,
      },
      {
        name: 'three descriptions picked up extra text',
        finding: {
          rule: DESCRIPTIONS, outcome: 'WARNING',
          details: {
            rowsChecked: 42, sectionMedianLength: 47,
            outliers: [outlier(2, 380), outlier(13, 300), outlier(19, 412)],
          },
        },
        label: 'Transaction descriptions', tag: 'needs review',
        // The shortest of the three, so "at least" is true of each.
        body: /^3 transactions have descriptions much longer than the others here, and they read like more than one sentence\. They may have picked up text that is not part of the transaction, such as a page footer\. Each is at least 300 characters long, where a typical one here is about 47\.$/,
        note: /Find those descriptions in the list and read them before you import\./,
      },
      {
        // A length for only some of them would describe none of them, so no figure is given.
        name: 'descriptions picked up extra text, one arriving without its length',
        finding: {
          rule: DESCRIPTIONS, outcome: 'WARNING',
          details: { rowsChecked: 42, sectionMedianLength: 47, outliers: [outlier(2), outlier(13, null)] },
        },
        label: 'Transaction descriptions', tag: 'needs review',
        body: /^2 transactions have descriptions much longer than the others here, and they read like more than one sentence\. They may have picked up text that is not part of the transaction, such as a page footer\.$/,
        note: /Find those descriptions in the list and read them before you import\./,
      },
      {
        name: 'a description warning that lists no transaction',
        finding: { rule: DESCRIPTIONS, outcome: 'WARNING', details: { rowsChecked: 42, sectionMedianLength: 47 } },
        label: 'Transaction descriptions', tag: 'needs review',
        body: /^At least one transaction has a description much longer than the others here/,
        note: /Find the longest descriptions in the list and read them before you import\./,
      },
    ];

    const expand = (...findings: VerificationFinding[]) => {
      render(<VerificationPanel verification={report({ findings })} />);
      fireEvent.press(screen.getByText('Statement verification'));
    };

    it.each(cases)('$name', ({ finding, label, tag, body, note }) => {
      expand(finding);

      // The label shares a Text node with its outcome.
      expect(screen.getByText(`${label} · ${tag}`)).toBeTruthy();
      expect(screen.getByText(body)).toBeTruthy();
      if (note) expect(screen.getByText(note)).toBeTruthy();

      // Nothing of the machinery: not the rule id, not the fallback line, not a details key or
      // a reason code, and not the server's own sentence about parsers and candidates.
      expect(screen.queryByText(
        /ROW_ACCOUNTING|DESCRIPTION_CORRUPTION|doesn't know how to display|candidate|parser|accounted-for|non-blank|sample|narration|PRE_HEADER|PAGE_LEGEND|sectionMedian|undefined|NaN/,
      )).toBeNull();
    });

    it('never names a transaction by its row number', () => {
      // rowIndex counts rows in the order the document printed them, and a PDF's review list is
      // sorted by date afterwards. On a statement printed newest-first it names a different
      // transaction, so a report that carries nothing else points at none -- here rowIndex 13.
      const one = cases.find((c) => c.name === 'one description picked up extra text')!;
      const verification = report({ findings: [one.finding] });
      render(<VerificationPanel verification={verification} flagged={flaggedRows(verification, 42)} />);
      fireEvent.press(screen.getByText('Statement verification'));

      expect(screen.queryByText(/\brows? \d|\b1[34]\b|in the list below/i)).toBeNull();
      expect(screen.getByText(one.note!)).toBeTruthy();
    });

    describe('when the report says where the rows are shown', () => {
      const shownAt = (displayIndex: number, rowIndex: number, length = 412) =>
        ({ rowIndex, displayIndex, length, sectionMedianLength: 47, sentenceBreaks: 3 });
      const warning = (outliers: Record<string, number>[]): VerificationFinding => ({
        rule: DESCRIPTIONS, outcome: 'WARNING', details: { rowsChecked: 42, sectionMedianLength: 47, outliers },
      });
      const expandMarked = (found: VerificationFinding, rowCount: number | null) => {
        const verification = report({ findings: [found] });
        render(rowCount === null
          ? <VerificationPanel verification={verification} />
          : <VerificationPanel verification={verification} flagged={flaggedRows(verification, rowCount)} />);
        fireEvent.press(screen.getByText('Statement verification'));
      };

      it('points at the mark on the card, and still names no row by number', () => {
        expandMarked(warning([shownAt(1, 5)]), 42);

        expect(screen.getByText('It is marked "Check description" in the list below. Read it before you import.'))
          .toBeTruthy();
        expect(screen.queryByText(/\brows? \d|Find that description/i)).toBeNull();
      });

      it('says they, for several', () => {
        expandMarked(warning([shownAt(19, 2), shownAt(2, 19)]), 42);

        expect(screen.getByText('They are marked "Check description" in the list below. Read them before you import.'))
          .toBeTruthy();
      });

      it('goes back to the unmarked wording when one row has no position', () => {
        expandMarked(warning([shownAt(2, 2), { rowIndex: 13, length: 300, sectionMedianLength: 47, sentenceBreaks: 3 }]), 42);

        expect(screen.getByText(/Find those descriptions in the list and read them before you import\./)).toBeTruthy();
        expect(screen.queryByText(/in the list below/)).toBeNull();
      });

      it('goes back to the unmarked wording where no list is shown below the panel', () => {
        expandMarked(warning([shownAt(1, 5)]), null);

        expect(screen.getByText(/Find that description in the list and read it before you import\./)).toBeTruthy();
        expect(screen.queryByText(/in the list below/)).toBeNull();
      });
    });

    it('gives no count of the rows that could not be read', () => {
      // The screen's own count can be higher than the finding's: rows from a table that was not an
      // account are carried onto this one after the check has run. Two counts would disagree.
      expand(cases.find((c) => c.name === 'accounted for, with rows that could not be read')!.finding);

      expect(screen.queryByText(/\b2 rows?\b/)).toBeNull();
    });

    it('survives findings that arrive with no details at all', () => {
      const bare = (rule: string, outcome: VerificationFinding['outcome']): VerificationFinding =>
        ({ rule, outcome, details: null } as unknown as VerificationFinding);
      expand(bare(ROWS, 'VERIFIED'), bare(ROWS, 'WARNING'), bare(DESCRIPTIONS, 'VERIFIED'), bare(DESCRIPTIONS, 'WARNING'));

      expect(screen.getByText('Rows accounted for · verified')).toBeTruthy();
      expect(screen.getByText('Rows accounted for · needs review')).toBeTruthy();
      expect(screen.getByText(/^This check found no line outside the transaction table/)).toBeTruthy();
      expect(screen.getByText(/^Every transaction's description was checked\./)).toBeTruthy();
      expect(screen.queryByText(/undefined|NaN|null/)).toBeNull();
    });
  });
});
