package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real HSBC credit-card statement draws its payment-summary box labels ("Payment due date",
 * "Credit limit", ...) in a font the PDF does not embed, so they extract as nothing; only the values
 * are text. Every value below is invented; the GEOMETRY (x/y of each run relative to the fixed
 * cheque-instruction line) is the one measured on both real HSBC statements in the corpus.
 */
class UnlabelledCardPaymentSummaryExtractorTest {

    private static final String ANCHOR =
            "Please make all cheques/demand drafts duly crossed, payable to \"HSBC";

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    /** The measured layout, with invented values. */
    private static List<PositionedText> layout(String dueDate, String periodStart, String periodEnd,
                                               String creditLimit, String cashLimit) {
        return new ArrayList<>(List.of(
                run(dueDate, 371f, 41f, 51.9f),
                run("1,234.00", 503f, 27f, 51.9f),
                // The cardholder's name on the left sits between the due-date row and the period row.
                run("SAMPLE HOLDER NAME", 58f, 104f, 72.6f),
                // The address block on the left shares the period row's height, as on the real page.
                run("SAMPLE ADDRESS LINE", 58f, 66f, 83.1f),
                run("  " + periodStart + "  To", 336f, 58f, 82.9f),
                run(periodEnd, 397f, 40f, 82.9f),
                run("12,345.00", 501f, 33f, 82.9f),
                run("SAMPLE CARD", 379f, 66f, 103.3f),
                run(ANCHOR, 337.4f, 225.8f, 124.6f),
                run("A/c - your 16 digit credit card number\" and write your NAME", 337.4f, 234.7f, 132.8f),
                // ...and the state line on the left shares the card numbers' row.
                run("State: 00 - SAMPLE", 53.5f, 88f, 176.7f),
                run("00xx xxxx xxxx 0000", 364f, 64f, 175f),
                run("00xx xxxx xxxx 0000", 486f, 64f, 175f),
                run(creditLimit, 86f, 31f, 198.1f),
                run(cashLimit, 214f, 29f, 196.9f)));
    }

    private static UnlabelledCardPaymentSummaryExtractor.CardPaymentSummary read(List<PositionedText> runs) {
        return UnlabelledCardPaymentSummaryExtractor.extract(runs);
    }

    @Test
    void readsTheDueDateAndCreditLimit_fromTheirPlacesInTheSummaryBoxes() {
        var summary = read(layout("05 MAY 2026", "15 MAR 2026", "14 APR 2026", "50,000.00", "10,000.00"));

        assertThat(summary.paymentDueDate()).isEqualTo(LocalDate.of(2026, 5, 5));
        assertThat(summary.creditLimit()).isEqualByComparingTo("50000.00");
    }

    @Test
    void readsNothing_withoutTheFixedInstructionLine() {
        List<PositionedText> runs = layout("05 MAY 2026", "15 MAR 2026", "14 APR 2026", "50,000.00", "10,000.00");
        runs.removeIf(t -> t.text().equals(ANCHOR));

        var summary = read(runs);

        assertThat(summary.paymentDueDate()).isNull();
        assertThat(summary.creditLimit()).isNull();
    }

    @Test
    void readsNoDueDate_onOrBeforeThePeriodEnd() {
        assertThat(read(layout("14 APR 2026", "15 MAR 2026", "14 APR 2026", "50,000.00", "10,000.00"))
                .paymentDueDate()).isNull();
        assertThat(read(layout("01 APR 2026", "15 MAR 2026", "14 APR 2026", "50,000.00", "10,000.00"))
                .paymentDueDate()).isNull();
    }

    @Test
    void readsNoDueDate_moreThanSixtyDaysAfterThePeriodEnd() {
        // 14 Apr to 14 Jun is 61 days.
        assertThat(read(layout("14 JUN 2026", "15 MAR 2026", "14 APR 2026", "50,000.00", "10,000.00"))
                .paymentDueDate()).isNull();
    }

    @Test
    void readsTheDueDate_exactlySixtyDaysAfterThePeriodEnd() {
        // 14 Apr to 13 Jun is 60 days.
        assertThat(read(layout("13 JUN 2026", "15 MAR 2026", "14 APR 2026", "50,000.00", "10,000.00"))
                .paymentDueDate()).isEqualTo(LocalDate.of(2026, 6, 13));
    }

    @Test
    void readsNoDueDate_whenItsRowCarriesAnythingElse() {
        List<PositionedText> runs = layout("05 MAY 2026", "15 MAR 2026", "14 APR 2026", "50,000.00", "10,000.00");
        runs.add(run("SAMPLE NOTE", 430f, 40f, 51.9f));

        assertThat(read(runs).paymentDueDate()).isNull();
    }

    @Test
    void readsNoDueDate_withoutThePeriodRowBetweenItAndTheInstructionLine() {
        List<PositionedText> runs = layout("05 MAY 2026", "15 MAR 2026", "14 APR 2026", "50,000.00", "10,000.00");
        runs.removeIf(t -> t.y() == 82.9f || t.y() == 83.1f);

        assertThat(read(runs).paymentDueDate()).isNull();
    }

    @Test
    void readsNoCreditLimit_belowTheCashLimit() {
        assertThat(read(layout("05 MAY 2026", "15 MAR 2026", "14 APR 2026", "5,000.00", "10,000.00"))
                .creditLimit()).isNull();
    }

    @Test
    void readsNoCreditLimit_whenTheRowHoldsAThirdFigure() {
        List<PositionedText> runs = layout("05 MAY 2026", "15 MAR 2026", "14 APR 2026", "50,000.00", "10,000.00");
        runs.add(run("2,000.00", 290f, 29f, 197.5f));

        assertThat(read(runs).creditLimit()).isNull();
    }

    @Test
    void readsNoCreditLimit_withoutTheTwoCardNumbersAboveIt() {
        List<PositionedText> runs = layout("05 MAY 2026", "15 MAR 2026", "14 APR 2026", "50,000.00", "10,000.00");
        runs.removeIf(t -> t.text().startsWith("00xx"));

        assertThat(read(runs).creditLimit()).isNull();
    }

    @Test
    void readsNoCreditLimit_fromFiguresOnTheInstructionLinesSide() {
        List<PositionedText> runs = layout("05 MAY 2026", "15 MAR 2026", "14 APR 2026", "50,000.00", "10,000.00");
        runs.removeIf(t -> t.y() == 198.1f || t.y() == 196.9f);
        runs.add(run("50,000.00", 380f, 31f, 198.1f));
        runs.add(run("10,000.00", 480f, 29f, 196.9f));

        assertThat(read(runs).creditLimit()).isNull();
    }

    @Test
    void readsTheTotalPaymentDue_besideThePeriodEnd() {
        // The box's "Total payment due" -- what the card bills this cycle. The summary table's
        // "Net Outstanding Balance" also counts a loan's future instalments, so it is not this.
        var summary = read(layout("05 MAY 2026", "15 MAR 2026", "14 APR 2026", "50,000.00", "10,000.00"));

        assertThat(summary.totalPaymentDue()).isEqualByComparingTo("12345.00");
    }

    @Test
    void readsNoTotalPaymentDue_whenThePeriodRowCarriesAnythingElse() {
        List<PositionedText> runs = layout("05 MAY 2026", "15 MAR 2026", "14 APR 2026", "50,000.00", "10,000.00");
        runs.add(run("9.99", 460f, 20f, 82.9f));

        assertThat(read(runs).totalPaymentDue()).isNull();
    }

    @Test
    void readsNoTotalPaymentDue_whenSomethingSitsBeforeThePeriodStart() {
        List<PositionedText> runs = layout("05 MAY 2026", "15 MAR 2026", "14 APR 2026", "50,000.00", "10,000.00");
        runs.removeIf(t -> t.text().equals("12,345.00"));
        runs.add(run("9", 334f, 1f, 82.9f));

        assertThat(read(runs).totalPaymentDue()).isNull();
    }

    @Test
    void readsNoTotalPaymentDue_whenTheFigureBesideThePeriodEndIsNotAnAmount() {
        List<PositionedText> runs = layout("05 MAY 2026", "15 MAR 2026", "14 APR 2026", "50,000.00", "10,000.00");
        runs.removeIf(t -> t.text().equals("12,345.00"));
        runs.add(run("SAMPLE", 501f, 33f, 82.9f));

        assertThat(read(runs).totalPaymentDue()).isNull();
    }

    @Test
    void readsNoTotalPaymentDue_withoutTheInstructionLine() {
        List<PositionedText> runs = layout("05 MAY 2026", "15 MAR 2026", "14 APR 2026", "50,000.00", "10,000.00");
        runs.removeIf(t -> t.text().equals(ANCHOR));

        assertThat(read(runs).totalPaymentDue()).isNull();
    }

    @Test
    void readsNothing_fromEmptyInput() {
        assertThat(read(List.of()).paymentDueDate()).isNull();
        assertThat(read(null).creditLimit()).isNull();
    }
}
