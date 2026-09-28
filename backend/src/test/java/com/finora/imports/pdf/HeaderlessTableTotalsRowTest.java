package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TABLE_TOTALS_ROW_DIVERTED on the inferred-headerless path. That path merges every dateless line
 * after a transaction into it, so a closing "Total" line was folded into the last transaction the
 * same way the header-based path folded it (see TableTotalsRowPdfTableLocatorTest).
 *
 * <p>Geometry follows HeaderlessLayoutInferenceTest's fixture; all text is synthetic.
 */
class HeaderlessTableTotalsRowTest {

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    private static PositionedText amount(String text, float endX, float y) {
        float width = text.length() * 6.2f;
        return run(text, endX - width, width, y);
    }

    private static List<PositionedText> transaction(String date, String narration, String debitText,
                                                      String creditText, String balanceText, float y) {
        List<PositionedText> row = new ArrayList<>();
        row.add(run(date, 30f, 55f, y));
        row.add(run(date, 95f, 55f, y));
        row.add(run(narration, 165f, narration.length() * 5.2f, y));
        row.add(amount(debitText, 390f, y));
        row.add(amount(creditText, 520f, y));
        row.add(amount(balanceText, 650f, y));
        return row;
    }

    private static List<PositionedText> statement() {
        List<PositionedText> runs = new ArrayList<>();
        runs.addAll(transaction("01/01/2026", "GROCERY STORE PURCHASE MONTHLY", "500.00", "-", "9500.00", 300f));
        runs.addAll(transaction("02/01/2026", "SALARY CREDIT FROM EMPLOYER LTD", "-", "20000.00", "29500.00", 320f));
        runs.addAll(transaction("03/01/2026", "ELECTRICITY BILL PAYMENT ONLINE", "1500.00", "-", "28000.00", 340f));
        runs.addAll(transaction("04/01/2026", "MOBILE RECHARGE PREPAID PLAN", "300.00", "-", "27700.00", 360f));
        runs.addAll(transaction("05/01/2026", "REFUND FROM ONLINE MERCHANT STORE", "-", "200.00", "27900.00", 380f));
        return runs;
    }

    @Test
    void aTotalLineAfterTheLastTransaction_isAuxiliaryText_andLeavesTheTransactionAlone() {
        List<PositionedText> runs = statement();
        runs.add(run("Total", 30f, 25f, 400f));
        runs.add(amount("2300.00", 390f, 400f));
        runs.add(amount("20200.00", 520f, 400f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows).hasSize(5);
        assertThat(rows.get(4)).containsEntry("Description", "REFUND FROM ONLINE MERCHANT STORE")
                .containsEntry("Credit", "200.00").containsEntry("Balance", "27900.00");
        assertThat(rows.get(4).get("Debit")).isIn(null, "-");
        assertThat(doc.sections().get(0).auxiliaryText()).anySatisfy(line ->
                assertThat(line).contains("Total").contains("2300.00").contains("20200.00"));
        List<String> capabilities = ctx.capabilities().stream().map(c -> c.capability()).toList();
        assertThat(capabilities).contains("INFERRED_HEADERLESS_LAYOUT", "TABLE_TOTALS_ROW_DIVERTED");
    }

    @Test
    void aWrappedNarrationLine_isStillMergedOnTheHeaderlessPath() {
        List<PositionedText> runs = statement();
        runs.add(run("TOTAL REF ABCDE123", 165f, 90f, 390f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(doc.sections().get(0).rows().get(4).get("Description")).contains("TOTAL REF ABCDE123");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).doesNotContain("TABLE_TOTALS_ROW_DIVERTED");
    }

    @Test
    void aTotalLineSplitOverTwoPhysicalRows_isAuxiliaryText() {
        List<PositionedText> runs = statement();
        runs.add(run("Total", 30f, 25f, 396f));
        runs.add(amount("2300.00", 390f, 400f));
        runs.add(amount("20200.00", 520f, 400f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);

        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows).hasSize(5);
        assertThat(rows.get(4)).containsEntry("Description", "REFUND FROM ONLINE MERCHANT STORE")
                .containsEntry("Credit", "200.00");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).contains("TABLE_TOTALS_ROW_DIVERTED");
    }

    @Test
    void aLoneTotalLabelAtTheLeftMargin_isAuxiliaryText() {
        List<PositionedText> runs = statement();
        runs.add(run("Total", 30f, 25f, 400f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(doc.sections().get(0).rows().get(4))
                .containsEntry("Description", "REFUND FROM ONLINE MERCHANT STORE");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).contains("TABLE_TOTALS_ROW_DIVERTED");
    }

    @Test
    void aLoneTotalsWordWhereTheNarrationStarts_isNarration() {
        List<PositionedText> runs = statement();
        runs.add(run("TOTAL", 165f, 30f, 390f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(doc.sections().get(0).rows().get(4).get("Description")).contains("TOTAL");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).doesNotContain("TABLE_TOTALS_ROW_DIVERTED");
    }

    @Test
    void aLoneTotalsWordThatIsTheFirstLeftMarginWrap_isNarration_whenLaterWrapsStartThere() {
        List<PositionedText> runs = new ArrayList<>();
        runs.addAll(transaction("01/01/2026", "GROCERY STORE PURCHASE MONTHLY", "500.00", "-", "9500.00", 300f));
        runs.add(run("TOTAL", 30f, 30f, 310f));
        runs.addAll(transaction("02/01/2026", "SALARY CREDIT FROM EMPLOYER LTD", "-", "20000.00", "29500.00", 320f));
        runs.add(run("SAMPLE REF LINE", 30f, 80f, 330f));
        runs.addAll(transaction("03/01/2026", "ELECTRICITY BILL PAYMENT ONLINE", "1500.00", "-", "28000.00", 340f));
        runs.addAll(transaction("04/01/2026", "MOBILE RECHARGE PREPAID PLAN", "300.00", "-", "27700.00", 360f));
        runs.addAll(transaction("05/01/2026", "REFUND FROM ONLINE MERCHANT STORE", "-", "200.00", "27900.00", 380f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(doc.sections().get(0).rows().get(0).get("Description")).contains("TOTAL");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).doesNotContain("TABLE_TOTALS_ROW_DIVERTED");
    }
}
