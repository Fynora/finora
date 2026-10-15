package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The row map (docs/superpowers/specs/2026-10-15-row-map-design.md): every row the locator
 * returns names the printed lines it was built from, on every row-building path, and a line that
 * went to auxiliary text is in no row.
 *
 * <p>All text is synthetic; the layouts are copied from the existing locator tests named beside
 * each case, so each path is reached the way that test already proves it is.
 */
class PdfTableLocatorRowLinesTest {

    private static PositionedText run(String text, float x, float y) {
        return new PositionedText(text, x, y, 0);
    }

    private static PositionedText run(String text, float x, float y, int page) {
        return new PositionedText(text, x, y, page);
    }

    private static PositionedText spanned(String text, float x, float endX, float y) {
        return new PositionedText(text, x, y, 0, endX - x);
    }

    private static RowLines.Line line(int page, float y) {
        return new RowLines.Line(page, RowLines.hundredths(y));
    }

    private static List<String> capabilities(DocumentContext ctx) {
        return ctx.capabilities().stream().map(c -> c.capability()).toList();
    }

    /** Every row has a line list aligned with it, and row {@code i}'s lines are {@code expected[i]}. */
    @SafeVarargs
    private static void assertRowLines(PdfTableLocator.LocatedSection section, List<RowLines.Line>... expected) {
        assertThat(section.rowLines()).hasSameSizeAs(section.rows());
        assertThat(section.rows()).hasSize(expected.length);
        for (int i = 0; i < expected.length; i++) {
            assertThat(section.rowLines().get(i).lines()).as("row %d", i).containsExactlyElementsOf(expected[i]);
        }
    }

    // ---- the header-based path: Date | Description | Debit | Credit | Balance, 14pt spacing

    private static final float DATE_X = 30f;
    private static final float DESC_X = 100f;
    private static final float DEBIT_X = 300f;
    private static final float CREDIT_X = 380f;
    private static final float BALANCE_X = 460f;

    private static List<PositionedText> header(float y, int page) {
        return List.of(
                run("Date", DATE_X, y, page),
                run("Description", DESC_X, y, page),
                run("Debit", DEBIT_X, y, page),
                run("Credit", CREDIT_X, y, page),
                run("Balance", BALANCE_X, y, page));
    }

    private static void transaction(List<PositionedText> runs, String date, String narration, String debit,
                                    String balance, float y, int page) {
        runs.add(run(date, DATE_X, y, page));
        runs.add(run(narration, DESC_X, y, page));
        runs.add(run(debit, DEBIT_X, y, page));
        runs.add(run(balance, BALANCE_X, y, page));
    }

    @Test
    void aSingleLineRowRecordsItsLine() {
        List<PositionedText> runs = new ArrayList<>(header(100f, 0));
        transaction(runs, "01/05/2026", "SAMPLE SHOP ONE", "100.00", "900.00", 114f, 0);
        transaction(runs, "02/05/2026", "SAMPLE SHOP TWO", "50.00", "850.00", 128f, 0);

        PdfTableLocator.LocatedSection section = new PdfTableLocator().locateAll(runs).sections().get(0);

        assertRowLines(section, List.of(line(0, 114f)), List.of(line(0, 128f)));
    }

    @Test
    void aContinuationLineJoinsTheRowAbove() {
        List<PositionedText> runs = new ArrayList<>(header(100f, 0));
        transaction(runs, "01/05/2026", "SAMPLE SHOP ONE", "100.00", "900.00", 114f, 0);
        runs.add(run("WRAPPED TAIL", DESC_X, 128f));
        transaction(runs, "02/05/2026", "SAMPLE SHOP TWO", "50.00", "850.00", 142f, 0);

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedSection section = new PdfTableLocator().locateAll(runs, ctx).sections().get(0);

        assertThat(section.rows().get(0).get("Description")).contains("WRAPPED TAIL");
        assertThat(capabilities(ctx)).contains("WRAPPED_DESCRIPTION");
        assertRowLines(section, List.of(line(0, 114f), line(0, 128f)), List.of(line(0, 142f)));
    }

    // ---- leading narration, the buffer split and the leading merge
    //      (layout from WrappedNarrationBeyondCountCapPdfTableLocatorTest)

    private static final float WN_DATE_X = 33.7f;
    private static final float WN_NARRATION_X = 72.0f;
    private static final float WN_AMOUNT_X = 470.0f;
    private static final float WN_BALANCE_X = 530.0f;
    private static final float WN_CONT_GAP = 10.04f;

    private static List<PositionedText> alignedHeader(float y, int page) {
        return List.of(
                run("Date", 39.9f, y, page),
                run("Narration", WN_NARRATION_X, y, page),
                run("Chq./Ref.No.", 283.5f, y, page),
                run("Withdrawal Amt.", WN_AMOUNT_X, y, page),
                run("Closing Balance", WN_BALANCE_X, y, page));
    }

    private static void datedAmountLine(List<PositionedText> runs, String date, String amount, String balance,
                                        float y, int page) {
        runs.add(run(date, WN_DATE_X, y, page));
        runs.add(run(amount, WN_AMOUNT_X, y, page));
        runs.add(run(balance, WN_BALANCE_X, y, page));
    }

    @Test
    void aLeadingNarrationLineIsRecorded() {
        List<PositionedText> runs = new ArrayList<>(alignedHeader(100f, 0));
        float anchor1 = 100f + WN_CONT_GAP;
        datedAmountLine(runs, "01/05/26", "170.00", "9,830.00", anchor1, 0);
        float alpha = anchor1 + 5.0f;
        float bravo = alpha + WN_CONT_GAP;
        float charlie = bravo + WN_CONT_GAP;
        float delta = charlie + WN_CONT_GAP;
        float head = delta + 9.86f;
        float anchor2 = head + 5.06f;
        runs.add(run("OWN NARRATION ALPHA", WN_NARRATION_X, alpha));
        runs.add(run("OWN NARRATION BRAVO", WN_NARRATION_X, bravo));
        runs.add(run("OWN NARRATION CHARLIE", WN_NARRATION_X, charlie));
        runs.add(run("OWN NARRATION DELTA", WN_NARRATION_X, delta));
        runs.add(run("NEXT TRANSACTION HEAD", WN_NARRATION_X, head));
        datedAmountLine(runs, "02/05/26", "50.00", "9,780.00", anchor2, 0);

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedSection section = new PdfTableLocator().locateAll(runs, ctx).sections().get(0);

        // CHARLIE and DELTA were buffered forward and then split back onto the row above; the head
        // line was buffered and prepended to the row below.
        assertThat(capabilities(ctx)).contains("LEADING_BUFFER_SPLIT_AT_ITS_OWN_BOUNDARY", "LEADING_NARRATION_CONTINUATION");
        assertThat(section.rows().get(1).get("Narration")).contains("NEXT TRANSACTION HEAD");
        assertRowLines(section,
                List.of(line(0, anchor1), line(0, alpha), line(0, bravo), line(0, charlie), line(0, delta)),
                List.of(line(0, head), line(0, anchor2)));
    }

    @Test
    void aRowAcrossAPageBreakRecordsBothPages() {
        List<PositionedText> runs = new ArrayList<>(alignedHeader(600f, 0));
        datedAmountLine(runs, "01/05/26", "170.00", "9,830.00", 614f, 0);
        runs.add(run("OWN NARRATION ALPHA", WN_NARRATION_X, 619f));
        runs.add(run("OWN NARRATION BRAVO", WN_NARRATION_X, 629f));
        // Past the trailing cap, with nothing below it on this page: buffered for the next anchor,
        // which is printed at the top of the next page.
        runs.add(run("NEXT TRANSACTION HEAD", WN_NARRATION_X, 639f));
        datedAmountLine(runs, "02/05/26", "50.00", "9,780.00", 80f, 1);

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedSection section = new PdfTableLocator().locateAll(runs, ctx).sections().get(0);

        assertThat(section.rows().get(1).get("Narration")).isEqualTo("NEXT TRANSACTION HEAD");
        assertRowLines(section,
                List.of(line(0, 614f), line(0, 619f), line(0, 629f)),
                List.of(line(0, 639f), line(1, 80f)));
    }

    @Test
    void aDiscardedLeadingLineIsNotRecordedAnywhere() {
        String banner = "SAVINGS ACCOUNT - 100000000001"; // synthetic-ok: placeholder account number
        List<PositionedText> runs = new ArrayList<>();
        runs.add(run(banner, WN_DATE_X, 80f, 0));
        runs.addAll(alignedHeader(100f, 0));
        datedAmountLine(runs, "01/05/26", "170.00", "9,830.00", 114f, 0);
        runs.add(run("OWN NARRATION ALPHA", WN_NARRATION_X, 119f));
        runs.add(run("OWN NARRATION BRAVO", WN_NARRATION_X, 129f));
        runs.add(run("PAGE FOOT NOTE", WN_NARRATION_X, 139f));
        // The next page repeats the account banner: whatever was buffered belongs to the page that
        // ended, and goes to auxiliary text.
        runs.add(run(banner, WN_DATE_X, 80f, 1));
        runs.addAll(alignedHeader(100f, 1));
        datedAmountLine(runs, "02/05/26", "50.00", "9,780.00", 114f, 1);

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(capabilities(ctx)).contains("LEADING_BUFFER_CLOSED_AT_REPEATED_BANNER");
        assertThat(doc.sections()).hasSize(1);
        PdfTableLocator.LocatedSection section = doc.sections().get(0);
        assertThat(section.auxiliaryText()).anySatisfy(text -> assertThat(text).contains("PAGE FOOT NOTE"));
        assertRowLines(section,
                List.of(line(0, 114f), line(0, 119f), line(0, 129f)),
                List.of(line(1, 114f)));
    }

    // ---- same-day rows (layout from SameDayContinuationTransactionPdfTableLocatorTest)

    @Test
    void sameDayRowsKeepTheirOwnLines() {
        List<PositionedText> runs = new ArrayList<>();
        runs.add(spanned("Date", 30f, 64f, 100f));
        runs.add(spanned("Details", 90f, 220f, 100f));
        runs.add(spanned("Withdrawals", 300f, 360f, 100f));
        runs.add(spanned("Deposits", 400f, 460f, 100f));
        runs.add(spanned("Balance", 500f, 560f, 100f));
        runs.add(spanned("01 Jun 2026", 30f, 90f, 120f));
        runs.add(spanned("REF0001", 90f, 160f, 120f));
        runs.add(spanned("REF0002", 90f, 160f, 132f));
        runs.add(spanned("John Smith", 90f, 160f, 144f));
        runs.add(spanned("500.00", 400f, 460f, 144f));
        runs.add(spanned("1500.00", 500f, 560f, 144f));
        runs.add(spanned("REF0003", 90f, 160f, 156f));
        runs.add(spanned("REF0004", 90f, 160f, 168f));
        runs.add(spanned("Acme Corp", 90f, 160f, 181.7f));
        runs.add(spanned("800.00", 300f, 360f, 181.7f));
        runs.add(spanned("700.00", 500f, 560f, 181.7f));
        runs.add(spanned("02 Jun 2026", 30f, 90f, 195f));
        runs.add(spanned("REF0005", 90f, 160f, 195f));
        runs.add(spanned("REF0006", 90f, 160f, 207f));
        runs.add(spanned("Payee X", 90f, 160f, 219f));
        runs.add(spanned("200.00", 300f, 360f, 219f));
        runs.add(spanned("500.00", 500f, 560f, 219f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedSection section = new PdfTableLocator().locateAll(runs, ctx).sections().get(0);

        assertThat(capabilities(ctx)).contains("SAME_DAY_CONTINUATION_TRANSACTION");
        // The two reference lines trail the first transaction; the same-day row is its own line.
        assertThat(section.rows().get(0).get("Details")).contains("REF0003").contains("REF0004");
        assertThat(section.rows().get(1).get("Details")).isEqualTo("Acme Corp");
        assertRowLines(section,
                List.of(line(0, 120f), line(0, 132f), line(0, 144f), line(0, 156f), line(0, 168f)),
                List.of(line(0, 181.7f)),
                List.of(line(0, 195f), line(0, 207f), line(0, 219f)));
    }

    @Test
    void aSameDayRowTakesTheLeadingLinesBufferedBeforeIt() {
        List<PositionedText> runs = new ArrayList<>(header(100f, 0));
        // The first transaction prints no narration on its own date row.
        runs.add(run("01/05/2026", DATE_X, 114f));
        runs.add(run("100.00", DEBIT_X, 114f));
        runs.add(run("900.00", BALANCE_X, 114f));
        runs.add(run("OWN NARRATION ALPHA", DESC_X, 128f));
        runs.add(run("OWN NARRATION BRAVO", DESC_X, 142f));
        // Past the trailing cap: buffered as leading narration for whatever comes next.
        runs.add(run("SECOND PAYEE", DESC_X, 156f));
        // No date of its own, and a balance that is not the open transaction's: a second
        // transaction on the same day, which takes the buffered line.
        runs.add(run("50.00", DEBIT_X, 170f));
        runs.add(run("850.00", BALANCE_X, 170f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedSection section = new PdfTableLocator().locateAll(runs, ctx).sections().get(0);

        assertThat(capabilities(ctx)).contains("SAME_DAY_CONTINUATION_TRANSACTION", "LEADING_NARRATION_CONTINUATION");
        assertThat(section.rows().get(1).get("Description")).isEqualTo("SECOND PAYEE");
        assertRowLines(section,
                List.of(line(0, 114f), line(0, 128f), line(0, 142f)),
                List.of(line(0, 156f), line(0, 170f)));
    }

    // ---- the headerless path (layout from HeaderlessLayoutInferenceTest)

    private static PositionedText cell(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    private static PositionedText amount(String text, float endX, float y) {
        float width = text.length() * 6.2f;
        return cell(text, endX - width, width, y);
    }

    private static List<PositionedText> headerlessTransaction(String date, String narration, String debit,
                                                              String credit, String balance, float y) {
        return List.of(
                cell(date, 30f, 55f, y),
                cell(date, 95f, 55f, y),
                cell(narration, 165f, narration.length() * 5.2f, y),
                amount(debit, 390f, y),
                amount(credit, 520f, y),
                amount(balance, 650f, y));
    }

    @Test
    void theHeaderlessPathRecordsLines() {
        List<PositionedText> runs = new ArrayList<>();
        runs.addAll(headerlessTransaction("01/01/2026", "GROCERY STORE PURCHASE MONTHLY", "500.00", "-", "9500.00", 300f));
        runs.add(cell("REF ID ABCDE123", 165f, 80f, 308f));
        runs.addAll(headerlessTransaction("02/01/2026", "SALARY CREDIT FROM EMPLOYER LTD", "-", "20000.00", "29500.00", 320f));
        runs.addAll(headerlessTransaction("03/01/2026", "ELECTRICITY BILL PAYMENT ONLINE", "1500.00", "-", "28000.00", 340f));
        runs.addAll(headerlessTransaction("04/01/2026", "MOBILE RECHARGE PREPAID PLAN", "300.00", "-", "27700.00", 360f));
        runs.addAll(headerlessTransaction("05/01/2026", "REFUND FROM ONLINE MERCHANT STORE", "-", "200.00", "27900.00", 380f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedSection section = new PdfTableLocator().locateAll(runs, ctx).sections().get(0);

        assertThat(capabilities(ctx)).contains("INFERRED_HEADERLESS_LAYOUT");
        assertThat(section.rows().get(0).get("Description")).contains("REF ID ABCDE123");
        assertRowLines(section,
                List.of(line(0, 300f), line(0, 308f)),
                List.of(line(0, 320f)),
                List.of(line(0, 340f)),
                List.of(line(0, 360f)),
                List.of(line(0, 380f)));
    }

    // ---- the balance-corroborated headerless fallback (layout from HeaderlessInstalmentContinuationTest)

    @Test
    void theCorroboratedFallbackRecordsAnInstalmentLineWithItsRow() {
        List<PositionedText> runs = List.of(
                spanned("05 OCT 2026", 370.3f, 412.7f, 10f),
                spanned("4,999.99", 440f, 470f, 10f),
                spanned("OPENING BALANCE", 77.3f, 144.2f, 100f),
                spanned("5,000.00", 381.1f, 408.4f, 100f),
                spanned("15SEP", 30.7f, 52.1f, 110f),
                spanned("SAMPLE BILL PAYMENT", 77.3f, 213.1f, 110f),
                spanned("1,000.00", 381.1f, 408.4f, 110f),
                spanned("CR", 413.5f, 423.6f, 110f),
                spanned("PURCHASES & INSTALMENTS", 77.3f, 213.1f, 120f),
                spanned("Interest rate applicable : 1.00% p.m.", 77.3f, 300f, 130f),
                spanned("20SEP", 30.7f, 52.1f, 140f),
                spanned("SAMPLE LENDER LTD", 77.3f, 213.1f, 140f),
                spanned("1,000.00", 381.1f, 408.4f, 140f),
                spanned("CR", 413.5f, 423.6f, 140f),
                spanned("2ND OF 3 INSTALMENTS PRINCIPAL", 77.3f, 260f, 150f),
                spanned("20SEP", 30.7f, 52.1f, 160f),
                spanned("SAMPLE LENDER LTD", 77.3f, 213.1f, 160f),
                spanned("1,000.00", 381.1f, 408.4f, 160f),
                spanned("2ND OF 3 INSTALMENTS PRINCIPAL", 77.3f, 260f, 170f),
                spanned("20SEP", 30.7f, 52.1f, 180f),
                spanned("SAMPLE FEE", 77.3f, 213.1f, 180f),
                spanned("1,020.00", 381.1f, 408.4f, 180f),
                spanned("30SEP", 31.4f, 51.7f, 200f),
                spanned("NET OUTSTANDING BALANCE", 78.7f, 180.2f, 200f),
                spanned("5,020.00", 393.1f, 406.7f, 200f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedSection section = new PdfTableLocator().locateAll(runs, ctx).sections().get(0);

        assertThat(capabilities(ctx)).contains("HEADERLESS_BALANCE_RECONCILIATION_CORROBORATED");
        assertThat(section.rows().get(1).get("Description")).contains("2ND OF 3 INSTALMENTS PRINCIPAL");
        assertThat(section.rows().get(2).get("Description")).contains("2ND OF 3 INSTALMENTS PRINCIPAL");
        assertRowLines(section,
                List.of(line(0, 110f)),
                List.of(line(0, 140f), line(0, 150f)),
                List.of(line(0, 160f), line(0, 170f)),
                List.of(line(0, 180f)));
    }

    // ---- the two-line date block (layout from TwoLineDateBlockInferenceTest)

    private static List<PositionedText> twoRowBlock(String day, String narration, String amountText,
                                                    String monthYear, String direction, float y) {
        return List.of(
                cell(day, 40f, 15f, y),
                cell(narration, 70f, narration.length() * 5.2f, y),
                amount(amountText, 510f, y),
                cell(monthYear, 34f, 21f, y + 16f),
                cell(direction, 74f, 8f, y + 16f));
    }

    @Test
    void aTwoLineBlockRecordsBothLines() {
        List<PositionedText> runs = new ArrayList<>();
        runs.addAll(twoRowBlock("07", "GROCERY MART PURCHASE", "₹500.00", "Jan 26", "Dr", 300f));
        runs.addAll(twoRowBlock("09", "SALARY CREDIT RECEIVED", "+₹20000.00", "Jan 26", "Cr", 340f));
        runs.addAll(twoRowBlock("14", "ONLINE SUBSCRIPTION FEE", "₹150.00", "Jan 26", "Dr", 380f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedSection section = new PdfTableLocator().locateAll(runs, ctx).sections().get(0);

        assertThat(capabilities(ctx)).contains("INFERRED_TWO_LINE_DATE_BLOCK");
        assertRowLines(section,
                List.of(line(0, 300f), line(0, 316f)),
                List.of(line(0, 340f), line(0, 356f)),
                List.of(line(0, 380f), line(0, 396f)));
    }

    // ---- more than one section

    @Test
    void rowLinesAlignWithRowsInEverySection() {
        List<PositionedText> runs = new ArrayList<>();
        runs.add(run("SAVINGS ACCOUNT - 100000000001", DATE_X, 80f, 0)); // synthetic-ok: placeholder
        runs.addAll(header(100f, 0));
        transaction(runs, "01/05/2026", "SAMPLE SHOP ONE", "100.00", "900.00", 114f, 0);
        transaction(runs, "02/05/2026", "SAMPLE SHOP TWO", "50.00", "850.00", 128f, 0);
        runs.add(run("SAVINGS ACCOUNT - 100000000002", DATE_X, 80f, 1)); // synthetic-ok: placeholder
        runs.addAll(header(100f, 1));
        transaction(runs, "03/05/2026", "SAMPLE SHOP THREE", "10.00", "490.00", 114f, 1);

        DocumentContext ctx = new DocumentContext("PDF", "test");
        List<PdfTableLocator.LocatedSection> sections = new PdfTableLocator().locateAll(runs, ctx).sections();

        assertThat(capabilities(ctx)).contains("COMPOSITE_STATEMENT");
        assertThat(sections).hasSize(2);
        assertRowLines(sections.get(0), List.of(line(0, 114f)), List.of(line(0, 128f)));
        assertRowLines(sections.get(1), List.of(line(1, 114f)));
    }

    @Test
    void aSectionBuiltWithoutARowMapHasNone() {
        PdfTableLocator.LocatedSection section = new PdfTableLocator.LocatedSection(
                List.of(), List.of(Map.of("Date", "01/05/2026")), PdfTableLocator.ExtractionEvidence.NONE);
        assertThat(section.rowLines()).isEmpty();
    }
}
