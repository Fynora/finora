package com.finora.imports.pdf;

import com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence.ExtractionMethod;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A card summary the grid strategy reads in full, on a page that also carries a rewards grid
 * headed "Opening Balance" -- and a previous balance printed below zero. The shape is a real HDFC
 * card's; every position and figure below is invented.
 *
 * <p>Before: the rewards heading was read as a second previous balance, the two disagreed, the
 * field was refused, and without it the summary was not a summary. Both card rules then reported
 * NOT_APPLICABLE and nothing checked the statement's amounts.
 */
class CardSummaryAboveARewardsGridTest {

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    /** -0.40 - 0.00 + 6,200.30 + 0.00 = 6,199.90, which the statement prints as 6,200.00. The rupee
     *  sign arrives as "C", and the minus between the first two figures as an underscore above the row. */
    private static List<PositionedText> summary(String previousDues) {
        return new ArrayList<>(List.of(
                run("PAYMENTS/CREDITS", 155f, 60f, 222.3f),
                run("PURCHASES/DEBIT", 258f, 55f, 222.3f),
                run("PREVIOUS STATEMENT DUES", 40f, 84f, 226.5f),
                run("FINANCE CHARGES", 353f, 55f, 226.5f),
                run("TOTAL AMOUNT DUE", 446f, 62f, 227.1f),
                run("RECEIVED", 171f, 28f, 230.8f),
                run("(Current Billing Cycle)", 254f, 63f, 230.8f),
                run("_", 133f, 5f, 245.6f),
                run("+", 233f, 5f, 249.8f),
                run("+", 334f, 5f, 249.8f),
                run("=", 424f, 5f, 249.8f),
                run(previousDues, 71f, 21f, 250.2f),
                run("C0.00", 176f, 18f, 250.2f),
                run("C6,200.30", 268f, 37f, 250.2f),
                run("C0.00", 372f, 18f, 250.2f),
                run("C6,200.00", 446f, 40f, 250.2f)));
    }

    /** The rewards grid's heading row and its row of point counts. Nothing here says "points". */
    private static List<PositionedText> rewardsHeadingAndCounts() {
        return List.of(
                run("Opening Balance", 299.5f, 42f, 463.7f),
                run("Feature + Bonus Reward", 365.4f, 61f, 463.7f),
                run("Disbursed", 442.4f, 25f, 463.7f),
                run("Adjusted/Lapsed", 518.9f, 43f, 463.7f),
                run("310", 299.5f, 11f, 480f),
                run("62", 365.4f, 7f, 480f),
                run("0", 442.4f, 4f, 480f),
                run("0", 518.9f, 4f, 480f),
                run("372", 197.9f, 31f, 484.6f));
    }

    /** The grid's own title and one heading's second line, 2.5pt and 7.4pt under the heading row. */
    private static List<PositionedText> pointsWordingJustBelowTheHeading() {
        return List.of(
                run("Reward Points", 197.9f, 49f, 466.2f),
                run("Points Earned", 365.4f, 35f, 471.1f));
    }

    private static List<PositionedText> statement(String previousDues) {
        List<PositionedText> runs = summary(previousDues);
        runs.addAll(rewardsHeadingAndCounts());
        runs.addAll(pointsWordingJustBelowTheHeading());
        return runs;
    }

    @Test
    void readsTheSummary_whenTheRewardsGridNamesItsPointsOneLineUnderItsHeadings() {
        var summary = CreditCardSummaryExtractor.extract(statement("C-0.40"));

        assertThat(summary.extractionMethod()).isEqualTo(ExtractionMethod.GRID);
        assertThat(summary.hasReconcilableFields()).isTrue();
        assertThat(summary.conflictingFields()).isEmpty();
        assertThat(summary.paymentsAndCredits()).isEqualByComparingTo("0.00");
        assertThat(summary.purchases()).isEqualByComparingTo("6200.30");
        assertThat(summary.fees()).isEqualByComparingTo("0.00");
        assertThat(summary.totalAmountDue()).isEqualByComparingTo("6200.00");
    }

    @Test
    void thePreviousBalanceIsTheSummarysOwn_neverTheRewardsGridsPointCount() {
        var summary = CreditCardSummaryExtractor.extract(statement("C0.40"));

        assertThat(summary.previousBalance()).isEqualByComparingTo("0.40");
    }

    @Test
    void aHeadingRowWithNoPointsWordingNearIt_stillMakesThePreviousBalanceAmbiguous() {
        // The rule is the word, not the shape: an "Opening Balance" over a figure with nothing to
        // say it counts points is a second previous balance, and two that disagree are refused.
        List<PositionedText> runs = summary("C0.40");
        runs.addAll(rewardsHeadingAndCounts());

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.previousBalance()).isNull();
        assertThat(summary.hasReconcilableFields()).isFalse();
    }

    @Test
    void pointsWordingFurtherDownThePage_isNotPartOfTheHeading() {
        // A line about points 30pt below the heading row is another line of the page, not the
        // heading's own second line.
        List<PositionedText> runs = summary("C0.40");
        runs.addAll(rewardsHeadingAndCounts());
        runs.add(run("Points expiring in 30 days", 299.5f, 90f, 463.7f + 30f));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.previousBalance()).isNull();
        assertThat(summary.hasReconcilableFields()).isFalse();
    }

    @Test
    void pointsWordingAtTheTopOfTheNextPage_isNotPartOfTheHeading() {
        // The heading and its figure end page one; page two opens with a line about points. Read
        // by height alone, a line at the top of the next page is "under" everything before it.
        List<PositionedText> runs = summary("C0.40");
        runs.add(run("Opening Balance", 299.5f, 42f, 463.7f));
        runs.add(run("310", 299.5f, 11f, 473.7f));
        runs.add(new PositionedText("Reward Points", 197.9f, 20f, 1, 49f));

        assertThat(CreditCardSummaryExtractor.extract(runs).previousBalance()).isNull();
    }

    // --- A previous balance below zero ---

    @Test
    void aPreviousBalancePrintedWithItsOwnMinusSign_isABalanceInTheCustomersFavour() {
        assertThat(CreditCardSummaryExtractor.extract(statement("C-0.40")).previousBalance())
                .isEqualByComparingTo("-0.40");
        assertThat(CreditCardSummaryExtractor.extract(statement("-0.40")).previousBalance())
                .isEqualByComparingTo("-0.40");
        assertThat(CreditCardSummaryExtractor.extract(statement("Rs. -1,250.00")).previousBalance())
                .isEqualByComparingTo("-1250.00");
    }

    @Test
    void aPreviousBalanceMarkedDr_isStillMoneyOwed() {
        // The number parser reads a trailing "Dr" as negative, a savings-account convention. On a
        // card it means owed, and that reading must not come back with the minus-sign rule.
        assertThat(CreditCardSummaryExtractor.extract(statement("0.40 Dr")).previousBalance())
                .isEqualByComparingTo("0.40");
    }

    @Test
    void aMinusSignOnAnyOtherField_changesNothing_theyAreNotBalances() {
        // Some statements print what was paid as a negative figure. Payments, purchases and
        // charges are amounts that moved, each on a side its label already names.
        List<PositionedText> runs = statement("C0.40");
        runs.removeIf(t -> t.x() == 176f || t.x() == 268f);
        runs.add(run("C-500.00", 176f, 30f, 250.2f));
        runs.add(run("C-6,200.30", 268f, 40f, 250.2f));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.paymentsAndCredits()).isEqualByComparingTo("500.00");
        assertThat(summary.purchases()).isEqualByComparingTo("6200.30");
        assertThat(summary.totalAmountDue()).isEqualByComparingTo("6200.00");
    }

    @Test
    void theSameRowStrategyReadsAMinusSignTheSameWay() {
        List<PositionedText> runs = List.of(
                run("Previous balance", 40f, 70f, 300f), run("-120.50", 200f, 35f, 300f),
                run("Purchases", 40f, 45f, 315f), run("1,000.00", 200f, 35f, 315f),
                run("Payments / Credits", 40f, 80f, 330f), run("0.00", 200f, 20f, 330f),
                run("Total Amount Due", 40f, 75f, 345f), run("879.50", 200f, 30f, 345f));

        var summary = CreditCardSummaryExtractor.extract(runs);

        assertThat(summary.extractionMethod()).isEqualTo(ExtractionMethod.INLINE_LABEL_VALUE);
        assertThat(summary.previousBalance()).isEqualByComparingTo("-120.50");
    }
}
