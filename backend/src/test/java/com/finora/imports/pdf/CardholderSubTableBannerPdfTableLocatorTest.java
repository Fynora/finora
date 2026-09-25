package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CARDHOLDER_SUBTABLE_BANNER: a credit-card ledger split into sub-tables under one shared column
 * header, each opened by a banner naming the cardholder and the masked card. Geometry measured from
 * a real IndusInd credit-card statement; every name, number and narration below is synthetic per
 * the Synthetic Fixture Policy. See the pattern's own doc comment in {@link PdfTableLocator} for
 * the two real failures it closes (a standalone unmatched row, and a purchase whose description
 * began with the banner).
 */
class CardholderSubTableBannerPdfTableLocatorTest {

    private static final float HEADER_Y = 393.4f;

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    private static List<PositionedText> header() {
        return List.of(
                run("Date", 40.9f, 14.3f, HEADER_Y),
                run("Transaction Details", 117.4f, 57.7f, HEADER_Y),
                run("Merchant Category", 232.3f, 57.6f, HEADER_Y),
                run("CRED Points", 316.1f, 36.3f, HEADER_Y),
                run("Amount (in `)", 376.0f, 41.9f, HEADER_Y));
    }

    @Test
    void theBannerBecomesAuxiliaryTextAndNeverARowOrPartOfOne() {
        List<PositionedText> runs = new ArrayList<>(header());
        runs.addAll(List.of(
                run("Payment Details for MR RAVI KUMAR (Credit Card No. 1234XXXXXXXX5678)", 25.9f, 236.9f, 405.8f),
                run("01/09/2026", 25.9f, 33.2f, 420.0f),
                run("SAMPLE PAYMENT", 72.4f, 49.8f, 420.0f),
                run("0", 333.2f, 3.6f, 420.0f),
                run("1,285.00 CR", 386.3f, 36.3f, 420.0f),
                run("Purchases & Cash Transactions for MR RAVI KUMAR (Credit Card No. 1234XXXXXXXX5678)",
                        25.9f, 280.7f, 445.8f),
                run("24/08/2026", 25.9f, 33.2f, 460.0f),
                run("UPI SAMPLE MERCHANT 000000000003", 72.4f, 127.3f, 460.0f),
                run("COMPUTERS", 222.4f, 40.6f, 460.0f),
                run("0", 333.2f, 3.6f, 460.0f),
                run("16.96 DR", 395.5f, 27.1f, 460.0f)));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(doc.sections()).hasSize(1);
        var section = doc.sections().get(0);
        assertThat(section.rows()).hasSize(2);
        assertThat(section.rows().get(0).get("Transaction Details")).isEqualTo("SAMPLE PAYMENT");
        assertThat(section.rows().get(1).get("Transaction Details")).isEqualTo("UPI SAMPLE MERCHANT 000000000003");
        for (var row : section.rows()) {
            assertThat(String.join(" ", row.values())).doesNotContain("Credit Card No");
        }
        assertThat(section.auxiliaryText())
                .contains("Payment Details for MR RAVI KUMAR (Credit Card No. 1234XXXXXXXX5678)")
                .contains("Purchases & Cash Transactions for MR RAVI KUMAR (Credit Card No. 1234XXXXXXXX5678)");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("CARDHOLDER_SUBTABLE_BANNER");
    }

    @Test
    void aNarrationThatMerelyMentionsACardNumberIsStillARow() {
        // Whole-line anchored on the two sub-table names: a transaction narration is never one.
        List<PositionedText> runs = new ArrayList<>(header());
        runs.addAll(List.of(
                run("24/08/2026", 25.9f, 33.2f, 460.0f),
                run("Payment received for card (Credit Card No. 1234XXXXXXXX5678)", 72.4f, 200.0f, 460.0f),
                run("0", 333.2f, 3.6f, 460.0f),
                run("16.96 DR", 395.5f, 27.1f, 460.0f)));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).rows()).hasSize(1);
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .doesNotContain("CARDHOLDER_SUBTABLE_BANNER");
    }
}
