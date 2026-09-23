package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real Axis Bank credit-card statement prints an "EMI BALANCES" heading directly under its last
 * real transaction, then one dateless row per running card EMI (merchant, EMI reference, outstanding
 * balance). Before {@link PdfTableLocator#EMI_BALANCES_TABLE_MARKER} existed, the heading and the
 * EMI row were merged into the last transaction's description -- outstanding balance included.
 *
 * <p>Coordinates and column shapes follow the real document; every value is invented, per the
 * Synthetic Fixture Policy.
 */
class EmiBalancesTableClosedPdfTableLocatorTest {

    private static PositionedText run(String text, float x, float endX, float y) {
        return new PositionedText(text, x, y, 0, endX - x);
    }

    private static List<PositionedText> statement(String heading) {
        List<PositionedText> runs = new ArrayList<>();
        runs.add(run("DATE", 49.5f, 67.5f, 318.5f));
        runs.add(run("TRANSACTION DETAILS", 183.5f, 260.5f, 318.5f));
        runs.add(run("MERCHANT CATEGORY", 386.5f, 463.5f, 318.5f));
        runs.add(run("AMOUNT (Rs.)", 514.0f, 563.0f, 318.5f));
        runs.add(run("20/09/2026", 35.3f, 73.3f, 578.6f));
        runs.add(run("UPI/SAMPLE KITCHEN/Q000000000@YBL/000", 90.3f, 285.3f, 578.6f));
        runs.add(run("RESTAURANTS", 360.3f, 408.2f, 578.6f));
        runs.add(run("120.00 Dr", 550.0f, 582.0f, 578.6f));
        runs.add(run("20/09/2026", 35.3f, 73.3f, 588.6f));
        runs.add(run("UPI/SAMPLE TEA STALL/Q000000001@YBL/0000000000@", 90.3f, 288.3f, 588.6f));
        runs.add(run("RESTAURANTS", 360.3f, 408.2f, 588.6f));
        runs.add(run("45.00 Dr", 554.0f, 582.0f, 588.6f));
        runs.add(run(heading, 287.0f, 336.0f, 598.5f));
        runs.add(run("SAMPLE ELECTRONICS RETAIL", 90.3f, 188.2f, 608.6f));
        runs.add(run("12345678", 360.3f, 392.3f, 608.6f));
        runs.add(run("50,000.00", 542.0f, 582.0f, 608.6f));
        return runs;
    }

    @Test
    void theEmiBalancesTableIsNotMergedIntoTheLastTransaction() {
        DocumentContext ctx = new DocumentContext("PDF", "EmiBalancesTableClosedPdfTableLocatorTest");
        List<Map<String, String>> rows = new PdfTableLocator().locateAll(statement("EMI BALANCES"), ctx)
                .sections().get(0).rows();

        assertThat(rows).extracting(r -> r.get("TRANSACTION DETAILS")).containsExactly(
                "UPI/SAMPLE KITCHEN/Q000000000@YBL/000",
                "UPI/SAMPLE TEA STALL/Q000000001@YBL/0000000000@");
        assertThat(rows.get(1)).containsEntry("AMOUNT (Rs.)", "45.00 Dr");
        assertThat(ctx.capabilities()).extracting(c -> c.capability()).contains("EMI_BALANCES_TABLE_CLOSED");
    }

    /** Mutation guard: the same layout without the heading still merges, so the test above is
     *  testing the trigger and not something incidental about the fixture's geometry. */
    @Test
    void withoutTheHeadingTheSameRowsStillMerge() {
        DocumentContext ctx = new DocumentContext("PDF", "EmiBalancesTableClosedPdfTableLocatorTest");
        List<Map<String, String>> rows = new PdfTableLocator().locateAll(statement("SOME OTHER NOTE"), ctx)
                .sections().get(0).rows();

        assertThat(rows.get(rows.size() - 1).get("TRANSACTION DETAILS")).contains("SAMPLE ELECTRONICS RETAIL");
        assertThat(ctx.capabilities()).extracting(c -> c.capability()).doesNotContain("EMI_BALANCES_TABLE_CLOSED");
    }
}
