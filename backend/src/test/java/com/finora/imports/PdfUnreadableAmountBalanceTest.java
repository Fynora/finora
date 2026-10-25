package com.finora.imports;

import com.finora.dto.ImportDto;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.imports.trust.HoldDecision;
import com.finora.imports.trust.TrustPredicate;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A PDF ledger row with a date and a balance but an amount cell that could not be read.
 *
 * <p>The normaliser falls back to the balance for such a row's amount, and that figure used to feed
 * the opening/closing derivation as money moving: a first row with an unreadable deposit and a
 * balance of 9,700.00 gave an opening balance of 19,400.00. Now the row is kept out of the
 * derivation, and the opening or closing balance is not established when such a row may be first or
 * last -- the CSV path's rule. The statement is held only when that leaves a balance unknown and the
 * statement printed none to fill it (Sid, 2026-10-25): in a real PDF such a row can be the
 * statement's own closing-balance line under the table, which must not hold.
 *
 * <p>Every value here is invented.
 */
class PdfUnreadableAmountBalanceTest {

    private static final String[] HEADER = {"Date", "Narration", "Withdrawals", "Deposits", "Balance"};
    private static final float[] COLUMN = {50f, 130f, 300f, 380f, 460f};

    @Test
    void anUnreadFirstRowLeavesTheOpeningUnknownNotDoubled_andHoldsWhenNothingPrintedFillsIt() throws Exception {
        StagingResponse r = stage(List.of(), List.of(
                row("13/07/2026", "SYNTHETIC UNREAD", "", "abc", "9700.00"),
                row("14/07/2026", "SYNTHETIC SHOP", "1500.00", "", "8200.00"),
                row("15/07/2026", "SYNTHETIC CAFE", "200.00", "", "8000.00")));

        assertThat(r.rows()).hasSize(2);
        assertThat(r.detectedAccount().openingBalance()).isNull();
        assertThat(r.detectedAccount().closingBalance()).isEqualByComparingTo("8000.00");
        assertHeldForTheUnreadAmount(r, 1);
    }

    @Test
    void aPrintedOpeningBalanceFillsIt_andThenTheUnreadRowDoesNotHold() throws Exception {
        StagingResponse r = stage(List.<String[]>of(new String[] {"Opening Balance", "9000.00"}), List.of(
                row("13/07/2026", "SYNTHETIC UNREAD", "", "abc", "9700.00"),
                row("14/07/2026", "SYNTHETIC SHOP", "1500.00", "", "8200.00"),
                row("15/07/2026", "SYNTHETIC CAFE", "200.00", "", "8000.00")));

        assertThat(r.detectedAccount().openingBalance()).isEqualByComparingTo("9000.00");
        assertNoUnreadAmountWarning(r);
    }

    /** The real shape: a statement's own "Closing Balance" line under the table, its label in an
     *  amount column and its figure under Balance, on the last day. */
    @Test
    void aClosingBalanceLineUnderTheTableIsFilledByItsOwnPrintedFigure_andDoesNotHold() throws Exception {
        StagingResponse r = stage(List.of(), List.of(
                row("13/07/2026", "SYNTHETIC SALARY", "", "500.00", "10500.00"),
                row("14/07/2026", "SYNTHETIC SHOP", "1500.00", "", "9000.00"),
                row("15/07/2026", "SYNTHETIC CAFE", "200.00", "", "8800.00"),
                row("15/07/2026", "DISCLAIMER", "", "Closing Balance", "8800.00")));

        assertThat(r.rows()).hasSize(3);
        assertThat(r.detectedAccount().openingBalance()).isEqualByComparingTo("10000.00");
        assertThat(r.detectedAccount().closingBalance()).isEqualByComparingTo("8800.00");
        assertNoUnreadAmountWarning(r);
        assertThat(hold(r).hold()).isFalse();
    }

    @Test
    void anUnreadRowOnTheLastDayLeavesTheClosingUnknown_andHoldsWhenNothingPrintedFillsIt() throws Exception {
        StagingResponse r = stage(List.of(), List.of(
                row("13/07/2026", "SYNTHETIC SALARY", "", "500.00", "10500.00"),
                row("14/07/2026", "SYNTHETIC SHOP", "1500.00", "", "9000.00"),
                row("15/07/2026", "SYNTHETIC CAFE", "200.00", "", "8800.00"),
                row("15/07/2026", "SYNTHETIC UNREAD", "xyz", "", "8700.00")));

        assertThat(r.detectedAccount().openingBalance()).isEqualByComparingTo("10000.00");
        assertThat(r.detectedAccount().closingBalance()).isNull();
        assertHeldForTheUnreadAmount(r, 1);
    }

    @Test
    void anUnreadRowAfterEveryReadableOneGivesTheClosingByItsOwnBalance_andDoesNotHold() throws Exception {
        StagingResponse r = stage(List.of(), List.of(
                row("13/07/2026", "SYNTHETIC SALARY", "", "500.00", "10500.00"),
                row("14/07/2026", "SYNTHETIC SHOP", "1500.00", "", "9000.00"),
                row("16/07/2026", "SYNTHETIC UNREAD", "xyz", "", "8700.00")));

        assertThat(r.detectedAccount().openingBalance()).isEqualByComparingTo("10000.00");
        assertThat(r.detectedAccount().closingBalance()).isEqualByComparingTo("8700.00");
        assertNoUnreadAmountWarning(r);
    }

    @Test
    void anUnreadRowInTheMiddleLeavesBothEndsToTheReadableRows_andDoesNotHoldByThisRule() throws Exception {
        StagingResponse r = stage(List.of(), List.of(
                row("13/07/2026", "SYNTHETIC SALARY", "", "500.00", "10500.00"),
                row("14/07/2026", "SYNTHETIC UNREAD", "xyz", "", "9000.00"),
                row("15/07/2026", "SYNTHETIC CAFE", "200.00", "", "8800.00")));

        assertThat(r.detectedAccount().openingBalance()).isEqualByComparingTo("10000.00");
        assertThat(r.detectedAccount().closingBalance()).isEqualByComparingTo("8800.00");
        assertNoUnreadAmountWarning(r);
    }

    /** No running balance at all: the rows never gave an opening or closing balance, so the unread
     *  row is not what left them unknown, and this rule does not hold. */
    @Test
    void withNoBalanceColumn_anUnreadRowLeavesNothingOpen_andDoesNotHoldByThisRule() throws Exception {
        StagingResponse r = stage(new String[] {"Date", "Narration", "Withdrawals", "Deposits"}, List.of(), List.of(
                new String[] {"13/07/2026", "SYNTHETIC UNREAD", "", "abc"},
                new String[] {"14/07/2026", "SYNTHETIC SHOP", "1500.00", ""},
                new String[] {"15/07/2026", "SYNTHETIC CAFE", "200.00", ""}));

        assertThat(r.rows()).hasSize(2);
        assertThat(r.detectedAccount().openingBalance()).isNull();
        assertThat(r.detectedAccount().closingBalance()).isNull();
        assertNoUnreadAmountWarning(r);
    }

    private static void assertHeldForTheUnreadAmount(StagingResponse r, int rows) {
        ImportDto.VerificationFinding accounting = rowAccounting(r);
        assertThat(accounting.outcome()).isEqualTo("WARNING");
        assertThat(accounting.details()).containsEntry(RowAccountingValidator.UNREADABLE_AMOUNT_ROW_COUNT, rows);
        HoldDecision hold = hold(r);
        assertThat(hold.hold()).isTrue();
        assertThat(hold.categories()).contains(TrustPredicate.Category.DROPPED_TRANSACTION);
        assertThat(hold.reasons()).contains("A transaction's amount could not be read");
    }

    private static void assertNoUnreadAmountWarning(StagingResponse r) {
        assertThat(rowAccounting(r).details()).doesNotContainKey(RowAccountingValidator.UNREADABLE_AMOUNT_ROW_COUNT);
        assertThat(hold(r).reasons()).doesNotContain("A transaction's amount could not be read");
    }

    private static ImportDto.VerificationFinding rowAccounting(StagingResponse r) {
        return r.verification().findings().stream()
                .filter(f -> RowAccountingValidator.RULE.equals(f.rule())).findFirst().orElseThrow();
    }

    private static HoldDecision hold(StagingResponse r) {
        return TrustPredicate.evaluate(List.of(r.verification()), List.of(), LocalDate.of(2026, 10, 25));
    }

    private static String[] row(String date, String narration, String withdrawal, String deposit, String balance) {
        return new String[] {date, narration, withdrawal, deposit, balance};
    }

    /** Lines printed above the table (label, value), then the table itself. */
    private static StagingResponse stage(List<String[]> aboveTable, List<String[]> rows) throws Exception {
        return stage(HEADER, aboveTable, rows);
    }

    private static StagingResponse stage(String[] header, List<String[]> aboveTable, List<String[]> rows)
            throws Exception {
        List<String[]> table = new ArrayList<>();
        table.add(header);
        table.addAll(rows);
        byte[] bytes;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 9f);
                float y = 790f;
                text(content, 50f, y, "Statement of Account");
                y -= 14f;
                for (String[] line : aboveTable) {
                    text(content, 50f, y, line[0]);
                    text(content, 160f, y, line[1]);
                    y -= 14f;
                }
                y -= 14f;
                for (String[] cells : table) {
                    for (int i = 0; i < cells.length; i++) {
                        if (!cells[i].isEmpty()) text(content, COLUMN[i], y, cells[i]);
                    }
                    y -= 12f;
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            bytes = out.toByteArray();
        }
        return PdfGeneratorTestSupport.generator().generate(UUID.randomUUID(), "synthetic.pdf", bytes);
    }

    private static void text(PDPageContentStream content, float x, float y, String s) throws Exception {
        content.beginText();
        content.newLineAtOffset(x, y);
        content.showText(s);
        content.endText();
    }
}
