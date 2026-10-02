package com.finora.imports.pdf;

import com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence.ExtractionMethod;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a credit-card statement's own billing-summary panel via either of two independent
 * strategies -- GRID (a stacked label-row/value-row grid) and INLINE_LABEL_VALUE (a same-visual-row
 * label-left/value-right layout).
 *
 * <p>The label vocabulary and the two layout shapes tested here were drawn from reading all 6 real
 * credit-card documents' raw positioned text (coordinates intact, not the lossy line-joined
 * auxiliary text) during the Credit Card Direction Evidence Study and its follow-up measurement —
 * never invented in advance. The specific geometry in each test below is invented, per the
 * Synthetic Fixture Policy: it reproduces the SHAPE actually observed (a grid row-merge from a
 * real Axis statement, a same-row layout from a real AU statement), not a copy of either real
 * document's own positions or figures.
 */
class CreditCardSummaryExtractorTest {

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    private static PositionedText runOnPage(String text, float x, float width, float y, int page) {
        return new PositionedText(text, x, y, page, width);
    }

    /** A clean, invented billing-summary grid carrying all six fields this extractor reads. */
    private static List<PositionedText> cleanSummaryBlock() {
        return new ArrayList<>(List.of(
                run("Previous Balance", 50f, 90f, 300f),
                run("Purchases", 150f, 60f, 300f),
                run("Cash Advances", 220f, 70f, 300f),
                run("Fees", 300f, 30f, 300f),
                run("Payments / Credits", 340f, 90f, 300f),
                run("Total Amount Due", 440f, 90f, 300f),
                run("10,000.00", 55f, 40f, 330f),
                run("5,000.00", 155f, 40f, 330f),
                run("0.00", 230f, 30f, 330f),
                run("100.00", 305f, 30f, 330f),
                run("2,000.00", 345f, 40f, 330f),
                run("13,100.00", 445f, 40f, 330f)));
    }

    @Test
    void readsAllSixFieldsFromACleanLabelValueGrid() {
        var summary = CreditCardSummaryExtractor.extract(cleanSummaryBlock());

        assertThat(summary.previousBalance()).isEqualByComparingTo("10000.00");
        assertThat(summary.purchases()).isEqualByComparingTo("5000.00");
        assertThat(summary.cashAdvances()).isEqualByComparingTo("0.00");
        assertThat(summary.fees()).isEqualByComparingTo("100.00");
        assertThat(summary.paymentsAndCredits()).isEqualByComparingTo("2000.00");
        assertThat(summary.totalAmountDue()).isEqualByComparingTo("13100.00");
        assertThat(summary.hasReconcilableFields()).isTrue();
        assertThat(summary.extractionMethod()).isEqualTo(ExtractionMethod.GRID);
    }

    @Test
    void matchesEachValueToTheLabelAboveItRatherThanByOrder() {
        // Previous Balance sits leftmost with the largest printed value (10,000.00); reading by
        // order rather than position would put it under Purchases instead.
        var summary = CreditCardSummaryExtractor.extract(cleanSummaryBlock());

        assertThat(summary.previousBalance()).isEqualByComparingTo("10000.00");
        assertThat(summary.purchases()).isEqualByComparingTo("5000.00");
    }

    @Test
    void feesIsOptionalUnlikeThePreviousBalancePurchasesPaymentsAndTotalDue() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Previous Balance", 50f, 90f, 300f),
                run("Purchases", 150f, 60f, 300f),
                run("Cash Advances", 220f, 70f, 300f),
                run("Payments / Credits", 340f, 90f, 300f),
                run("Total Amount Due", 440f, 90f, 300f),
                run("10,000.00", 55f, 40f, 330f),
                run("5,000.00", 155f, 40f, 330f),
                run("0.00", 230f, 30f, 330f),
                run("2,000.00", 345f, 40f, 330f),
                run("13,000.00", 445f, 40f, 330f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.fees()).isNull();
        assertThat(summary.hasReconcilableFields())
                .as("fees is not one of the fields this needs to attempt a reconciliation")
                .isTrue();
    }

    @Test
    void cashAdvancesIsAlsoOptional() {
        // The real shape found on AU's statement: no "Cash Advances" line printed anywhere on the
        // page at all (the customer had none), not merely a zero value under that label.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Previous Balance", 50f, 90f, 300f),
                run("Purchases", 150f, 60f, 300f),
                run("Payments / Credits", 340f, 90f, 300f),
                run("Total Amount Due", 440f, 90f, 300f),
                run("10,000.00", 55f, 40f, 330f),
                run("5,000.00", 155f, 40f, 330f),
                run("2,000.00", 345f, 40f, 330f),
                run("13,000.00", 445f, 40f, 330f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.cashAdvances()).isNull();
        assertThat(summary.hasReconcilableFields()).isTrue();
    }

    @Test
    void refusesATransactionTableHeaderThatHappensToNameTotalAmountDue() {
        // A transaction row carries a date and a description alongside its amount, so it is never
        // an all-numeric value row -- the same discriminator StatementSummaryExtractor relies on.
        // Also exercises the GRID row-merge recovery path below: "01/07/2026" is date-shaped and
        // "SOME PAYMENT" is neither date- nor amount-shaped, so recovery must refuse this row
        // rather than pull "111.00" out of it.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Date", 50f, 20f, 100f),
                run("Narration", 120f, 40f, 100f),
                run("Total Amount Due", 200f, 90f, 100f),
                run("01/07/2026", 50f, 40f, 120f),
                run("SOME PAYMENT", 120f, 60f, 120f),
                run("111.00", 200f, 30f, 120f)));
        runs.addAll(cleanSummaryBlock());

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.totalAmountDue()).isEqualByComparingTo("13100.00");
    }

    @Test
    void readsNothingFromADocumentThatNeverPrintsATotalAmountDue() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Date", 50f, 20f, 100f),
                run("Amount", 200f, 30f, 100f),
                run("01/07/2026", 50f, 40f, 120f),
                run("111.00", 200f, 30f, 120f)));

        assertThat(CreditCardSummaryExtractor.extract(runs))
                .isEqualTo(CreditCardSummaryExtractor.CreditCardSummaryEvidence.NONE);
    }

    @Test
    void readsNothingFromAnEmptyDocument() {
        assertThat(CreditCardSummaryExtractor.extract(List.of()).hasReconcilableFields()).isFalse();
        assertThat(CreditCardSummaryExtractor.extract(null).hasReconcilableFields()).isFalse();
    }

    // --- GRID row-merge recovery (real shape: a real Axis statement's date-range row and amount
    // row sit ~1.0pt apart in y, close enough that groupIntoRows merges them into one group) ---

    @Test
    void recoversTheAmountFromAGridRowThatGroupIntoRowsMergedWithADateRange() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Statement Period", 150f, 100f, 224f),
                run("Total Amount Due", 50f, 90f, 224f),
                // Merged by groupIntoRows despite being two visually distinct rows: 1.5pt apart,
                // well within ROW_TOLERANCE -- the same gap observed on the real document.
                run("24/06/2026 - 22/07/2026", 150f, 150f, 236.5f),
                run("34,521.90", 55f, 40f, 238.0f),
                // The other three required fields, in a separate clean grid far enough away not to
                // interact with the merge above -- without these, GRID's own result wouldn't be
                // reconcilable and the extractor would (correctly) move on to try INLINE_LABEL_VALUE instead,
                // which would find nothing here and mask whether recovery actually worked.
                run("Previous Balance", 50f, 90f, 400f),
                run("Purchases", 150f, 60f, 400f),
                run("Payments / Credits", 220f, 90f, 400f),
                run("1,000.00", 55f, 40f, 430f),
                run("500.00", 155f, 40f, 430f),
                run("200.00", 225f, 40f, 430f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.totalAmountDue())
                .as("the date range must not block recovery of the genuine amount in the same "
                        + "merged row")
                .isEqualByComparingTo("34521.90");
        assertThat(summary.extractionMethod()).isEqualTo(ExtractionMethod.GRID);
    }

    @Test
    void doesNotRecoverAValueFromARowWithUnclassifiableContentAlongsideAnAmount() {
        // "SOME PAYMENT" is neither date-shaped nor amount-shaped -- recovery requires the merged
        // row to be FULLY explained as dates-plus-amounts, so a third, unrecognised kind of content
        // must refuse the whole row rather than guess which number belongs to the label.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total Amount Due", 50f, 90f, 224f),
                run("24/06/2026", 150f, 80f, 236.5f),
                run("SOME PAYMENT", 250f, 90f, 236.8f),
                run("34,521.90", 55f, 40f, 238.0f)));

        assertThat(CreditCardSummaryExtractor.extract(runs).totalAmountDue()).isNull();
    }

    @Test
    void recoversTheAmountsFromAGridRowThatGroupIntoRowsMergedWithEquationOperators() {
        // Real bug, found verifying against a real HDFC (Tata Neu Plus) statement: its grid prints
        // the billing equation literally -- "prevBal + purchases + fees = totalDue" -- as one row,
        // with "+" and "=" each their own standalone token merged in with the five real figures by
        // groupIntoRows. Invented labels/numbers below reproduce the SHAPE (operator glyphs sharing
        // a value row with real amounts), not the real document's own content.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Previous Balance", 50f, 90f, 224f),
                run("Payments / Credits", 155f, 90f, 224f),
                run("Purchases", 260f, 60f, 224f),
                run("Total Amount Due", 445f, 90f, 224f),
                run("440.46", 68f, 40f, 250f),
                run("+", 130f, 10f, 250f),
                run("440.00", 172f, 40f, 250f),
                run("+", 245f, 10f, 250f),
                run("1,817.02", 269f, 50f, 250f),
                run("=", 425f, 10f, 250f),
                run("1,817.00", 445f, 60f, 250f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.totalAmountDue())
                .as("the equation's own '+'/'=' glyphs must not block recovery of the genuine "
                        + "amounts in the same merged row")
                .isEqualByComparingTo("1817.00");
        assertThat(summary.previousBalance()).isEqualByComparingTo("440.46");
        assertThat(summary.purchases()).isEqualByComparingTo("1817.02");
        assertThat(summary.paymentsAndCredits()).isEqualByComparingTo("440.00");
        assertThat(summary.extractionMethod()).isEqualTo(ExtractionMethod.GRID);
    }

    @Test
    void aBareMinusSignIsNotTreatedAsAnEquationOperator() {
        // Deliberately excluded (see amountBearingSubset's own doc comment): a bare "-" is
        // ambiguous with a genuinely negative amount printed as its own token, which "+"/"=" can
        // never be, and no real document has evidenced this shape. "SOME LABEL" also keeps this
        // row unrecoverable regardless, so this specifically pins the "-" exclusion rather than
        // relying on the other token to fail the row.
        // The "-" sits in the label's own column, just before the figure -- where a sign would be --
        // so neither the row nor the column can tell a negative total from a stray glyph.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total Amount Due", 50f, 90f, 224f),
                run("-", 55f, 6f, 236.5f),
                run("SOME LABEL", 250f, 60f, 236.6f),
                run("34,521.90", 65f, 40f, 238.0f)));

        assertThat(CreditCardSummaryExtractor.extract(runs).totalAmountDue()).isNull();
    }

    // --- INLINE_LABEL_VALUE strategy (real shape: a real AU statement's "Bill summary" widget, label left,
    // value right, at a roughly fixed y and a right-hand x offset) ---

    private static List<PositionedText> sameRowSummaryBlock() {
        return new ArrayList<>(List.of(
                run("Opening balance", 355f, 70f, 229.6f),
                run("40,000.00", 518f, 46f, 228.2f),
                run("Total spends", 355f, 53f, 250.9f),
                run("6,000.00", 523f, 41f, 249.4f),
                run("Payments & Refunds", 355f, 86f, 272.1f),
                run("44,000.00", 518f, 46f, 270.7f),
                run("Total amount due", 355f, 74f, 363.2f),
                run("2,000.00", 523f, 41f, 363.5f)));
    }

    @Test
    void readsFieldsFromASameRowLabelLeftValueRightLayout() {
        var summary = CreditCardSummaryExtractor.extract(sameRowSummaryBlock());

        assertThat(summary.previousBalance()).isEqualByComparingTo("40000.00");
        assertThat(summary.purchases()).isEqualByComparingTo("6000.00");
        assertThat(summary.paymentsAndCredits()).isEqualByComparingTo("44000.00");
        assertThat(summary.totalAmountDue()).isEqualByComparingTo("2000.00");
        assertThat(summary.extractionMethod()).isEqualTo(ExtractionMethod.INLINE_LABEL_VALUE);
    }

    @Test
    void gridIsTriedBeforeSameRow() {
        // A document exercising the clean GRID shape must never fall through to INLINE_LABEL_VALUE, even
        // though nothing here prevents INLINE_LABEL_VALUE from also matching a stacked grid's labels --
        // GRID's own values are stacked BELOW, not beside, so INLINE_LABEL_VALUE's same-y search would find
        // no candidate to its right and correctly produce nothing for it to compete with.
        var summary = CreditCardSummaryExtractor.extract(cleanSummaryBlock());

        assertThat(summary.extractionMethod()).isEqualTo(ExtractionMethod.GRID);
    }

    @Test
    void refusesToGuessWhenTwoCandidateAmountsCompeteForTheSameLabel() {
        // Two numeric tokens both sit to the right of "Opening balance", both within the same-row
        // y-tolerance -- a genuinely ambiguous case. The nearer one is usually right, but "usually
        // right" is exactly the confident-wrong-guess this strategy exists to avoid.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total amount due", 355f, 74f, 363.2f),
                run("2,000.00", 523f, 41f, 363.5f),
                run("Opening balance", 355f, 70f, 229.6f),
                run("40,000.00", 518f, 46f, 228.2f),
                run("90,000.00", 518f, 46f, 230.0f)));

        assertThat(CreditCardSummaryExtractor.extract(runs).previousBalance())
                .as("ambiguous -- must not guess the nearer of two competing candidates")
                .isNull();
    }

    @Test
    void requiresTheCandidateToBeToTheRightOfTheLabelNotJustNearItInY() {
        // A number at the same y but to the LEFT of the label (e.g. a page-margin figure, or the
        // end of the previous line) must never be picked up as this label's value.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("50.00", 10f, 30f, 229.6f),
                run("Opening balance", 355f, 70f, 229.6f),
                run("Total amount due", 355f, 74f, 363.2f),
                run("2,000.00", 523f, 41f, 363.5f)));

        assertThat(CreditCardSummaryExtractor.extract(runs).previousBalance()).isNull();
    }

    @Test
    void requiresTheCandidateToBeReasonablyCloseInXNotJustAnywhereOnThePage() {
        // Real bug, found verifying against the real corpus: a real Axis statement has an unrelated
        // fee-schedule example elsewhere on the same page (an isolated "Purchase" label followed,
        // much further right, by an unrelated example amount) that trySameRow matched as if it were
        // this statement's own summary field before this distance cap existed. Invented numbers/
        // labels below reproduce the SHAPE of that bug -- a label and a same-page, same-row-ish
        // numeric token separated by an implausibly wide gap -- not the real document's content.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total amount due", 355f, 74f, 363.2f),
                run("2,000.00", 523f, 41f, 363.5f),
                run("Purchases", 60f, 60f, 600f),
                run("7250", 800f, 30f, 600.5f)));

        assertThat(CreditCardSummaryExtractor.extract(runs).purchases())
                .as("far enough away that it is almost certainly unrelated content, not this "
                        + "statement's own summary panel")
                .isNull();
    }

    @Test
    void recognisesNetOutstandingBalanceAsTheTotalAmountDueLabel() {
        // Real HSBC shape: this statement never prints "Total Amount Due" anywhere -- its own
        // headline figure is labeled "Net Outstanding Balance" instead. Confirmed the same concept
        // by the real document's own printed arithmetic (four "...Outstanding" component labels sum
        // to it exactly), not invented. Coordinates below mirror the real document's own same-row,
        // right-aligned-column shape: a date prefix, the label, then the value far enough right that
        // it exercises the widened SAME_ROW_MAX_X_DISTANCE (gap 212.9pt on the real document -- a
        // short "0.00" value sits further from the label than a longer value does in the identical
        // column on a same-layout statement, since the column right-aligns).
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("23JUL", 31f, 20f, 375f),
                run("Net Outstanding Balance", 78f, 102f, 375f),
                run("0.00", 393f, 14f, 375f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        // Only totalAmountDue is printed here (no previous balance/purchases/payments alongside
        // it), so hasReconcilableFields() correctly refuses and extractionMethod stays null --
        // same "surfaces alone" pattern as totalAmountDueSurfacesAlone_... above; the value still
        // reaches the caller via bestEffortTotalAmountDue.
        assertThat(summary.totalAmountDue()).isEqualByComparingTo("0.00");
    }

    @Test
    void sameRowMaxXDistanceCoversTheRealHsbcGapButStillRejectsTheAxisFeeScheduleGap() {
        // Pins the exact boundary the widened constant was calibrated against: 220pt (just past the
        // real HSBC "0.00" gap of 212.9pt) still resolves, 250pt (comfortably short of the Axis
        // fee-schedule gap of 680pt, but past the widened cap) is refused.
        List<PositionedText> withinNewCap = new ArrayList<>(List.of(
                run("Net Outstanding Balance", 78f, 102f, 375f),
                run("500.00", 400f, 40f, 375f)));
        assertThat(CreditCardSummaryExtractor.extract(withinNewCap).totalAmountDue())
                .isEqualByComparingTo("500.00");

        List<PositionedText> beyondNewCap = new ArrayList<>(List.of(
                run("Net Outstanding Balance", 78f, 102f, 375f),
                run("500.00", 430f, 40f, 375f)));
        assertThat(CreditCardSummaryExtractor.extract(beyondNewCap).totalAmountDue()).isNull();
    }

    @Test
    void refusesADuplicateLabelRatherThanTakingTheFirstOccurrence() {
        // Real banks repeat summary-style wording in footers or help sections. Two "Opening
        // balance" occurrences, each individually resolving to its OWN single, unambiguous
        // candidate -- but which one is genuinely this statement's own summary field is not
        // decidable from position alone, so neither should win by being scanned first.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Opening balance", 355f, 70f, 229.6f),
                run("10,000.00", 518f, 46f, 228.2f),
                run("Opening balance", 355f, 70f, 700f),
                run("50,000.00", 518f, 46f, 699f),
                run("Total amount due", 355f, 74f, 363.2f),
                run("2,000.00", 523f, 41f, 363.5f)));

        assertThat(CreditCardSummaryExtractor.extract(runs).previousBalance())
                .as("a repeated label is ambiguous, not first-wins")
                .isNull();
    }

    @Test
    void gridAlsoRefusesADuplicateLabelRatherThanTakingTheFirst() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Previous Balance", 50f, 90f, 300f),
                run("Purchases", 150f, 60f, 300f),
                run("Payments / Credits", 220f, 90f, 300f),
                run("Total Amount Due", 320f, 90f, 300f),
                run("10,000.00", 55f, 40f, 330f),
                run("5,000.00", 155f, 40f, 330f),
                run("2,000.00", 225f, 40f, 330f),
                run("13,000.00", 325f, 40f, 330f),
                // The same label repeated in an unrelated block elsewhere on the page, its own
                // clean grid shape, a different value.
                run("Previous Balance", 50f, 90f, 500f),
                run("50,000.00", 55f, 40f, 530f)));

        assertThat(CreditCardSummaryExtractor.extract(runs).previousBalance())
                .as("ambiguous for GRID too, not just INLINE_LABEL_VALUE")
                .isNull();
    }

    @Test
    void flagsAConflictWhenTheTwoStrategiesDisagreeOnTheSameField() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                // A clean, fully-reconcilable GRID block.
                run("Previous Balance", 50f, 90f, 100f),
                run("Purchases", 150f, 60f, 100f),
                run("Payments / Credits", 220f, 90f, 100f),
                run("Total Amount Due", 320f, 90f, 100f),
                run("10,000.00", 55f, 40f, 130f),
                run("5,000.00", 155f, 40f, 130f),
                run("2,000.00", 225f, 40f, 130f),
                run("10,000.00", 325f, 40f, 130f),
                // An isolated label/value pair elsewhere on the page, independently naming a
                // DIFFERENT total amount due via the INLINE_LABEL_VALUE shape -- an invented
                // fixture reproducing the possibility (not observed on a real document yet) that
                // two genuinely independent readings of the same statement could disagree.
                run("Total Amount Due", 50f, 90f, 600f),
                run("12,000.00", 250f, 40f, 600.5f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.conflictingFields())
                .as("GRID's own equation would otherwise be VERIFIED-able on its own numbers -- "
                        + "the conflict must still be reported")
                .containsExactly("totalAmountDue");
    }

    // --- Page-region selection: real shape found verifying against the actual corpus for BOTH AU
    // and Axis -- in both cases the confounding duplicate/unrelated match was on a DIFFERENT page
    // than the real summary, not just elsewhere on the same page. ---

    @Test
    void aCompletePageWinsOverAWeakerDuplicateOnAnotherPage() {
        // AU's real shape: a full, real "Bill summary" cluster on page 0, and a lone, unrelated
        // repeat of "Opening balance" (a different real-world concept -- likely a rewards-points
        // balance -- with a different number) on page 1. Page 0 covers all four required fields;
        // page 1 covers a lone one. The complete page must win outright, not be blocked by the
        // page-1 repeat the way a same-page duplicate would be.
        List<PositionedText> runs = new ArrayList<>(List.of(
                runOnPage("Opening balance", 355f, 70f, 229.6f, 0),
                runOnPage("40,000.00", 518f, 46f, 228.2f, 0),
                runOnPage("Total spends", 355f, 53f, 250.9f, 0),
                runOnPage("6,000.00", 523f, 41f, 249.4f, 0),
                runOnPage("Payments & Refunds", 355f, 86f, 272.1f, 0),
                runOnPage("44,000.00", 518f, 46f, 270.7f, 0),
                runOnPage("Total amount due", 355f, 74f, 363.2f, 0),
                runOnPage("2,000.00", 523f, 41f, 363.5f, 0),
                // The unrelated repeat, on a different page.
                runOnPage("Opening balance", 30f, 70f, 292.6f, 1),
                runOnPage("8,500.00", 105f, 30f, 292.6f, 1)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.previousBalance())
                .as("page 0's complete cluster must win outright over page 1's lone, weaker match")
                .isEqualByComparingTo("40000.00");
        assertThat(summary.hasReconcilableFields()).isTrue();
    }

    @Test
    void neverCombinesFieldsFoundOnDifferentPagesIntoOneAnswer() {
        // Axis's real shape: the genuine billing total lives on page 0; an unrelated fee-schedule
        // example elsewhere in the document (page 2, real document) independently names "Purchase"
        // near an unrelated number. Before page-scoping, each field resolved independently and the
        // two got silently combined into one evidence object as if they belonged together.
        List<PositionedText> runs = new ArrayList<>(List.of(
                runOnPage("Total Amount Due", 50f, 90f, 224f, 0),
                runOnPage("34,521.90", 55f, 40f, 254f, 0),
                // An unrelated label/value pair on a different page.
                runOnPage("Purchase", 74f, 29f, 467.5f, 2),
                runOnPage("7250", 200f, 30f, 468f, 2)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.purchases())
                .as("a field resolved on an unrelated page must never be combined with a different "
                        + "field resolved on the real summary's own page")
                .isNull();
    }

    // ------------------------------------------------- gate loosening (Phase 5, task 1)

    @Test
    void totalAmountDueSurfacesAlone_whenOnlyOneStrategyFoundIt_evenWithoutFullReconciliation() {
        // GRID finds ONLY totalAmountDue on this page (no previous balance, purchases, or payments
        // printed alongside it) -- a real shape: some statements' top summary prints just the
        // headline total next to a due date, with no component breakdown anywhere.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total Amount Due", 50f, 100f, 200f),
                run("13,100.00", 55f, 60f, 230f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.totalAmountDue()).isEqualByComparingTo("13100.00");
        assertThat(summary.hasReconcilableFields())
                .as("the other three fields are genuinely absent -- reconciliation must still refuse")
                .isFalse();
    }

    @Test
    void totalAmountDueSurfacesAlone_whenItsOwnValueRowSitsSeparatelyFromTheOtherFields() {
        // Real shape, found verifying against a real ICICI statement: "Total Amount due"'s label
        // sits close enough to the other four labels to group into one label row, but its own
        // printed VALUE sits ~3.5pt further from the other four values than groupIntoRows' own
        // tolerance allows -- splitting what is visually one summary line into two value rows.
        // valueRowWithinGap returns the FIRST row that qualifies, which is the lone total-due
        // value -- so the total is correctly recovered, but the other four fields, one row later,
        // are never reached. Also exercises the Rupee-as-backtick font quirk this same real
        // document evidences (see CsvParser's own comment) -- without that fix nothing here would
        // parse as numeric at all.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total Amount Due", 50f, 90f, 224f),
                run("Previous Balance", 150f, 90f, 225f),
                run("Purchases / Charges", 260f, 90f, 225f),
                run("Payments / Credits", 360f, 90f, 225f),
                run("`7,362.70", 55f, 60f, 235f),
                run("`0.00", 155f, 40f, 238.5f),
                run("`7,362.70", 265f, 60f, 238.5f),
                run("`0.00", 365f, 40f, 238.5f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.totalAmountDue())
                .as("the total's own value row is reached and correctly parsed despite the "
                        + "backtick Rupee-glyph substitute")
                .isEqualByComparingTo("7362.70");
        // This was a documented limitation: the other fields' value row was never reached once the
        // total's own row satisfied valueRowWithinGap. Reading each label's own column reaches them.
        assertThat(summary.previousBalance()).isEqualByComparingTo("0.00");
        assertThat(summary.purchases()).isEqualByComparingTo("7362.70");
        assertThat(summary.paymentsAndCredits()).isEqualByComparingTo("0.00");
    }

    @Test
    void totalAmountDuePrefersGrid_whenTheTwoStrategiesDisagree() {
        // GRID resolves a value from a clean stacked grid on page 0; INLINE_LABEL_VALUE separately
        // resolves a DIFFERENT value from an unrelated same-row match on page 1 (the shape of a
        // real illustrative worked-example section elsewhere in a statement). Genuine disagreement
        // -- but now resolved by preferring GRID's reading rather than discarding both, per
        // bestEffortTotalAmountDue's own doc comment: confirmed on a real Axis document that GRID
        // is the reading that was actually right. The disagreement itself is still reported.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total Amount Due", 50f, 100f, 200f),
                run("13,100.00", 55f, 60f, 230f),
                runOnPage("Total Amount Due", 50f, 100f, 500f, 1),
                runOnPage("9,999.00", 160f, 60f, 500f, 1)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.totalAmountDue())
                .as("GRID's reading wins a genuine disagreement")
                .isEqualByComparingTo("13100.00");
        assertThat(summary.conflictingFields())
                .as("still flagged as disputed, even though GRID's value is what surfaces")
                .contains("totalAmountDue");
    }

    @Test
    void totalAmountDueIsReadAsAMagnitude_evenWhenPrintedWithATrailingDrMarker() {
        // Confirmed on a real Axis statement: a credit card's own "Total Payment Due" is routinely
        // printed with a trailing "Dr" marker (e.g. "27,665.16 Dr") -- Dr here means "you owe
        // this," the ordinary case for a card, not the unusual overdrawn-savings-balance case
        // CsvParser's Dr=negative convention was written for. Left un-abs()'d, this class returned
        // a negative reading for a real, correctly GRID-matched total.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total Amount Due", 50f, 100f, 200f),
                run("27,665.16 Dr", 55f, 90f, 230f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.totalAmountDue()).isEqualByComparingTo("27665.16");
    }

    @Test
    void everyFieldIsReadAsAMagnitude_notJustTotalAmountDue() {
        // The Dr/Cr fix lives in the one amount() helper every field shares -- proving it here for
        // Previous Balance too (not just totalAmountDue) is what shows the fix is general, not a
        // field-specific patch. All four required fields are present so previousBalance surfaces
        // through the normal hasReconcilableFields() path, not totalAmountDue's own bypass.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Previous Balance", 50f, 90f, 300f),
                run("Purchases", 150f, 60f, 300f),
                run("Payments / Credits", 220f, 90f, 300f),
                run("Total Amount Due", 320f, 90f, 300f),
                run("10,000.00 Dr", 55f, 70f, 330f),
                run("5,000.00", 155f, 40f, 330f),
                run("2,000.00", 225f, 40f, 330f),
                run("13,000.00", 325f, 40f, 330f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.previousBalance())
                .as("Dr suffix must not flip this to a negative reading")
                .isEqualByComparingTo("10000.00");
        assertThat(summary.hasReconcilableFields()).isTrue();
    }

    @Test
    void totalAmountDueSurfaces_whenTheTwoStrategiesAgree() {
        // A genuinely different shape per page, each strategy resolving the SAME amount from its own
        // page independently: page 0 is a stacked grid (label y=200, value row y=230 -- GRID's
        // shape, too far apart in y for SAME_ROW's 3pt tolerance); page 1 is a same-row layout
        // (label and value both y=200 -- SAME_ROW's shape; GRID finds nothing there, since there is
        // no second row on that page for rowBelow to pair it with). Both land on the identical
        // figure, so this exercises the TRUE agreement branch, not just "one strategy silent."
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total Amount Due", 50f, 100f, 200f),
                run("13,100.00", 55f, 60f, 230f),
                runOnPage("Total Amount Due", 50f, 100f, 200f, 1),
                runOnPage("13,100.00", 160f, 60f, 200f, 1)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.totalAmountDue()).isEqualByComparingTo("13100.00");
        assertThat(summary.conflictingFields())
                .as("equal values across strategies must never register as a conflict")
                .doesNotContain("totalAmountDue");
    }

    @Test
    void aFullyReconciledDocumentIsUnaffected() {
        // Guards against Task 1 accidentally changing AU's already-passing, already-tested shape.
        var summary = CreditCardSummaryExtractor.extract(cleanSummaryBlock());

        assertThat(summary.totalAmountDue()).isEqualByComparingTo("13100.00");
        assertThat(summary.hasReconcilableFields()).isTrue();
    }

    // ------------------------------------------------- duplicate-label agreement (Phase 5, task 2)

    @Test
    void aDuplicateLabelIsAcceptedWhenEveryOccurrenceAgrees() {
        // Two occurrences of the same label on one page, same value both times -- a bank printing
        // its own total under two different footnote markers/wordings for the identical figure.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Previous Balance", 50f, 90f, 300f),
                run("Purchases", 150f, 60f, 300f),
                run("Payments / Credits", 340f, 90f, 300f),
                run("Total Amount Due", 440f, 90f, 300f),
                run("10,000.00", 55f, 40f, 330f),
                run("5,000.00", 155f, 40f, 330f),
                run("2,000.00", 345f, 40f, 330f),
                run("13,000.00", 445f, 40f, 330f),
                run("Total Amount Due", 440f, 90f, 400f),
                run("13,000.00", 445f, 40f, 430f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.totalAmountDue()).isEqualByComparingTo("13000.00");
        assertThat(summary.hasReconcilableFields()).isTrue();
    }

    @Test
    void aDuplicateLabelStillRefusesWhenOccurrencesDisagree() {
        // Same shape as above, but the second occurrence's value differs -- must remain refused,
        // unchanged from today's behaviour (this is the existing test this task must not break,
        // made explicit).
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Previous Balance", 50f, 90f, 300f),
                run("Purchases", 150f, 60f, 300f),
                run("Payments / Credits", 340f, 90f, 300f),
                run("Total Amount Due", 440f, 90f, 300f),
                run("10,000.00", 55f, 40f, 330f),
                run("5,000.00", 155f, 40f, 330f),
                run("2,000.00", 345f, 40f, 330f),
                run("13,000.00", 445f, 40f, 330f),
                run("Total Amount Due", 440f, 90f, 400f),
                run("999.00", 445f, 40f, 430f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.totalAmountDue()).isNull();
        assertThat(summary.hasReconcilableFields()).isFalse();
    }

    // ------------------------------------------------- label decoration (Phase 5, task 3)

    @Test
    void aFootnoteMarkedTotalDueLabelStillMatches() {
        // A real shape: some statements print an asterisk before "Total Amount Due" pointing to a
        // footnote, and/or a currency-symbol placeholder in parens after it.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("*Total Amount Due ( `)", 50f, 130f, 200f),
                run("13,100.00", 55f, 60f, 230f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.totalAmountDue()).isEqualByComparingTo("13100.00");
    }

    @Test
    void decorationStrippingDoesNotCreateAFalseMatchForAnUnrelatedLabel() {
        // Guards against over-generalising the strip: an unrelated label that happens to end in a
        // parenthetical must still not match anything.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total Outstanding (Principal)", 50f, 140f, 200f),
                run("13,100.00", 55f, 60f, 230f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.totalAmountDue()).isNull();
    }

    // ------------------------------------------------- row-search past an intervening row (Phase 5, task 4)

    @Test
    void findsTheValueRowPastAnUnrelatedInterveningRow() {
        // A real shape: an unrelated marketing/notice column running down the left side of the page
        // (x=30) has text at a y-position BETWEEN the summary label and its own value, in the right
        // column (x=440+). The immediate next row by y is the unrelated column's text -- not
        // numeric, not recoverable -- so the value one row further down must still be reachable.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total Amount Due", 440f, 90f, 200f),
                run("Please note our updated fee schedule", 30f, 200f, 206f),   // unrelated column
                run("13,100.00", 445f, 60f, 214f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.totalAmountDue()).isEqualByComparingTo("13100.00");
    }

    @Test
    void readsTheValueInTheLabelsOwnColumn_whenUnrelatedProseMergesIntoItsRow_neverTheNextFieldsValue() {
        // Real shape, a real IndusInd statement: its "Total Amount Due" value merges, by y, with an
        // unrelated promotional line printed in a column to its LEFT. The whole merged row cannot be
        // trusted as a value row (the prose is neither date- nor operator-shaped), and an earlier
        // fix stopped the scan there so it could never run on to "Minimum Amount Due"'s own clean
        // value two rows later -- which had been read as the total. Refusing was safe, but it left
        // the total empty on every IndusInd statement. Read straight down the label's own column
        // instead: the first thing under it is its value, and the prose never overlaps the label.
        // Invented labels and figures; the shape is the real one.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total Amount Due", 440f, 90f, 200f),
                run("1,250.00", 445f, 60f, 214f),
                run("Some unrelated promotional sentence continues here", 30f, 220f, 214.5f),
                run("Minimum Amount Due", 440f, 90f, 234f),
                run("100.00", 460f, 40f, 245f)));

        assertThat(CreditCardSummaryExtractor.extract(runs).totalAmountDue())
                .as("the label's own column holds its value; another field's value is never used")
                .isEqualByComparingTo("1250.00");
    }

    @Test
    void refusesWhenTheFirstThingUnderTheLabelIsAnotherLabel_ratherThanReadingThatLabelsValue() {
        // The total's own value is missing; the next thing in its column is the next field's label.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total Amount Due", 440f, 90f, 200f),
                run("Some unrelated promotional sentence continues here", 30f, 220f, 214.5f),
                run("Minimum Amount Due", 440f, 90f, 224f),
                run("100.00", 460f, 40f, 236f)));

        assertThat(CreditCardSummaryExtractor.extract(runs).totalAmountDue()).isNull();
    }

    @Test
    void refusesWhenTwoItemsSitUnderTheLabel_inItsOwnColumn() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total Amount Due", 440f, 90f, 200f),
                run("1,250.00", 442f, 40f, 214f),
                run("100.00", 488f, 40f, 214f),
                run("Some unrelated promotional sentence continues here", 30f, 220f, 214.5f)));

        assertThat(CreditCardSummaryExtractor.extract(runs).totalAmountDue()).isNull();
    }

    /** Real shape, a real HDFC card: some labels wrap onto a second line ("RECEIVED",
     *  "(Current Billing Cycle)") printed straight under them, above the value row. That second
     *  line is part of the label, not something else sitting in its column. */
    @Test
    void aLabelsOwnSecondLine_doesNotHideTheValueUnderIt() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("PREVIOUS STATEMENT DUES", 40f, 84f, 226.5f),
                run("PAYMENTS/CREDITS", 156f, 60f, 222.3f),
                run("RECEIVED", 171f, 28f, 230.8f),
                run("PURCHASES/DEBIT", 258f, 55f, 222.3f),
                run("(Current Billing Cycle)", 254f, 63f, 230.8f),
                run("TOTAL AMOUNT DUE", 446f, 62f, 227.1f),
                run("500.00", 68f, 26f, 250.2f),
                run("500.00", 172f, 26f, 250.2f),
                run("+", 233f, 5f, 249.8f),
                run("2,000.00", 270f, 32f, 250.2f),
                run("=", 424f, 5f, 249.8f),
                run("2,000.00", 446f, 61f, 250.2f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.previousBalance()).isEqualByComparingTo("500.00");
        assertThat(summary.paymentsAndCredits()).isEqualByComparingTo("500.00");
        assertThat(summary.purchases()).isEqualByComparingTo("2000.00");
        assertThat(summary.totalAmountDue()).isEqualByComparingTo("2000.00");
    }

    /** A tightly stacked panel: the next field's label within a line of this one, with this
     *  field's own value missing. That label is not this label's second line, so its value is
     *  never read as this one's. */
    @Test
    void aCloseNextFieldLabel_isNotTakenForThisLabelsSecondLine() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total Amount Due", 440f, 90f, 200f),
                run("Minimum Amount Due", 440f, 90f, 210f),
                run("100.00", 460f, 40f, 222f)));

        assertThat(CreditCardSummaryExtractor.extract(runs).totalAmountDue()).isNull();
    }

    /** The whole IndusInd-shaped panel: each field's label stacked in a right-hand column with its
     *  value under it, a prose column to the left whose lines merge with the values by y, and the
     *  panel's own wording ("Purchases & Other Charges", "Payments & Other Credits"). */
    @Test
    void readsAStackedSidePanel_whoseValuesMergeWithAProseColumnToTheLeft() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Previous Balance", 470f, 60f, 100f),
                run("Notice text line one about charges", 30f, 170f, 112f),
                run("1,000.00 DR", 480f, 45f, 112.5f),
                run("Purchases & Other Charges", 460f, 85f, 140f),
                run("Notice text line two about fees", 30f, 170f, 152f),
                run("2,500.00", 485f, 35f, 152.5f),
                run("Cash Advance", 475f, 50f, 180f),
                run("0.00", 495f, 15f, 192f),
                run("Payments & Other Credits", 462f, 82f, 220f),
                run("Notice text line three", 30f, 170f, 232f),
                run("1,000.00", 485f, 35f, 232.5f),
                run("Total Amount Due", 468f, 70f, 260f),
                run("Notice text line four about rates", 30f, 170f, 272f),
                run("2,500.00 DR", 480f, 45f, 272.5f),
                run("Minimum Amount Due", 460f, 86f, 300f),
                run("125.00", 490f, 25f, 312f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.previousBalance()).isEqualByComparingTo("1000.00");
        assertThat(summary.purchases()).isEqualByComparingTo("2500.00");
        assertThat(summary.cashAdvances()).isEqualByComparingTo("0.00");
        assertThat(summary.paymentsAndCredits()).isEqualByComparingTo("1000.00");
        assertThat(summary.totalAmountDue()).isEqualByComparingTo("2500.00");
    }

    // ------------------------------------------------- multi-run label joining (Phase 5, task 5)

    @Test
    void joinsAdjacentSameRowRunsIntoOneLabel() {
        // A real shape: "Total", "Amount", "Due" printed as three separate positioned-text runs
        // (individually differently styled/spaced) rather than one contiguous string, immediately
        // followed on the same row by the value.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total", 280f, 18f, 132f),
                run("Amount", 300.7f, 28f, 132f),
                run("Due", 331.5f, 15f, 132f),
                run("13,100.00", 435f, 60f, 132f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.totalAmountDue()).isEqualByComparingTo("13100.00");
    }

    @Test
    void doesNotJoinRunsAcrossALargeXGap() {
        // Guards against over-generalising the join: two runs far enough apart to plausibly belong
        // to different columns must not be joined even if their concatenation would happen to match.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total", 30f, 18f, 132f),
                run("Amount", 500f, 28f, 132f),      // implausibly far from "Total" to be one label
                run("Due", 531f, 15f, 132f),
                run("13,100.00", 600f, 60f, 132f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.totalAmountDue()).isNull();
    }

    @Test
    void aJoinedLabelStillRequiresExactlyOneUnambiguousCandidate() {
        // The existing "refuse on competing candidates" rule must still apply to a joined label,
        // not just a single-run one.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total", 280f, 18f, 132f),
                run("Amount", 300.7f, 28f, 132f),
                run("Due", 331.5f, 15f, 132f),
                run("13,100.00", 435f, 60f, 132f),
                run("14,200.00", 500f, 60f, 132f)));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.totalAmountDue()).isNull();
    }

    /**
     * A newer Kotak credit-card layout: its billing summary prints each label with its value on the
     * same line ("Purchases made in this cycle", "Other fees & charges", "Payments and Other
     * Credits"), and further down a rewards-points grid whose first column is headed "Opening
     * balance". Geometry follows that statement; every figure is synthetic.
     */
    @Test
    void readsTheSameLineSummary_andNeverTheRewardsPointsGridsOpeningBalance() {
        List<PositionedText> runs = List.of(
                run("Previous statement dues", 38f, 106.6f, 339f),
                run("10,000.00", 241.1f, 41.4f, 339f),
                run("Total Amount Due", 299.5f, 77.6f, 339f),
                run("9,618.00", 515.6f, 41.4f, 339f),
                run("Purchases made in this cycle", 38f, 122.8f, 355f),
                run("9,500.00", 241.1f, 41.4f, 355f),
                run("Other fees & charges", 38f, 88.4f, 372f),
                run("118.00", 259.2f, 23.3f, 372f),
                run("Payments and Other Credits", 38f, 120.8f, 389f),
                run("10,000.00", 241.1f, 41.4f, 389f),
                run("Opening balance", 45f, 60f, 480f),
                run("Points earned", 180f, 55f, 480f),
                run("Points redeemed", 300f, 65f, 480f),
                run("Points available", 430f, 65f, 480f),
                run("1,234", 55f, 25f, 500f),
                run("56", 190f, 12f, 500f),
                run("0", 320f, 6f, 500f),
                run("1,290", 445f, 25f, 500f));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.conflictingFields()).isEmpty();
        assertThat(summary.previousBalance()).isEqualByComparingTo("10000.00");
        assertThat(summary.purchases()).isEqualByComparingTo("9500.00");
        assertThat(summary.fees()).isEqualByComparingTo("118.00");
        assertThat(summary.paymentsAndCredits()).isEqualByComparingTo("10000.00");
        assertThat(summary.totalAmountDue()).isEqualByComparingTo("9618.00");
    }
}
