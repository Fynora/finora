package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MARGIN_PANEL_TEXT_EXCLUDED: a summary panel printed in the RIGHT MARGIN, at the same heights as
 * the transaction ledger, must not be bucketed into the ledger's rightmost column.
 *
 * <p>Every other correction in {@link PdfTableLocator#bucketRow} redirects a run BETWEEN columns.
 * None of them can express "this run belongs to no column at all", and {@code nearestColumn} has no
 * maximum-distance cap -- so a margin run sharing a physical row with a real transaction was
 * appended to whichever column was least far away, which is always the rightmost one.
 *
 * <p>Geometry below is measured from a real IndusInd credit-card statement; text is synthesized per
 * the Synthetic Fixture Policy -- no narration, merchant name, reference or amount is copied from
 * the source document. On that statement the ledger's five header cells end at x=55.2 (Date), 175.1,
 * 289.9, 352.4 and 417.9 (the rightmost, an amount column), while the margin panel's runs begin at
 * x=455.8 and beyond -- roughly 38pt of white space, with no horizontal overlap at all. The ledger's
 * own amounts are right-aligned to end at 422.6, so they START at 386-395: comfortably inside the
 * table, which is what makes the two separable by geometry rather than by a tolerance.
 *
 * <p>The concrete loss this caused: a margin label landed in a real transaction's amount cell,
 * making it "&lt;amount&gt; CR &lt;label&gt;". That string fails {@code CsvParser.parseNumeric}, so
 * {@code TransactionNormalizer} dropped the whole row -- and the document still classified
 * PARSED_COMPLETE, so a genuine transaction disappeared with nothing in any summary pointing at it.
 */
class MarginPanelTextPdfTableLocatorTest {

    private static final float HEADER_Y = 393.4f;

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    /** The real statement's five column anchors and widths, ending at x=417.9. */
    private static List<PositionedText> header() {
        return List.of(
                run("Date", 40.9f, 14.3f, HEADER_Y),
                run("Transaction Details", 117.4f, 57.7f, HEADER_Y),
                run("Merchant Category", 232.3f, 57.6f, HEADER_Y),
                run("CRED Points", 316.1f, 36.3f, HEADER_Y),
                run("Amount (in `)", 376.0f, 41.9f, HEADER_Y));
    }

    @Test
    void aMarginPanelLabelIsNotAppendedToTheAmountCell() {
        List<PositionedText> runs = new ArrayList<>(header());
        runs.addAll(List.of(
                run("18/08/2026", 25.9f, 45.0f, 420.0f),
                run("UPI SAMPLE MERCHANT 000000000001", 72.4f, 122.0f, 420.0f),
                run("DEPARTMENTAL STORES", 232.3f, 80.0f, 420.0f),
                run("0", 330.0f, 3.4f, 420.0f),
                // Right-aligned amount: starts at 395.4, ends at 422.6 -- inside the table.
                run("12.00 CR", 395.4f, 27.2f, 420.0f),
                // The margin panel's label, ~2pt above the row's baseline so groupIntoRows merges
                // it into this same physical row, and 61pt clear of the rightmost column.
                run("Statement Date", 479.4f, 48.1f, 417.8f)));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        var table = new PdfTableLocator().locate(runs, ctx);

        assertThat(table.rows()).hasSize(1);
        var row = table.rows().get(0);
        // The amount survives as a parseable value rather than "12.00 CR Statement Date".
        assertThat(row.get("Amount (in `)")).isEqualTo("12.00 CR");
        assertThat(row.get("Amount (in `)")).doesNotContain("Statement");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("MARGIN_PANEL_TEXT_EXCLUDED");
    }

    @Test
    void aNumberBeyondTheRightmostHeaderEndIsKept() {
        // Differential guard for the regression the first draft of this fix caused. A real Kotak
        // savings statement prints a Balance column whose header is NARROWER than the values under
        // it, so every right-aligned balance legitimately begins past the rightmost header end.
        // Excluding those replaced that document's closing balance with an earlier row's and turned
        // STATEMENT_TOTALS from VERIFIED to FAILED -- with the row count unchanged, so nothing
        // pointed at the wrong number. Only NON-numeric margin text may be excluded; a figure out
        // there is a right-aligned value overflowing its own header and must reach the redirect
        // rules. Header ends at 417.9; the balance below starts at 430.0, beyond it.
        List<PositionedText> runs = new ArrayList<>(header());
        runs.addAll(List.of(
                run("18/08/2026", 25.9f, 45.0f, 420.0f),
                run("UPI SAMPLE MERCHANT 000000000002", 72.4f, 122.0f, 420.0f),
                run("99,999.00", 430.0f, 40.0f, 420.0f)));

        var table = new PdfTableLocator().locate(runs, null);

        assertThat(table.rows()).hasSize(1);
        assertThat(String.join(" ", table.rows().get(0).values())).contains("99,999.00");
    }

    @Test
    void aPanelFigureInsideTheLearnedPanelBandIsNotAppendedToTheAmountCell() {
        // The numeric half of the same real IndusInd layout, one billing cycle later. The panel's
        // "Total Outstanding" figure (a Dr-suffixed amount, so parseNumeric accepts it) printed
        // 2.4pt below a real purchase's baseline and 62pt past the table's right edge, and the
        // numeric exemption above appended it to that purchase's own amount -- "16.96 DR 2,429.08
        // DR", unparseable, row dropped. The panel's labels on earlier rows have already been
        // excluded, so the section knows where the panel prints; a figure starting nearer that band
        // than the table is the panel's. Geometry measured from the real document, text synthetic.
        List<PositionedText> runs = new ArrayList<>(header());
        runs.addAll(List.of(
                run("18/08/2026", 25.9f, 45.0f, 420.0f),
                run("UPI SAMPLE MERCHANT 000000000001", 72.4f, 122.0f, 420.0f),
                run("0", 333.2f, 3.6f, 420.0f),
                run("12.00 CR", 395.4f, 27.2f, 420.0f),
                run("Statement Date", 479.4f, 48.1f, 417.8f),   // the panel label: teaches the band
                run("19/08/2026", 25.9f, 45.0f, 460.0f),
                run("UPI SAMPLE MERCHANT 000000000003", 72.4f, 127.3f, 460.0f),
                run("COMPUTERS", 222.4f, 40.6f, 460.0f),
                run("0", 333.2f, 3.6f, 460.0f),
                run("16.96 DR", 395.5f, 27.1f, 460.0f),
                run("2,429.08 DR", 480.3f, 44.9f, 462.4f)));  // the panel figure, same physical row

        DocumentContext ctx = new DocumentContext("PDF", "test");
        var table = new PdfTableLocator().locate(runs, ctx);

        assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().get(1).get("Amount (in `)")).isEqualTo("16.96 DR");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("MARGIN_PANEL_TEXT_EXCLUDED");
    }

    @Test
    void aNumberNearerTheTableThanTheLearnedPanelBandIsStillKept() {
        // The Kotak guard, with a panel present: a label has taught the band (x=479.4), but a
        // figure at 430.0 sits on the table's side of the midpoint between the header end (417.9)
        // and the band, so it is a right-aligned value overflowing its own column and stays.
        List<PositionedText> runs = new ArrayList<>(header());
        runs.addAll(List.of(
                run("18/08/2026", 25.9f, 45.0f, 420.0f),
                run("UPI SAMPLE MERCHANT 000000000001", 72.4f, 122.0f, 420.0f),
                run("Statement Date", 479.4f, 48.1f, 417.8f),
                run("19/08/2026", 25.9f, 45.0f, 440.0f),
                run("UPI SAMPLE MERCHANT 000000000002", 72.4f, 122.0f, 440.0f),
                run("99,999.00", 430.0f, 40.0f, 440.0f)));

        var table = new PdfTableLocator().locate(runs, null);

        assertThat(table.rows()).hasSize(2);
        assertThat(String.join(" ", table.rows().get(1).values())).contains("99,999.00");
    }

    @Test
    void aSubTableTotalRowIsStillRecognisedWhenAPanelValueSharesItsLine() {
        // The other half of the same real collision: the ledger's "Total 0 1,285.00" sub-table
        // total row carried the panel's "Statement Date" VALUE (a date, 2.3pt above its baseline)
        // on the same physical row, so the whole-line PAGE_LEGEND_BLOCK_START alternative written
        // for exactly that row never matched -- the total row was merged into the payment above
        // it as a wrapped description ("BBPS PAYMENT Total 1,285.00"), and with no legend block
        // open, the divider line under it was merged into the purchase below. rowLine is now the
        // table's own text, so the total row matches and the divider is suppressed.
        List<PositionedText> runs = new ArrayList<>(header());
        runs.addAll(List.of(
                run("01/09/2026", 25.9f, 33.2f, 420.0f),
                run("SAMPLE PAYMENT", 72.4f, 49.8f, 420.0f),
                run("0", 333.2f, 3.6f, 420.0f),
                run("1,285.00 CR", 386.3f, 36.3f, 420.0f),
                run("Statement Date", 479.4f, 48.1f, 417.8f),
                run("Total", 25.9f, 17.9f, 433.3f),
                run("0", 332.9f, 4.2f, 432.7f),
                run("1,285.00", 381.4f, 29.2f, 432.7f),
                run("22/09/2026", 482.7f, 40.0f, 430.4f),
                run("Some divider words printed between the two sub tables", 25.9f, 200.0f, 445.8f),
                run("24/08/2026", 25.9f, 33.2f, 460.0f),
                run("UPI SAMPLE MERCHANT 000000000003", 72.4f, 127.3f, 460.0f),
                run("0", 333.2f, 3.6f, 460.0f),
                run("16.96 DR", 395.5f, 27.1f, 460.0f)));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        var table = new PdfTableLocator().locate(runs, ctx);

        assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().get(0).get("Transaction Details")).isEqualTo("SAMPLE PAYMENT");
        assertThat(table.rows().get(1).get("Transaction Details")).isEqualTo("UPI SAMPLE MERCHANT 000000000003");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("PAGE_LEGEND_BLOCK_SUPPRESSED");
    }

    @Test
    void aRowMadeOnlyOfPanelTextKeepsItsOwnLineText() {
        // A physical row with nothing of the table's on it is not scoped to the table at all --
        // its text is whatever the page printed there, exactly as before this change.
        List<PositionedText> runs = new ArrayList<>(header());
        runs.addAll(List.of(
                run("23/08/2026 To 22/09/2026", 455.8f, 93.8f, 397.4f),
                run("18/08/2026", 25.9f, 45.0f, 420.0f),
                run("UPI SAMPLE MERCHANT 000000000001", 72.4f, 122.0f, 420.0f),
                run("12.00 CR", 395.4f, 27.2f, 420.0f)));

        var table = new PdfTableLocator().locate(runs, null);

        assertThat(table.rows()).hasSize(1);
        assertThat(table.rows().get(0).get("Amount (in `)")).isEqualTo("12.00 CR");
    }

    @Test
    void withoutMeasuredWidthsNothingIsExcluded() {
        // Hand-built fixtures and traces recorded before run widths existed carry width 0, so
        // headerEnds degenerates to a copy of headerAnchors. The rightmost ANCHOR sits in the
        // middle of the last column's own data, so treating it as the table's edge would discard
        // real values. The check must disable itself entirely in that case.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Date", 40.9f, 0f, HEADER_Y),
                run("Description", 117.4f, 0f, HEADER_Y),
                run("Amount", 376.0f, 0f, HEADER_Y),
                run("18/08/2026", 25.9f, 0f, 420.0f),
                run("SAMPLE NARRATION", 117.4f, 0f, 420.0f),
                // Beyond the rightmost anchor (376.0) and non-numeric -- would be excluded if the
                // check ran without real widths.
                run("REF ABC123", 430.0f, 0f, 420.0f)));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        var table = new PdfTableLocator().locate(runs, ctx);

        assertThat(table.rows()).hasSize(1);
        assertThat(String.join(" ", table.rows().get(0).values())).contains("REF ABC123");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .doesNotContain("MARGIN_PANEL_TEXT_EXCLUDED");
    }
}
