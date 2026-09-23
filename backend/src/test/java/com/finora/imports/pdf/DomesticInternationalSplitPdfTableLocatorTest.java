package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real HDFC (Paytm HDFC) credit-card statement splits its ledger into a "Domestic Transactions"
 * table and an "International Transactions" table. The International table's heading and header
 * print at the bottom of page 1 with no rows under them, and the table resumes on page 2 under a
 * per-page banner (statement title, HSN code, GSTIN, an "Offers on your card" heading). A
 * cardholder divider ("NAME [CKYC ID : digits]") sits under each table's column header, and the
 * table closes with a "*Transaction time captured in IST Zone." footnote followed by an EMI-
 * conversion panel.
 *
 * <p>Before these fixes, on the real document: the divider was merged into the domestic row twice,
 * the page-2 banner was merged into rows on both sides of the page break, the footnote and EMI panel
 * were merged into the last international row, and nothing recorded which rows were international.
 *
 * <p>Coordinates and column shapes follow the real document; every value is invented, per the
 * Synthetic Fixture Policy.
 */
class DomesticInternationalSplitPdfTableLocatorTest {

    private static PositionedText run(String text, float x, float endX, float y, int page) {
        return new PositionedText(text, x, y, page, endX - x);
    }

    private static void header(List<PositionedText> runs, float dateX, float descX, float amountX, float y, int page) {
        runs.add(run("DATE & TIME", dateX, dateX + 38.5f, y, page));
        runs.add(run("TRANSACTION DESCRIPTION", descX, descX + 82.8f, y, page));
        runs.add(run("AMOUNT", amountX, amountX + 27.8f, y, page));
        runs.add(run("PI", amountX + 38.7f, amountX + 44.3f, y, page));
    }

    private static List<PositionedText> statement() {
        List<PositionedText> runs = new ArrayList<>();
        // Page 1: domestic table, one row, then the international heading/header with no rows.
        runs.add(run("Domestic Transactions", 169.5f, 239.9f, 682.2f, 0));
        header(runs, 169.5f, 260.2f, 528.4f, 697.2f, 0);
        runs.add(run("SAMPLE HOLDER [CKYC ID : 11112222333344 ]", 260.2f, 411.9f, 711.8f, 0));
        runs.add(run("06/09/2026| 12:14", 169.5f, 224.2f, 726.4f, 0));
        runs.add(run("EMI", 240.6f, 252.0f, 726.4f, 0));
        runs.add(run("SAMPLE AIRLINE", 260.2f, 327.3f, 726.4f, 0));
        runs.add(run(" C 5,000.00", 521.2f, 556.2f, 726.4f, 0));
        runs.add(run("l", 567.3f, 572.5f, 727.0f, 0));
        runs.add(run("International Transactions", 169.5f, 251.5f, 772.8f, 0));
        header(runs, 169.5f, 260.0f, 530.9f, 787.8f, 0);
        runs.add(run("SAMPLE HOLDER [CKYC ID : 11112222333344 ]", 260.0f, 411.7f, 802.0f, 0));
        runs.add(run("Page 1 of 2", 26.6f, 53.9f, 835.1f, 0));
        // Page 2: the per-page banner, then the international table resumes, shifted left.
        runs.add(run("HSN Code: 997113", 508.8f, 569.4f, 29.7f, 1));
        runs.add(run("DUPLICATE Sample HDFC Bank Credit Card Statement", 29.8f, 290.8f, 35.6f, 1));
        runs.add(run("HDFC Bank Credit Cards GSTIN: 00AAAAA0000A0Z0", 402.2f, 569.4f, 39.5f, 1));
        runs.add(run("Offers on your card", 22.7f, 90.9f, 58.8f, 1));
        runs.add(run("International Transactions", 25.5f, 107.5f, 256.5f, 1));
        header(runs, 25.5f, 148.7f, 525.4f, 271.5f, 1);
        runs.add(run("24/08/2026 | 19:40", 25.5f, 81.8f, 285.6f, 1));
        runs.add(run("SAMPLE CLOUD HOST", 148.7f, 223.8f, 285.6f, 1));
        runs.add(run("USD 10.00", 412.8f, 442.3f, 285.6f, 1));
        runs.add(run(" C 1,000.00", 521.7f, 553.1f, 285.6f, 1));
        runs.add(run("l", 565.8f, 571.1f, 286.3f, 1));
        runs.add(run("25/08/2026 | 00:00", 25.5f, 81.8f, 299.8f, 1));
        runs.add(run("IGST-VPS0000000000001-RATE 18.0 -09 (Ref# VT0000000000000000000001)", 148.7f, 364.5f, 299.8f, 1));
        runs.add(run(" C 5.00", 534.1f, 553.1f, 299.8f, 1));
        runs.add(run("l", 565.8f, 571.1f, 300.5f, 1));
        runs.add(run("20/09/2026 | 00:00", 25.5f, 81.8f, 399.0f, 1));
        runs.add(run("CONSOLIDATED FCY MARKUP FEE (Ref# VT0000000000000000000001)", 148.7f, 346.6f, 399.0f, 1));
        runs.add(run(" C 30.00", 527.0f, 553.1f, 399.0f, 1));
        runs.add(run("l", 565.8f, 571.1f, 399.7f, 1));
        // The table's own closing footnote and the EMI-conversion panel under it.
        runs.add(run(" *Transaction time captured in IST Zone.", 18.4f, 132.9f, 427.4f, 1));
        runs.add(run("TRANSACTIONS", 196.5f, 261.5f, 456.7f, 1));
        runs.add(run("TOTAL AMOUNT", 349.0f, 417.8f, 456.7f, 1));
        runs.add(run("CONVERT TO EMI", 494.4f, 552.4f, 462.3f, 1));
        runs.add(run("Eligible for    EMI", 53.7f, 123.2f, 462.8f, 1));
        runs.add(run("1", 226.5f, 231.5f, 468.9f, 1));
        runs.add(run("C5,000.00", 360.6f, 406.2f, 468.9f, 1));
        return runs;
    }

    private static String description(Map<String, String> row) {
        return row.get("TRANSACTION DESCRIPTION");
    }

    @Test
    void everyRealRowIsLocated_withNoBannerDividerOrFootnoteTextInAnyDescription() {
        DocumentContext ctx = new DocumentContext("PDF", "DomesticInternationalSplitPdfTableLocatorTest");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(statement(), ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows).extracting(DomesticInternationalSplitPdfTableLocatorTest::description).containsExactly(
                "EMI SAMPLE AIRLINE",
                "SAMPLE CLOUD HOST",
                "IGST-VPS0000000000001-RATE 18.0 -09 (Ref# VT0000000000000000000001)",
                "CONSOLIDATED FCY MARKUP FEE (Ref# VT0000000000000000000001)");
        assertThat(rows.get(1).get("AMOUNT")).isEqualTo("USD 10.00  C 1,000.00");
        assertThat(ctx.capabilities()).extracting(c -> c.capability()).contains(
                "TRANSACTION_REGION_HEADING",
                "TRANSACTION_CATEGORY_HEADER_SUPPRESSED",
                "PAGE_BOUNDARY_ISOLATION",
                "TRANSACTION_TIME_FOOTNOTE_CLOSED");
    }

    @Test
    void rowsUnderTheInternationalHeadingAreTaggedAndTheDomesticRowIsNot() {
        DocumentContext ctx = new DocumentContext("PDF", "DomesticInternationalSplitPdfTableLocatorTest");
        List<Map<String, String>> rows = new PdfTableLocator().locateAll(statement(), ctx).sections().get(0).rows();

        assertThat(ctx.isInternationalRow(rows.get(0))).as("the domestic row").isFalse();
        // Including the GST and markup rows, which print no foreign amount of their own -- the
        // heading is the only thing that says they are international.
        assertThat(rows.subList(1, 4)).allSatisfy(row -> assertThat(ctx.isInternationalRow(row)).isTrue());
    }

    @Test
    void aStatementWithNoRegionHeadingsTagsNothing() {
        List<PositionedText> runs = new ArrayList<>(statement());
        runs.removeIf(r -> r.text().endsWith("Transactions"));
        DocumentContext ctx = new DocumentContext("PDF", "DomesticInternationalSplitPdfTableLocatorTest");
        List<Map<String, String>> rows = new PdfTableLocator().locateAll(runs, ctx).sections().get(0).rows();

        assertThat(rows).isNotEmpty().noneSatisfy(row -> assertThat(ctx.isInternationalRow(row)).isTrue());
        assertThat(ctx.capabilities()).extracting(c -> c.capability()).doesNotContain("TRANSACTION_REGION_HEADING");
    }

    @Test
    void aHeadingMentionedMidSentenceIsNotARegionHeading() {
        List<PositionedText> runs = new ArrayList<>();
        header(runs, 30f, 120f, 500f, 100f, 0);
        runs.add(run("01/07/2026 | 10:00", 30f, 90f, 120f, 0));
        runs.add(run("SAMPLE SHOP", 120f, 200f, 120f, 0));
        runs.add(run(" C 100.00", 500f, 530f, 120f, 0));
        runs.add(run("Block international transactions in a single click", 120f, 330f, 140f, 0));
        runs.add(run("02/07/2026 | 10:00", 30f, 90f, 160f, 0));
        runs.add(run("SAMPLE CAFE", 120f, 200f, 160f, 0));
        runs.add(run(" C 50.00", 500f, 530f, 160f, 0));
        DocumentContext ctx = new DocumentContext("PDF", "DomesticInternationalSplitPdfTableLocatorTest");
        List<Map<String, String>> rows = new PdfTableLocator().locateAll(runs, ctx).sections().get(0).rows();

        assertThat(rows).noneSatisfy(row -> assertThat(ctx.isInternationalRow(row)).isTrue());
        assertThat(ctx.capabilities()).extracting(c -> c.capability()).doesNotContain("TRANSACTION_REGION_HEADING");
    }
}
