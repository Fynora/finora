package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TRAILING_REFUSED_BEHIND_LEADING_BUFFER: once a dateless line has been buffered as the next
 * transaction's leading narration, no line printed below it can be the previous transaction's
 * trailing continuation -- text does not interleave.
 *
 * <p>Traced on a real Canara Bank statement, whose layout prints up to four narration lines ABOVE
 * each date row and a short trail (reference, time, "Chq: &lt;number&gt;") below it, in two
 * variants with one root. Across a page break: a transaction closed at the bottom of one page with
 * a bare "Chq:" and only one trailing line counted; the next transaction's first line at the top of
 * the next page was refused as a trailing continuation (different page) and buffered as leading
 * narration, which moved the "last row page" to the new page; its SECOND line then passed the
 * same-page test, the count cap still had room, the spacing was a tie, and it was merged into the
 * transaction on the previous page. On one page: the first line sat visibly nearer the transaction
 * below it (buffered by proximity) and the second, at the leading pitch, tied with its neighbours
 * and was merged above. Either way the earlier transaction's narration ended with a fragment of
 * the next one's UPI address, and the next one lost its second line.
 *
 * <p>Geometry below is the real document's (narration column at x=106.64, date column at 26.56,
 * leading lines 12pt apart, 6pt from the last leading line to the date row, trailing lines 18pt
 * apart, a repeated header at y=50 on the new page). Text is synthetic per the Synthetic Fixture
 * Policy.
 */
class PageBreakLeadingNarrationPdfTableLocatorTest {

    private static final String CAPABILITY = "TRAILING_REFUSED_BEHIND_LEADING_BUFFER";

    private static final float DATE_X = 26.56f;
    private static final float NARRATION_X = 106.64f;
    private static final float DEPOSITS_X = 330.0f;
    private static final float WITHDRAWALS_X = 420.0f;
    private static final float BALANCE_X = 500.0f;
    private static final float LEADING_PITCH = 12.0f;
    private static final float LEADING_TO_DATE = 6.0f;
    private static final float TRAILING_PITCH = 18.0f;

    private static PositionedText run(String text, float x, float y, int page) {
        return new PositionedText(text, x, y, page);
    }

    private static void header(List<PositionedText> runs, float y, int page) {
        runs.add(run("Date", 43.57f, y, page));
        runs.add(run("Particulars", NARRATION_X, y, page));
        runs.add(run("Deposits", DEPOSITS_X, y, page));
        runs.add(run("Withdrawals", WITHDRAWALS_X, y, page));
        runs.add(run("Balance", BALANCE_X, y, page));
    }

    private static void dateRow(List<PositionedText> runs, float y, int page, String date, String deposit, String balance) {
        runs.add(run(date, DATE_X, y, page));
        runs.add(run(deposit, DEPOSITS_X, y, page));
        runs.add(run(balance, BALANCE_X, y, page));
    }

    /** A transaction whose two trailing lines (time, "Chq: &lt;number&gt;") use up the trailing
     *  count cap, so the lines that follow are read as the NEXT transaction's leading narration --
     *  the state every transaction after the first is in on the real document. */
    private static void seedTransaction(List<PositionedText> runs, float y, int page) {
        runs.add(run("MOB-IMPS-CR/SAMPLE/0000000001/IMPS/", NARRATION_X, y, page));
        dateRow(runs, y += LEADING_TO_DATE, page, "08-07-2026", "51.00", "1,17,501.50");
        runs.add(run("19:41:00/000000000001", NARRATION_X, y += TRAILING_PITCH, page));
        runs.add(run("Chq: 000000000001", NARRATION_X, y += TRAILING_PITCH, page));
    }

    private static List<Map<String, String>> rowsOf(List<PositionedText> runs, DocumentContext ctx) {
        return new PdfTableLocator().locate(runs, ctx).rows();
    }

    private static List<PositionedText> twoPageSample() {
        List<PositionedText> runs = new ArrayList<>();
        // Page 0: a first transaction whose trail fills the count cap (as on the real document),
        // then transaction A -- two leading lines, its date row, a bare "Chq:" trail.
        header(runs, 50.0f, 0);
        float y = 700.0f;
        seedTransaction(runs, y, 0);
        y += 2 * TRAILING_PITCH + 22.0f;
        runs.add(run("NACH SAMPLE MANDATE 0000001", NARRATION_X, y, 0));
        runs.add(run("2388041", NARRATION_X, y += LEADING_PITCH, 0));
        dateRow(runs, y += LEADING_TO_DATE, 0, "09-07-2026", "3.00", "1,17,504.50");
        runs.add(run("Chq:", NARRATION_X, y += TRAILING_PITCH, 0));
        runs.add(run("page 3", 531.65f, y += TRAILING_PITCH, 0));
        // Page 1: repeated header, then transaction B -- three leading lines, its date row, its trail.
        header(runs, 50.0f, 1);
        y = 74.0f;
        runs.add(run("LINE-ONE-OF-B/000000000002/PAYEE", NARRATION_X, y, 1));
        runs.add(run("LINE-TWO-OF-B/SAMPLE@OKBANK/UPI//I", NARRATION_X, y += LEADING_PITCH, 1));
        runs.add(run("LINE-THREE-OF-B00000000000000000", NARRATION_X, y += LEADING_PITCH, 1));
        dateRow(runs, y += LEADING_TO_DATE, 1, "09-07-2026", "25,000.00", "1,42,504.50");
        runs.add(run("00000000B/09/07/2026 22:26:42", NARRATION_X, y += LEADING_TO_DATE, 1));
        runs.add(run("Chq: 000000000002", NARRATION_X, y += 24.0f, 1));
        return runs;
    }

    @Test
    void leadingNarrationAtTheTopOfANewPage_belongsToTheRowBelow_neverToTheRowAbove() {
        DocumentContext ctx = new DocumentContext("PDF", "test");
        List<Map<String, String>> rows = rowsOf(twoPageSample(), ctx);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(1).get("Particulars"))
                .as("the transaction on the previous page keeps exactly its own text")
                .isEqualTo("NACH SAMPLE MANDATE 0000001 2388041 Chq:");
        assertThat(rows.get(2).get("Particulars"))
                .as("the next transaction keeps all three of its leading lines, in order")
                .startsWith("LINE-ONE-OF-B/000000000002/PAYEE LINE-TWO-OF-B/SAMPLE@OKBANK/UPI//I LINE-THREE-OF-B");
        assertThat(rows.get(2)).containsEntry("Deposits", "25,000.00");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).contains(CAPABILITY);
    }

    @Test
    void onOnePage_aLineBelowABufferedLeadingLine_neverTrailsTheRowAbove() {
        // The same sequence with no page break: the first line of B is buffered by proximity (it
        // sits nearer B's date row than A's trail), and the second line, which ties, must follow it
        // into B rather than trail A.
        List<PositionedText> runs = new ArrayList<>();
        header(runs, 50.0f, 0);
        float y = 100.0f;
        seedTransaction(runs, y, 0);
        y += 2 * TRAILING_PITCH + 22.0f;
        runs.add(run("NACH SAMPLE MANDATE 0000001", NARRATION_X, y, 0));
        runs.add(run("2388041", NARRATION_X, y += LEADING_PITCH, 0));
        dateRow(runs, y += LEADING_TO_DATE, 0, "09-07-2026", "3.00", "1,17,504.50");
        runs.add(run("Chq:", NARRATION_X, y += TRAILING_PITCH, 0));
        runs.add(run("LINE-ONE-OF-B/000000000002/PAYEE", NARRATION_X, y += TRAILING_PITCH, 0));
        runs.add(run("LINE-TWO-OF-B/SAMPLE@OKBANK/UPI//I", NARRATION_X, y += LEADING_PITCH, 0));
        runs.add(run("LINE-THREE-OF-B00000000000000000", NARRATION_X, y += LEADING_PITCH, 0));
        dateRow(runs, y += LEADING_TO_DATE, 0, "09-07-2026", "25,000.00", "1,42,504.50");

        DocumentContext ctx = new DocumentContext("PDF", "test");
        List<Map<String, String>> rows = rowsOf(runs, ctx);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(1).get("Particulars")).isEqualTo("NACH SAMPLE MANDATE 0000001 2388041 Chq:");
        assertThat(rows.get(2).get("Particulars"))
                .startsWith("LINE-ONE-OF-B/000000000002/PAYEE LINE-TWO-OF-B/SAMPLE@OKBANK/UPI//I LINE-THREE-OF-B");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).contains(CAPABILITY);
    }
}
