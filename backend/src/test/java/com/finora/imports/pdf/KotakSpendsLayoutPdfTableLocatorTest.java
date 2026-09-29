package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A newer Kotak credit-card layout (a "Date / Description Spends / Category / Amount" table) whose
 * group headings, group subtotals and page footer were read into transactions: the last purchase's
 * description ended with the purchases subtotal and the next group's heading, the fee row's with the
 * fees subtotal, the payment row's with the purchases heading, and the last row of each page with
 * the page footer's sentence. Geometry measured from that statement; every description, reference,
 * card number and amount below is synthetic per the Synthetic Fixture Policy.
 */
class KotakSpendsLayoutPdfTableLocatorTest {

    private static PositionedText run(String text, float x, float width, float y, int page) {
        return new PositionedText(text, x, y, page, width);
    }

    private static List<PositionedText> header(int page) {
        return List.of(
                run("Date", 66.0f, 16.5f, 121.0f, page),
                run("Description Spends", 120.5f, 68.3f, 121.0f, page),
                run("Category", 383.5f, 32.1f, 121.0f, page),
                run("Amount (₹)", 490.3f, 39.4f, 121.0f, page));
    }

    private static List<PositionedText> row(String date, String narration, String category, String amount,
                                            float y, int page) {
        List<PositionedText> runs = new ArrayList<>(List.of(
                run(date, 50.9f, 42.7f, y, page),
                run(narration, 120.5f, 150.0f, y, page)));
        if (category != null) runs.add(run(category, 384.1f, 27.1f, y, page));
        runs.add(run(amount, 525.0f - 4f * (amount.length() - 6), 4f * amount.length(), y, page));
        return runs;
    }

    private static List<PositionedText> footer(String pageNumber, int page) {
        return List.of(
                run("SAMPLE FOOTER NOTICE ABOUT CHARGES. ANOTHER SAMPLE SENTENCE.", 36.0f, 242.0f, 827.0f, page),
                run(pageNumber, 531.0f, 31.5f, 827.0f, page));
    }

    private static List<PositionedText> statement() {
        List<PositionedText> runs = new ArrayList<>(header(0));
        runs.add(run("Payments and Other Credits", 120.5f, 99.3f, 145.0f, 0));
        runs.addAll(row("01-Feb-2026", "SAMPLEPAYMENTREF0001", null, "5,000.00 Cr", 169.9f, 0));
        runs.add(run("Purchases made in this cycle - Primary Card X0000", 120.5f, 176.4f, 188.9f, 0));
        runs.addAll(row("15-Jan-2026", "UPI-K-000000000001-SAMPLE STORE", "Grocery", "250.00", 213.8f, 0));
        runs.addAll(row("16-Jan-2026", "UPI-K-000000000002-SAMPLE CAFE", "Restaurants", "120.00", 231.7f, 0));
        runs.addAll(footer("Page 1 of 2", 0));
        runs.addAll(header(1));
        runs.addAll(row("14-Feb-2026", "UPI-K-000000000003-SAMPLE MART", "Grocery", "430.00", 143.9f, 1));
        runs.add(run("Total Purchases", 120.5f, 56.3f, 162.9f, 1));
        runs.add(run("800.00", 525.0f, 22.7f, 162.9f, 1));
        runs.add(run("Other fees and charges", 120.5f, 80.8f, 188.9f, 1));
        runs.addAll(row("01-Feb-2026", "GST", null, "18.00", 213.8f, 1));
        runs.add(run("Total Fees & Charges", 120.5f, 73.2f, 232.8f, 1));
        runs.add(run("18.00", 528.4f, 19.3f, 232.8f, 1));
        runs.addAll(footer("Page 2 of 2", 1));
        return runs;
    }

    @Test
    void headingsSubtotalsAndPageFootersStayOutOfEveryTransaction() {
        DocumentContext ctx = new DocumentContext("PDF", "test");
        var doc = new PdfTableLocator().locateAll(statement(), ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows).extracting(r -> r.get("Description Spends")).containsExactly(
                "SAMPLEPAYMENTREF0001",
                "UPI-K-000000000001-SAMPLE STORE",
                "UPI-K-000000000002-SAMPLE CAFE",
                "UPI-K-000000000003-SAMPLE MART",
                "GST");
        assertThat(rows).extracting(r -> r.get("Amount (₹)"))
                .containsExactly("5,000.00 Cr", "250.00", "120.00", "430.00", "18.00");
        for (var r : rows) {
            assertThat(String.join(" ", r.values()))
                    .doesNotContain("Total").doesNotContain("Purchases made").doesNotContain("fees and charges")
                    .doesNotContain("SAMPLE FOOTER").doesNotContain("Page");
        }
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("TRANSACTION_CATEGORY_HEADER_SUPPRESSED", "PAGE_BOUNDARY_ISOLATION");
    }

    @Test
    void aNarrationLineThatOnlyMentionsAPageIsStillNarration() {
        // pageNumberBesideTheTable requires the text outside the table to be exactly a page number;
        // a continuation line wholly inside the table is untouched.
        List<PositionedText> runs = new ArrayList<>(header(0));
        runs.addAll(row("15-Jan-2026", "UPI-K-000000000001-SAMPLE STORE", "Grocery", "250.00", 213.8f, 0));
        runs.add(run("SAMPLE BOOKS PAGE ONE", 120.5f, 90f, 225.0f, 0));
        runs.addAll(row("16-Jan-2026", "UPI-K-000000000002-SAMPLE CAFE", "Restaurants", "120.00", 243.7f, 0));

        var doc = new PdfTableLocator().locateAll(runs, new DocumentContext("PDF", "test"));

        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(String.join(" ", rows.get(0).values()) + String.join(" ", rows.get(1).values()))
                .contains("SAMPLE BOOKS PAGE ONE");
    }
}
