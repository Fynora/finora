package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LEADING_BUFFER_CLOSED_AT_REPEATED_BANNER: whatever dateless text was buffered as leading
 * narration before a repeated per-page banner or header belongs to the page that ended, never to
 * the first transaction printed after the banner.
 *
 * <p>Traced on a real Bank of Baroda statement, whose layout prints each transaction's narration
 * on its own line ABOVE the date row and closes every page with a footer block (a "Page N | M"
 * line, two helpline numbers, a URL line). The footer lines are dateless and were buffered as
 * leading narration; the next page's repeated account banner and repeated header were correctly
 * recognised and skipped, but neither closed that buffer, so the next transaction's own narration
 * line was appended to the SAME buffer. At that transaction's date row the whole buffer was refused
 * (its footer text sat in the date column and would have broken the date), staged as one
 * unparseable row -- footer plus the transaction's narration and its reference number -- and the
 * transaction itself was left with only the fragment printed under its date row.
 *
 * <p>Geometry from that document: narration lines 5.1pt above their date row, the wrapped tail
 * 5.1pt below it, 10.2pt between transactions, the footer block far below the last row. Text is
 * synthetic per the Synthetic Fixture Policy.
 */
class RepeatedBannerLeadingNarrationPdfTableLocatorTest {

    private static final String CAPABILITY = "LEADING_BUFFER_CLOSED_AT_REPEATED_BANNER";
    private static final float[] COL = {50f, 141f, 252f, 308f, 423f, 530f};
    private static final float NARRATION_X = 80f;   // nearer the DATE anchor than NARRATION's, as on the real document
    private static final float LINE = 5.1f;
    private static final float BLOCK = 10.2f;

    private static PositionedText run(String text, float x, float y, int page) {
        return new PositionedText(text, x, y, page);
    }

    private static void banner(List<PositionedText> runs, float y, int page) {
        runs.add(run("SAMPLE HOLDER SAVINGS ACCOUNT  - 00000000000001", COL[0], y, page));
    }

    private static void header(List<PositionedText> runs, float y, int page) {
        String[] names = {"DATE", "NARRATION", "CHQ.NO.", "WITHDRAWAL (DR)", "DEPOSIT (CR)", "BALANCE"};
        for (int i = 0; i < names.length; i++) runs.add(run(names[i], COL[i], y, page));
    }

    /** One transaction: narration line above, the date row, a wrapped tail below. Returns the tail's y. */
    private static float transaction(List<PositionedText> runs, float y, int page, String narration,
                                     String date, String withdrawal, String balance, String tail) {
        runs.add(run(narration, NARRATION_X, y, page));
        y += LINE;
        runs.add(run(date, COL[0], y, page));
        runs.add(run(withdrawal, COL[3], y, page));
        runs.add(run(balance, COL[5], y, page));
        y += LINE;
        runs.add(run(tail, NARRATION_X, y, page));
        return y;
    }

    private static void footer(List<PositionedText> runs, float y, int page) {
        runs.add(run("Page  1  |  3", COL[5], y, page));
        runs.add(run("1800 0000", COL[5], y + 25.4f, page));
        runs.add(run("  https://www.samplebank.example", COL[0], y + 30.8f, page));
        runs.add(run("Cyber Crime Helpline", COL[2], y + 30.8f, page));
        runs.add(run("1930", COL[3], y + 30.8f, page));
        runs.add(run("Customer Care", COL[4], y + 30.8f, page));
        runs.add(run("1800 0001", COL[5], y + 35.6f, page));
    }

    private static List<PositionedText> twoPageSample() {
        List<PositionedText> runs = new ArrayList<>();
        banner(runs, 64.4f, 0);
        header(runs, 76.95f, 0);
        float y = 88.4f;
        y = transaction(runs, y, 0, "UPI/000000000001/18:39:34/UPI/first-merchant", "03-06-2026", "1420.00", "31470.16 Cr", "one@bank");
        y = transaction(runs, y + BLOCK, 0, "UPI/000000000002/00:32:28/UPI/second-merchant", "03-06-2026", "1211.00", "30259.16 Cr", "two@bank");
        footer(runs, 775.9f, 0);
        runs.add(run(" Statement of transactions in Savings Account 00000000000001", 200f, 40.5f, 1));
        banner(runs, 64.4f, 1);
        header(runs, 76.95f, 1);
        y = 88.4f;
        y = transaction(runs, y, 1, "UPI/000000000003/22:13:30/UPI/first-line-of-b", "04-06-2026", "800.00", "29459.16 Cr", "ez@bank");
        transaction(runs, y + BLOCK, 1, "UPI/000000000004/00:08:45/UPI/fourth-merchant", "05-06-2026", "920.00", "28539.16 Cr", "four@bank");
        return runs;
    }

    private static List<Map<String, String>> rowsOf(List<PositionedText> runs, DocumentContext ctx) {
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);
        assertThat(doc.sections()).hasSize(1);
        return doc.sections().get(0).rows();
    }

    @Test
    void theFirstNarrationLineAfterARepeatedBanner_belongsToTheNextDatedRow() {
        DocumentContext ctx = new DocumentContext("PDF", "test");
        List<Map<String, String>> rows = rowsOf(twoPageSample(), ctx);

        List<Map<String, String>> dated = rows.stream().filter(r -> r.get("DATE") != null && r.get("DATE").matches("\\d\\d-\\d\\d-\\d{4}")).toList();
        assertThat(dated).as("four transactions").hasSize(4);
        Map<String, String> b = dated.get(2);
        assertThat(b).containsEntry("DATE", "04-06-2026").containsEntry("WITHDRAWAL (DR)", "800.00");
        assertThat(b.get("NARRATION"))
                .as("the first transaction after the page break keeps its own narration line, then its tail")
                .startsWith("UPI/000000000003/22:13:30/UPI/first-line-of-b").endsWith("ez@bank");
        assertThat(rows.stream().filter(r -> !dated.contains(r)))
                .as("no dateless leftover row carries the transaction's narration")
                .noneMatch(r -> String.join(" ", r.values()).contains("first-line-of-b"));
        assertThat(dated.get(1).get("NARRATION")).doesNotContain("Page").doesNotContain("Helpline");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).contains(CAPABILITY);
    }

    @Test
    void withoutAPageBreak_theSameTransactionsAreUnchanged() {
        // Control: the same four transactions on one page, no footer, no repeated banner.
        List<PositionedText> runs = new ArrayList<>();
        banner(runs, 64.4f, 0);
        header(runs, 76.95f, 0);
        float y = 88.4f;
        y = transaction(runs, y, 0, "UPI/000000000001/18:39:34/UPI/first-merchant", "03-06-2026", "1420.00", "31470.16 Cr", "one@bank");
        y = transaction(runs, y + BLOCK, 0, "UPI/000000000002/00:32:28/UPI/second-merchant", "03-06-2026", "1211.00", "30259.16 Cr", "two@bank");
        y = transaction(runs, y + BLOCK, 0, "UPI/000000000003/22:13:30/UPI/first-line-of-b", "04-06-2026", "800.00", "29459.16 Cr", "ez@bank");
        transaction(runs, y + BLOCK, 0, "UPI/000000000004/00:08:45/UPI/fourth-merchant", "05-06-2026", "920.00", "28539.16 Cr", "four@bank");

        DocumentContext ctx = new DocumentContext("PDF", "test");
        List<Map<String, String>> rows = rowsOf(runs, ctx);

        assertThat(rows).hasSize(4);
        assertThat(rows.get(2).get("NARRATION")).startsWith("UPI/000000000003/22:13:30/UPI/first-line-of-b");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).doesNotContain(CAPABILITY);
    }

    @Test
    void anIdentityLineRepeatedMidTable_onTheAnchorsOwnPage_doesNotCloseTheTrail() {
        // The same banner text printed between a date row and its wrapped tail, on the page the
        // date row is on. That is not page-top furniture: nothing ended, so the tail still belongs
        // to the row above it and must not be handed to the next transaction.
        List<PositionedText> runs = new ArrayList<>();
        banner(runs, 64.4f, 0);
        header(runs, 76.95f, 0);
        float y = 88.4f;
        y = transaction(runs, y, 0, "UPI/000000000001/18:39:34/UPI/first-merchant", "03-06-2026", "1420.00", "31470.16 Cr", "one@bank");
        y += BLOCK;
        runs.add(run("UPI/000000000002/00:32:28/UPI/second-merchant", NARRATION_X, y, 0));
        y += LINE;
        runs.add(run("03-06-2026", COL[0], y, 0));
        runs.add(run("1211.00", COL[3], y, 0));
        runs.add(run("30259.16 Cr", COL[5], y, 0));
        y += LINE;
        banner(runs, y, 0);                                          // repeated mid-table
        y += LINE;
        runs.add(run("two@bank", NARRATION_X, y, 0));                // the tail of the row above
        y = transaction(runs, y + BLOCK, 0, "UPI/000000000003/22:13:30/UPI/first-line-of-b", "04-06-2026", "800.00", "29459.16 Cr", "ez@bank");
        transaction(runs, y + BLOCK, 0, "UPI/000000000004/00:08:45/UPI/fourth-merchant", "05-06-2026", "920.00", "28539.16 Cr", "four@bank");

        DocumentContext ctx = new DocumentContext("PDF", "test");
        List<Map<String, String>> rows = rowsOf(runs, ctx);

        assertThat(rows).hasSize(4);
        assertThat(rows.get(1).get("NARRATION")).as("the tail stays with its own row").endsWith("two@bank");
        assertThat(rows.get(2).get("NARRATION"))
                .startsWith("UPI/000000000003/22:13:30/UPI/first-line-of-b").doesNotContain("two@bank");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).doesNotContain(CAPABILITY);
    }
}
