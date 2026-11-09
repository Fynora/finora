package com.finora.imports.pdf;

import com.finora.imports.CsvParser;
import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPLIT_PARENTHETICAL_HEADER: the three-tier header {@link InteriorTierWrappedHeaderPdfTableLocatorTest}
 * covers, except that the text layer splits one middle-tier label in two -- "Debit(" and "Rs)" as
 * two runs that touch, most likely at a font change.
 *
 * <p>Modeled on a real Indian Overseas Bank savings statement held for review on 2026-11-08 (every
 * value and coordinate here is synthetic, per the Synthetic Fixture Policy). Neither half is
 * column-name vocabulary on its own, so the middle tier failed the interior-tier content gate, the
 * three tiers were never joined, and the bottom tier alone was taken for the header. Its date
 * column was then named "Date)", which no date hint matches, and every transaction on the
 * statement was rejected.
 */
class SplitParentheticalHeaderPdfTableLocatorTest {

    private static PositionedText run(String text, float x, float y, float width) {
        return new PositionedText(text, x, y, 0, width);
    }

    private static PositionedText run(String text, float x, float y, float width, int page) {
        return new PositionedText(text, x, y, page, width);
    }

    /** Three header tiers, the middle one carrying "Debit(" + "Rs)" as touching runs, then two
     *  transactions. */
    private List<PositionedText> documentRuns() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                // Top tier: the outer group's upper half.
                run("Date(Value", 50f, 100f, 48f),
                run("Ref No.", 280f, 98f, 32f),
                run("Transaction", 330f, 100f, 50f),

                // Middle tier: the interior group. "Debit(" ends at exactly x=420, where "Rs)" starts.
                run("Particulars", 165f, 103f, 43f),
                run("Debit(", 395f, 103f, 25f),
                run("Rs)", 420f, 103f, 14f),
                run("Credit(Rs)", 452f, 103f, 42f),
                run("Balance(Rs)", 505f, 103f, 49f),

                // Bottom tier: the outer group's lower half.
                run("Date)", 63f, 109f, 22f),
                run("/Cheque No", 272f, 109f, 48f),
                run("Type", 344f, 109f, 21f)));

        runs.addAll(List.of(
                run("03-Jul-26", 45f, 130f, 40f), run("SALARY CREDIT", 112f, 130f, 70f),
                run("R0001", 278f, 130f, 25f), run("Transfer", 342f, 130f, 30f),
                run("-", 431f, 130f, 3f), run("5,000.00", 460f, 130f, 34f),
                run("15,000.00", 517f, 130f, 37f),

                run("02-Jul-26", 45f, 150f, 40f), run("GROCERY STORE", 112f, 150f, 70f),
                run("R0002", 278f, 150f, 25f), run("Transfer", 342f, 150f, 30f),
                run("1,200.00", 400f, 150f, 34f), run("-", 491f, 150f, 3f),
                run("10,000.00", 517f, 150f, 37f)));
        return runs;
    }

    @Test
    void theSplitLabelIsRejoined_andAllThreeTiersBecomeOneHeader() {
        DocumentContext ctx = new DocumentContext("PDF", "SplitParentheticalHeaderTest");

        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(documentRuns(), ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(ctx.buildMetadata().headers()).containsExactlyInAnyOrder(
                "Date(Value Date)", "Particulars", "Ref No. /Cheque No", "Transaction Type",
                "Debit(Rs)", "Credit(Rs)", "Balance(Rs)");
        assertThat(ctx.capabilities()).extracting("capability").contains("WRAPPED_HEADER");
    }

    @Test
    void everyTransactionKeepsAParseableDate_andItsAmountUnderTheRightColumn() {
        List<Map<String, String>> rows = new PdfTableLocator().locateAll(documentRuns(), null)
                .sections().get(0).rows();

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> assertThat(CsvParser.parseDate(row.get("Date(Value Date)")))
                .as("a date column the importer recognises, so no transaction is rejected")
                .isNotNull());
        assertThat(rows.get(0)).containsEntry("Credit(Rs)", "5,000.00").containsEntry("Balance(Rs)", "15,000.00");
        assertThat(rows.get(1)).containsEntry("Debit(Rs)", "1,200.00").containsEntry("Balance(Rs)", "10,000.00");
    }

    // ---------- the rejoining rule itself ----------

    @Test
    void touchingRunsThatOpenAndCloseOneBracket_areJoinedIntoOneRun() {
        List<PositionedText> joined = PdfTableLocator.joinSplitParentheticals(List.of(
                run("Particulars", 165f, 103f, 43f), run("Debit(", 395f, 103f, 25f), run("Rs)", 420f, 103f, 14f),
                run("Credit(Rs)", 452f, 103f, 42f)));

        assertThat(joined).extracting(PositionedText::text)
                .containsExactly("Particulars", "Debit(Rs)", "Credit(Rs)");
        PositionedText debit = joined.get(1);
        assertThat(debit.x()).isEqualTo(395f);
        assertThat(debit.width()).as("spans both halves, so the column's right edge is unchanged").isEqualTo(39f);
    }

    @Test
    void aSubPointGapStillJoins_butAWordSizedGapDoesNot() {
        assertThat(PdfTableLocator.joinSplitParentheticals(List.of(
                run("Debit(", 395f, 103f, 25f), run("Rs)", 420.8f, 103f, 14f))))
                .extracting(PositionedText::text).containsExactly("Debit(Rs)");
        assertThat(PdfTableLocator.joinSplitParentheticals(List.of(
                run("Debit(", 395f, 103f, 25f), run("Rs)", 425f, 103f, 14f))))
                .extracting(PositionedText::text).containsExactly("Debit(", "Rs)");
    }

    @Test
    void labelsThatAlreadyBalanceTheirBrackets_areNeverJoined_evenWhenTouching() {
        assertThat(PdfTableLocator.joinSplitParentheticals(List.of(
                run("Credit(Rs)", 452f, 103f, 42f), run("Balance(Rs)", 494f, 103f, 49f))))
                .extracting(PositionedText::text).containsExactly("Credit(Rs)", "Balance(Rs)");
    }

    @Test
    void aJoinThatWouldNotCloseTheBracket_isRefused() {
        assertThat(PdfTableLocator.joinSplitParentheticals(List.of(
                run("Debit(", 395f, 103f, 25f), run("Rs", 420f, 103f, 10f))))
                .extracting(PositionedText::text).containsExactly("Debit(", "Rs");
    }

    @Test
    void runsOnDifferentPages_orWithoutAMeasuredWidth_areNeverJoined() {
        assertThat(PdfTableLocator.joinSplitParentheticals(List.of(
                run("Debit(", 395f, 103f, 25f, 0), run("Rs)", 420f, 103f, 14f, 1))))
                .extracting(PositionedText::text).containsExactly("Debit(", "Rs)");
        assertThat(PdfTableLocator.joinSplitParentheticals(List.of(
                run("Debit(", 395f, 103f, 0f), run("Rs)", 395f, 103f, 14f))))
                .extracting(PositionedText::text).containsExactly("Debit(", "Rs)");
    }

    @Test
    void aLineWithNothingToJoin_isReturnedUnchanged() {
        List<PositionedText> line = List.of(run("Date)", 63f, 109f, 22f), run("Type", 344f, 109f, 21f));
        assertThat(PdfTableLocator.joinSplitParentheticals(line)).isSameAs(line);
    }
}
