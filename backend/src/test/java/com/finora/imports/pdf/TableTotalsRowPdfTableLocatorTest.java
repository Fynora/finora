package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TABLE_TOTALS_ROW_DIVERTED: a dateless line whose only text is a totals label, printed beside the
 * column totals, is kept as auxiliary text rather than merged into the last transaction.
 *
 * <p>Measured on a real Standard Chartered savings export: the table ends with the word "Total"
 * in the cheque column's x-range and the deposit and withdrawal totals under their own columns,
 * one line pitch below the last transaction. The line has no date and carries figures, so it was
 * taken as that transaction's trailing continuation; the deposit total was appended to the
 * transaction's description and the rest of the line was lost.
 *
 * <p>All text is synthetic per the Synthetic Fixture Policy; the column positions follow the real
 * layout's geometry.
 */
class TableTotalsRowPdfTableLocatorTest {

    private static final float HEADER_Y = 234.4f;
    private static final float DATE_X = 43.7f;
    private static final float VALUE_DATE_X = 80.5f;
    private static final float NARRATION_X = 180.5f;
    private static final float CHEQUE_X = 338.5f;
    private static final float DEPOSIT_X = 395.5f;
    private static final float WITHDRAWAL_X = 460.1f;
    private static final float BALANCE_X = 525.4f;

    private static PositionedText run(String text, float x, float y) {
        return new PositionedText(text, x, y, 0);
    }

    private static List<PositionedText> table() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Date", DATE_X, HEADER_Y),
                run("Value Date", VALUE_DATE_X, HEADER_Y),
                run("Description", NARRATION_X, HEADER_Y),
                run("Cheque", CHEQUE_X, HEADER_Y),
                run("Deposit", DEPOSIT_X, HEADER_Y),
                run("Withdrawal", WITHDRAWAL_X, HEADER_Y),
                run("Balance", BALANCE_X, HEADER_Y)));
        runs.add(run("29 Jun 2026", DATE_X, 256.7f));
        runs.add(run("29 Jun 2026", VALUE_DATE_X, 256.7f));
        runs.add(run("UPI/000000000001/", 118.8f, 256.7f));
        runs.add(run("40.00", 470.0f, 256.7f));
        runs.add(run("1,000.00", 531.7f, 256.7f));
        runs.add(run("30 Jun 2026", DATE_X, 274.8f));
        runs.add(run("30 Jun 2026", VALUE_DATE_X, 274.8f));
        runs.add(run("SAMPLE INTEREST CREDIT", 118.8f, 274.1f));
        runs.add(run("12.00", 413.3f, 274.1f));
        runs.add(run("1,012.00", 531.7f, 274.1f));
        return runs;
    }

    private static PdfTableLocator.LocatedSection only(List<PositionedText> runs, DocumentContext ctx) {
        List<PdfTableLocator.LocatedSection> sections = new PdfTableLocator().locateAll(runs, ctx).sections();
        assertThat(sections).hasSize(1);
        return sections.get(0);
    }

    @Test
    void aBareTotalLineBelowTheLastTransaction_isAuxiliaryText_andLeavesTheTransactionAlone() {
        List<PositionedText> runs = table();
        runs.add(run("Total", 307.2f, 292.1f));
        runs.add(run(" 2,500.00", 399.8f, 292.8f));
        runs.add(run(" 1,488.00", 465.8f, 292.8f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedSection section = only(runs, ctx);

        assertThat(section.rows()).hasSize(2);
        Map<String, String> last = section.rows().get(1);
        assertThat(last.get("Description")).isEqualTo("SAMPLE INTEREST CREDIT");
        assertThat(last).containsEntry("Deposit", "12.00").containsEntry("Balance", "1,012.00")
                .doesNotContainKey("Withdrawal").doesNotContainKey("Cheque");
        assertThat(section.auxiliaryText()).anySatisfy(line ->
                assertThat(line).contains("Total").contains("2,500.00").contains("1,488.00"));
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).contains("TABLE_TOTALS_ROW_DIVERTED");
    }

    @Test
    void aTotalLabelPrintedAtTheLeftMarginInTheDateColumn_isAlsoATotalsRow() {
        // Real credit-card statements in the corpus print their "Total" line at the left margin,
        // in the date column's x-range, rather than beside the amounts.
        List<PositionedText> runs = table();
        runs.add(run("Total", 26.0f, 292.1f));
        runs.add(run(" 2,500.00", 399.8f, 292.8f));
        runs.add(run(" 1,488.00", 465.8f, 292.8f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedSection section = only(runs, ctx);

        assertThat(section.rows()).hasSize(2);
        Map<String, String> last = section.rows().get(1);
        assertThat(last.get("Description")).isEqualTo("SAMPLE INTEREST CREDIT");
        assertThat(last).containsEntry("Date", "30 Jun 2026").containsEntry("Deposit", "12.00")
                .doesNotContainKey("Withdrawal");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).contains("TABLE_TOTALS_ROW_DIVERTED");
    }

    @Test
    void aTotalLabelEndingInANonBreakingSpace_isStillATotalsRow() {
        // Real statements in the corpus carry a trailing U+00A0 on text runs, and nothing on the
        // extraction path replaces it.
        List<PositionedText> runs = table();
        runs.add(run("Total ", 307.2f, 292.1f));
        runs.add(run(" 2,500.00", 399.8f, 292.8f));
        runs.add(run(" 1,488.00", 465.8f, 292.8f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedSection section = only(runs, ctx);

        assertThat(section.rows().get(1).get("Description")).isEqualTo("SAMPLE INTEREST CREDIT");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).contains("TABLE_TOTALS_ROW_DIVERTED");
    }

    @Test
    void aDateColumnHoldingARealDate_isNeverATotalsRow() {
        List<PositionedText> runs = table();
        runs.add(run("01 Jul 2026", DATE_X, 292.1f));
        runs.add(run("Total", 118.8f, 292.1f));
        runs.add(run(" 2,500.00", 399.8f, 292.8f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedSection section = only(runs, ctx);

        assertThat(section.rows()).hasSize(3);
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).doesNotContain("TABLE_TOTALS_ROW_DIVERTED");
    }

    @Test
    void aNarrationLineThatOnlyBeginsWithTotal_isStillTheTransactionsContinuation() {
        List<PositionedText> runs = table();
        runs.add(run("TOTAL SAMPLE CHARGES", 118.8f, 284.5f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedSection section = only(runs, ctx);

        assertThat(section.rows()).hasSize(2);
        assertThat(section.rows().get(1).get("Description")).contains("TOTAL SAMPLE CHARGES");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).doesNotContain("TABLE_TOTALS_ROW_DIVERTED");
    }

    @Test
    void aLoneTotalLabelOutsideTheNarration_isATotalsRow() {
        // No figure, no placeholder, nothing on an adjacent row: the label alone, in the cheque
        // column, where no narration of this table starts.
        List<PositionedText> runs = table();
        runs.add(run("Total", 307.2f, 292.1f));
        assertDiverted(runs);
    }

    @Test
    void aLoneTotalLabelAtTheLeftMargin_whereNoNarrationStarts_isATotalsRow() {
        List<PositionedText> runs = table();
        runs.add(run("Total", 26.0f, 292.1f));
        assertDiverted(runs);
    }

    @Test
    void aLoneTotalsWordWhereTheNarrationStarts_isNarration() {
        // A merchant named "TOTAL" wrapped onto its own line starts where the narration starts.
        List<PositionedText> runs = table();
        runs.add(run("TOTAL", 118.8f, 284.5f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedSection section = only(runs, ctx);

        assertThat(section.rows().get(1).get("Description")).isEqualTo("SAMPLE INTEREST CREDIT TOTAL");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).doesNotContain("TABLE_TOTALS_ROW_DIVERTED");
    }

    @Test
    void aLoneTotalsWordAtALeftEdgeThisLayoutsNarrationAlreadyWrapsTo_isNarration() {
        // Bank of Baroda-style: wrapped narration starts at the left margin and buckets into the
        // date column. Once the document has shown a line starting there, a lone "TOTAL" there
        // is not ruled out as narration.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Date", DATE_X, HEADER_Y),
                run("Value Date", VALUE_DATE_X, HEADER_Y),
                run("Description", NARRATION_X, HEADER_Y),
                run("Cheque", CHEQUE_X, HEADER_Y),
                run("Deposit", DEPOSIT_X, HEADER_Y),
                run("Withdrawal", WITHDRAWAL_X, HEADER_Y),
                run("Balance", BALANCE_X, HEADER_Y)));
        runs.add(run("29 Jun 2026", DATE_X, 256.7f));
        runs.add(run("29 Jun 2026", VALUE_DATE_X, 256.7f));
        runs.add(run("UPI/000000000001/", 118.8f, 256.7f));
        runs.add(run("40.00", 470.0f, 256.7f));
        runs.add(run("1,000.00", 531.7f, 256.7f));
        runs.add(run("SAMPLE PAYEE/REF", 26.0f, 266.0f));
        runs.add(run("30 Jun 2026", DATE_X, 280.8f));
        runs.add(run("30 Jun 2026", VALUE_DATE_X, 280.8f));
        runs.add(run("UPI/000000000002/", 118.8f, 280.8f));
        runs.add(run("12.00", 413.3f, 280.8f));
        runs.add(run("1,012.00", 531.7f, 280.8f));
        runs.add(run("TOTAL", 26.0f, 290.1f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedSection section = only(runs, ctx);

        assertThat(section.rows()).hasSize(2);
        assertThat(section.rows().get(1).get("Description")).contains("TOTAL");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).doesNotContain("TABLE_TOTALS_ROW_DIVERTED");
    }

    @Test
    void aLoneTotalLabelUnderATransactionWithNoAmountYet_isNotDiverted() {
        List<PositionedText> runs = new ArrayList<>(table());
        runs.add(run("01 Jul 2026", DATE_X, 288.0f));
        runs.add(run("01 Jul 2026", VALUE_DATE_X, 288.0f));
        runs.add(run("POS 000000000009", 118.8f, 288.0f));
        runs.add(run("Total", 307.2f, 298.0f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        only3(runs, ctx);

        assertThat(ctx.capabilities().stream().map(c -> c.capability())).doesNotContain("TABLE_TOTALS_ROW_DIVERTED");
    }

    @Test
    void aTotalsLabelSharingItsLineWithOtherText_isNotATotalsRow() {
        List<PositionedText> runs = table();
        runs.add(run("Total", 307.2f, 292.1f));
        runs.add(run("SAMPLE NOTE", 118.8f, 292.1f));
        runs.add(run(" 2,500.00", 399.8f, 292.8f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        only(runs, ctx);

        assertThat(ctx.capabilities().stream().map(c -> c.capability())).doesNotContain("TABLE_TOTALS_ROW_DIVERTED");
    }

    /** The standard SC-shaped assertion: two transactions staged, the last one untouched, the
     *  capability recorded. */
    private static void assertDiverted(List<PositionedText> runs) {
        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedSection section = only(runs, ctx);

        assertThat(section.rows()).hasSize(2);
        Map<String, String> last = section.rows().get(1);
        assertThat(last.get("Description")).isEqualTo("SAMPLE INTEREST CREDIT");
        assertThat(last).containsEntry("Deposit", "12.00").containsEntry("Balance", "1,012.00")
                .doesNotContainKey("Withdrawal");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).contains("TABLE_TOTALS_ROW_DIVERTED");
    }

    @Test
    void aPlaceholderInAnAmountColumn_doesNotStopATotalsRow() {
        // "-", "NIL" and "NA" are all printed in amount cells by real statements in the corpus.
        for (String placeholder : List.of("-", "NIL", "NA", "N/A")) {
            List<PositionedText> runs = table();
            runs.add(run("Total", 307.2f, 292.1f));
            runs.add(run(" 2,500.00", 399.8f, 292.8f));
            runs.add(run(placeholder, 480.0f, 292.8f));
            assertDiverted(runs);
        }
    }

    @Test
    void aTotalLabelBucketedIntoAnAmountColumn_isStillATotalsRow() {
        // A layout with no column between the narration and the amounts buckets the label into
        // the first amount column.
        List<PositionedText> runs = table();
        runs.add(run("Total", DEPOSIT_X, 292.1f));
        runs.add(run(" 1,488.00", 465.8f, 292.8f));
        assertDiverted(runs);
    }

    @Test
    void aTotalLabelAndItsFigureInOneAmountCell_isStillATotalsRow() {
        List<PositionedText> runs = table();
        runs.add(run("Total 2,500.00", DEPOSIT_X, 292.1f));
        runs.add(run(" 1,488.00", 465.8f, 292.8f));
        assertDiverted(runs);
    }

    @Test
    void aTotalsLabelSplitAcrossTwoColumns_isStillATotalsRow() {
        List<PositionedText> runs = table();
        runs.add(run("Grand", 118.8f, 292.1f));
        runs.add(run("Total", 307.2f, 292.1f));
        runs.add(run(" 2,500.00", 399.8f, 292.8f));
        assertDiverted(runs);
    }

    @Test
    void labelVariants_areTotalsRows() {
        // "TOTAL (INR)" is a real Bank of Baroda label; the others are the same word's spellings.
        for (String label : List.of("TOTAL (INR)", "Total (Rs.)", "Totals", "Sub-total", "Subtotal",
                "Page Total", "Grand Total:", "TOTAL:")) {
            List<PositionedText> runs = table();
            runs.add(run(label, 307.2f, 292.1f));
            runs.add(run(" 2,500.00", 399.8f, 292.8f));
            assertDiverted(runs);
        }
    }

    @Test
    void aStandaloneDrCrMarkerOrPointsCountOnTheTotalsLine_doesNotStopIt() {
        // Real statements print CR/DR as a run of its own, and card statements print an integer
        // points total on the same line as the amount total.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Date", DATE_X, HEADER_Y),
                run("Description", NARRATION_X, HEADER_Y),
                run("Points", 300.0f, HEADER_Y),
                run("Amount", DEPOSIT_X, HEADER_Y),
                run("Type", 460.0f, HEADER_Y)));
        runs.add(run("29 Jun 2026", DATE_X, 256.7f));
        runs.add(run("SAMPLE STORE", NARRATION_X, 256.7f));
        runs.add(run("4", 300.0f, 256.7f));
        runs.add(run("400.00", 405.0f, 256.7f));
        runs.add(run("DR", 460.0f, 256.7f));
        runs.add(run("30 Jun 2026", DATE_X, 274.8f));
        runs.add(run("SAMPLE PAYMENT RECEIVED", NARRATION_X, 274.8f));
        runs.add(run("0", 300.0f, 274.8f));
        runs.add(run("1,000.00", 400.0f, 274.8f));
        runs.add(run("CR", 460.0f, 274.8f));
        runs.add(run("Total", 26.0f, 292.1f));
        runs.add(run("4", 300.0f, 292.1f));
        runs.add(run("1,400.00", 400.0f, 292.1f));
        runs.add(run("DR", 460.0f, 292.1f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedSection section = only(runs, ctx);

        assertThat(section.rows()).hasSize(2);
        assertThat(section.rows().get(1).get("Description")).isEqualTo("SAMPLE PAYMENT RECEIVED");
        assertThat(section.rows().get(1)).containsEntry("Amount", "1,000.00").containsEntry("Type", "CR");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).contains("TABLE_TOTALS_ROW_DIVERTED");
    }

    @Test
    void aWholeTotalsLinePrintedAsOneRun_inTheNarrationColumn_isATotalsRow() {
        List<PositionedText> runs = table();
        runs.add(run("TOTAL 2,500.00 1,488.00", 118.8f, 292.1f));
        assertDiverted(runs);
    }

    @Test
    void aTotalsWordFollowedByAWordOrACount_inTheNarrationColumn_isNotATotalsRow() {
        // Decimals are required: "TOTAL 3" and "TOTAL REF 2,500.00" read as narration.
        for (String line : List.of("TOTAL 3", "TOTAL REF 2,500.00", "TOTAL 123456")) {
            List<PositionedText> runs = table();
            runs.add(run(line, 118.8f, 284.5f));

            DocumentContext ctx = new DocumentContext("PDF", "test");
            PdfTableLocator.LocatedSection section = only(runs, ctx);

            assertThat(section.rows().get(1).get("Description")).as(line).contains(line);
            assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                    .as(line).doesNotContain("TABLE_TOTALS_ROW_DIVERTED");
        }
    }

    @Test
    void aLabelWithOnlyPlaceholdersInTheAmountColumns_isATotalsRow() {
        // A period with no activity prints "-" under both totals; the placeholders are table
        // structure, never narration.
        List<PositionedText> runs = table();
        runs.add(run("Total", 307.2f, 292.1f));
        runs.add(run("-", 410.0f, 292.8f));
        runs.add(run("-", 480.0f, 292.8f));
        assertDiverted(runs);
    }

    @Test
    void aLabelThatIsNotATotalsWord_isNotATotalsRow() {
        for (String label : List.of("Total Amount Due", "TOTALENERGIES", "Totalled", "Page 2")) {
            List<PositionedText> runs = table();
            runs.add(run(label, 307.2f, 292.1f));
            runs.add(run(" 2,500.00", 399.8f, 292.8f));

            DocumentContext ctx = new DocumentContext("PDF", "test");
            only(runs, ctx);

            assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                    .as(label).doesNotContain("TABLE_TOTALS_ROW_DIVERTED");
        }
    }

    @Test
    void aTotalsLineSplitOverTwoPhysicalRows_isDivertedWhole_labelAbove() {
        // The label's baseline sits 3.9pt above its figures': more than ROW_Y_TOLERANCE, so the
        // two halves of one printed line form two physical rows.
        List<PositionedText> runs = table();
        runs.add(run("Total", 307.2f, 292.1f));
        runs.add(run(" 2,500.00", 399.8f, 296.0f));
        runs.add(run(" 1,488.00", 465.8f, 296.0f));
        assertDiverted(runs);
        assertThat(only(runs, new DocumentContext("PDF", "test")).auxiliaryText())
                .anySatisfy(line -> assertThat(line).contains("Total"))
                .anySatisfy(line -> assertThat(line).contains("2,500.00").contains("1,488.00"));
    }

    @Test
    void aTotalsLineSplitOverTwoPhysicalRows_isDivertedWhole_figuresAbove() {
        List<PositionedText> runs = table();
        runs.add(run(" 2,500.00", 399.8f, 292.1f));
        runs.add(run(" 1,488.00", 465.8f, 292.1f));
        runs.add(run("Total", 307.2f, 296.0f));
        assertDiverted(runs);
    }

    @Test
    void figuresALinePitchUnderALabelThatStandsApart_areDivertedWithIt() {
        List<PositionedText> runs = table();
        runs.add(run("Total", 307.2f, 292.1f));
        runs.add(run(" 2,500.00", 399.8f, 302.5f));
        assertDiverted(runs);
    }

    @Test
    void figuresALinePitchUnderANarrationAlignedTotalsWord_areNotPairedWithIt() {
        // "TOTAL" where the narration starts may be narration, so it establishes nothing; beyond
        // the split-line gap its figures are not claimed as a totals line.
        List<PositionedText> runs = table();
        runs.add(run("TOTAL", 118.8f, 284.5f));
        runs.add(run(" 2,500.00", 399.8f, 295.0f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        only(runs, ctx);

        assertThat(ctx.capabilities().stream().map(c -> c.capability())).doesNotContain("TABLE_TOTALS_ROW_DIVERTED");
    }

    @Test
    void aTotalsShapedLineUnderATransactionWithNoAmountYet_staysWithThatTransaction() {
        // Layouts that print a transaction's amount on its own dateless line under the narration:
        // a merchant named "TOTAL" beside that amount is the transaction's own amount line.
        List<PositionedText> runs = new ArrayList<>(table());
        runs.add(run("01 Jul 2026", DATE_X, 288.0f));
        runs.add(run("01 Jul 2026", VALUE_DATE_X, 288.0f));
        runs.add(run("POS 000000000009", 118.8f, 288.0f));
        runs.add(run("TOTAL", 118.8f, 298.0f));
        runs.add(run("300.00", 470.0f, 298.0f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedSection section = only3(runs, ctx);

        Map<String, String> last = section.rows().get(2);
        assertThat(last).containsEntry("Withdrawal", "300.00");
        assertThat(last.get("Description")).contains("TOTAL");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).doesNotContain("TABLE_TOTALS_ROW_DIVERTED");
    }

    private static PdfTableLocator.LocatedSection only3(List<PositionedText> runs, DocumentContext ctx) {
        PdfTableLocator.LocatedSection section = only(runs, ctx);
        assertThat(section.rows()).hasSize(3);
        return section;
    }
}
