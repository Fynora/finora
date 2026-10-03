package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A scanned Union Bank of India savings statement, read through OCR: a gridded table whose
 * Particulars print on two lines, the first about 4pt ABOVE the date row and the second about 4pt
 * below it, a bare "1 of 10" page number bottom-right of every page (OCR reads it "1of 10"), the
 * bank's letterhead above each page's repeated header, and an in-grid "Summary :" block closing the
 * table.
 *
 * <p>Coordinates are the OCR'd document's own, measured from its recognised runs; every value is
 * invented, per the Synthetic Fixture Policy. Each test was mutation-checked by reverting the change
 * it covers and watching it fail.
 */
class ScannedGridLedgerPdfTableLocatorTest {

    private static final float LINE1 = -4.1f, LINE2 = 4.5f;

    private static PositionedText run(String text, float x, float endX, float y, int page) {
        return new PositionedText(text, x, y, page, endX - x);
    }

    private static void header(List<PositionedText> runs, float y, int page, boolean withDate) {
        runs.add(run("SI", 32.2f, 38.6f, y, page));
        if (withDate) runs.add(run("Date", 64.1f, 80.4f, y, page));
        runs.add(run("Particulars", 143.5f, 180.7f, y, page));
        runs.add(run("Chq Num", 247.9f, 278.9f, y, page));
        runs.add(run("Withdrawal", 317.3f, 357.8f, y, page));
        runs.add(run("Deposit", 397.7f, 424.3f, y, page));
        runs.add(run("Balance", 477.8f, 505.0f, y, page));
    }

    /** One transaction as the scan prints it: narration line one above the date row, line two below. */
    private static void transaction(List<PositionedText> runs, float y, int page, String si, String line1,
                                    String line2, String withdrawal, String deposit, String balance) {
        runs.add(run(line1, 102.5f, 219.1f, y + LINE1, page));
        runs.add(run(si, 30.0f, 42.0f, y, page));
        runs.add(run("01-09-2026", 51.6f, 90.0f, y, page));
        if (withdrawal != null) runs.add(run(withdrawal, 346.8f, 369.1f, y, page));
        if (deposit != null) runs.add(run(deposit, 420.5f, 443.0f, y, page));
        runs.add(run(balance, 499.0f, 530.4f, y, page));
        runs.add(run(line2, 102.0f, 191.0f, y + LINE2, page));
    }

    private static List<Map<String, String>> rows(List<PositionedText> runs, DocumentContext ctx) {
        return new PdfTableLocator().locateAll(runs, ctx).sections().get(0).rows();
    }

    private static List<String> capabilities(DocumentContext ctx) {
        return ctx.capabilities().stream().map(c -> c.capability()).toList();
    }

    // ---- The in-grid "Summary :" block -----------------------------------------------------------

    private static List<PositionedText> endsWithSummary(String totalsLabel) {
        List<PositionedText> runs = new ArrayList<>();
        header(runs, 87.6f, 0, true);
        transaction(runs, 104.9f, 0, "7", "UPIAR/000000000001/DR/SAMPLE", "SA/YESB/sample1@ybl",
                "37.00", null, "0.99 Cr");
        transaction(runs, 124.3f, 0, "8", "UPIAB/000000000002/CR/SAMPLE", "S/HDFC/sample2@ybl",
                null, "50.00", "50.99 Cr");
        runs.add(run(totalsLabel, 250.6f, 291.6f, 183.4f, 0));
        runs.add(run("1,000.07", 330.2f, 369.1f, 183.4f, 0));
        runs.add(run("Summary:", 176.6f, 222.0f, 194.2f, 0));
        runs.add(run("Closing Balance :", 385.4f, 443.0f, 194.2f, 0));
        runs.add(run("50.99 Cr", 502.8f, 530.9f, 194.2f, 0));
        runs.add(run("Total Credits", 248.4f, 291.6f, 201.6f, 0));
        runs.add(run("1,050.08", 330.2f, 369.1f, 201.6f, 0));
        return runs;
    }

    @Test
    void theSummaryBlockClosesTheTableInsteadOfJoiningTheLastTransaction() {
        DocumentContext ctx = new DocumentContext("PDF", "ScannedGridLedgerPdfTableLocatorTest");
        List<Map<String, String>> rows = rows(endsWithSummary("Total Debits"), ctx);

        assertThat(rows).hasSize(2);
        Map<String, String> last = rows.get(1);
        assertThat(last).containsEntry("Deposit", "50.00");
        assertThat(last).doesNotContainKey("Withdrawal");
        assertThat(last.get("Chq Num")).isNull();
        assertThat(capabilities(ctx)).contains("TABLE_TOTALS_SUMMARY_CLOSED");
    }

    /** Mutation guard: any other label in the same place is still read as the last row's
     *  continuation, so the test above exercises the trigger, not the fixture's geometry. */
    @Test
    void withoutTheTotalsLabelTheSameLineStillJoinsTheLastTransaction() {
        DocumentContext ctx = new DocumentContext("PDF", "ScannedGridLedgerPdfTableLocatorTest");
        List<Map<String, String>> rows = rows(endsWithSummary("Some Note"), ctx);

        assertThat(rows.get(1)).containsKey("Withdrawal");
        assertThat(capabilities(ctx)).doesNotContain("TABLE_TOTALS_SUMMARY_CLOSED");
    }

    // ---- A bare "N of M" page number at a page break ---------------------------------------------

    private static List<PositionedText> twoPages(String pageNumber) {
        List<PositionedText> runs = new ArrayList<>();
        header(runs, 299.8f, 0, true);
        transaction(runs, 335.8f, 0, "2", "UPIAR/000000000003/DR/SAMPLE", "Me/HDFC/sample3@hdf",
                "40.00", null, "500.97 Cr");
        transaction(runs, 724.1f, 0, "3", "UPIAR/000000000004/DR/SAMPLE", "Me/HDFC/sample4@hdf",
                "40.00", null, "460.97 Cr");
        runs.add(run(pageNumber, 493.4f, 517.7f, 751.9f, 0));
        runs.add(run("Union Bank", 298.3f, 371.3f, 42.7f, 1));
        header(runs, 87.6f, 1, true);
        transaction(runs, 106.1f, 1, "4", "UPIAR/000000000005/DR/SAMPLE", "XX/YESB/sample5@yb",
                "56.00", null, "404.97 Cr");
        return runs;
    }

    @Test
    void aBarePageNumberIsNeitherNarrationNorACostToTheNextPagesFirstLine() {
        DocumentContext ctx = new DocumentContext("PDF", "ScannedGridLedgerPdfTableLocatorTest");
        List<Map<String, String>> rows = rows(twoPages("1of 10"), ctx);

        assertThat(rows).hasSize(3);
        assertThat(String.join(" ", rows.get(1).values())).doesNotContain("of 10");
        assertThat(rows.get(2).get("Particulars")).startsWith("UPIAR/000000000005/DR/SAMPLE");
    }

    // ---- The first transaction after "Opening Balance" -------------------------------------------

    @Test
    void theFirstTransactionAfterTheOpeningBalanceKeepsItsFirstNarrationLine() {
        List<PositionedText> runs = new ArrayList<>();
        header(runs, 299.8f, 0, true);
        runs.add(run("Opening Balance", 102.5f, 158.6f, 318.5f, 0));
        runs.add(run("40.97 Cr", 502.8f, 530.4f, 318.5f, 0));
        transaction(runs, 335.8f, 0, "2", "UPIAB/000000000006/CR/SAMPLE", "S/HDFC/sample6@yes",
                null, "500.00", "540.97 Cr");
        transaction(runs, 354.7f, 0, "3", "UPIAR/000000000007/DR/SAMPLE", "Me/HDFC/sample7@hdf",
                "40.00", null, "500.97 Cr");

        List<Map<String, String>> rows = rows(runs, new DocumentContext("PDF", "ScannedGridLedgerPdfTableLocatorTest"));

        assertThat(rows).hasSize(3);
        assertThat(rows.get(1).get("Date")).isEqualTo("01-09-2026");
        assertThat(rows.get(1).get("Particulars")).startsWith("UPIAB/000000000006/CR/SAMPLE");
    }

    // ---- The next page's letterhead, above a header OCR could not read whole ---------------------

    @Test
    void theNextPagesLetterheadNeverJoinsThePreviousPagesLastTransaction() {
        List<PositionedText> runs = new ArrayList<>();
        header(runs, 299.8f, 0, true);
        transaction(runs, 724.1f, 0, "9", "UPIAR/000000000008/DR/SAMPLE", "XX/YESB/sample8@",
                "15.00", null, "37.99 Cr");
        runs.add(run("9 of 10", 493.4f, 517.7f, 751.9f, 0));
        runs.add(run("A Government of India Undertaking", 313.9f, 370.3f, 48.0f, 1));
        // OCR dropped "Date" from this page's header, so it is not recognised as a repeat.
        header(runs, 87.6f, 1, false);
        transaction(runs, 104.9f, 1, "10", "UPIAR/000000000009/DR/SAMPLE", "SA/YESB/sample9@",
                "37.00", null, "0.99 Cr");

        List<Map<String, String>> rows = rows(runs, new DocumentContext("PDF", "ScannedGridLedgerPdfTableLocatorTest"));

        assertThat(String.join(" ", rows.get(0).values())).doesNotContain("Government");
    }
}
