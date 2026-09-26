package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DATELESS_AMOUNT_ROW_SPLIT: a dateless line that carries its own currency value in an amount
 * column the open anchor already filled is a second transaction printed under one date, not a
 * wrapped description.
 *
 * <p>Found on a real credit-card statement that prints a fee and the tax on that fee as two
 * consecutive lines under a single date: the tax line reached the trailing-continuation branch
 * with its own value in the Amount column, and {@link PdfTableLocator#mergeInto}'s guard against
 * invalidating an already-valid amount redirected that value into the description. The document
 * printed one more transaction than the parser staged, and the missing amount was visible only as
 * digits inside the previous line's narration.
 *
 * <p>Geometry below reproduces the shape that matters: the narration run sits nearer the Date
 * header's anchor than its own header's, so it buckets into the date column (exactly as on the real
 * document), which is why the split has to rehome it before stamping the inherited date. All text
 * is synthetic per the Synthetic Fixture Policy.
 */
class DatelessAmountRowPdfTableLocatorTest {

    private static final float HEADER_Y = 393.4f;
    private static final float PITCH = 12.0f;
    private static final float DATE_X = 40.9f;
    private static final float NARRATION_X = 72.4f;
    private static final float AMOUNT_X = 380.0f;
    private static final float INDICATOR_X = 430.0f;

    private static PositionedText run(String text, float x, float y) {
        return new PositionedText(text, x, y, 0);
    }

    private static List<PositionedText> cardHeader() {
        return List.of(
                run("Date", DATE_X, HEADER_Y),
                run("Transaction Details", 117.4f, HEADER_Y),
                run("Amount", AMOUNT_X, HEADER_Y),
                run("D/C", INDICATOR_X, HEADER_Y));
    }

    private static List<Map<String, String>> rowsOf(List<PositionedText> runs, DocumentContext ctx) {
        return new PdfTableLocator().locate(runs, ctx).rows();
    }

    @Test
    void aDatelessRowRepeatingTheAmountColumn_becomesItsOwnTransaction_withTheDateAbove() {
        List<PositionedText> runs = new ArrayList<>(cardHeader());
        float y = HEADER_Y + PITCH;
        runs.add(run("07 Aug 26", DATE_X, y));
        runs.add(run("INTEREST ON EMI", NARRATION_X, y));
        runs.add(run("274.43", AMOUNT_X, y));
        runs.add(run("D", INDICATOR_X, y));
        y += PITCH;                                             // the tax on that fee: dateless, own amount
        runs.add(run("IGST DB @ 18.00%", NARRATION_X, y));
        runs.add(run("49.40", AMOUNT_X, y));
        runs.add(run("D", INDICATOR_X, y));
        y += PITCH;                                             // a narration-only line under it
        runs.add(run("TRANSACTIONS FOR A B CDE", NARRATION_X, y));
        y += PITCH;
        runs.add(run("09 Aug 26", DATE_X, y));
        runs.add(run("SHOP", NARRATION_X, y));
        runs.add(run("100.00", AMOUNT_X, y));
        runs.add(run("D", INDICATOR_X, y));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        List<Map<String, String>> rows = rowsOf(runs, ctx);

        assertThat(rows).as("fee, tax on the fee, and the next purchase").hasSize(3);
        assertThat(rows.get(0)).containsEntry("Date", "07 Aug 26").containsEntry("Amount", "274.43");
        assertThat(rows.get(0).get("Transaction Details")).doesNotContain("49.40").doesNotContain("IGST");
        assertThat(rows.get(1)).containsEntry("Date", "07 Aug 26").containsEntry("Amount", "49.40")
                .containsEntry("D/C", "D");
        assertThat(rows.get(1).get("Transaction Details")).startsWith("IGST DB @ 18.00%");
        assertThat(rows.get(1).get("Transaction Details"))
                .as("the narration-only line still trails the row it is printed under")
                .contains("TRANSACTIONS FOR A B CDE");
        assertThat(rows.get(2)).containsEntry("Date", "09 Aug 26").containsEntry("Amount", "100.00");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("DATELESS_AMOUNT_ROW_SPLIT");
    }

    @Test
    void aDatelessAmountLine_underAnAnchorWithNoAmount_isStillAContinuation() {
        // The layout PdfTableLocator's trailing branch already documents: the date row carries only
        // the narration, and a second, genuinely dateless line carries the amounts and balance. The
        // anchor has no value in the amount column, so there is nothing for the second line to
        // repeat, and the rule cannot fire.
        float withdrawalX = 380.0f, balanceX = 470.0f;
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Date", DATE_X, HEADER_Y),
                run("Particulars", 117.4f, HEADER_Y),
                run("Withdrawal", withdrawalX, HEADER_Y),
                run("Balance", balanceX, HEADER_Y)));
        float y = HEADER_Y + PITCH;
        runs.add(run("01/05/26", DATE_X, y));
        runs.add(run("PAYEE ONE", 117.4f, y));
        y += PITCH;
        runs.add(run("500.00", withdrawalX, y));
        runs.add(run("9,500.00", balanceX, y));
        y += PITCH;
        runs.add(run("02/05/26", DATE_X, y));
        runs.add(run("PAYEE TWO", 117.4f, y));
        y += PITCH;
        runs.add(run("250.00", withdrawalX, y));
        runs.add(run("9,250.00", balanceX, y));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        List<Map<String, String>> rows = rowsOf(runs, ctx);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("Withdrawal", "500.00").containsEntry("Balance", "9,500.00");
        assertThat(rows.get(1)).containsEntry("Withdrawal", "250.00");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .doesNotContain("DATELESS_AMOUNT_ROW_SPLIT");
    }

    @Test
    void aDatelessAmountLine_onARunningBalanceLedger_isStillAContinuation() {
        // A ledger prints a balance on every transaction; a dateless line carrying an amount and
        // no balance is a totals line or a footer figure, never a second entry. Measured on real
        // running-balance statements: the first draft of this rule turned a closing-summary
        // figure and a totals line into transactions of their own.
        float withdrawalX = 380.0f, balanceX = 470.0f;
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Date", DATE_X, HEADER_Y),
                run("Particulars", 117.4f, HEADER_Y),
                run("Withdrawal", withdrawalX, HEADER_Y),
                run("Balance", balanceX, HEADER_Y)));
        float y = HEADER_Y + PITCH;
        runs.add(run("01/05/26", DATE_X, y));
        runs.add(run("PAYEE ONE", 117.4f, y));
        runs.add(run("500.00", withdrawalX, y));
        runs.add(run("9,500.00", balanceX, y));
        y += PITCH;
        runs.add(run("Total for the period", 117.4f, y));
        runs.add(run("500.00", withdrawalX, y));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        List<Map<String, String>> rows = rowsOf(runs, ctx);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("Withdrawal", "500.00").containsEntry("Balance", "9,500.00");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .doesNotContain("DATELESS_AMOUNT_ROW_SPLIT");
    }

    @Test
    void aColumnThatMerelyContainsAnAmountWord_isNotAnAmountColumnForThisRule() {
        // A card statement's summary grid: a date-bearing "Payment Due Date" column beside an
        // "Available Credit Limit" column. Both grid rows carry currency values there, and the
        // second row must not be promoted into a transaction of the first's date.
        float dueDateX = 40.9f, limitX = 200.0f, cashX = 300.0f;
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Payment Due Date", dueDateX, HEADER_Y),
                run("Available Credit Limit", limitX, HEADER_Y),
                run("Available Cash Limit", cashX, HEADER_Y)));
        float y = HEADER_Y + PITCH;
        runs.add(run("27 Aug 2026", dueDateX, y));
        runs.add(run("26,089.88", limitX, y));
        runs.add(run("10,000.00", cashX, y));
        y += PITCH;
        runs.add(run("90,653.62", limitX, y));
        runs.add(run("4,870.00", cashX, y));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        rowsOf(runs, ctx);

        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .doesNotContain("DATELESS_AMOUNT_ROW_SPLIT");
    }

    @Test
    void aLineOfFinePrintWithAFigure_underAnAnchorThatPrintsAReference_doesNotSplit() {
        // A card layout with a reference column: the transaction prints its reference, the
        // illustrative panel line under it prints only text and a figure. Measured on a committed
        // trace, without this guard a fifty-line interest illustration became fifty transactions.
        float refX = 300.0f;
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Date", DATE_X, HEADER_Y),
                run("Transaction Details", 117.4f, HEADER_Y),
                run("Ref No.", refX, HEADER_Y),
                run("Amount", AMOUNT_X, HEADER_Y)));
        float y = HEADER_Y + PITCH;
        runs.add(run("06/07/2026", DATE_X, y));
        runs.add(run("SAMPLE MERCHANT", 117.4f, y));
        runs.add(run("00000000001", refX, y));
        runs.add(run("2,711.70", AMOUNT_X, y));
        y += PITCH;
        runs.add(run("Total Amount Due on statement date", 117.4f, y));
        runs.add(run("2,000.00", AMOUNT_X, y));
        y += PITCH;
        runs.add(run("Minimum Amount Due on statement date", 117.4f, y));
        runs.add(run("100.00", AMOUNT_X, y));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        List<Map<String, String>> rows = rowsOf(runs, ctx);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("Amount", "2,711.70");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .doesNotContain("DATELESS_AMOUNT_ROW_SPLIT");
    }

    @Test
    void aSplitRowNeverSeedsAChain_pastTheTrailingCap() {
        // Three figure-bearing lines under one dated anchor on a plain three-column card layout:
        // the count cap that bounds trailing continuations bounds splits the same way, so the
        // third line is not promoted off the second one's back.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Date", DATE_X, HEADER_Y),
                run("Transaction Details", 117.4f, HEADER_Y),
                run("Amount", AMOUNT_X, HEADER_Y)));
        float y = HEADER_Y + PITCH;
        runs.add(run("06/07/2026", DATE_X, y));
        runs.add(run("SAMPLE MERCHANT", 117.4f, y));
        runs.add(run("2,711.70", AMOUNT_X, y));
        for (String amount : List.of("2,000.00", "100.00", "20.00", "3.60")) {
            y += PITCH;
            runs.add(run("PANEL LINE " + amount, 117.4f, y));
            runs.add(run(amount, AMOUNT_X, y));
        }

        DocumentContext ctx = new DocumentContext("PDF", "test");
        List<Map<String, String>> rows = rowsOf(runs, ctx);

        List<Map<String, String>> dated = rows.stream().filter(r -> "06/07/2026".equals(r.get("Date"))).toList();
        assertThat(dated).as("the anchor plus at most the cap's worth of splits under it").hasSizeLessThanOrEqualTo(3);
        assertThat(dated.stream().map(r -> r.get("Amount")).toList())
                .as("the lines past the cap never inherit the date")
                .doesNotContain("20.00", "3.60");
    }

    @Test
    void aRowWhoseDateCellHoldsAJoinedFigureAndDate_isNotDateless_andIsNotSplit() {
        // A deposit schedule whose date cell reads "<figure> <date>": the date failed to bucket,
        // it was not absent, and inheriting the row above's date would replace a printed one.
        float principalX = 150.0f, openDateX = 250.0f, maturityX = 380.0f;
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Deposit Principal", principalX, HEADER_Y),
                run("Open Date", openDateX, HEADER_Y),
                run("Maturity Amount", maturityX, HEADER_Y)));
        float y = HEADER_Y + PITCH;
        runs.add(run("26,757.00", principalX, y));
        runs.add(run("04/11/2026", openDateX, y));
        runs.add(run("25,000.00", maturityX, y));
        y += PITCH;
        runs.add(run("5,000.00 07/10/2025", openDateX, y));
        runs.add(run("5,516.00", maturityX, y));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        rowsOf(runs, ctx);

        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .doesNotContain("DATELESS_AMOUNT_ROW_SPLIT");
    }

    @Test
    void aColumnTotalLine_underTheLastTransaction_isNeverATransaction() {
        // A card table's own column total: same page, directly under the last transaction, as
        // complete as any transaction row, currency figure in the Amount column. Only its wording
        // says what it is.
        float categoryX = 300.0f;
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Date", DATE_X, HEADER_Y),
                run("Transaction details", 117.4f, HEADER_Y),
                run("Category", categoryX, HEADER_Y),
                run("Amount (Rs.)", AMOUNT_X, HEADER_Y)));
        float y = HEADER_Y + PITCH;
        runs.add(run("14/03/2026", DATE_X, y));
        runs.add(run("SAMPLE MERCHANT ONE", 117.4f, y));
        runs.add(run("Shopping", categoryX, y));
        runs.add(run("160.00", AMOUNT_X, y));
        y += PITCH;
        runs.add(run("Total Purchases & Other", 117.4f, y));
        runs.add(run("Charges", categoryX, y));
        runs.add(run("5,178.69", AMOUNT_X, y));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        List<Map<String, String>> rows = rowsOf(runs, ctx);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("Amount (Rs.)", "160.00");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .doesNotContain("DATELESS_AMOUNT_ROW_SPLIT");
    }

    @Test
    void aBareDigitRunLandingInTheAmountColumn_isNotAnAmount_andDoesNotSplit() {
        // A wrapped reference tail whose digit group happens to land in the amount column's x-range
        // parses as a number but carries no decimal point; mergeInto already rehomes it into the
        // narration, and this rule must not promote it into a transaction of that "amount".
        List<PositionedText> runs = new ArrayList<>(cardHeader());
        float y = HEADER_Y + PITCH;
        runs.add(run("07 Aug 26", DATE_X, y));
        runs.add(run("SAMPLE PAYEE", NARRATION_X, y));
        runs.add(run("274.43", AMOUNT_X, y));
        runs.add(run("D", INDICATOR_X, y));
        y += PITCH;
        runs.add(run("REFERENCE TAIL", NARRATION_X, y));
        runs.add(run("2211900", AMOUNT_X, y));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        List<Map<String, String>> rows = rowsOf(runs, ctx);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("Amount", "274.43");
        assertThat(rows.get(0).get("Transaction Details")).contains("REFERENCE TAIL").contains("2211900");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .doesNotContain("DATELESS_AMOUNT_ROW_SPLIT");
    }
}
