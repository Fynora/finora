package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CreditCardFlowReconciliationValidatorTest {

    private final CreditCardFlowReconciliationValidator validator = new CreditCardFlowReconciliationValidator();

    private static StagedRow row(String description, String amount, String type) {
        return new StagedRow(
                LocalDate.of(2026, 7, 10), description, new BigDecimal(amount), type,
                "Other", "default", null, false, null, null);
    }

    // extractionMethod and conflictingFields are irrelevant to this validator -- it only ever
    // reads purchases()/paymentsAndCredits(), never which strategy produced them or whether the
    // two strategies disagreed on some OTHER field. GRID/empty-list used as arbitrary valid values,
    // matching CreditCardStatementTotalsValidatorTest's own summary() helper.
    private static CreditCardSummaryEvidence summary(String purchases, String paymentsAndCredits) {
        return new CreditCardSummaryEvidence(
                new BigDecimal("10000"), purchases == null ? null : new BigDecimal(purchases),
                null, null, paymentsAndCredits == null ? null : new BigDecimal(paymentsAndCredits),
                new BigDecimal("13100"), CreditCardSummaryEvidence.ExtractionMethod.GRID, List.of());
    }

    @Test
    void verifiesWhenExtractedTransactionsReconcileWithTheBillingSummary() {
        // Matches the real shape confirmed on AU's own statement: sum(EXPENSE) == purchases and
        // sum(INCOME) == paymentsAndCredits, exactly.
        var rows = List.of(
                row("GROCERY STORE", "3000", "EXPENSE"),
                row("FUEL STATION", "2000", "EXPENSE"),
                row("PAYMENT RECEIVED", "4000", "INCOME"));

        var finding = validator.check(rows, summary("5000", "4000"));

        assertThat(finding.rule()).isEqualTo("CREDIT_CARD_FLOW_RECONCILIATION");
        assertThat(finding.outcome()).isEqualTo("VERIFIED");
        assertThat(finding.details().get("evidenceLevel"))
                .isEqualTo("FULL_SUMMARY_RECONCILIATION");
    }

    @Test
    void warnsWhenTheAggregatesDisagreeWithTheBillingSummary() {
        // sum(EXPENSE) = 3000, but the statement's own printed purchases figure is 5000 -- a row
        // that should have been classified EXPENSE was likely misclassified as INCOME (or vice
        // versa), but this validator cannot say which.
        var rows = List.of(
                row("GROCERY STORE", "3000", "EXPENSE"),
                row("PAYMENT RECEIVED", "4000", "INCOME"));

        var finding = validator.check(rows, summary("5000", "4000"));

        assertThat(finding.outcome()).isEqualTo("WARNING");
        assertThat(finding.details().get("observedExpenseAmount"))
                .isEqualTo(new BigDecimal("3000"));
        assertThat(finding.details().get("expectedExpenseAmount"))
                .isEqualTo(new BigDecimal("5000"));
        assertThat(finding.details().get("differenceExpenseAmount"))
                .isEqualTo(new BigDecimal("-2000"));
        assertThat(finding.details().get("differenceIncomeAmount"))
                .isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void reportsFactualAmountEvidenceEvenWhenTheReconciliationSucceeds() {
        // The purpose is debugging real customer documents, not just explaining a WARNING -- the
        // amounts belong in details() on the VERIFIED path too.
        var rows = List.of(
                row("GROCERY STORE", "5000", "EXPENSE"),
                row("PAYMENT RECEIVED", "4000", "INCOME"));

        var finding = validator.check(rows, summary("5000", "4000"));

        assertThat(finding.details().get("expectedExpenseAmount")).isEqualTo(new BigDecimal("5000"));
        assertThat(finding.details().get("observedExpenseAmount")).isEqualTo(new BigDecimal("5000"));
        assertThat(finding.details().get("differenceExpenseAmount")).isEqualTo(BigDecimal.ZERO);
        assertThat(finding.details().get("expectedIncomeAmount")).isEqualTo(new BigDecimal("4000"));
        assertThat(finding.details().get("observedIncomeAmount")).isEqualTo(new BigDecimal("4000"));
        assertThat(finding.details().get("differenceIncomeAmount")).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void neverAttributesTheMismatchToEitherSideOfTheDisagreement() {
        var rows = List.of(row("GROCERY STORE", "3000", "EXPENSE"));

        var finding = validator.check(rows, summary("5000", "0"));

        String explanation = ((String) finding.details().get("explanation")).toLowerCase();
        assertThat(explanation)
                .as("a mismatch here means the aggregates disagree, not that a specific side is "
                        + "known to be wrong")
                .doesNotContain("wrong debit").doesNotContain("wrong credit")
                .doesNotContain("wrong debit/credit detected");
    }

    @Test
    void reportsNoSummaryWhenNoBillingPanelEvidenceWasExtractedAtAll() {
        var rows = List.of(row("GROCERY STORE", "3000", "EXPENSE"));

        var finding = validator.check(rows, CreditCardSummaryEvidence.NONE);

        assertThat(finding.outcome()).isEqualTo("NOT_APPLICABLE");
        assertThat(finding.details().get("evidenceLevel")).isEqualTo("NO_SUMMARY");
    }

    @Test
    void reportsNoSummaryWhenSummaryIsNull() {
        var finding = validator.check(List.of(), null);

        assertThat(finding.outcome()).isEqualTo("NOT_APPLICABLE");
        assertThat(finding.details().get("evidenceLevel")).isEqualTo("NO_SUMMARY");
    }

    @Test
    void reportsPartialSummaryOnlyWhenThePanelIsReadableButMissesOneOfTheTwoFields() {
        // The real shape found on Axis's statement: a readable billing-summary panel that prints
        // a headline total but not a purchases/payments breakdown -- distinct from HDFC's
        // completely absent panel (NO_SUMMARY), even though both currently produce NOT_APPLICABLE.
        var rows = List.of(row("GROCERY STORE", "3000", "EXPENSE"));

        var finding = validator.check(rows, summary("5000", null));

        assertThat(finding.outcome()).isEqualTo("NOT_APPLICABLE");
        assertThat(finding.details().get("evidenceLevel")).isEqualTo("PARTIAL_SUMMARY_ONLY");
    }

    @Test
    void distinguishesPartialSummaryFromNoSummaryInTheReasonText() {
        var partial = validator.check(List.of(), summary(null, "4000"));
        var none = validator.check(List.of(), CreditCardSummaryEvidence.NONE);

        assertThat((String) partial.details().get("reason"))
                .isNotEqualTo(none.details().get("reason"));
    }

    @Test
    void reportsNoClassifiedTransactionsWhenTheSummaryIsFullButNoRowWasClassified() {
        // The scenario this state exists for: the billing-summary panel is completely readable
        // (both purchases and payments/credits printed), but zero rows extracted -- a different
        // product problem than "the bank doesn't print a summary" (NO_SUMMARY) and different again
        // from "the summary is only partial" (PARTIAL_SUMMARY_ONLY): here the transaction table
        // itself failed to extract or classify anything.
        var finding = validator.check(List.of(), summary("5000", "2000"));

        assertThat(finding.outcome()).isEqualTo("NOT_APPLICABLE");
        assertThat(finding.details().get("evidenceLevel")).isEqualTo("NO_CLASSIFIED_TRANSACTIONS");
    }

    @Test
    void noClassifiedTransactionsAlsoFiresWhenRowsExistButNoneAreExpenseOrIncome() {
        // "Zero rows" and "rows exist but none are classified" both mean there is nothing to sum on
        // either side -- both must resolve to the same evidence level.
        var rows = List.of(row("UNCLASSIFIED ROW", "999", "TRANSFER"));

        var finding = validator.check(rows, summary("5000", "2000"));

        assertThat(finding.outcome()).isEqualTo("NOT_APPLICABLE");
        assertThat(finding.details().get("evidenceLevel")).isEqualTo("NO_CLASSIFIED_TRANSACTIONS");
    }

    @Test
    void allThreeNotApplicableReasonsAreDistinctText() {
        var noSummary = validator.check(List.of(), CreditCardSummaryEvidence.NONE);
        var partialSummary = validator.check(List.of(), summary(null, "4000"));
        var noClassifiedTransactions = validator.check(List.of(), summary("5000", "2000"));

        assertThat(List.of(
                noSummary.details().get("reason"),
                partialSummary.details().get("reason"),
                noClassifiedTransactions.details().get("reason")))
                .as("each NOT_APPLICABLE reason must be independently distinguishable -- a future "
                        + "failure-rate breakdown needs to tell these apart by reason text alone")
                .doesNotHaveDuplicates();
    }

    @Test
    void ignoresRowsOfNeitherExpenseNorIncomeType() {
        var rows = List.of(
                row("GROCERY STORE", "5000", "EXPENSE"),
                row("UNCLASSIFIED ROW", "999", "TRANSFER"),
                row("PAYMENT RECEIVED", "4000", "INCOME"));

        var finding = validator.check(rows, summary("5000", "4000"));

        assertThat(finding.outcome()).isEqualTo("VERIFIED");
    }

    private static CreditCardSummaryEvidence summaryWithFees(String purchases, String fees, String paymentsAndCredits) {
        return new CreditCardSummaryEvidence(
                new BigDecimal("10000"), new BigDecimal(purchases), null, new BigDecimal(fees),
                new BigDecimal(paymentsAndCredits), new BigDecimal("13100"),
                CreditCardSummaryEvidence.ExtractionMethod.INLINE_LABEL_VALUE, List.of());
    }

    /** The shape of a real Kotak statement: fees printed apart from purchases, and a merchant refund
     *  netted into the purchases figure -- the refunded purchase and the refund are both rows. */
    @Test
    void verifiesWhenFeesArePrintedSeparatelyAndARefundIsNettedIntoPurchases() {
        var rows = List.of(
                row("SAMPLE STORE", "500", "EXPENSE"),
                row("SAMPLE STORE", "200", "EXPENSE"),
                row("GST", "18", "EXPENSE"),
                row("SAMPLE STORE REFUND", "200", "INCOME"),
                row("PAYMENT RECEIVED", "1000", "INCOME"));

        var finding = validator.check(rows, summaryWithFees("500", "18", "1000"));

        assertThat(finding.outcome()).isEqualTo("VERIFIED");
        assertThat(finding.details().get("creditsNettedIntoPurchases")).isEqualTo(new BigDecimal("200"));
    }

    @Test
    void warnsWhenBothSidesAreShortByTheSameAmount_missingRowsAreNeverNetting() {
        var rows = List.of(
                row("SAMPLE STORE", "300", "EXPENSE"),
                row("GST", "18", "EXPENSE"),
                row("PAYMENT RECEIVED", "800", "INCOME"));

        var finding = validator.check(rows, summaryWithFees("500", "18", "1000"));

        assertThat(finding.outcome()).isEqualTo("WARNING");
    }

    /** A summary printed as its own equation: 4000 + 6000 purchases + 50 other charges, less 3250
     *  payments and credits. Its figures reach 6800; {@code totalDue} is what it prints as the total. */
    private static CreditCardSummaryEvidence equationSummary(
            CreditCardSummaryEvidence.ExtractionMethod method, String totalDue) {
        return new CreditCardSummaryEvidence(
                new BigDecimal("4000"), new BigDecimal("6000"), BigDecimal.ZERO, new BigDecimal("50"),
                new BigDecimal("3250"), new BigDecimal(totalDue), method, List.of());
    }

    private static final CreditCardSummaryEvidence.ExtractionMethod EQUATION_ROW =
            CreditCardSummaryEvidence.ExtractionMethod.EQUATION_ROW;

    /** The shape of a real Axis statement: a refund the summary counted in neither its payments nor
     *  its credits figure, while its total due (150 under what its figures reach) reflects it. */
    @Test
    void verifiesAnEquationSummary_whoseCreditsLeaveOutARefundItsTotalIncludes() {
        var rows = List.of(
                row("SAMPLE STORE", "6000", "EXPENSE"),
                row("SAMPLE FEE", "50", "EXPENSE"),
                row("PAYMENT RECEIVED", "3000", "INCOME"),
                row("SAMPLE STORE REVERSAL", "250", "INCOME"),
                row("SAMPLE REFUND RECEIVED", "150", "INCOME"));

        var finding = validator.check(rows, equationSummary(EQUATION_ROW, "6650"));

        assertThat(finding.outcome()).isEqualTo("VERIFIED");
        assertThat(finding.details().get("creditsNotCountedInSummary")).isEqualTo(new BigDecimal("150"));
        assertThat(finding.details().get("rowsCarryPreviousBalanceToTotalDue")).isEqualTo(true);
    }

    @Test
    void theSameRowsAgainstAnyOtherKindOfSummary_stillWarn() {
        var rows = List.of(
                row("SAMPLE STORE", "6000", "EXPENSE"),
                row("SAMPLE FEE", "50", "EXPENSE"),
                row("PAYMENT RECEIVED", "3000", "INCOME"),
                row("SAMPLE STORE REVERSAL", "250", "INCOME"),
                row("SAMPLE REFUND RECEIVED", "150", "INCOME"));

        var finding = validator.check(rows,
                equationSummary(CreditCardSummaryEvidence.ExtractionMethod.GRID, "6650"));

        assertThat(finding.outcome()).isEqualTo("WARNING");
    }

    @Test
    void warnsOnAnEquationSummary_whenTheExtraCreditIsNotInItsTotalEither() {
        // The summary adds up to its own total (6800), so the extra 150 of credits is in no figure
        // the statement prints: a row read twice looks exactly like this.
        var rows = List.of(
                row("SAMPLE STORE", "6000", "EXPENSE"),
                row("SAMPLE FEE", "50", "EXPENSE"),
                row("PAYMENT RECEIVED", "3000", "INCOME"),
                row("SAMPLE STORE REVERSAL", "250", "INCOME"),
                row("SAMPLE REFUND RECEIVED", "150", "INCOME"));

        var finding = validator.check(rows, equationSummary(EQUATION_ROW, "6800"));

        assertThat(finding.outcome()).isEqualTo("WARNING");
        assertThat(finding.details()).doesNotContainKey("creditsNotCountedInSummary");
    }

    @Test
    void warnsOnAnEquationSummary_whenTheChargesDisagreeToo() {
        // The rows still carry 4000 to 6650 (6100 charged, 3450 credited), but 50 of the charges
        // is in no figure of the summary. Only the credits side is allowed to run over.
        var rows = List.of(
                row("SAMPLE STORE", "6000", "EXPENSE"),
                row("SAMPLE FEE", "50", "EXPENSE"),
                row("SAMPLE STORE", "50", "EXPENSE"),
                row("PAYMENT RECEIVED", "3000", "INCOME"),
                row("SAMPLE STORE REVERSAL", "250", "INCOME"),
                row("SAMPLE REFUND RECEIVED", "200", "INCOME"));

        var finding = validator.check(rows, equationSummary(EQUATION_ROW, "6650"));

        assertThat(finding.outcome()).isEqualTo("WARNING");
    }

    @Test
    void warnsOnAnEquationSummary_whenTheCreditsFallShortOfIt() {
        // 3100 credited against 3250 printed, and a total (6950) the rows do carry the previous
        // balance to. Credits the rows lack are a missed row until shown otherwise.
        var rows = List.of(
                row("SAMPLE STORE", "6000", "EXPENSE"),
                row("SAMPLE FEE", "50", "EXPENSE"),
                row("PAYMENT RECEIVED", "3000", "INCOME"),
                row("SAMPLE STORE REVERSAL", "100", "INCOME"));

        var finding = validator.check(rows, equationSummary(EQUATION_ROW, "6950"));

        assertThat(finding.outcome()).isEqualTo("WARNING");
    }

    @Test
    void warnsOnAnEquationSummary_whenItsTotalDueIsContestedBetweenTwoReadings() {
        // The same rows and figures that verify above, but the statement's headline total and its
        // equation's total were read as different amounts. Neither is a balance to check against.
        var rows = List.of(
                row("SAMPLE STORE", "6000", "EXPENSE"),
                row("SAMPLE FEE", "50", "EXPENSE"),
                row("PAYMENT RECEIVED", "3000", "INCOME"),
                row("SAMPLE STORE REVERSAL", "250", "INCOME"),
                row("SAMPLE REFUND RECEIVED", "150", "INCOME"));
        var contested = new CreditCardSummaryEvidence(
                new BigDecimal("4000"), new BigDecimal("6000"), BigDecimal.ZERO, new BigDecimal("50"),
                new BigDecimal("3250"), new BigDecimal("6650"), EQUATION_ROW, List.of("totalAmountDue"));

        assertThat(validator.check(rows, contested).outcome()).isEqualTo("WARNING");
    }

    @Test
    void warnsRatherThanThrows_whenAnEquationSummaryCarriesNoBalanceToCheckTheRowsAgainst() {
        var rows = List.of(
                row("SAMPLE STORE", "6000", "EXPENSE"),
                row("PAYMENT RECEIVED", "3000", "INCOME"),
                row("SAMPLE REFUND RECEIVED", "150", "INCOME"));
        var noBalances = new CreditCardSummaryEvidence(null, new BigDecimal("6000"), null, null,
                new BigDecimal("3000"), null, EQUATION_ROW, List.of());

        assertThat(validator.check(rows, noBalances).outcome()).isEqualTo("WARNING");
    }

    @Test
    void anEquationSummaryThatAddsUp_verifiesTheOrdinaryWay() {
        var rows = List.of(
                row("SAMPLE STORE", "6000", "EXPENSE"),
                row("SAMPLE FEE", "50", "EXPENSE"),
                row("PAYMENT RECEIVED", "3000", "INCOME"),
                row("SAMPLE STORE REVERSAL", "250", "INCOME"));

        var finding = validator.check(rows, equationSummary(EQUATION_ROW, "6800"));

        assertThat(finding.outcome()).isEqualTo("VERIFIED");
        assertThat(finding.details()).doesNotContainKey("creditsNotCountedInSummary");
    }

    @Test
    void warnsWhenTheTwoSurplusesDiffer() {
        var rows = List.of(
                row("SAMPLE STORE", "700", "EXPENSE"),
                row("GST", "18", "EXPENSE"),
                row("SAMPLE STORE REFUND", "150", "INCOME"),
                row("PAYMENT RECEIVED", "1000", "INCOME"));

        var finding = validator.check(rows, summaryWithFees("500", "18", "1000"));

        assertThat(finding.outcome()).isEqualTo("WARNING");
    }

    // ---- A panel that totals to the outstanding balance, with instalment rows in the ledger ----

    /**
     * The summary a real SBI card prints: purchases, fees and payments, totalling to "Total
     * Outstanding" rather than to the amount due. An instalment on an earlier purchase is listed
     * among the transactions but is in neither the purchases nor the fees figure, because its
     * principal was already part of the outstanding balance.
     */
    private static CreditCardSummaryEvidence outstandingSummary(String purchases, String fees, String payments) {
        return new CreditCardSummaryEvidence(
                new BigDecimal("40000"), new BigDecimal(purchases), null, new BigDecimal(fees),
                new BigDecimal(payments), new BigDecimal("25000"),
                CreditCardSummaryEvidence.ExtractionMethod.STACKED_LABEL_COLUMNS, List.of(),
                new BigDecimal("30500"));
    }

    @Test
    void verifiesWhenInstalmentRowsAccountForTheWholeSurplusOnAnOutstandingPanel() {
        var rows = List.of(
                row("SAMPLE STORE", "12000", "EXPENSE"),
                row("SAMPLE PHARMACY", "8000", "EXPENSE"),
                row("INTEREST ON EMI", "424.15", "EXPENSE"),
                row("IGST DB @ 18.00%", "76.35", "EXPENSE"),
                row("FP EMI 03/06(EXCL TAX   76.35)", "3000", "EXPENSE"),
                row("PAYMENT RECEIVED", "30000", "INCOME"));

        var finding = validator.check(rows, outstandingSummary("20000", "500.50", "30000"));

        assertThat(finding.outcome()).isEqualTo("VERIFIED");
        assertThat(finding.details().get("evidenceLevel")).isEqualTo("FULL_SUMMARY_RECONCILIATION");
        assertThat(finding.details().get("instalmentRowsSetAside")).isEqualTo(1);
    }

    @Test
    void anOutstandingPanelWithNoInstalmentsVerifiesOnTheChargesAlone() {
        var rows = List.of(
                row("SAMPLE STORE", "20000", "EXPENSE"),
                row("LATE FEE", "500.50", "EXPENSE"),
                row("PAYMENT RECEIVED", "30000", "INCOME"));

        var finding = validator.check(rows, outstandingSummary("20000", "500.50", "30000"));

        assertThat(finding.outcome()).isEqualTo("VERIFIED");
        assertThat(finding.details()).doesNotContainKey("instalmentRowsSetAside");
    }

    @Test
    void anUnexplainedSurplusOnAnOutstandingPanelWarnsSoTheImportIsHeld() {
        // An instalment worded in a way nothing here recognises -- or a row read twice. The two
        // cannot be told apart, so the amounts are not confirmed and the import waits for a person.
        var rows = List.of(
                row("SAMPLE STORE", "20000", "EXPENSE"),
                row("LATE FEE", "500.50", "EXPENSE"),
                row("LOAN INSTALMENT", "3000", "EXPENSE"),
                row("PAYMENT RECEIVED", "30000", "INCOME"));

        var finding = validator.check(rows, outstandingSummary("20000", "500.50", "30000"));

        assertThat(finding.outcome()).isEqualTo("WARNING");
        assertThat(finding.details().get("instalmentRowsRecognised")).isEqualTo(0);
        assertThat(finding.details()).doesNotContainKey("instalmentRowsSetAside");
    }

    @Test
    void aSurplusOnlyPartlyMadeOfInstalmentsWarnsRatherThanGuessing() {
        // Recognised instalments come to 3000 but the surplus is 2000: setting them aside would
        // leave the charges short, and which reading is right is not knowable from here.
        var rows = List.of(
                row("SAMPLE STORE", "19000", "EXPENSE"),
                row("LATE FEE", "500.50", "EXPENSE"),
                row("FP EMI 03/06(EXCL TAX   76.35)", "3000", "EXPENSE"),
                row("PAYMENT RECEIVED", "30000", "INCOME"));

        var finding = validator.check(rows, outstandingSummary("20000", "500.50", "30000"));

        assertThat(finding.outcome()).isEqualTo("WARNING");
        assertThat(finding.details().get("instalmentRowsRecognised")).isEqualTo(1);
    }

    @Test
    void chargesShortOfAnOutstandingPanelStillWarn() {
        // Fewer charges than the summary counts is a row that was not read, whatever the panel.
        var rows = List.of(
                row("SAMPLE STORE", "12000", "EXPENSE"),
                row("LATE FEE", "500.50", "EXPENSE"),
                row("PAYMENT RECEIVED", "30000", "INCOME"));

        var finding = validator.check(rows, outstandingSummary("20000", "500.50", "30000"));

        assertThat(finding.outcome()).isEqualTo("WARNING");
    }

    @Test
    void paymentsDisagreeingWithAnOutstandingPanelStillWarnEvenWithASurplus() {
        var rows = List.of(
                row("SAMPLE STORE", "20000", "EXPENSE"),
                row("LATE FEE", "500.50", "EXPENSE"),
                row("FP EMI 03/06(EXCL TAX   76.35)", "3000", "EXPENSE"),
                row("PAYMENT RECEIVED", "29000", "INCOME"));

        var finding = validator.check(rows, outstandingSummary("20000", "500.50", "30000"));

        assertThat(finding.outcome()).isEqualTo("WARNING");
    }

    @Test
    void instalmentRowsAreNotSetAsideOnAPanelThatTotalsToTheAmountDue() {
        // No statement of that kind has shown instalments left out of its purchases figure, so the
        // old rule stands there: an unexplained surplus is a disagreement.
        var rows = List.of(
                row("SAMPLE STORE", "500", "EXPENSE"),
                row("GST", "18", "EXPENSE"),
                row("FP EMI 03/06(EXCL TAX   76.35)", "3000", "EXPENSE"),
                row("PAYMENT RECEIVED", "1000", "INCOME"));

        var finding = validator.check(rows, summaryWithFees("500", "18", "1000"));

        assertThat(finding.outcome()).isEqualTo("WARNING");
    }

    @Test
    void aRowMentioningInstalmentsWithoutACounterIsNotAnInstalment() {
        // "INTEREST ON EMI" is a charge the fees figure counts. Only a row carrying its own
        // "n of m" counter is an instalment.
        var rows = List.of(
                row("SAMPLE STORE", "20000", "EXPENSE"),
                row("INTEREST ON EMI", "500.50", "EXPENSE"),
                row("PAYMENT RECEIVED", "30000", "INCOME"));

        var finding = validator.check(rows, outstandingSummary("20000", "500.50", "30000"));

        assertThat(finding.outcome()).isEqualTo("VERIFIED");
        assertThat(finding.details()).doesNotContainKey("instalmentRowsSetAside");
    }

    /** Only an opening balance and an outstanding total, as a real HSBC card prints as text; the
     *  total due, smaller by a loan's unbilled instalments, is set apart from both. */
    private static CreditCardSummaryEvidence balancesOnly(String opening, String outstanding, List<String> conflicts) {
        return new CreditCardSummaryEvidence(new BigDecimal(opening), null, null, null, null,
                new BigDecimal("900.00"), CreditCardSummaryEvidence.ExtractionMethod.INLINE_LABEL_VALUE,
                conflicts, new BigDecimal(outstanding));
    }

    @Test
    void verifiesRowsThatCarryTheOpeningBalanceToTheOutstandingBalance() {
        // 1,000.00 + (400.00 + 300.00 + 150.00 instalment) - (500.00 payment + 150.00 instalment
        // reversal) = 1,200.00, the printed outstanding total -- not the 900.00 total due.
        var rows = List.of(
                row("SAMPLE PAYMENT", "500.00", "INCOME"),
                row("SAMPLE STORE", "400.00", "EXPENSE"),
                row("SAMPLE TELECOM", "300.00", "EXPENSE"),
                row("SAMPLE EMI 2ND OF 3 INSTALLMENTS PRINCIPAL", "150.00", "INCOME"),
                row("SAMPLE EMI 2ND OF 3 INSTALLMENTS PRINCIPAL", "150.00", "EXPENSE"));

        var finding = validator.check(rows, balancesOnly("1000.00", "1200.00", List.of()));

        assertThat(finding.outcome()).isEqualTo("VERIFIED");
        assertThat(finding.details().get("evidenceLevel")).isEqualTo("OPENING_AND_OUTSTANDING_BALANCES");
        assertThat(finding.details().get("rowsCarryOpeningToOutstanding")).isEqualTo(true);
        // The review screens draw a purchases/payments table from these; none exists here.
        assertThat(finding.details()).doesNotContainKeys("observedExpenseAmount", "expectedExpenseAmount");
    }

    @Test
    void warnsWhenARowIsMissingAgainstTheTwoBalances() {
        // The same statement with the 300.00 charge lost: the rows reach 900.00, not 1,200.00.
        var rows = List.of(
                row("SAMPLE PAYMENT", "500.00", "INCOME"),
                row("SAMPLE STORE", "400.00", "EXPENSE"));

        var finding = validator.check(rows, balancesOnly("1000.00", "1200.00", List.of()));

        assertThat(finding.outcome()).isEqualTo("WARNING");
        assertThat(finding.details().get("rowsCarryOpeningToOutstanding")).isEqualTo(false);
        assertThat((BigDecimal) finding.details().get("difference")).isEqualByComparingTo("300.00");
    }

    @Test
    void aContestedBalanceOrNoOutstandingTotalLeavesTheCheckNotApplicable() {
        var rows = List.of(row("SAMPLE STORE", "400.00", "EXPENSE"));

        assertThat(validator.check(rows, balancesOnly("1000.00", "1400.00", List.of("totalAmountDue"))).outcome())
                .isEqualTo("NOT_APPLICABLE");
        var noOutstanding = new CreditCardSummaryEvidence(new BigDecimal("1000.00"), null, null, null, null,
                new BigDecimal("1400.00"), CreditCardSummaryEvidence.ExtractionMethod.INLINE_LABEL_VALUE, List.of());
        var finding = validator.check(rows, noOutstanding);
        assertThat(finding.outcome()).isEqualTo("NOT_APPLICABLE");
        assertThat(finding.details().get("evidenceLevel")).isEqualTo("PARTIAL_SUMMARY_ONLY");
    }
}
