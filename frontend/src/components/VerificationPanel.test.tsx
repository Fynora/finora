import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { VerificationPanel } from './VerificationPanel';
import { flaggedRows } from './verificationFlags';
import type { VerificationFinding, VerificationReport } from '../types';

/**
 * The panel's job is to be honest about what was and wasn't proven, so these tests are written
 * around the states that differ in MEANING -- not checked, checked and clean, checked and not --
 * rather than around rendering details.
 */
describe('VerificationPanel', () => {
  // reliabilityStatus null throughout this helper -- these tests exercise the LEGACY fallback
  // badge logic (allClear/notable), which only runs when the server never computed a status. The
  // server-computed badge has its own coverage below.
  const report = (findings: VerificationFinding[]): VerificationReport => ({
    findings, headerReconstructionUncertain: false, textSource: null, reliabilityStatus: null,
  });

  const verified: VerificationReport = report([{
    rule: 'BALANCE_CHAIN',
    outcome: 'VERIFIED',
    details: { rowsChecked: 127, rowsWithBalance: 127, anchoredOnOpeningBalance: true, discrepancies: [] },
  }]);

  const withFindings: VerificationReport = report([{
    rule: 'BALANCE_CHAIN',
    outcome: 'WARNING',
    details: {
      rowsChecked: 127, rowsWithBalance: 127, anchoredOnOpeningBalance: false,
      discrepancies: [
        { rowIndex: 16, expectedBalance: 54220, actualBalance: 54656, difference: 436 },
      ],
    },
  }]);

  it('renders nothing when verification was never performed', () => {
    // Null means not checked. A reassuring tick here would claim a check that never happened, and
    // an alarming one would report a failure that never occurred.
    const { container } = render(<VerificationPanel verification={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when there are no findings at all', () => {
    const { container } = render(<VerificationPanel verification={report([])} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('summarises a clean result without needing to be expanded', () => {
    render(<VerificationPanel verification={verified} />);

    expect(screen.getByText(/Running balance verified/)).toBeInTheDocument();
    // Collapsed by default: the per-row detail is noise on an import that reconciles.
    expect(screen.queryByText(/54,220/)).not.toBeInTheDocument();
  });

  it('reports how many findings there are, without judging the import overall', () => {
    render(<VerificationPanel verification={withFindings} />);

    expect(screen.getByText(/1 finding/)).toBeInTheDocument();
    // No document-level verdict -- the backend deliberately has no aggregator, and inventing one
    // here would be a second source of truth that could disagree with the findings.
    expect(screen.queryByText(/^Failed$/)).not.toBeInTheDocument();
    expect(screen.queryByText(/^Verified$/)).not.toBeInTheDocument();
  });

  it('reveals the offending row only once expanded', async () => {
    const user = userEvent.setup();
    render(<VerificationPanel verification={withFindings} />);

    await user.click(screen.getByRole('button', { name: /Statement verification/ }));

    expect(screen.getByText(/54,220\.00/)).toBeInTheDocument();
    expect(screen.getByText(/54,656\.00/)).toBeInTheDocument();
  });

  // A finding's rowIndex has counted lists the customer never sees (the document's own sequence,
  // the balance walk along it). Only displayIndex says where a row is in the list on screen, and
  // only a report that carries it may have a row number printed from it.
  describe('which row the balance check points at', () => {
    const chain = (discrepancies: Record<string, number>[]): VerificationReport => report([{
      rule: 'BALANCE_CHAIN',
      outcome: 'WARNING',
      details: { rowsChecked: 40, rowsWithBalance: 40, anchoredOnOpeningBalance: true, discrepancies },
    }]);
    const amounts = { expectedBalance: 54220, actualBalance: 54656, difference: 436 };

    const expand = async (verification: VerificationReport, rowCount: number | null) => {
      const user = userEvent.setup();
      const view = render(rowCount === null
        ? <VerificationPanel verification={verification} />
        : <VerificationPanel verification={verification} flagged={flaggedRows(verification, rowCount)} />);
      await user.click(screen.getByRole('button', { name: /Statement verification/ }));
      return view;
    };

    it('prints the row number the list shows beside that transaction', async () => {
      // rowIndex and displayIndex differ here on purpose: the number has to come from the second.
      await expand(chain([{ rowIndex: 2, displayIndex: 4, ...amounts }]), 40);

      expect(screen.getByRole('columnheader', { name: 'Row' })).toBeInTheDocument();
      expect(screen.getByRole('cell', { name: '5' })).toBeInTheDocument();
      expect(screen.queryByRole('cell', { name: '3' })).not.toBeInTheDocument();
      expect(screen.getByText('That row is marked "Balance didn\'t match" in the list below.')).toBeInTheDocument();
    });

    it('says rows, for several', async () => {
      await expand(chain([{ rowIndex: 4, displayIndex: 4, ...amounts }, { rowIndex: 9, displayIndex: 9, ...amounts }]), 40);

      expect(screen.getByRole('cell', { name: '5' })).toBeInTheDocument();
      expect(screen.getByRole('cell', { name: '10' })).toBeInTheDocument();
      expect(screen.getByText('Those rows are marked "Balance didn\'t match" in the list below.')).toBeInTheDocument();
    });

    it('prints no row number for a report staged before the position was sent', async () => {
      // The same statement as stored by an earlier version: rowIndex only. It may name a
      // neighbouring row, so the balances are shown and the number is not.
      const { container } = await expand(chain([{ rowIndex: 16, ...amounts }]), 40);

      expect(screen.getByText(/54,220\.00/)).toBeInTheDocument();
      expect(screen.queryByRole('columnheader', { name: 'Row' })).not.toBeInTheDocument();
      expect(container.textContent).not.toMatch(/\b1[67]\b|marked/);
    });

    it('prints no row number when one of several rows has no position', async () => {
      const { container } = await expand(
        chain([{ rowIndex: 4, displayIndex: 4, ...amounts }, { rowIndex: 9, ...amounts }]), 40);

      expect(screen.queryByRole('columnheader', { name: 'Row' })).not.toBeInTheDocument();
      expect(container.textContent).not.toMatch(/marked/);
    });

    it('prints no row number for a position past the end of the list', async () => {
      const { container } = await expand(chain([{ rowIndex: 16, displayIndex: 16, ...amounts }]), 10);

      expect(screen.queryByRole('columnheader', { name: 'Row' })).not.toBeInTheDocument();
      expect(container.textContent).not.toMatch(/\b17\b|marked/);
    });

    it('prints no row number where no list is shown beside the panel', async () => {
      const { container } = await expand(chain([{ rowIndex: 4, displayIndex: 4, ...amounts }]), null);

      expect(screen.queryByRole('columnheader', { name: 'Row' })).not.toBeInTheDocument();
      expect(container.textContent).not.toMatch(/marked/);
    });
  });

  it('collapses again on a second click', async () => {
    const user = userEvent.setup();
    render(<VerificationPanel verification={withFindings} />);
    const toggle = screen.getByRole('button', { name: /Statement verification/ });

    await user.click(toggle);
    expect(screen.getByText(/54,220\.00/)).toBeInTheDocument();

    await user.click(toggle);
    expect(screen.queryByText(/54,220\.00/)).not.toBeInTheDocument();
  });

  it('says plainly when the first row could not be covered', async () => {
    // A real limit on the evidence, not implementation detail: without an opening balance the
    // first transaction has nothing before it to check against, and saying "verified" flatly
    // would overstate what was proven.
    const user = userEvent.setup();
    render(<VerificationPanel verification={withFindings} />);

    await user.click(screen.getByRole('button', { name: /Statement verification/ }));

    expect(screen.getByText(/first transaction could not be checked/)).toBeInTheDocument();
  });

  it('names an unknown rule instead of hiding it or dumping raw data', async () => {
    // A newer backend reporting a check this build has no renderer for. Deliberately a rule that
    // does not exist yet. This test has now been rewritten twice, first off STATEMENT_TOTALS and
    // then off COLUMN_AMBIGUITY, each time because the rule it named stopped being unknown when
    // that validator shipped -- which is the behaviour under test working, not a flaky test.
    const user = userEvent.setup();
    render(<VerificationPanel verification={report(
      [{ rule: 'CURRENCY_CONSISTENCY', outcome: 'FAILED', details: { row: 17 } }],
    )} />);

    await user.click(screen.getByRole('button', { name: /Statement verification/ }));

    expect(screen.getByText(/CURRENCY_CONSISTENCY/)).toBeInTheDocument();
    expect(screen.getByText(/doesn't know how to display yet/)).toBeInTheDocument();
    expect(screen.queryByText(/\{/)).not.toBeInTheDocument();
  });

  it('reports a not-applicable check as unchecked rather than as a pass', async () => {
    render(<VerificationPanel verification={report([{
      rule: 'BALANCE_CHAIN', outcome: 'NOT_APPLICABLE',
      details: { rowsChecked: 0, rowsWithBalance: 0, anchoredOnOpeningBalance: false, discrepancies: [] },
    }])} />);

    expect(screen.getByText(/Couldn't be checked/)).toBeInTheDocument();
    expect(screen.queryByText(/verified/i)).not.toBeInTheDocument();
  });
  it("shows the bank's own totals beside ours, and only what the bank printed", async () => {
    // The value of this rule is that its evidence is external, so the panel shows both sides. Only
    // the fields the statement actually printed appear -- here it gave counts but no totals, and a
    // row of dashes for the totals would read as a comparison that was made and passed.
    const user = userEvent.setup();
    render(<VerificationPanel verification={report([{
      rule: 'SUMMARY_TOTALS', outcome: 'FAILED',
      details: {
        printedCreditCount: 1, parsedCreditCount: 0,
        printedDebitCount: 3, parsedDebitCount: 4,
        suspectedCause: 'DIRECTION',
        explanation: 'At least one is being read as money moving the wrong way.',
      },
    }])} />);

    await user.click(screen.getByRole('button', { name: /Statement verification/ }));

    expect(screen.getByText(/The bank's own totals/)).toBeInTheDocument();
    expect(screen.getByText(/wrong way/)).toBeInTheDocument();
    expect(screen.queryByText(/Money in/)).not.toBeInTheDocument();
  });

  it('shows the server-computed status badge, not the client-side fallback, when one is present', () => {
    // findings are all VERIFIED here -- if the panel were still computing its own badge, this
    // would render "Running balance verified" too, and the test wouldn't distinguish the two
    // code paths. NEEDS_ATTENTION with clean findings only happens via header-reconstruction/OCR
    // provenance, which is exactly the case a client-side findings-only heuristic cannot see.
    render(<VerificationPanel verification={{
      findings: [{
        rule: 'BALANCE_CHAIN', outcome: 'VERIFIED',
        details: { rowsChecked: 1, rowsWithBalance: 1, anchoredOnOpeningBalance: true, discrepancies: [] },
      }],
      headerReconstructionUncertain: true, textSource: 'NATIVE_PDF', reliabilityStatus: 'NEEDS_ATTENTION',
    }} />);

    expect(screen.getByText(/Import needs attention/)).toBeInTheDocument();
    expect(screen.queryByText(/Running balance verified/)).not.toBeInTheDocument();
  });

  it('explains an OCR-driven review status with a visible reason, not just the badge', async () => {
    const user = userEvent.setup();
    render(<VerificationPanel verification={{
      findings: [{
        rule: 'BALANCE_CHAIN', outcome: 'VERIFIED',
        details: { rowsChecked: 1, rowsWithBalance: 1, anchoredOnOpeningBalance: true, discrepancies: [] },
      }],
      headerReconstructionUncertain: false, textSource: 'OCR', reliabilityStatus: 'REVIEW_RECOMMENDED',
    }} />);

    expect(screen.getByText(/Imported with notes/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Statement verification/ }));
    // Without this line, an OCR-only REVIEW_RECOMMENDED would show a badge with every finding
    // below it VERIFIED -- inexplicable, since OCR provenance isn't a finding at all.
    expect(screen.getByText(/read using OCR/)).toBeInTheDocument();
  });

  // A partially unreadable file is the one finding that must not hide behind a click: rows may be
  // missing from what the user is about to confirm. 2026-09-20: a file damaged part-way imported 1 of
  // 6 rows as "Imported successfully".
  describe('content integrity (a damaged file)', () => {
    const damaged = (details: Record<string, unknown> = { corruptContentStreams: 1, damagedPages: [2] }): VerificationReport => ({
      findings: [{ rule: 'CONTENT_INTEGRITY', outcome: 'FAILED', details }],
      headerReconstructionUncertain: false, textSource: 'NATIVE_PDF', reliabilityStatus: 'NEEDS_ATTENTION',
    });

    it('opens by itself, so the warning is read without a click', () => {
      render(<VerificationPanel verification={damaged()} />);

      expect(screen.getByText(/could not be read/i)).toBeInTheDocument();
    });

    it('says which page and what to do, in plain words', () => {
      render(<VerificationPanel verification={damaged()} />);

      expect(screen.getByText('File integrity')).toBeInTheDocument();
      expect(screen.getByText(/some transactions may be missing/i)).toBeInTheDocument();
      expect(screen.getByText(/Download the statement again from your bank/i)).toBeInTheDocument();
      expect(screen.getByText(/page 2/i)).toBeInTheDocument();
    });

    it('names several pages', () => {
      render(<VerificationPanel verification={damaged({ corruptContentStreams: 2, damagedPages: [1, 3] })} />);

      expect(screen.getByText(/pages 1, 3/i)).toBeInTheDocument();
    });

    it('leaves every other report collapsed', () => {
      render(<VerificationPanel verification={{ ...withFindings, reliabilityStatus: 'NEEDS_ATTENTION' }} />);

      expect(screen.queryByText(/Row 17/)).not.toBeInTheDocument();
    });
  });

  // Both card checks reach every import, and until they had renderers each showed its rule id over
  // "doesn't know how to display yet". The fixtures carry the details each backend validator sends
  // for that outcome, the server's own sentences included, so a test can prove those stay unseen.
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
      name: string; finding: VerificationFinding; label: string; tag: RegExp; body: RegExp; note?: RegExp;
    }

    const cases: Case[] = [
      {
        name: 'summary adds up to the amount due',
        finding: { rule: SUMMARY, outcome: 'VERIFIED', details: amountDue },
        label: 'Card statement summary', tag: /· verified/,
        body: /figures in this statement's summary add up to its total amount due\.$/,
      },
      {
        name: 'summary adds up to an amount due printed in whole rupees',
        finding: {
          rule: SUMMARY, outcome: 'VERIFIED',
          details: { ...amountDue, totalAmountDue: 8500, difference: -0.5, totalAmountDueRoundedToRupee: true },
        },
        label: 'Card statement summary', tag: /· verified/,
        body: /add up to its total amount due\. The statement prints that total in whole rupees/,
      },
      {
        name: 'summary adds up to the outstanding balance',
        finding: { rule: SUMMARY, outcome: 'VERIFIED', details: outstanding },
        label: 'Card statement summary', tag: /· verified/,
        body: /figures in this statement's summary add up to its total outstanding\.$/,
      },
      {
        name: 'summary adds up to an outstanding balance printed in whole rupees',
        finding: {
          rule: SUMMARY, outcome: 'VERIFIED',
          details: { ...outstanding, totalOutstanding: 34590, difference: -0.4, totalRoundedToRupee: true },
        },
        label: 'Card statement summary', tag: /· verified/,
        body: /add up to its total outstanding\. The statement prints that total in whole rupees/,
      },
      {
        name: 'summary does not add up to the amount due',
        finding: {
          rule: SUMMARY, outcome: 'WARNING',
          details: { ...amountDue, totalAmountDue: 9100.5, difference: 600, explanation: summaryMisread },
        },
        label: 'Card statement summary', tag: /· needs review/,
        body: /figures read from this statement's summary do not add up to its total amount due/,
        note: /one of the summary figures was read incorrectly.*not at your transactions/,
      },
      {
        name: 'summary does not add up to the outstanding balance',
        finding: {
          rule: SUMMARY, outcome: 'WARNING',
          details: { ...outstanding, totalOutstanding: 35000, difference: 409.6, explanation: summaryMisread },
        },
        label: 'Card statement summary', tag: /· needs review/,
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
        label: 'Card statement summary', tag: /· needs review/,
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
        label: 'Card statement summary', tag: /· not applicable/,
        body: /No card summary with a previous balance, purchases, payments and a total was found/,
      },
      {
        name: 'transactions add up to the summary',
        finding: { rule: FLOW, outcome: 'VERIFIED', details: flowAmounts },
        label: 'Card purchases and payments', tag: /· verified/,
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
        label: 'Card purchases and payments', tag: /· verified/,
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
        label: 'Card purchases and payments', tag: /· verified/,
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
        label: 'Card purchases and payments', tag: /· verified/,
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
        label: 'Card purchases and payments', tag: /· needs review/,
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
        label: 'Card purchases and payments', tag: /· needs review/,
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
        label: 'Card purchases and payments', tag: /· needs review/,
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
        label: 'Card purchases and payments', tag: /· not applicable/,
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
        label: 'Card purchases and payments', tag: /· not applicable/,
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
        label: 'Card purchases and payments', tag: /· not applicable/,
        body: /no transaction on it was read as a purchase or a payment/,
      },
      {
        name: 'a reason for not checking that this build has not heard of',
        finding: { rule: FLOW, outcome: 'NOT_APPLICABLE', details: { evidenceLevel: 'SOME_FUTURE_LEVEL' } },
        label: 'Card purchases and payments', tag: /· not applicable/,
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
        label: 'Card purchases and payments', tag: /· needs review/,
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
        label: 'Card purchases and payments', tag: /· verified/,
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
        label: 'Card statement summary', tag: /· not applicable/,
        body: /^The figures in this statement's summary do not add up to its total amount due, because its payments and credits leave out a credit, such as a refund, that the total includes\. The transactions read from it account for that credit exactly, so nothing was misread\.$/,
      },
      {
        // Set aside for a reason this build does not know, with the summary's figures still on it.
        name: 'a summary that was read, set aside for an unknown reason',
        finding: { rule: SUMMARY, outcome: 'NOT_APPLICABLE', details: { ...amountDue, reason: 'SOME_FUTURE_LEVEL: x' } },
        label: 'Card statement summary', tag: /· not applicable/,
        body: /^This statement's summary was read, but this check could not be completed on it\.$/,
      },
      // Neither validator reports FAILED today. One that did would still mean a disagreement, so
      // it must not fall through to "could not be run".
      {
        name: 'a failed summary check from a later backend',
        finding: { rule: SUMMARY, outcome: 'FAILED', details: { ...amountDue, totalAmountDue: 9100.5, difference: 600 } },
        label: 'Card statement summary', tag: /· did not reconcile/,
        body: /figures read from this statement's summary do not add up to its total amount due/,
        note: /not at your transactions/,
      },
      {
        name: 'a failed transactions check from a later backend',
        finding: {
          rule: FLOW, outcome: 'FAILED',
          details: { ...flowAmounts, observedExpenseAmount: 7300.5, differenceExpenseAmount: -1200 },
        },
        label: 'Card purchases and payments', tag: /· did not reconcile/,
        body: /do not add up to the purchases and payments totals in its summary/,
        note: /cannot tell which side is off/,
      },
    ];

    const expand = async (finding: VerificationFinding) => {
      const user = userEvent.setup();
      const view = render(<VerificationPanel verification={report([finding])} />);
      await user.click(screen.getByRole('button', { name: /Statement verification/ }));
      return view;
    };

    it.each(cases)('$name', async ({ finding, label, tag, body, note }) => {
      const { container } = await expand(finding);

      expect(screen.getByText(label)).toBeInTheDocument();
      expect(screen.getByText(tag)).toBeInTheDocument();
      expect(screen.getByText(body)).toBeInTheDocument();
      if (note) expect(screen.getByText(note)).toBeInTheDocument();

      // Nothing of the machinery: not the rule id, not the fallback line, not a details key or
      // value, and not the server's own sentence about panels and extraction.
      expect(container.textContent).not.toMatch(
        /CREDIT_CARD|doesn't know how to display|billing-summary|GRID|INLINE_LABEL|extract|evidenceLevel|_ONLY|NO_SUMMARY|NO_CLASSIFIED|FULL_SUMMARY|FUTURE_LEVEL|undefined|NaN/,
      );
    });

    it('never tells someone a statement with one side unproven can go ahead', async () => {
      // The owner's rule (2026-10-11): an import goes ahead only once its amounts are confirmed,
      // by a check or by a person. A softer label here would say the opposite.
      const unproven = cases.find((c) => c.name.startsWith('payments match but purchases are over, with instalments'))!;
      const { container } = await expand(unproven.finding);

      expect(container.textContent).not.toMatch(/not applicable|not fully checked|verified|go ahead/i);
    });

    it('shows the summary figures as the sum they were checked as', async () => {
      await expand({ rule: SUMMARY, outcome: 'VERIFIED', details: outstanding });

      // Fees are printed, cash advances are not: a zero term is left out of the sum.
      expect(screen.getByText(
        /Previous balance ₹40,000\.00 \+ purchases ₹9,000\.40 \+ fees ₹590\.00 − payments and credits ₹15,000\.00 = ₹34,590\.40, against a printed total outstanding of ₹34,590\.40\./,
      )).toBeInTheDocument();
    });

    it('shows no sum when the summary gave no figures to add', async () => {
      const { container } = await expand({ rule: SUMMARY, outcome: 'NOT_APPLICABLE', details: {} });

      expect(container.textContent).not.toMatch(/Previous balance|₹|—/);
    });

    it('puts the statement and the import side by side when they disagree', async () => {
      const disagree = cases.find((c) => c.name === 'transactions do not add up to the summary')!;
      await expand(disagree.finding);

      const purchases = screen.getByText('Purchases').closest('tr')!;
      expect(purchases).toHaveTextContent('₹8,500.50');
      expect(purchases).toHaveTextContent('₹7,300.50');
      // Only the side that disagrees is marked.
      expect(screen.getByText('₹7,300.50')).toHaveClass('text-warning');
      const payments = screen.getByText('Payments and credits').closest('tr')!;
      expect(payments.querySelector('.text-warning')).toBeNull();
    });

    it('names the statement figure for what it is once fees are counted in', async () => {
      const unproven = cases.find((c) => c.name.startsWith('payments match but purchases are over, with instalments'))!;
      await expand(unproven.finding);

      // Fees and cash advances are in the statement's figure by now, so the row says so.
      const charges = screen.getByText('Purchases and charges').closest('tr')!;
      expect(charges).toHaveTextContent('₹8,500.50');
      expect(charges).toHaveTextContent('₹11,750.50');
      expect(screen.getByText('₹11,750.50')).toHaveClass('text-warning');
      expect(screen.queryByText('Purchases')).not.toBeInTheDocument();
    });

    it.each([
      'transactions add up once refunds and fees are allowed for',
      'transactions add up once one instalment is set aside',
      'transactions verified against the balances, the summary leaving a credit out',
    ])('leaves the figures out when %s', async (name) => {
      // The two sides differ on purpose here, and unequal figures under "verified" read as a mistake.
      const { container } = await expand(cases.find((c) => c.name === name)!.finding);

      expect(container.querySelector('table')).toBeNull();
    });

    it('survives a finding that arrives with no details at all', async () => {
      const bare = (rule: string): VerificationFinding =>
        ({ rule, outcome: 'VERIFIED', details: null } as unknown as VerificationFinding);
      const user = userEvent.setup();
      render(<VerificationPanel verification={report([bare(SUMMARY), bare(FLOW)])} />);
      await user.click(screen.getByRole('button', { name: /Statement verification/ }));

      expect(screen.getByText('Card statement summary')).toBeInTheDocument();
      expect(screen.getByText('Card purchases and payments')).toBeInTheDocument();
    });
  });

  // The other two checks every report carries that had no renderer. Fixtures carry what
  // RowAccountingValidator and DescriptionCorruptionValidator send, their own sentences included.
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
      name: string; finding: VerificationFinding; label: string; tag: RegExp; body: RegExp; note?: RegExp;
    }

    const cases: Case[] = [
      {
        name: 'every row accounted for',
        finding: { rule: ROWS, outcome: 'VERIFIED', details: { ...counts, explanation: allAccounted } },
        label: 'Rows accounted for', tag: /· verified/,
        body: /^42 transactions were read\. This check found no line outside the transaction table that looks like a missed transaction\.$/,
      },
      {
        name: 'a statement of one transaction',
        finding: {
          rule: ROWS, outcome: 'VERIFIED',
          details: { ...counts, stagedTransactionCount: 1, locatedRowCount: 1, explanation: allAccounted },
        },
        label: 'Rows accounted for', tag: /· verified/,
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
        label: 'Rows accounted for', tag: /· verified/,
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
        label: 'Rows accounted for', tag: /· needs review/,
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
        label: 'Rows accounted for', tag: /· not applicable/,
        body: /^No transaction table was found, so there were no rows to account for\.$/,
      },
      {
        name: 'a failed row check from a later backend',
        finding: { rule: ROWS, outcome: 'FAILED', details: { ...counts, droppedTransactionCandidateCount: 1 } },
        label: 'Rows accounted for', tag: /· did not reconcile/,
        body: /At least one line outside the transaction table/,
        note: /Compare this import with your statement/,
      },
      {
        name: 'descriptions all look like descriptions',
        finding: { rule: DESCRIPTIONS, outcome: 'VERIFIED', details: { rowsChecked: 42, sectionMedianLength: 47 } },
        label: 'Transaction descriptions', tag: /· verified/,
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
        label: 'Transaction descriptions', tag: /· not applicable/,
        body: /^There are too few transactions here to tell what a normal description looks like, so this check was skipped\.$/,
      },
      {
        name: 'one description picked up extra text',
        finding: {
          rule: DESCRIPTIONS, outcome: 'WARNING',
          details: { rowsChecked: 42, sectionMedianLength: 47, outliers: [outlier(13)], explanation: absorbed },
        },
        label: 'Transaction descriptions', tag: /· needs review/,
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
        label: 'Transaction descriptions', tag: /· needs review/,
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
        label: 'Transaction descriptions', tag: /· needs review/,
        body: /^2 transactions have descriptions much longer than the others here, and they read like more than one sentence\. They may have picked up text that is not part of the transaction, such as a page footer\.$/,
        note: /Find those descriptions in the list and read them before you import\./,
      },
      {
        name: 'a description warning that lists no transaction',
        finding: { rule: DESCRIPTIONS, outcome: 'WARNING', details: { rowsChecked: 42, sectionMedianLength: 47 } },
        label: 'Transaction descriptions', tag: /· needs review/,
        body: /^At least one transaction has a description much longer than the others here/,
        note: /Find the longest descriptions in the list and read them before you import\./,
      },
    ];

    const expand = async (...findings: VerificationFinding[]) => {
      const user = userEvent.setup();
      const view = render(<VerificationPanel verification={report(findings)} />);
      await user.click(screen.getByRole('button', { name: /Statement verification/ }));
      return view;
    };

    it.each(cases)('$name', async ({ finding, label, tag, body, note }) => {
      const { container } = await expand(finding);

      expect(screen.getByText(label)).toBeInTheDocument();
      expect(screen.getByText(tag)).toBeInTheDocument();
      expect(screen.getByText(body)).toBeInTheDocument();
      if (note) expect(screen.getByText(note)).toBeInTheDocument();

      // Nothing of the machinery: not the rule id, not the fallback line, not a details key or
      // a reason code, and not the server's own sentence about parsers and candidates.
      expect(container.textContent).not.toMatch(
        /ROW_ACCOUNTING|DESCRIPTION_CORRUPTION|doesn't know how to display|candidate|parser|accounted-for|non-blank|sample|narration|PRE_HEADER|PAGE_LEGEND|sectionMedian|undefined|NaN/,
      );
    });

    it('never names a transaction by its rowIndex', async () => {
      // rowIndex counts rows in the order the document printed them, and a PDF's review list is
      // sorted by date afterwards. On a statement printed newest-first it names a different
      // transaction, so a report that carries nothing else names none -- here rowIndex 13, row 14.
      const one = cases.find((c) => c.name === 'one description picked up extra text')!;
      const user = userEvent.setup();
      const verification = report([one.finding]);
      const { container } = render(
        <VerificationPanel verification={verification} flagged={flaggedRows(verification, 42)} />);
      await user.click(screen.getByRole('button', { name: /Statement verification/ }));

      expect(container.textContent).not.toMatch(/\brows? \d|\b1[34]\b|marked/i);
      expect(screen.getByText(one.note!)).toBeInTheDocument();
    });

    describe('when the report says where the rows are shown', () => {
      const shownAt = (displayIndex: number, rowIndex: number, length = 412) =>
        ({ rowIndex, displayIndex, length, sectionMedianLength: 47, sentenceBreaks: 3 });
      const warning = (outliers: Record<string, number>[]): VerificationFinding => ({
        rule: DESCRIPTIONS, outcome: 'WARNING', details: { rowsChecked: 42, sectionMedianLength: 47, outliers },
      });

      const expandMarked = async (finding: VerificationFinding, rowCount: number | null) => {
        const user = userEvent.setup();
        const verification = report([finding]);
        const view = render(rowCount === null
          ? <VerificationPanel verification={verification} />
          : <VerificationPanel verification={verification} flagged={flaggedRows(verification, rowCount)} />);
        await user.click(screen.getByRole('button', { name: /Statement verification/ }));
        return view;
      };

      it('points at the mark and names the row by its place in the list shown', async () => {
        // Printed sixth in the document (rowIndex 5), shown second (displayIndex 1).
        const { container } = await expandMarked(warning([shownAt(1, 5)]), 42);

        expect(screen.getByText(/^It is marked "Check description" in the list below\. Read it before you import\.$/))
          .toBeInTheDocument();
        expect(screen.getByText('It is row 2.')).toHaveClass('text-warning');
        expect(container.textContent).not.toMatch(/row 6|Find that description/i);
      });

      it('names several rows top to bottom', async () => {
        await expandMarked(warning([shownAt(19, 2), shownAt(2, 19), shownAt(13, 13)]), 42);

        expect(screen.getByText(/^They are marked "Check description" in the list below\. Read them before you import\.$/))
          .toBeInTheDocument();
        expect(screen.getByText('They are rows 3, 14 and 20.')).toBeInTheDocument();
      });

      it('names two rows', async () => {
        await expandMarked(warning([shownAt(2, 2), shownAt(13, 13)]), 42);

        expect(screen.getByText('They are rows 3 and 14.')).toBeInTheDocument();
      });

      it('goes back to the unmarked wording when one row has no position', async () => {
        const { container } = await expandMarked(
          warning([shownAt(2, 2), { rowIndex: 13, length: 300, sectionMedianLength: 47, sentenceBreaks: 3 }]), 42);

        expect(screen.getByText(/Find those descriptions in the list and read them before you import\./)).toBeInTheDocument();
        expect(container.textContent).not.toMatch(/\brows? \d|marked/i);
      });

      it('goes back to the unmarked wording where no list is shown beside the panel', async () => {
        const { container } = await expandMarked(warning([shownAt(1, 5)]), null);

        expect(screen.getByText(/Find that description in the list and read it before you import\./)).toBeInTheDocument();
        expect(container.textContent).not.toMatch(/\brows? \d|marked/i);
      });
    });

    it('gives no count of the rows that could not be read', async () => {
      // The screen's own count can be higher than the finding's: rows from a table that was not an
      // account are carried onto this one after the check has run. Two counts would disagree.
      const unread = cases.find((c) => c.name === 'accounted for, with rows that could not be read')!;
      const { container } = await expand(unread.finding);

      expect(container.textContent).not.toMatch(/\b2 rows?\b/);
    });

    it('marks what to do in the warning colour, and only on a warning', async () => {
      const left = cases.find((c) => c.name === 'a line that looks like a transaction was left out')!;
      const first = await expand(left.finding);
      expect(screen.getByText(left.note!)).toHaveClass('text-warning');
      first.unmount();

      const { container } = await expand(cases[0].finding);
      expect(container.querySelector('.text-warning')).toBeNull();
    });

    it('survives findings that arrive with no details at all', async () => {
      const bare = (rule: string, outcome: VerificationFinding['outcome']): VerificationFinding =>
        ({ rule, outcome, details: null } as unknown as VerificationFinding);
      const { container } = await expand(
        bare(ROWS, 'VERIFIED'), bare(ROWS, 'WARNING'), bare(DESCRIPTIONS, 'VERIFIED'), bare(DESCRIPTIONS, 'WARNING'),
      );

      expect(screen.getAllByText('Rows accounted for')).toHaveLength(2);
      expect(screen.getAllByText('Transaction descriptions')).toHaveLength(2);
      expect(screen.getByText(/^This check found no line outside the transaction table/)).toBeInTheDocument();
      expect(screen.getByText(/^Every transaction's description was checked\./)).toBeInTheDocument();
      expect(container.textContent).not.toMatch(/undefined|NaN|null/);
    });
  });
});
