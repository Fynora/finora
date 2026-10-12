package com.finora.imports.pdf;

import com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence;
import com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence.ExtractionMethod;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A card summary whose figures are joined by the signs of its own billing equation, each under a
 * label wrapped over two lines, every word a run of its own. The shape is a real Kotak card's
 * "My Summary" box; every position and figure below is invented.
 *
 * <p>Read through {@link CreditCardSummaryExtractor#extract}, the way the pipeline reads it, so
 * each test also covers where the reader sits among the older strategies.
 */
class CardSummarySignedFigureRowReaderTest {

    private static final float ROW_Y = 296.5f;
    /** The signs sit half a point under the figures, as on the real row. */
    private static final float SIGN_Y = 297f;
    private static final float FIRST_COLUMN_X = 60f;
    private static final float COLUMN_PITCH = 92f;

    /** One column of the box: the sign printed before its figure (null for the first column), the
     *  figure, and the lines of the label above it. */
    private record BoxColumn(String sign, String figure, String... labelLines) {}

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    /** The statement's headline total due, which the same-row strategy reads today. */
    private static List<PositionedText> headline(String totalDue) {
        return new ArrayList<>(List.of(
                run("Total", 280f, 18f, 132f), run("Amount", 300f, 28f, 132f), run("Due", 331f, 15f, 132f),
                run("(TAD)", 349f, 22f, 132f), run("Rs.", 435f, 11f, 132f), run(totalDue, 448f, 28f, 132f)));
    }

    /** The rewards box that stands beside the summary, and a stray word in the left margin. */
    private static List<PositionedText> surroundings() {
        return List.of(
                // A points balance, not rupees: "Opening Balance" with a number beside it.
                run("Opening", 426f, 32f, 261f), run("Balance", 461f, 31f, 261f), run("1200", 539f, 16f, 262f),
                run("Earned", 426f, 27f, 281f), run("this", 455f, 13f, 281f), run("month", 471f, 23f, 281f),
                run("40", 541f, 12f, 282f),
                run("Note", 8f, 16f, 270f));
    }

    /**
     * The box as word-level runs: each column a currency mark and a figure on the row, the sign
     * before it midway to the previous column, and its label's words on one, two or three lines
     * above, the last line 20pt over the row.
     */
    private static List<PositionedText> box(BoxColumn... columns) {
        List<PositionedText> runs = new ArrayList<>();
        for (int k = 0; k < columns.length; k++) {
            BoxColumn column = columns[k];
            float left = FIRST_COLUMN_X + COLUMN_PITCH * k;
            if (column.sign() != null) runs.add(run(column.sign(), left - 25f, 4f, SIGN_Y));
            runs.add(run("Rs.", left, 11f, ROW_Y));
            runs.add(run(column.figure(), left + 13f, 32f, ROW_Y));
            String[] lines = column.labelLines();
            for (int line = 0; line < lines.length; line++) {
                // A one-line label sits between where the two lines of its neighbours do.
                float y = lines.length == 1 ? 265f : 276f - 13f * (lines.length - 1 - line);
                float x = left;
                for (String word : lines[line].split(" ")) {
                    runs.add(run(word, x, 4f * word.length(), y));
                    x += 4f * word.length() + 3f;
                }
            }
        }
        return runs;
    }

    private static final BoxColumn PREVIOUS = new BoxColumn(null, "9,000.00", "Previous", "Amount Due");
    private static final BoxColumn PAYMENTS = new BoxColumn("-", "9,000.00", "Payments");
    private static final BoxColumn PURCHASES = new BoxColumn("+", "4,250.00", "Purchases &", "Other Charges");
    private static final BoxColumn TOTAL = new BoxColumn("=", "4,250.00", "Total", "Amount Due");

    /** 9,000.00 - 9,000.00 + 4,250.00 = 4,250.00, with the page around it. */
    private static List<PositionedText> statement(BoxColumn... columns) {
        List<PositionedText> runs = headline("4,250.00");
        runs.addAll(box(columns));
        // The rewards box stands where a fifth column would; the real box has four.
        if (columns.length <= 4) runs.addAll(surroundings());
        return runs;
    }

    private static List<PositionedText> statement() {
        return statement(PREVIOUS, PAYMENTS, PURCHASES, TOTAL);
    }

    private static void assertNotRead(List<PositionedText> runs) {
        CreditCardSummaryEvidence summary = CreditCardSummaryExtractor.extract(runs);
        assertThat(summary.extractionMethod()).isNull();
        assertThat(summary.hasReconcilableFields()).isFalse();
        assertThat(summary.previousBalance()).isNull();
        assertThat(summary.purchases()).isNull();
        assertThat(summary.paymentsAndCredits()).isNull();
        // The headline total is still the older strategies' to read, exactly as before.
        assertThat(summary.totalAmountDue()).isEqualByComparingTo("4250.00");
    }

    @Test
    void readsEachFigureUnderItsWrappedLabel() {
        var summary = CreditCardSummaryExtractor.extract(statement());

        assertThat(summary.extractionMethod()).isEqualTo(ExtractionMethod.SIGNED_FIGURE_ROW);
        assertThat(summary.previousBalance()).isEqualByComparingTo("9000.00");
        assertThat(summary.paymentsAndCredits()).isEqualByComparingTo("9000.00");
        assertThat(summary.purchases()).isEqualByComparingTo("4250.00");
        assertThat(summary.totalAmountDue()).isEqualByComparingTo("4250.00");
        assertThat(summary.cashAdvances()).isNull();
        assertThat(summary.fees()).isNull();
        assertThat(summary.hasReconcilableFields()).isTrue();
        assertThat(summary.conflictingFields()).isEmpty();
    }

    @Test
    void theRewardsOpeningBalanceBesideTheBox_isNeverThePreviousBalance() {
        // The same-row strategy pairs "Opening Balance" with the points count beside it. That
        // reading never formed an equation and still does not reach the evidence.
        var summary = CreditCardSummaryExtractor.extract(statement());

        assertThat(summary.previousBalance()).isEqualByComparingTo("9000.00");
    }

    @Test
    void wordsOutsideTheOuterColumns_areNotTheirLabels() {
        // The rewards box starts 21pt right of the last figure, and a stray word sits in the left
        // margin. Were the outer columns open-ended, the last label would read "Total Opening
        // Balance Amount Due Earned this month" and the first "Previous Note Amount Due".
        var summary = CreditCardSummaryExtractor.extract(statement());

        assertThat(summary.extractionMethod()).isEqualTo(ExtractionMethod.SIGNED_FIGURE_ROW);
        assertThat(summary.totalAmountDue()).isEqualByComparingTo("4250.00");
        assertThat(summary.previousBalance()).isEqualByComparingTo("9000.00");
    }

    @Test
    void readsARowWithoutCurrencyMarks_andALabelLinePrintedAsOneRun() {
        List<PositionedText> runs = headline("4,250.00");
        runs.addAll(List.of(
                run("Purchases &", 244f, 47f, 263f), run("Other Charges", 244f, 56f, 276f),
                run("Previous", 60f, 33f, 263f), run("Amount Due", 60f, 46f, 276f),
                run("Payments", 152f, 36f, 265f),
                run("Total", 336f, 19f, 263f), run("Amount Due", 336f, 46f, 276f),
                run("9,000.00", 73f, 32f, ROW_Y), run("-", 127f, 4f, SIGN_Y),
                run("9,000.00", 165f, 32f, ROW_Y), run("+", 219f, 4f, SIGN_Y),
                run("4,250.00", 257f, 32f, ROW_Y), run("=", 311f, 4f, SIGN_Y),
                run("4,250.00", 349f, 32f, ROW_Y)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.extractionMethod()).isEqualTo(ExtractionMethod.SIGNED_FIGURE_ROW);
        assertThat(summary.previousBalance()).isEqualByComparingTo("9000.00");
        assertThat(summary.purchases()).isEqualByComparingTo("4250.00");
    }

    @Test
    void readsAChargeColumnBesidePurchases_andSumsEverythingSubtracted() {
        // 9,000.00 - 8,000.00 - 1,000.00 + 4,000.00 + 250.00 = 4,250.00
        var summary = CreditCardSummaryExtractor.extract(statement(
                new BoxColumn(null, "9,000.00", "Previous", "Amount Due"),
                new BoxColumn("-", "8,000.00", "Payments"),
                new BoxColumn("-", "1,000.00", "Payments &", "Refunds"),
                new BoxColumn("+", "4,000.00", "Purchases &", "Other Charges"),
                new BoxColumn("+", "250.00", "Finance", "Charges"),
                new BoxColumn("=", "4,250.00", "Total", "Amount Due")));

        assertThat(summary.extractionMethod()).isEqualTo(ExtractionMethod.SIGNED_FIGURE_ROW);
        assertThat(summary.paymentsAndCredits()).isEqualByComparingTo("9000.00");
        assertThat(summary.purchases()).isEqualByComparingTo("4000.00");
        assertThat(summary.fees()).isEqualByComparingTo("250.00");
    }

    @Test
    void recordsItsOwnCapability_onlyWhenItReadTheRow() {
        var read = new com.finora.imports.DocumentContext("PDF", "test");
        CreditCardSummaryExtractor.extract(statement(), read);
        var notRead = new com.finora.imports.DocumentContext("PDF", "test");
        CreditCardSummaryExtractor.extract(
                statement(PREVIOUS, new BoxColumn("-", "9,000.00", "Adjustments"), PURCHASES, TOTAL), notRead);

        assertThat(read.capabilities()).extracting(c -> c.capability())
                .contains("CREDIT_CARD_SUMMARY_SIGNED_FIGURE_ROW");
        assertThat(notRead.capabilities()).extracting(c -> c.capability())
                .doesNotContain("CREDIT_CARD_SUMMARY_SIGNED_FIGURE_ROW");
    }

    @Test
    void refusesWhenAnythingElseSharesTheRow() {
        List<PositionedText> withAWord = statement();
        withAWord.add(run("month", 471f, 23f, ROW_Y));
        List<PositionedText> withANumber = statement();
        withANumber.add(run("40", 541f, 12f, ROW_Y));

        assertNotRead(withAWord);
        assertNotRead(withANumber);
    }

    @Test
    void refusesAFigurePrintedWithoutPaise() {
        assertNotRead(statement(PREVIOUS, new BoxColumn("-", "9,000", "Payments"), PURCHASES, TOTAL));
    }

    @Test
    void refusesARowWithAnythingAfterItsEqualsSignButOneFigure() {
        // "previous - payments = purchases = total" and "previous - payments = purchases + total":
        // every label on a side it could stand on, were the first "=" read as a plus.
        assertNotRead(statement(PREVIOUS, PAYMENTS,
                new BoxColumn("=", "4,250.00", "Purchases &", "Other Charges"), TOTAL));
        assertNotRead(statement(PREVIOUS, PAYMENTS,
                new BoxColumn("=", "4,250.00", "Purchases &", "Other Charges"),
                new BoxColumn("+", "4,250.00", "Total", "Amount Due")));
    }

    @Test
    void aRowNamingNoPayment_isAnIncompleteSummary_andIsDiscardedWhole() {
        // "previous + purchases = total" is a well-formed row of known labels. It is still not the
        // equation the two card rules check, and none of its figures reaches the evidence.
        assertNotRead(statement(PREVIOUS, PURCHASES, TOTAL));
    }

    @Test
    void refusesARowThatEndsOnItsEqualsSign() {
        // "previous - payments + purchases + total =", with nothing after the sign: the last
        // figure is then one more thing added, not what the others come to.
        List<PositionedText> runs = statement(PREVIOUS, PAYMENTS, PURCHASES,
                new BoxColumn("+", "4,250.00", "Total", "Amount Due"));
        runs.add(run("=", FIRST_COLUMN_X + COLUMN_PITCH * 4 - 25f, 4f, SIGN_Y));

        assertNotRead(runs);
    }

    @Test
    void refusesTwoFiguresWithNoSignBetweenThem() {
        assertNotRead(statement(PREVIOUS, PAYMENTS,
                new BoxColumn(null, "4,250.00", "Purchases &", "Other Charges"), TOTAL));
    }

    @Test
    void refusesALabelItDoesNotKnow() {
        assertNotRead(statement(PREVIOUS, new BoxColumn("-", "9,000.00", "Adjustments"), PURCHASES, TOTAL));
    }

    @Test
    void refusesAFigureWithNoLabelAboveIt() {
        assertNotRead(statement(PREVIOUS, new BoxColumn("-", "9,000.00"), PURCHASES, TOTAL));
    }

    @Test
    void aNumberAmongALabelsWords_makesItALabelNobodyKnows() {
        List<PositionedText> runs = statement();
        runs.add(run("2026", FIRST_COLUMN_X + COLUMN_PITCH, 16f, 282f));

        assertNotRead(runs);
    }

    @Test
    void refusesWhenAChargeIsSubtracted() {
        // The sign is what says which side a figure falls on. Subtracted, the cash advance would
        // be taken for a payment: 9,000.00 - 9,000.00 - 500.00 + 4,750.00 = 4,250.00.
        assertNotRead(statement(PREVIOUS, PAYMENTS,
                new BoxColumn("-", "500.00", "Cash", "Advances"),
                new BoxColumn("+", "4,750.00", "Purchases &", "Other Charges"), TOTAL));
    }

    @Test
    void refusesWhenAPaymentIsAdded() {
        // Added, the refunds would be taken for a charge.
        assertNotRead(statement(PREVIOUS, PAYMENTS, PURCHASES,
                new BoxColumn("+", "500.00", "Payments &", "Refunds"), TOTAL));
    }

    @Test
    void refusesALabelPrintedOverTwoFigures() {
        assertNotRead(statement(PREVIOUS, PAYMENTS, new BoxColumn("-", "500.00", "Payments"), PURCHASES, TOTAL));
    }

    @Test
    void refusesTwoLabelsForTheSameCharge() {
        assertNotRead(statement(PREVIOUS, PAYMENTS, PURCHASES, new BoxColumn("+", "500.00", "Purchases"), TOTAL));
    }

    @Test
    void refusesWhenTheFigureAfterTheEqualsSignIsNotTheTotalDue() {
        // Known labels both: one names nothing after an "=", the other names a charge.
        assertNotRead(statement(PREVIOUS, PAYMENTS, PURCHASES,
                new BoxColumn("=", "4,250.00", "Minimum", "Amount Due")));
        assertNotRead(statement(PREVIOUS, PAYMENTS, PURCHASES, new BoxColumn("=", "4,250.00", "Finance", "Charges")));
    }

    @Test
    void aLineFarAboveTheRow_isNotPartOfALabel() {
        // "Previous" printed up where the box's own heading sits: what is left over the first
        // figure is "Amount Due", which names nothing.
        List<PositionedText> runs = statement(
                new BoxColumn(null, "9,000.00", "Amount Due"), PAYMENTS, PURCHASES, TOTAL);
        runs.add(run("Previous", FIRST_COLUMN_X, 32f, ROW_Y - 50f));

        assertNotRead(runs);
    }

    @Test
    void refusesWhenTheDocumentHasTwoSuchRows() {
        List<PositionedText> runs = statement();
        for (PositionedText t : box(PREVIOUS, PAYMENTS, PURCHASES, TOTAL)) {
            runs.add(new PositionedText(t.text(), t.x(), t.y(), 1, t.width()));
        }

        assertNotRead(runs);
    }

    @Test
    void flagsAConflict_whenTheHeadlineTotalAndTheRowsTotalDisagree() {
        List<PositionedText> runs = headline("4,350.00");
        runs.addAll(box(PREVIOUS, PAYMENTS, PURCHASES, TOTAL));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.extractionMethod()).isEqualTo(ExtractionMethod.SIGNED_FIGURE_ROW);
        assertThat(summary.conflictingFields()).containsExactly("totalAmountDue");
    }

    @Test
    void aSummaryTheGridStrategyCanRead_isStillReadByIt_evenBesideASignedRow() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Previous Balance", 50f, 90f, 100f),
                run("Purchases", 150f, 60f, 100f),
                run("Payments / Credits", 340f, 90f, 100f),
                run("Total Amount Due", 440f, 90f, 100f),
                run("1,000.00", 55f, 40f, 130f),
                run("500.00", 155f, 40f, 130f),
                run("200.00", 345f, 40f, 130f),
                run("1,300.00", 445f, 40f, 130f)));
        runs.addAll(box(PREVIOUS, PAYMENTS, PURCHASES, TOTAL));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.extractionMethod()).isEqualTo(ExtractionMethod.GRID);
        assertThat(summary.previousBalance()).isEqualByComparingTo("1000.00");
        assertThat(summary.totalAmountDue()).isEqualByComparingTo("1300.00");
    }

    @Test
    void aPanelTheStackedLabelStrategyReads_isStillReadByIt_evenBesideASignedRow() {
        // A panel that totals to its outstanding balance (the shape of a real SBI card, lower on
        // the page here), on a statement whose amount due is named but not read. That reading has
        // no total amount due, and is still a reading: the signed row must not replace it.
        float down = 300f;
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total Amount Due", 30f, 80f, 100f),
                run("Payments, ", 124f, 34f, 305.5f + down),
                run("Previous Balance", 30f, 53f, 311f + down),
                run("Total Outstanding", 357f, 55f, 311f + down),
                run("Reversals & other ", 112f, 58f, 313.5f + down),
                run("Purchases & Other ", 192f, 60f, 315f + down),
                run("Credits", 130f, 22f, 321.5f + down),
                run("Debits", 212f, 20f, 323f + down),
                run("30,000.00", 368f, 35f, 341.7f + down),
                run("40,000.00", 36f, 35f, 341.7f + down),
                run("30,000.00", 119f, 35f, 341.7f + down),
                run("20,000.00", 203f, 35f, 341.7f + down)));
        runs.addAll(box(PREVIOUS, PAYMENTS, PURCHASES, TOTAL));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.extractionMethod()).isEqualTo(ExtractionMethod.STACKED_LABEL_COLUMNS);
        assertThat(summary.previousBalance()).isEqualByComparingTo("40000.00");
        assertThat(summary.totalAmountDue()).isNull();
    }

    @Test
    void aDocumentWithNoSignedRow_readsNothing() {
        assertThat(CardSummarySignedFigureRowReader.read(headline("4,250.00")))
                .isEqualTo(CreditCardSummaryEvidence.NONE);
    }
}
