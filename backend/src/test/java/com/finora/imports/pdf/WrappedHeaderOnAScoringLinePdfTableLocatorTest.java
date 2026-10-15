package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import com.finora.imports.TransactionNormalizer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * P-001 Fix B: a header printed across two bands is merged even when the UPPER band already scores
 * as a header on its own.
 *
 * <p>Captured from a real Central Bank of India savings statement. Its header is two genuine bands
 * 11.64pt apart -- already inside {@code HEADER_WRAP_MAX_GAP} -- but the merge was never attempted,
 * because {@code locateAll} only asked about a wrap on a line that did NOT already score. Band 1
 * scores on its own (token-aware matching sees "date" inside "Post Date", plus Debit/Credit/
 * Balance), so band 2 -- "Date | Code | Number" -- fell through and was consumed as the table's
 * first data row, and the date column stayed named "Value" rather than "Value Date".
 *
 * <p>The consumed junk row was the small half of the damage. {@code TransactionNormalizer} resolves
 * its date column by WHOLE-CELL comparison, and neither "value" nor "post date" is in its hints.
 * Measured before the fix: of 224 located rows, <b>0</b> carried a column the normalizer could read
 * as a date, so all 222 transactions were rejected downstream while the locator recorded a
 * successful single-section parse. It was the only 100% row loss in the committed corpus, and it
 * was silent.
 *
 * <p>Lifting that guard is the highest-risk change in this area, because on an already-scoring line
 * the alternative reading -- "band 2 is the table's first data row" -- is the CORRECT one on most
 * documents. So the merge is admitted only under {@code refinesRatherThanRedefines}' four gates,
 * and each of them is asserted here on its own adversarial layout: a case that clears every other
 * gate and is refused by exactly the one under test. Without that, a single over-broad gate would
 * be invisible behind the other three.
 *
 * <p>The tests that read committed statement traces moved to {@code
 * WrappedHeaderOnAScoringLinePdfTableLocatorTracesTest} in the private
 * Fynora/finora-statement-traces repository, with the traces themselves (see
 * docs/engineering/private-statement-traces.md). The tests left here build their input by hand.
 */
class WrappedHeaderOnAScoringLinePdfTableLocatorTest {

    // ------------------------------------------------------------------
    // The real document
    // ------------------------------------------------------------------

    private List<String> headerOf(List<PositionedText> runs) {
        DocumentContext ctx = new DocumentContext("PDF", "WrappedHeaderOnAScoringLineTest");
        new PdfTableLocator().locateAll(runs, ctx);
        return ctx.buildMetadata().headers();
    }

    // ------------------------------------------------------------------
    // The four gates, one adversarial layout each
    // ------------------------------------------------------------------

    /**
     * The CBI shape, reduced to its essentials: an upper band that already scores as a header on
     * its own, and a lower band that refines two of its columns. Every adversarial case below is
     * this layout with exactly one property spoiled, so the gate that refuses it is unambiguous.
     */
    private List<PositionedText> layout(List<PositionedText> lowerBand) {
        List<PositionedText> runs = new ArrayList<>(List.of(
                new PositionedText("Date", 26f, 100f, 0, 16f),
                new PositionedText("Value", 91f, 100f, 0, 25f),
                new PositionedText("Branch", 138f, 100f, 0, 31f),
                new PositionedText("Cheque", 188f, 100f, 0, 35f),
                new PositionedText("Description", 242f, 100f, 0, 60f),
                new PositionedText("Debit", 375f, 100f, 0, 23f),
                new PositionedText("Credit", 439f, 100f, 0, 26f),
                new PositionedText("Balance", 512f, 100f, 0, 36f)));
        runs.addAll(lowerBand);
        // Two ordinary transactions, a full row pitch below the heading.
        runs.addAll(List.of(
                new PositionedText("01/05/2026", 26f, 140f, 0, 40f),
                new PositionedText("01/05/2026", 91f, 140f, 0, 40f),
                new PositionedText("RENT", 242f, 140f, 0, 30f),
                new PositionedText("500.00", 375f, 140f, 0, 25f),
                new PositionedText("1,000.00", 512f, 140f, 0, 30f),
                new PositionedText("02/05/2026", 26f, 160f, 0, 40f),
                new PositionedText("02/05/2026", 91f, 160f, 0, 40f),
                new PositionedText("SALARY", 242f, 160f, 0, 40f),
                new PositionedText("900.00", 439f, 160f, 0, 25f),
                new PositionedText("1,900.00", 512f, 160f, 0, 30f)));
        return runs;
    }

    @Test
    void theBaselineLayoutDoesMerge_soEveryRefusalBelowIsTheGateAndNotTheLayout() {
        // Two lower cells, both within 5pt of an anchor, no new column, and the merge adds
        // "Value Date" -- a name the normalizer knows and "Value" alone is not.
        assertThat(headerOf(layout(List.of(
                new PositionedText("Date", 93.44f, 111.64f, 0, 21f),
                new PositionedText("Code", 142f, 111.64f, 0, 24f)))))
                .containsExactly("Date", "Value Date", "Branch Code", "Cheque", "Description",
                        "Debit", "Credit", "Balance");
    }

    @Test
    void gate1_oneStrayTokenBelowAHeaderIsNotASecondBand() {
        // Identical to the baseline except the lower band has ONE cell -- and that cell's own
        // text is NOT itself recognized column-name vocabulary (see the test immediately below
        // for the one narrow exception this floor now has). It is 2.44pt from its anchor and
        // would still improve the whole-cell count if joined ("Value Note" -- still not a
        // recognized name, but the merge doesn't even get that far) -- so only the cell-count
        // floor can refuse it. A footnote, a unit annotation and a narration fragment all look
        // exactly like this, and the corpus has several.
        assertThat(headerOf(layout(List.of(
                new PositionedText("Note", 93.44f, 111.64f, 0, 21f)))))
                .containsExactly("Date", "Value", "Branch", "Cheque", "Description",
                        "Debit", "Credit", "Balance");
    }

    /**
     * The one narrow exception to Gate 1's floor: a lone lower cell whose ENTIRE text exactly
     * equals a recognized column-name word is admitted anyway -- see {@code
     * refinesRatherThanRedefines}'s own Gate 1 doc comment for the real Standard Chartered
     * savings statement this exists for (a bare "Date" wrapping under "Value", nowhere else on
     * the statement naming that column, which most rows' own date depends on for the row-anchor
     * gate to recognize them at all). Still gated by 2 (within STRICT_COLUMN_JOIN) and 4 (a net
     * improvement in whole-cell hint matches) exactly like every other admission this class makes
     * -- this is narrower than "any one recognized word", not a general loosening of the floor.
     */
    @Test
    void gate1Exception_aSingleCellThatIsItselfRecognizedVocabularyIsAdmitted() {
        assertThat(headerOf(layout(List.of(
                new PositionedText("Date", 93.44f, 111.64f, 0, 21f)))))
                .containsExactly("Date", "Value Date", "Branch", "Cheque", "Description",
                        "Debit", "Credit", "Balance");
    }

    @Test
    void gate2_aLowerCellOffItsColumnMeansTheseAreNotOneHeading() {
        // Two lower cells, but "Code" now sits 12pt from Branch's anchor -- inside the existing
        // 40pt HEADER_WRAP_MAX_COLUMN_JOIN (so mergeHeaderLines itself is happy to join it) and
        // outside the 5pt strict bound. This is the gate that separates a printed second band from
        // a line that merely sits nearby, and the 40pt bound cannot do it.
        assertThat(headerOf(layout(List.of(
                new PositionedText("Date", 93.44f, 111.64f, 0, 21f),
                new PositionedText("Code", 150f, 111.64f, 0, 24f)))))
                .containsExactly("Date", "Value", "Branch", "Cheque", "Description",
                        "Debit", "Credit", "Balance");
    }

    @Test
    void gate3_aMergeThatWouldAddAColumnIsRefused() throws Exception {
        // Asserted on the predicate directly, and deliberately so. mergeHeaderLines returns null
        // rather than a row the moment a lower cell joins no column, so no run list can currently
        // reach this gate through locateAll -- it is an invariant, not a filter. It is still coded
        // and still tested, because it is the property that makes "one header over two lines" mean
        // refinement rather than redefinition, and mergeHeaderLines' own doc comment records real
        // pressure to relax exactly that (the half-named recurring-deposit heading).
        PdfTableLocator locator = new PdfTableLocator();
        Method refines = PdfTableLocator.class.getDeclaredMethod(
                "refinesRatherThanRedefines", List.class, List.class);
        refines.setAccessible(true);

        List<PositionedText> upper = List.of(
                new PositionedText("Date", 26f, 100f, 0, 16f),
                new PositionedText("Value", 91f, 100f, 0, 25f),
                new PositionedText("Debit", 375f, 100f, 0, 23f),
                new PositionedText("Sheet", 512f, 100f, 0, 24f));
        List<List<PositionedText>> block = List.of(upper, List.of(
                new PositionedText("Date", 93f, 111f, 0, 21f),
                new PositionedText("Balance", 514f, 111f, 0, 36f)));

        // A well-formed refinement of that block: four columns in, four columns out -> admitted.
        assertThat((Boolean) refines.invoke(locator, block, List.of(
                new PositionedText("Date", 26f, 111f, 0, 16f),
                new PositionedText("Value Date", 91f, 111f, 0, 25f),
                new PositionedText("Debit", 375f, 111f, 0, 23f),
                new PositionedText("Sheet Balance", 512f, 111f, 0, 38f)))).isTrue();

        // The same block, merged into FIVE columns -- the lower band introduced one of its own
        // rather than renaming what was above it. Refused, even though it names strictly more
        // columns the normalizer knows than the upper line did.
        assertThat((Boolean) refines.invoke(locator, block, List.of(
                new PositionedText("Date", 26f, 111f, 0, 16f),
                new PositionedText("Value Date", 91f, 111f, 0, 25f),
                new PositionedText("Debit", 375f, 111f, 0, 23f),
                new PositionedText("Sheet", 512f, 111f, 0, 24f),
                new PositionedText("Balance", 514f, 111f, 0, 36f)))).isFalse();
    }

    @Test
    void gate4_aMergeThatNamesNoMoreColumnsThanBeforeIsRefused() {
        // Two lower cells, both tightly aligned, no new column -- gates 1, 2 and 3 all pass, and
        // the merged row still scores as a header. But "Branch Code" and "Cheque Number" name
        // nothing TransactionNormalizer knows by whole-cell comparison, so the merge is a rename,
        // not an improvement, and the unmerged reading stands. This is the safety valve: without
        // it, every already-correct header in the corpus is one tight-alignment coincidence away
        // from being renamed.
        assertThat(headerOf(layout(List.of(
                new PositionedText("Code", 142f, 111.64f, 0, 24f),
                new PositionedText("Number", 188.5f, 111.64f, 0, 35f)))))
                .containsExactly("Date", "Value", "Branch", "Cheque", "Description",
                        "Debit", "Credit", "Balance");
    }

    @Test
    void gate4CountsWholeCellMatches_notTheTokenAwareOnesLooksLikeHeaderRowScores() throws Exception {
        // The distinction is the entire reason CBI's merge is admitted. Token-aware matching sees
        // "date" inside "Post Date" and calls the date column found on BOTH readings, which would
        // make the merge look like no improvement at all. Whole-cell matching -- what the
        // normalizer actually does -- sees the difference between "Value" and "Value Date".
        PdfTableLocator locator = new PdfTableLocator();
        Method wholeCell = PdfTableLocator.class.getDeclaredMethod("wholeCellHintMatches", List.class);
        wholeCell.setAccessible(true);
        Method looksLike = PdfTableLocator.class.getDeclaredMethod("looksLikeHeaderRow", List.class);
        looksLike.setAccessible(true);

        List<PositionedText> unmerged = List.of(
                new PositionedText("Post Date", 26f, 100f, 0, 40f),
                new PositionedText("Value", 91f, 100f, 0, 25f),
                new PositionedText("Balance", 512f, 100f, 0, 36f));
        List<PositionedText> merged = List.of(
                new PositionedText("Post Date", 26f, 111f, 0, 40f),
                new PositionedText("Value Date", 91f, 111f, 0, 45f),
                new PositionedText("Balance", 512f, 111f, 0, 36f));

        assertThat((Boolean) looksLike.invoke(locator, unmerged))
                .as("token-aware scoring already calls this a header -- which is why the merge was refused")
                .isTrue();
        assertThat((Integer) wholeCell.invoke(locator, unmerged)).isEqualTo(1); // "balance" only
        assertThat((Integer) wholeCell.invoke(locator, merged)).isEqualTo(2);   // + "value date"
    }
}
