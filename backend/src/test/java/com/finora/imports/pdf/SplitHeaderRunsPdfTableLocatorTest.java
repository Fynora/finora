package com.finora.imports.pdf;

import com.finora.imports.TransactionNormalizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-001 Fix A: a header cell that PDFBox split into separate text runs is put back together before
 * the columns are named.
 *
 * <p>Captured from three real HDFC savings statements. Their header prints on ONE y band --
 * "Date | Narration | Chq./Ref.No. | Value Dt | Withdrawal Amt. | Deposit Amt. | Closing Balance" --
 * but arrives as ELEVEN runs, because every multi-word cell is split at its space. One column per
 * run gave a header with TWO columns literally named "Amt.", which collapse onto the same key in
 * {@code bucketRow}'s map, and "amt" names nothing in {@code TransactionNormalizer}'s hint lists.
 *
 * <p>The damage was NOT lost rows. The rows staged; their amounts and their signs were wrong.
 * {@code AMOUNT_HINTS} fell through to its last-resort "balance" entry, so a transaction's amount
 * became the account's running balance, and with no credit column recognized every deposit staged
 * as an EXPENSE. Measured before the fix, per trace: 230 / 343 / 7 rows resolved their amount from
 * the balance column, and 0 / 1 / 1 rows were recognized as credits. This is the same
 * silently-wrong-data failure already documented for Kotak's "Deposit (Cr.)" in
 * {@code TransactionNormalizer}, reaching the same place through a different mechanism -- which is
 * why it is asserted on the STAGED rows and not only on the column names.
 *
 * <p>Driven from the locator down rather than from bytes: a trace IS
 * {@code PdfTextExtractor}'s output, and replaying from there is what keeps the exact run
 * fragmentation that caused the bug (rebuilding a PDF would re-lay it out and lose it).
 *
 * <p>The tests that read committed statement traces moved to {@code
 * SplitHeaderRunsPdfTableLocatorTracesTest} in the private Fynora/finora-statement-traces
 * repository, with the traces themselves (see docs/engineering/private-statement-traces.md). The
 * tests left here build their input by hand.
 */
class SplitHeaderRunsPdfTableLocatorTest {

    @Test
    void runsWithNoMeasuredWidthAreNeverJoined() {
        // width == 0 means endX() == x, so the "gap" degenerates into the raw distance between two
        // LEFT edges -- which says nothing about whether the runs touch, and would join two
        // genuinely separate columns. Older v1/v2 traces are exactly that shape. Same header,
        // built twice: once with measured widths, once with none.
        List<PositionedText> measured = List.of(
                new PositionedText("Date", 40f, 100f, 0, 16f),
                new PositionedText("Withdrawal", 405f, 100f, 0, 41.3f),
                new PositionedText("Amt.", 448.7f, 100f, 0, 17.1f),
                new PositionedText("Deposit", 491f, 100f, 0, 25.8f),
                new PositionedText("Amt.", 518.8f, 100f, 0, 17.1f),
                new PositionedText("01/04/2026", 40f, 120f, 0, 40f),
                new PositionedText("500.00", 405f, 120f, 0, 20f),
                new PositionedText("02/04/2026", 40f, 140f, 0, 40f),
                new PositionedText("250.00", 495f, 140f, 0, 20f));
        List<PositionedText> unmeasured = new ArrayList<>();
        for (PositionedText t : measured) {
            unmeasured.add(new PositionedText(t.text(), t.x(), t.y(), t.pageIndex()));
        }

        Set<String> withWidths = new LinkedHashSet<>();
        new PdfTableLocator().locateAll(measured, null).sections()
                .forEach(s -> s.rows().forEach(r -> withWidths.addAll(r.keySet())));
        Set<String> withoutWidths = new LinkedHashSet<>();
        new PdfTableLocator().locateAll(unmeasured, null).sections()
                .forEach(s -> s.rows().forEach(r -> withoutWidths.addAll(r.keySet())));

        assertThat(withWidths).contains("Withdrawal Amt.", "Deposit Amt.");
        assertThat(withoutWidths)
                .as("no width measured, so exactly today's behaviour -- one column per run")
                .contains("Withdrawal", "Deposit")
                .doesNotContain("Withdrawal Amt.", "Deposit Amt.");
    }

    @Test
    void twoAdjacentValuesAreNeverGluedIntoOneColumnName() {
        // A header cell is a word. Two neighbouring narrow columns whose VALUES sit 2pt apart --
        // a date beside an amount, or two amounts -- must not become one fabricated column name,
        // which is what the date/number exclusion is for. Here the header row itself carries a
        // date-shaped and a number-shaped run right next to the words.
        List<PositionedText> runs = List.of(
                new PositionedText("Date", 40f, 100f, 0, 16f),
                new PositionedText("Narration", 100f, 100f, 0, 34f),
                new PositionedText("Withdrawal", 405f, 100f, 0, 41.3f),
                new PositionedText("Amt.", 448.7f, 100f, 0, 17.1f),
                new PositionedText("01/04/2026", 480f, 100f, 0, 40f),
                new PositionedText("1000.00", 522f, 100f, 0, 25f),
                new PositionedText("Balance", 560f, 100f, 0, 27f),
                new PositionedText("01/04/2026", 40f, 120f, 0, 40f),
                new PositionedText("Rent", 100f, 120f, 0, 20f),
                new PositionedText("500.00", 405f, 120f, 0, 20f));

        Set<String> columns = new LinkedHashSet<>();
        new PdfTableLocator().locateAll(runs, null).sections()
                .forEach(s -> s.rows().forEach(r -> columns.addAll(r.keySet())));

        assertThat(columns).contains("Withdrawal Amt.");
        assertThat(columns.stream().anyMatch(c -> c.contains("01/04/2026") || c.contains("1000.00")))
                .as("a value-shaped run was joined into a column name")
                .isFalse();
    }
}
