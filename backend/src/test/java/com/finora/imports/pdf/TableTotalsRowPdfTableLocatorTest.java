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
    void aTotalLabelWithoutAnyFigure_isNotATotalsRow() {
        List<PositionedText> runs = table();
        runs.add(run("Total", 307.2f, 292.1f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        only(runs, ctx);

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
}
