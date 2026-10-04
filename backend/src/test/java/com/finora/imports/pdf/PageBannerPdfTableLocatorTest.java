package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A slice small finance bank statement's page banner: the statement period and a page counter
 * ("1/2", "2/2") printed top right of BOTH pages at one height, with no header repeated on page 2.
 * On page 2 the two lines opened the page ahead of the first transaction and surfaced as one
 * unparseable row. Coordinates are the real statement's own, measured from its text runs; every
 * value is invented, per the Synthetic Fixture Policy. Mutation-checked: each rule reverted fails a
 * test here.
 */
class PageBannerPdfTableLocatorTest {

    private static final String PERIOD = "01 Sep '26 - 30 Sep '26";

    private static PositionedText run(String text, float x, float endX, float y, int page) {
        return new PositionedText(text, x, y, page, endX - x);
    }

    private static void banner(List<PositionedText> runs, float y, int page, String counter) {
        runs.add(run(PERIOD, 454.5f, 563.3f, y, page));
        runs.add(run(counter, 550.1f, 563.3f, y + 14.6f, page));
    }

    private static void transaction(List<PositionedText> runs, float y, int page, String date, String details,
                                    String amount, String balance) {
        runs.add(run(date, 32.0f, 70.0f, y, page));
        runs.add(run(details, 92.0f, 200.0f, y, page));
        runs.add(run("000000000001", 284.0f, 340.0f, y, page));
        runs.add(run(amount, 432.0f, 467.0f, y, page));
        runs.add(run(balance, 527.0f, 563.0f, y, page));
    }

    /** Page 1: banner, header, two rows. Page 2: banner at {@code page2BannerY}, two rows. */
    private static List<PositionedText> twoPages(float page2BannerY) {
        List<PositionedText> runs = new ArrayList<>();
        banner(runs, 41.0f, 0, "1/2");
        runs.add(run("DATE", 32.0f, 52.0f, 120.0f, 0));
        runs.add(run("DETAILS", 92.0f, 125.0f, 120.0f, 0));
        runs.add(run("REF NO.", 284.0f, 315.0f, 120.0f, 0));
        runs.add(run("AMOUNT", 430.0f, 467.0f, 120.0f, 0));
        runs.add(run("BALANCE", 525.0f, 563.0f, 120.0f, 0));
        transaction(runs, 140.0f, 0, "15 Sep '26", "Account Transfer-Credit-SAMPLE", "6,000.00", "6,018.20");
        transaction(runs, 165.0f, 0, "16 Sep '26", "Interest Cr. for 15-Sep-2026", "0.87", "6,019.07");
        banner(runs, page2BannerY, 1, "2/2");
        transaction(runs, 103.6f, 1, "17 Sep '26", "UPI-Debit-SAMPLE STORE", "-122.30", "5,896.77");
        transaction(runs, 128.6f, 1, "18 Sep '26", "Interest Cr. for 17-Sep-2026", "0.28", "5,897.05");
        return runs;
    }

    private static List<String> capabilities(DocumentContext ctx) {
        return ctx.capabilities().stream().map(c -> c.capability()).toList();
    }

    @Test
    void theBannerToppingPageTwo_isNoRow() {
        DocumentContext ctx = new DocumentContext("PDF", "test");
        var section = new PdfTableLocator().locateAll(twoPages(41.0f), ctx).sections().get(0);

        assertThat(section.rows()).hasSize(4);
        for (Map<String, String> row : section.rows()) {
            assertThat(String.join(" ", row.values())).doesNotContain("30 Sep '26").doesNotContain("2/2");
        }
        // The period is kept as the section's own text, not dropped.
        assertThat(section.auxiliaryText()).contains(PERIOD);
        assertThat(capabilities(ctx)).contains("REPEATED_PERIOD_BANNER_DIVERTED", "PAGE_BOUNDARY_ISOLATION");
    }

    @Test
    void aPeriodLineNotRepeatedAtTheSameHeight_isNotDiverted() {
        DocumentContext ctx = new DocumentContext("PDF", "test");
        new PdfTableLocator().locateAll(twoPages(60.0f), ctx);
        assertThat(capabilities(ctx)).doesNotContain("REPEATED_PERIOD_BANNER_DIVERTED");
    }

    @Test
    void aPageCounter_isItsOwnPagesNumberOverThePageCount() {
        List<PositionedText> onPageTwo = List.of(run("2/2", 550.1f, 563.3f, 55.6f, 1));
        assertThat(PdfTableLocator.isOwnPageCounter("2/2", onPageTwo, 2)).isTrue();
        assertThat(PdfTableLocator.isOwnPageCounter(" 2 / 2 ", onPageTwo, 2)).isTrue();
        // Another page's number, another page count, or anything else on the line: not a counter.
        assertThat(PdfTableLocator.isOwnPageCounter("1/2", onPageTwo, 2)).isFalse();
        assertThat(PdfTableLocator.isOwnPageCounter("2/3", onPageTwo, 2)).isFalse();
        assertThat(PdfTableLocator.isOwnPageCounter("EMI 2/2", onPageTwo, 2)).isFalse();
        assertThat(PdfTableLocator.isOwnPageCounter("2/2", List.of(), 2)).isFalse();
    }

    @Test
    void aWholePeriod_isTwoDatesAroundASpacedSeparatorAndNothingElse() {
        assertThat(PdfTableLocator.isWholePeriod(PERIOD)).isTrue();
        assertThat(PdfTableLocator.isWholePeriod("01/09/2026 to 30/09/2026")).isTrue();
        assertThat(PdfTableLocator.isWholePeriod("01-09-2026")).isFalse();
        assertThat(PdfTableLocator.isWholePeriod("Interest " + PERIOD)).isFalse();
        assertThat(PdfTableLocator.isWholePeriod("01 Sep '26 - SAMPLE STORE")).isFalse();
    }
}
