package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ACCOUNT_HOLDER_FROM_LEADING_RUN: the holder's name printed as the leftmost run of a line near the
 * top of page 0, with unrelated prose to its right on the same physical line and no label anywhere.
 * On a real ICICI credit-card statement the name run sits at the left margin and a marketing
 * sentence starts at mid-page on the same line; joined into one auxiliary line, no line-based
 * holder rule can separate them. Read at run level, the name is the line's own leftmost run.
 *
 * <p>All text synthetic per the Synthetic Fixture Policy.
 */
class LeadingNameRunExtractorTest {

    private static PositionedText run(String text, float x, float y, int page) {
        return new PositionedText(text, x, y, page);
    }

    @Test
    void theLeftmostNameShapedRunOfATopLine_isTheHolder_whateverProseSharesTheLine() {
        List<PositionedText> runs = List.of(
                run("CREDIT CARD STATEMENT", 37.5f, 56f, 0),
                run("Download the mobile app to -", 276f, 94f, 0),
                run("MS SAMPLE HOLDER", 38f, 95f, 0),
                run("12 SAMPLE STREET", 38f, 104f, 0),
                run("View statement instantly", 285f, 102f, 0),
                run("Date", 40f, 400f, 0), run("Details", 150f, 400f, 0), run("Amount", 470f, 400f, 0),
                run("01/07/2026", 40f, 412f, 0), run("SAMPLE STORE", 150f, 412f, 0), run("100.00", 470f, 412f, 0));
        DocumentContext ctx = new DocumentContext("PDF", "test");

        assertThat(LeadingNameRunExtractor.extract(runs, ctx)).isEqualTo("MS SAMPLE HOLDER");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).contains("ACCOUNT_HOLDER_FROM_LEADING_RUN");
    }

    @Test
    void aBankNameOrAStatementTitle_isNeverTheHolder() {
        List<PositionedText> runs = List.of(
                run("AXIS BANK", 38f, 56f, 0),
                run("Credit Card Statement", 38f, 70f, 0),
                run("Statement Summary", 38f, 84f, 0),
                run("Date", 40f, 400f, 0), run("Details", 150f, 400f, 0), run("Amount", 470f, 400f, 0));

        assertThat(LeadingNameRunExtractor.extract(runs, new DocumentContext("PDF", "test"))).isNull();
    }

    @Test
    void aNameBelowTheTopThirdOfThePage_orOnALaterPage_isNotRead() {
        // The rule is a page-top fallback; a counterparty name deep in the page, or on page 2, is
        // table content or a footer, never the holder.
        List<PositionedText> runs = List.of(
                run("CREDIT CARD STATEMENT", 37.5f, 56f, 0),
                run("SAMPLE COUNTERPARTY", 38f, 600f, 0),
                run("MR OTHER PERSON", 38f, 60f, 1),
                run("Date", 40f, 700f, 0), run("Amount", 470f, 700f, 0),
                run("Date", 40f, 700f, 1), run("Amount", 470f, 700f, 1));

        assertThat(LeadingNameRunExtractor.extract(runs, new DocumentContext("PDF", "test"))).isNull();
    }

    @Test
    void aRunWithALabelToItsLeft_isAValueNotAHolder() {
        List<PositionedText> runs = List.of(
                run("Nominee", 38f, 60f, 0), run("SAMPLE NOMINEE", 120f, 60f, 0),
                run("Date", 40f, 400f, 0), run("Amount", 470f, 400f, 0));

        assertThat(LeadingNameRunExtractor.extract(runs, new DocumentContext("PDF", "test"))).isNull();
    }
}
