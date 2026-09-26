package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PRINTED_OPENING_CLOSING_BALANCE: the two real shapes, grid and inline. All values synthetic. */
class PrintedBalanceExtractorTest {

    /** Runs carry their rendered width, as real extraction gives them; a grid value is matched to
     *  its label by horizontal overlap. */
    private static PositionedText run(String text, float x, float y) {
        return new PositionedText(text, x, y, 0, 6f * text.length());
    }

    @Test
    void aGridOfLabelsWithCurrencyPrefixedValuesBelow_isRead() {
        // A savings statement's summary: four labels on one row, the values (with an "INR" prefix)
        // on a row further down, as a real Bandhan Bank statement prints them.
        List<PositionedText> runs = List.of(
                run("Opening Balance", 50f, 100f), run("Total Credits", 180f, 100f),
                run("Total Debits", 300f, 100f), run("Closing Balance", 430f, 100f),
                run("INR10,728.84", 50f, 124f), run("INR12,000.00", 180f, 124f),
                run("INR3,281.00", 300f, 124f), run("INR19,447.84", 430f, 124f));
        DocumentContext ctx = new DocumentContext("PDF", "test");

        PrintedBalanceExtractor.PrintedBalances printed = PrintedBalanceExtractor.extract(runs, ctx);

        assertThat(printed.opening()).isEqualByComparingTo("10728.84");
        assertThat(printed.closing()).isEqualByComparingTo("19447.84");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).contains("PRINTED_OPENING_CLOSING_BALANCE");
    }

    @Test
    void aLabelWithItsAmountOnTheSameRow_isRead() {
        // As a real Canara Bank statement prints them: the label, then the figure to its right.
        List<PositionedText> runs = List.of(
                run("Opening Balance", 200f, 100f), run("1,15,238.60", 420f, 100f),
                run("Closing Balance", 200f, 700f), run("1,07,279.08", 420f, 700f));

        PrintedBalanceExtractor.PrintedBalances printed = PrintedBalanceExtractor.extract(runs, new DocumentContext("PDF", "test"));

        assertThat(printed.opening()).isEqualByComparingTo("115238.60");
        assertThat(printed.closing()).isEqualByComparingTo("107279.08");
    }

    @Test
    void balanceBroughtForward_isAnOpeningBalance() {
        List<PositionedText> runs = List.of(run("Balance Brought Forward", 200f, 100f), run("6,681.29", 520f, 100f));

        assertThat(PrintedBalanceExtractor.extract(runs, null).opening()).isEqualByComparingTo("6681.29");
    }

    @Test
    void aLabelWithNoFigureBesideOrBelowIt_yieldsNothing() {
        List<PositionedText> runs = List.of(
                run("Opening Balance", 50f, 100f), run("Total Credits", 180f, 100f),
                run("First transaction narration", 50f, 124f));

        assertThat(PrintedBalanceExtractor.extract(runs, null).isEmpty()).isTrue();
    }

    @Test
    void aFigureBesideTheLabel_winsOverADigitRunBelowIt() {
        // A scanned statement's brought-forward row: the balance beside the label, a 12-digit
        // reference number on the row below, left-aligned under the label.
        List<PositionedText> runs = List.of(
                run("BALANCE BROUGHT FORWARD", 106f, 187f), run("103,456.78", 495f, 187f),
                run("21JUN2026", 34f, 197f), run("UPI00000000000001", 106f, 197f),
                run("000000000001", 105f, 207f));

        assertThat(PrintedBalanceExtractor.extract(runs, null).opening()).isEqualByComparingTo("103456.78");
    }

    @Test
    void aBareDigitRun_isNeverAnAmount() {
        assertThat(PrintedBalanceExtractor.amountOf("000000000001")).isNull();
        assertThat(PrintedBalanceExtractor.amountOf("1,234")).isNotNull();
        assertThat(PrintedBalanceExtractor.amountOf("500.00")).isNotNull();
    }

    @Test
    void aLedgerHeaderEndingInClosingBalance_isNotASummaryLabel() {
        // Two real HDFC statements: the ledger header's last column is "Closing Balance"; the
        // summary grid with the statement's own closing balance is printed further down.
        List<PositionedText> runs = List.of(
                run("Txn Date", 55f, 314f), run("Narration", 176f, 314f), run("Withdrawals", 296f, 314f),
                run("Deposits", 386f, 314f), run("Closing Balance", 473f, 314f),
                run("01/07/2026", 52f, 328f), run("FIRST NARRATION", 111f, 328f), run("10,000.00", 408f, 328f), run("10,500.00", 526f, 328f),
                run("Opening Balance", 127f, 442f), run("Debit Amount", 227f, 442f), run("Credit Amount", 319f, 442f), run("Closing Balance", 448f, 442f),
                run("500.00", 133f, 461f), run("100.00", 243f, 461f), run("10,000.00", 338f, 461f), run("10,400.00", 446f, 461f));

        PrintedBalanceExtractor.PrintedBalances printed = PrintedBalanceExtractor.extract(runs, null);

        assertThat(printed.opening()).isEqualByComparingTo("500.00");
        assertThat(printed.closing()).as("the summary grid's figure, not the first row's balance").isEqualByComparingTo("10400.00");
    }

    @Test
    void theFirstPrintedValueWins_aLaterRepeatIsIgnored() {
        List<PositionedText> runs = List.of(
                run("Opening Balance", 200f, 100f), run("100.00", 420f, 100f),
                run("Opening Balance", 200f, 900f), run("999.00", 420f, 900f));

        assertThat(PrintedBalanceExtractor.extract(runs, null).opening()).isEqualByComparingTo("100.00");
    }
}
