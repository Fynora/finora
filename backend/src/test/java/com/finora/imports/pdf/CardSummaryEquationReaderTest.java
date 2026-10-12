package com.finora.imports.pdf;

import com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence;
import com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence.ExtractionMethod;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A card summary printed as its own billing equation: one line of text naming every term, over one
 * row of figures. The shape is a real Axis card's; every position and figure below is invented.
 *
 * <p>Read through {@link CreditCardSummaryExtractor#extract}, the way the pipeline reads it, so
 * each test also covers where the reader sits among the older strategies.
 */
class CardSummaryEquationReaderTest {

    private static final String EQUATION = "Previous Balance - Payments - Credits + Purchase + Cash Advance"
            + " + Other Debit&Charges =Total Payment Due";

    /** 4,000.00 - 3,000.00 - 250.00 + 6,000.00 + 0.00 + 50.00 = 6,800.00 */
    private static final String[] FIGURES = {
            "4,000.00  Dr", "3,000.00", "250.00", "6,000.00", "0.00", "50.00", "6,800.00   Dr"};
    private static final float[] FIGURE_X = {48f, 116f, 171f, 209f, 273f, 335f, 404f};

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    /** The headline grid every such statement opens with (it is what the older strategies read the
     *  total due from), then the equation, then prose in a column beside the panel. */
    private static List<PositionedText> panel(String equation, float figureY, String... figures) {
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total Payment Due", 50f, 65f, 224f),
                run("Minimum Payment Due", 150f, 80f, 224f),
                run("6,800.00   Dr", 60f, 46f, 237f),
                run("340.00   Dr", 170f, 40f, 237f),
                run(equation, 68f, 360f, 282f),
                run("Paying only the minimum every month", 479f, 100f, 280f),
                run("stretches the repayment out over", 473f, 110f, 287f),
                // A pixel above the figures: the same row to any reader that groups by height.
                run("years, with interest on the balance", 475f, 105f, figureY - 1f)));
        for (int i = 0; i < figures.length; i++) {
            runs.add(run(figures[i], i < FIGURE_X.length ? FIGURE_X[i] : 455f, 30f, figureY));
        }
        return runs;
    }

    private static List<PositionedText> panel() {
        return panel(EQUATION, 295f, FIGURES);
    }

    private static void assertNotRead(CreditCardSummaryEvidence summary) {
        assertThat(summary.extractionMethod()).isNull();
        assertThat(summary.hasReconcilableFields()).isFalse();
        assertThat(summary.previousBalance()).isNull();
        assertThat(summary.purchases()).isNull();
        assertThat(summary.paymentsAndCredits()).isNull();
        // The headline total is still the older strategies' to read, exactly as before.
        assertThat(summary.totalAmountDue()).isEqualByComparingTo("6800.00");
    }

    @Test
    void readsEveryTermOfAnEquationPrintedAsOneLineOverOneRowOfFigures() {
        var summary = CreditCardSummaryExtractor.extract(panel());

        assertThat(summary.extractionMethod()).isEqualTo(ExtractionMethod.EQUATION_ROW);
        assertThat(summary.previousBalance()).isEqualByComparingTo("4000.00");
        assertThat(summary.purchases()).isEqualByComparingTo("6000.00");
        assertThat(summary.cashAdvances()).isEqualByComparingTo("0.00");
        assertThat(summary.fees()).isEqualByComparingTo("50.00");
        assertThat(summary.totalAmountDue()).isEqualByComparingTo("6800.00");
        assertThat(summary.hasReconcilableFields()).isTrue();
        assertThat(summary.conflictingFields()).isEmpty();
    }

    @Test
    void sumsEveryTermTheEquationSubtractsIntoPaymentsAndCredits() {
        var summary = CreditCardSummaryExtractor.extract(panel());

        // "Payments" 3,000.00 and "Credits" 250.00 are separate terms; the evidence has one field.
        assertThat(summary.paymentsAndCredits()).isEqualByComparingTo("3250.00");
    }

    @Test
    void recordsItsOwnCapability_onlyWhenItReadTheEquation() {
        var read = new com.finora.imports.DocumentContext("PDF", "test");
        CreditCardSummaryExtractor.extract(panel(), read);
        var notRead = new com.finora.imports.DocumentContext("PDF", "test");
        CreditCardSummaryExtractor.extract(panel(EQUATION, 295f, "4,000.00  Dr", "3,000.00"), notRead);

        assertThat(read.capabilities()).extracting(c -> c.capability())
                .contains("CREDIT_CARD_SUMMARY_EQUATION_ROW");
        assertThat(notRead.capabilities()).extracting(c -> c.capability())
                .doesNotContain("CREDIT_CARD_SUMMARY_EQUATION_ROW");
    }

    @Test
    void refusesWhenTheRowHasFewerFiguresThanTheEquationHasTerms() {
        // Six figures for seven terms: taking them in order would put every figure after the gap
        // under the wrong term.
        List<PositionedText> sixFigures = panel(EQUATION, 295f,
                "4,000.00  Dr", "3,000.00", "250.00", "6,000.00", "50.00", "6,800.00   Dr");

        assertNotRead(CreditCardSummaryExtractor.extract(sixFigures));
        // Not even in part: the reader itself names no field of a row it cannot match term for term.
        assertThat(CardSummaryEquationReader.read(sixFigures)).isEqualTo(CreditCardSummaryEvidence.NONE);
    }

    @Test
    void refusesWhenAStrayAmountSharesTheFigureRow() {
        String[] eight = {"4,000.00  Dr", "3,000.00", "250.00", "6,000.00", "0.00", "50.00", "6,800.00   Dr", "99.00"};

        assertNotRead(CreditCardSummaryExtractor.extract(panel(EQUATION, 295f, eight)));
    }

    @Test
    void aFigurePrintedWithoutPaise_isNotCountedAsOne() {
        // A whole number on the row may be anything (a phone number in the prose column): it is no
        // figure, the count falls one short, and nothing is read.
        var summary = CreditCardSummaryExtractor.extract(panel(EQUATION, 295f,
                "4,000.00  Dr", "3,000.00", "250.00", "6,000.00", "0", "50.00", "6,800.00   Dr"));

        assertNotRead(summary);
    }

    @Test
    void refusesFiguresPrintedTooFarBelowTheEquation() {
        assertNotRead(CreditCardSummaryExtractor.extract(panel(EQUATION, 282f + 21f, FIGURES)));
    }

    @Test
    void takesOnlyTheNearestRowOfAmounts_neverALaterOne() {
        List<PositionedText> runs = panel();
        // A transaction's amount, further down and still within reach of the equation.
        runs.add(run("120.00 Dr", 550f, 32f, 300f));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.extractionMethod()).isEqualTo(ExtractionMethod.EQUATION_ROW);
        assertThat(summary.totalAmountDue()).isEqualByComparingTo("6800.00");
    }

    @Test
    void refusesAnEquationWithATermItDoesNotKnow() {
        String unknownTerm = "Previous Balance - Payments - Credits + Purchase + Loyalty Adjustment"
                + " + Other Debit&Charges =Total Payment Due";

        assertNotRead(CreditCardSummaryExtractor.extract(panel(unknownTerm, 295f, FIGURES)));
    }

    @Test
    void refusesWhenAChargeIsSubtractedOrAPaymentAdded() {
        // The sign is what says which side a term falls on, so a term on the wrong side of it is
        // not the equation this reader knows.
        String paymentsAdded = "Previous Balance + Payments - Credits + Purchase + Cash Advance"
                + " + Other Debit&Charges =Total Payment Due";
        String cashAdvanceSubtracted = "Previous Balance - Payments - Credits + Purchase - Cash Advance"
                + " + Other Debit&Charges =Total Payment Due";

        assertNotRead(CreditCardSummaryExtractor.extract(panel(paymentsAdded, 295f, FIGURES)));
        assertNotRead(CreditCardSummaryExtractor.extract(panel(cashAdvanceSubtracted, 295f, FIGURES)));
    }

    @Test
    void refusesATermNamedTwice() {
        String purchasesTwice = "Previous Balance - Payments - Credits + Purchase + Purchases"
                + " + Other Debit&Charges =Total Payment Due";
        String paymentsTwice = "Previous Balance - Payments - Payments + Purchase + Cash Advance"
                + " + Other Debit&Charges =Total Payment Due";

        assertNotRead(CreditCardSummaryExtractor.extract(panel(purchasesTwice, 295f, FIGURES)));
        assertNotRead(CreditCardSummaryExtractor.extract(panel(paymentsTwice, 295f, FIGURES)));
    }

    @Test
    void takesEachFigureByItsTerm_whateverOrderTheEquationPrintsThemIn() {
        String reordered = "Purchase + Other Debit&Charges - Payments + Previous Balance =Total Payment Due";

        var summary = CreditCardSummaryExtractor.extract(panel(reordered, 295f,
                "6,000.00", "50.00", "3,250.00", "4,000.00  Dr", "6,800.00   Dr"));

        assertThat(summary.extractionMethod()).isEqualTo(ExtractionMethod.EQUATION_ROW);
        assertThat(summary.purchases()).isEqualByComparingTo("6000.00");
        assertThat(summary.fees()).isEqualByComparingTo("50.00");
        assertThat(summary.paymentsAndCredits()).isEqualByComparingTo("3250.00");
        assertThat(summary.previousBalance()).isEqualByComparingTo("4000.00");
        assertThat(summary.cashAdvances()).isNull();
    }

    @Test
    void anEquationNamingNoPreviousBalance_isAnIncompleteSummary_andIsDiscardedWhole() {
        String noPreviousBalance = "Purchase + Other Debit&Charges - Payments =Total Payment Due";

        assertNotRead(CreditCardSummaryExtractor.extract(panel(noPreviousBalance, 295f,
                "6,000.00", "50.00", "3,250.00", "6,800.00   Dr")));
    }

    @Test
    void refusesAnEquationThatDoesNotEndInTheTotalDue() {
        String noTotal = "Previous Balance - Payments - Credits + Purchase + Cash Advance"
                + " + Other Debit&Charges =Minimum Payment Due";

        assertNotRead(CreditCardSummaryExtractor.extract(panel(noTotal, 295f, FIGURES)));
    }

    @Test
    void refusesABalanceMarkedCr() {
        // A balance in the customer's favour. Every field of the evidence is a magnitude, so this
        // would read as money owed.
        var previousInCredit = CreditCardSummaryExtractor.extract(panel(EQUATION, 295f,
                "4,000.00  Cr", "3,000.00", "250.00", "6,000.00", "0.00", "50.00", "6,800.00   Dr"));

        assertNotRead(previousInCredit);
    }

    @Test
    void refusesWhenTheEquationIsPrintedTwice() {
        List<PositionedText> runs = panel();
        // A worked example of the same equation on a later page, with figures of its own.
        runs.add(new PositionedText(EQUATION, 68f, 400f, 2, 360f));
        String[] example = {"1,000.00", "500.00", "0.00", "2,000.00", "0.00", "0.00", "2,500.00"};
        for (int i = 0; i < example.length; i++) {
            runs.add(new PositionedText(example[i], FIGURE_X[i], 413f, 2, 30f));
        }

        assertNotRead(CreditCardSummaryExtractor.extract(runs));
    }

    @Test
    void flagsAConflict_whenTheHeadlineTotalAndTheEquationsTotalDisagree() {
        var summary = CreditCardSummaryExtractor.extract(panel(EQUATION, 295f,
                "4,000.00  Dr", "3,000.00", "250.00", "6,000.00", "0.00", "50.00", "6,900.00   Dr"));

        assertThat(summary.conflictingFields()).containsExactly("totalAmountDue");
    }

    @Test
    void aSummaryTheGridStrategyCanRead_isStillReadByIt_evenBesideAnEquation() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Previous Balance", 50f, 90f, 100f),
                run("Purchases", 150f, 60f, 100f),
                run("Payments / Credits", 340f, 90f, 100f),
                run("Total Amount Due", 440f, 90f, 100f),
                run("1,000.00", 55f, 40f, 130f),
                run("500.00", 155f, 40f, 130f),
                run("200.00", 345f, 40f, 130f),
                run("1,300.00", 445f, 40f, 130f),
                run(EQUATION, 68f, 360f, 282f)));
        for (int i = 0; i < FIGURES.length; i++) runs.add(run(FIGURES[i], FIGURE_X[i], 30f, 295f));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.extractionMethod()).isEqualTo(ExtractionMethod.GRID);
        assertThat(summary.previousBalance()).isEqualByComparingTo("1000.00");
        assertThat(summary.totalAmountDue()).isEqualByComparingTo("1300.00");
    }

    @Test
    void aLineWithNoEquationInIt_readsNothing() {
        assertThat(CardSummaryEquationReader.read(List.of(
                run("Previous Balance", 50f, 90f, 100f), run("1,000.00", 55f, 40f, 113f))))
                .isEqualTo(CreditCardSummaryEvidence.NONE);
    }
}
