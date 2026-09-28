package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two heading lines of a real SBI credit-card statement that were read into transactions: the
 * header's second line ("for Statement Period: ...", printed under "Transaction Details") began the
 * first row's description, and the add-on cardholder's banner ("TRANSACTIONS FOR <name>") was
 * appended to the row above it. Geometry measured from that statement; every name, date, reference
 * and amount below is synthetic per the Synthetic Fixture Policy.
 */
class SbiCardHeadingLinesPdfTableLocatorTest {

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    private static List<PositionedText> header() {
        return List.of(
                run("Date", 35.3f, 16.4f, 440.8f),
                run("Transaction Details", 179.0f, 67.9f, 439.2f),
                run("Amount", 371.3f, 28.0f, 440.8f),
                run("( ` )", 402.7f, 13.1f, 440.0f));
    }

    private static List<PositionedText> row(String date, String narration, String amount, String marker, float y) {
        return List.of(
                run(date, 28.5f, 30.1f, y),
                run(narration, 70.7f, 150.0f, y),
                run(amount, 369.5f, 35.5f, y),
                run(marker, 414.9f, 5.1f, y));
    }

    @Test
    void theHeadersStatementPeriodLineIsNotTheFirstRowsNarration() {
        List<PositionedText> runs = new ArrayList<>(header());
        runs.add(run("for Statement Period: 08 Jan 26 to 07 Feb 26", 157.0f, 131.8f, 447.9f));
        runs.addAll(row("10 Jan 26", "PAYMENT RECEIVED 000000000001", "1,000.00", "C", 467.6f));
        runs.addAll(row("11 Jan 26", "SAMPLE STORE", "250.00", "D", 479.4f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        var doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(doc.sections()).hasSize(1);
        var rows = doc.sections().get(0).rows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("Transaction Details")).isEqualTo("PAYMENT RECEIVED 000000000001");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).contains("HEADER_ANNOTATION_SUPPRESSED");
    }

    @Test
    void theAddOnCardholdersBannerIsNotPartOfTheRowAboveIt() {
        List<PositionedText> runs = new ArrayList<>(header());
        runs.addAll(row("05 Jan 26", "INTEREST ON EMI", "274.43", "D", 550.2f));
        runs.addAll(row("05 Jan 26", "IGST DB @ 18.00%", "49.40", "D", 562.0f));
        runs.add(run("TRANSACTIONS FOR A K SAMPLE", 70.7f, 98.5f, 573.8f));
        runs.addAll(row("07 Jan 26", "UPI-SAMPLE STORE", "525.00", "D", 585.6f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        var doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(doc.sections()).hasSize(1);
        var rows = doc.sections().get(0).rows();
        assertThat(rows).hasSize(3);
        assertThat(rows.get(1).get("Transaction Details")).isEqualTo("IGST DB @ 18.00%");
        assertThat(rows.get(2).get("Transaction Details")).isEqualTo("UPI-SAMPLE STORE");
        for (var r : rows) assertThat(String.join(" ", r.values())).doesNotContain("TRANSACTIONS FOR");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).contains("CARDHOLDER_SUBTABLE_BANNER");
    }

    @Test
    void aWrappedNarrationLineThatOnlyStartsLikeABannerStaysWithItsRow() {
        // Whole-line and letters-only: a continuation carrying a reference is narration.
        List<PositionedText> runs = new ArrayList<>(header());
        runs.addAll(row("05 Jan 26", "SAMPLE STORE", "274.43", "D", 550.2f));
        runs.add(run("TRANSACTIONS FOR 000000000002", 70.7f, 98.5f, 562.0f));
        runs.addAll(row("07 Jan 26", "UPI-SAMPLE STORE", "525.00", "D", 573.8f));

        var doc = new PdfTableLocator().locateAll(runs, new DocumentContext("PDF", "test"));

        var rows = doc.sections().get(0).rows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("Transaction Details")).contains("TRANSACTIONS FOR 000000000002");
    }
}
