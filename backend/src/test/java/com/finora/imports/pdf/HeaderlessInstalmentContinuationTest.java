package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit F-28. On the balance-corroborated headerless path (HSBC card statements), an instalment
 * transaction prints a second line -- which instalment of how many, and whether it is principal or
 * interest -- one line below its merchant, at the same left edge. Those lines went to the section's
 * auxiliary text, so four rows of a real statement lost the only text that tells them apart.
 *
 * <p>Geometry is modelled on the real document (description column at x 77, line pitch about 10,
 * one continuation line under each instalment row); every value is invented.
 */
class HeaderlessInstalmentContinuationTest {

    private static PositionedText run(String text, float x, float endX, float y) {
        return new PositionedText(text, x, y, 0, endX - x);
    }

    /** OPENING 5,000.00; one payment (CR 1,000.00); a section heading and its note; two instalment
     *  rows (a principal reversal and its charge); one fee. Closing 5,000.00 + 20.00 = 5,020.00. */
    private static List<PositionedText> statement() {
        return new ArrayList<>(List.of(
                run("05 OCT 2026", 370.3f, 412.7f, 10f),
                run("4,999.99", 440f, 470f, 10f),
                run("OPENING BALANCE", 77.3f, 144.2f, 100f),
                run("5,000.00", 381.1f, 408.4f, 100f),
                run("15SEP", 30.7f, 52.1f, 110f),
                run("SAMPLE BILL PAYMENT", 77.3f, 213.1f, 110f),
                run("1,000.00", 381.1f, 408.4f, 110f),
                run("CR", 413.5f, 423.6f, 110f),
                // A heading one line below a transaction, at the description's left edge -- the same
                // position a continuation takes. What sets it apart is what follows: another
                // non-transaction line, not a transaction.
                run("PURCHASES & INSTALMENTS", 77.3f, 213.1f, 120f),
                run("Interest rate applicable : 1.00% p.m.", 77.3f, 300f, 130f),
                run("20SEP", 30.7f, 52.1f, 140f),
                run("SAMPLE LENDER LTD", 77.3f, 213.1f, 140f),
                run("1,000.00", 381.1f, 408.4f, 140f),
                run("CR", 413.5f, 423.6f, 140f),
                run("2ND OF 3 INSTALMENTS PRINCIPAL", 77.3f, 260f, 150f),
                run("20SEP", 30.7f, 52.1f, 160f),
                run("SAMPLE LENDER LTD", 77.3f, 213.1f, 160f),
                run("1,000.00", 381.1f, 408.4f, 160f),
                run("2ND OF 3 INSTALMENTS PRINCIPAL", 77.3f, 260f, 170f),
                run("20SEP", 30.7f, 52.1f, 180f),
                run("SAMPLE FEE", 77.3f, 213.1f, 180f),
                run("1,020.00", 381.1f, 408.4f, 180f),
                run("30SEP", 31.4f, 51.7f, 200f),
                run("NET OUTSTANDING BALANCE", 78.7f, 180.2f, 200f),
                run("5,020.00", 393.1f, 406.7f, 200f)));
    }

    private static PdfTableLocator.LocatedSection locate(List<PositionedText> runs) {
        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);
        assertThat(ctx.capabilities().stream().map(c -> c.capability()).toList())
                .contains("HEADERLESS_BALANCE_RECONCILIATION_CORROBORATED");
        return doc.sections().get(0);
    }

    @Test
    void anInstalmentLineBetweenTwoTransactionsJoinsTheOneAboveIt() {
        PdfTableLocator.LocatedSection section = locate(statement());

        assertThat(section.rows()).extracting(r -> r.get("Description")).containsExactly(
                "SAMPLE BILL PAYMENT",
                "SAMPLE LENDER LTD 2ND OF 3 INSTALMENTS PRINCIPAL",
                "SAMPLE LENDER LTD 2ND OF 3 INSTALMENTS PRINCIPAL",
                "SAMPLE FEE");
        assertThat(section.auxiliaryText()).noneMatch(l -> l.contains("2ND OF 3"));
    }

    @Test
    void aHeadingFollowedByAnotherNoteStaysOutOfTheRowAbove() {
        PdfTableLocator.LocatedSection section = locate(statement());

        assertThat(section.rows().get(0).get("Description")).isEqualTo("SAMPLE BILL PAYMENT");
        assertThat(section.auxiliaryText()).anyMatch(l -> l.contains("PURCHASES & INSTALMENTS"));
    }

    @Test
    void theAmountsAndDirectionsAreUnchanged() {
        PdfTableLocator.LocatedSection section = locate(statement());

        assertThat(section.rows()).extracting(r -> r.get("Debit") + "|" + r.get("Credit"))
                .containsExactly("|1,000.00", "|1,000.00", "1,000.00|", "1,020.00|");
    }

    @Test
    void aLineCarryingAnAmountIsNeverJoined() {
        // A dateless line with a figure on it is a summary or a sub-total, not narration. Joining it
        // would also hand the row above a second amount cell.
        List<PositionedText> runs = statement();
        runs.removeIf(r -> r.y() == 150f);
        runs.add(run("SAMPLE SUBTOTAL", 77.3f, 213.1f, 150f));
        runs.add(run("55.00", 381.1f, 408.4f, 150f));

        PdfTableLocator.LocatedSection section = locate(runs);

        assertThat(section.rows().get(1).get("Description")).isEqualTo("SAMPLE LENDER LTD");
    }

    @Test
    void aLineNotAlignedWithTheDescriptionIsNeverJoined() {
        // Indented well away from the description column: not the same field's next line.
        List<PositionedText> runs = statement();
        runs.removeIf(r -> r.y() == 150f);
        runs.add(run("2ND OF 3 INSTALMENTS PRINCIPAL", 200f, 380f, 150f));

        PdfTableLocator.LocatedSection section = locate(runs);

        assertThat(section.rows().get(1).get("Description")).isEqualTo("SAMPLE LENDER LTD");
    }

    @Test
    void theJoinedTextIsTheOnlyChange() {
        Map<String, String> joined = locate(statement()).rows().get(1);
        assertThat(joined.keySet()).containsExactly("Date", "Description", "Debit", "Credit");
    }
}
