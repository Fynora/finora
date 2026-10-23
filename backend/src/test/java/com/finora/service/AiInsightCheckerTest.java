package com.finora.service;

import com.finora.service.AiInsightFacts.FactSheet;
import com.finora.service.AiInsightFacts.Period;
import com.finora.service.AiInsightFacts.Row;
import com.finora.service.AiInsightFacts.Subject;
import com.finora.service.AiInsightFacts.Unit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checker suite (AI insights foundation §5.2, §7.2.1): replies that must be rejected, each with
 * the reason it must give, and replies that must pass. Passing this suite is a filter, not a
 * compliance proof -- the offline evaluation measures what it misses.
 */
class AiInsightCheckerTest {

    static final FactSheet MULTI = new FactSheet("2026-09",
            List.of(AiInsightFacts.MONTH,
                    new Subject("food", "Food", "Food"),
                    new Subject("shopping", "Shopping", "Shopping"),
                    new Subject("insurance", "Insurance", "Insurance"),
                    new Subject("loan_emi", "Loan EMI", "Loan EMI"),
                    new Subject("fuel", "Fuel", "Fuel")),
            List.of(AiInsightFacts.fact("month", "total", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal("40000"), null),
                    AiInsightFacts.fact("food", "this_month", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal("6200"), null),
                    AiInsightFacts.fact("food", "usual", Period.USUAL, Unit.RUPEES, new BigDecimal("5000"), null),
                    AiInsightFacts.fact("food", "change_pct", Period.THIS_MONTH, Unit.PERCENT, new BigDecimal("24"), null),
                    AiInsightFacts.fact("food", "weekend_payments_this_month", Period.THIS_MONTH, Unit.COUNT, new BigDecimal("4"), "payment"),
                    AiInsightFacts.fact("shopping", "this_month", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal("4000"), null),
                    AiInsightFacts.fact("insurance", "this_month", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal("2000"), null),
                    AiInsightFacts.fact("loan_emi", "this_month", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal("15000"), null),
                    AiInsightFacts.fact("fuel", "this_month", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal("800"), null),
                    AiInsightFacts.fact("fuel", "usual", Period.USUAL, Unit.RUPEES, new BigDecimal("1000"), null),
                    AiInsightFacts.fact("fuel", "change_pct", Period.THIS_MONTH, Unit.PERCENT, new BigDecimal("-20"), null)),
            List.of(new Row("row_1", "CATEGORY_HIGH|Food|2026-09", "running-high", "food"),
                    new Row("row_2", "CARD_STATEMENT_MISSING|2026-09", "card-statement", "month")),
            List.of("2026-06", "2026-07", "2026-08"));

    private static AiInsightTemplate one(String template) {
        return new AiInsightTemplate(List.of(new AiInsightTemplate.Sentence("OBSERVATION", template)), List.of());
    }

    static Stream<Arguments> mustFail() {
        return Stream.of(
                Arguments.of("{food} came to {shopping.this_month}.", AiInsightReason.FACT_BINDING),
                Arguments.of("{food} fell to {food.this_month}.", AiInsightReason.DIRECTION_MISMATCH),
                Arguments.of("{fuel} rose to {fuel.this_month}.", AiInsightReason.DIRECTION_MISMATCH),
                Arguments.of("{food} usually costs {food.this_month}.", AiInsightReason.PERIOD_MISMATCH),
                Arguments.of("{food} this month was {food.usual}.", AiInsightReason.PERIOD_MISMATCH),
                Arguments.of("{food} came to 6200.", AiInsightReason.LITERAL_NUMBER),
                Arguments.of("{food} doubled.", AiInsightReason.LITERAL_NUMBER),
                Arguments.of("{food} rose by half.", AiInsightReason.LITERAL_NUMBER),
                Arguments.of("{food} rose in October.", AiInsightReason.LITERAL_NUMBER),
                Arguments.of("{food} came to ₹ a lot.", AiInsightReason.LITERAL_NUMBER),
                Arguments.of("{food} rose by six payments.", AiInsightReason.LITERAL_NUMBER),
                Arguments.of("You should spend less on {food}.", AiInsightReason.ADVICE),
                Arguments.of("Many people in your situation move this to a fund.", AiInsightReason.PRODUCT_WORD),
                Arguments.of("You might not want to keep {food} this high.", AiInsightReason.ADVICE),
                Arguments.of("Cut back on {food}.", AiInsightReason.ADVICE),
                Arguments.of("Track {food} more closely.", AiInsightReason.ADVICE),
                Arguments.of("{food} will keep rising.", AiInsightReason.PREDICTION),
                Arguments.of("{food} is likely to stay high next month.", AiInsightReason.PREDICTION),
                Arguments.of("Consider an ELSS for tax saving.", AiInsightReason.ADVICE),
                Arguments.of("{food} money could earn returns in a SIP.", AiInsightReason.PRODUCT_WORD),
                Arguments.of("A term insurance would cover this.", AiInsightReason.PRODUCT_WORD),
                Arguments.of("{unknown.fact} was high.", AiInsightReason.UNKNOWN_FACT),
                Arguments.of("{nobody} was high.", AiInsightReason.UNKNOWN_FACT),
                Arguments.of("{food} was **high**.", AiInsightReason.SCHEMA_MISMATCH));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("mustFail")
    void mustFail(String template, AiInsightReason reason) {
        assertThat(AiInsightChecker.check(one(template), MULTI, false)).isEqualTo(reason);
    }

    static Stream<String> mustPass() {
        return Stream.of(
                "{food} came to {food.this_month}, above {food.usual}.",
                "{insurance} was {insurance.this_month}.",
                "{loan_emi} stayed at {loan_emi.this_month}.",
                "{food} rose {food.change_pct} to {food.this_month}, while {fuel} fell to {fuel.this_month}.",
                "{food} came to {food.this_month}, more than it usually does at {food.usual}.",
                "Spending came to {month.total} this month.",
                "Most of the rise in {food} came from {food.weekend_payments_this_month}.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mustPass")
    void mustPass(String template) {
        assertThat(AiInsightChecker.check(one(template), MULTI, false)).isEqualTo(AiInsightReason.OK);
    }

    @Test
    void aFactWithoutItsSubjectIsFineOnAOneCategorySheet() {
        FactSheet why = new FactSheet("2026-09", List.of(AiInsightFacts.MONTH, new Subject("food", "Food", "Food")),
                List.of(AiInsightFacts.fact("food", "change_pct", Period.THIS_MONTH, Unit.PERCENT, new BigDecimal("24"), null),
                        AiInsightFacts.fact("food", "weekend_payments_this_month", Period.THIS_MONTH, Unit.COUNT, new BigDecimal("4"), "payment")),
                List.of(), List.of("2026-06", "2026-07", "2026-08"));
        assertThat(AiInsightChecker.check(one("Most of the rise came from {food.weekend_payments_this_month}."), why, false))
                .isEqualTo(AiInsightReason.OK);
        // On the multi-category month sheet the same sentence does not say which category it is about.
        assertThat(AiInsightChecker.check(one("Most of the rise came from {food.weekend_payments_this_month}."), MULTI, false))
                .isEqualTo(AiInsightReason.FACT_BINDING);
    }

    /** A "Why?" sheet, as AiInsightRowSheetBuilder writes one: one category, its payments, its largest. */
    static final FactSheet WHY = new FactSheet("2026-09", List.of(AiInsightFacts.MONTH, new Subject("food", "Food", "Food")),
            List.of(AiInsightFacts.fact("food", "this_month", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal("9000"), null),
                    AiInsightFacts.fact("food", "usual", Period.USUAL, Unit.RUPEES, new BigDecimal("4000"), null),
                    AiInsightFacts.fact("food", "change_pct", Period.THIS_MONTH, Unit.PERCENT, new BigDecimal("125"), null),
                    AiInsightFacts.fact("food", "payments_this_month", Period.THIS_MONTH, Unit.COUNT, BigDecimal.ONE, "payment"),
                    AiInsightFacts.fact("food", "payments_usual", Period.USUAL, Unit.COUNT, BigDecimal.ONE, "payment"),
                    AiInsightFacts.fact("food", "avg_payment_usual", Period.USUAL, Unit.RUPEES, new BigDecimal("4000"), null),
                    AiInsightFacts.fact("food", "largest_1", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal("9000"), null),
                    AiInsightFacts.fact("food", "largest_1_day", Period.THIS_MONTH, Unit.DAY, new BigDecimal("6"), null),
                    AiInsightFacts.fact("food", "new_payees_count", Period.THIS_MONTH, Unit.COUNT, BigDecimal.ONE, "new payee"),
                    AiInsightFacts.fact("food", "new_payees_total", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal("7000"), null)),
            List.of(), List.of("2026-06", "2026-07", "2026-08"));

    /** The forms the evaluation measured the model writing on 2026-10-24 (prompt v1). Each must stay
     *  rejected: the v2 prompt steers the model away from them, the checker is not loosened for them. */
    static Stream<Arguments> measuredFailuresStayRejected() {
        return Stream.of(
                Arguments.of("{food} came to {food.this_month} this month, nearly double the {food.usual}.", AiInsightReason.LITERAL_NUMBER),
                Arguments.of("{food} came to {food.this_month}, more than triple the usual {food.usual}.", AiInsightReason.LITERAL_NUMBER),
                Arguments.of("{food} jumped to {food.this_month}, nearly three times its usual {food.usual}.", AiInsightReason.LITERAL_NUMBER),
                Arguments.of("A payment of {food.largest_1} was twice your usual payment size.", AiInsightReason.LITERAL_NUMBER),
                Arguments.of("You made one payment of {food.largest_1}, larger than your usual {food.avg_payment_usual}.", AiInsightReason.LITERAL_NUMBER),
                Arguments.of("A payment of {food.largest_1} on the 6th was larger than usual.", AiInsightReason.LITERAL_NUMBER),
                Arguments.of("{food} rose {food.change_pct} above {food.usual}, an increase of ₹5,000.", AiInsightReason.LITERAL_NUMBER),
                Arguments.of("Your spending came to {food.this_month}, with most of it going to {food}.", AiInsightReason.PREDICTION));
    }

    /** Rule 3b: texts that passed every other check under prompt v1-v2 and rendered as "2 payments
     *  payments", "1 new payee new payee" or "in a usual month in a usual month". */
    static Stream<String> repeatedWords() {
        return Stream.of(
                "{food} had {food.payments_this_month} payments.",
                "{food} had {food.payments_this_month} payment of {food.largest_1}.",
                "{food} had {food.payments_this_month}, against {food.payments_usual} payment in a usual month.",
                "{food} had {food.payments_this_month}, against {food.payments_usual} in a usual month.",
                "{food} came to {food.this_month}, up from {food.usual} in a usual month.",
                "The largest went to {food.new_payees_count} new payee.",
                "The largest went to {food.new_payees_count} payees.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("repeatedWords")
    void aFiguresOwnWordsRepeatedAfterItAreRejected(String template) {
        assertThat(AiInsightChecker.check(one(template), WHY, false)).isEqualTo(AiInsightReason.REPEATED_WORDS);
    }

    @Test
    void aBareCountHasNoNounToRepeat() {
        FactSheet bare = new FactSheet("2026-09", List.of(AiInsightFacts.MONTH, new Subject("food", "Food", "Food")),
                List.of(AiInsightFacts.fact("food", "payments_this_month", Period.THIS_MONTH, Unit.COUNT, new BigDecimal("6"), null)),
                List.of(), List.of());
        assertThat(AiInsightChecker.check(one("{food} had {food.payments_this_month} payments."), bare, false))
                .isEqualTo(AiInsightReason.OK);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("measuredFailuresStayRejected")
    void measuredFailuresStayRejected(String template, AiInsightReason reason) {
        assertThat(AiInsightChecker.check(one(template), WHY, false)).isEqualTo(reason);
    }

    /** What the v2 prompt tells the model to write instead must itself pass. */
    static Stream<String> promptedFormsPass() {
        return Stream.of(
                "{food} came to {food.this_month}, up {food.change_pct} from {food.usual}.",
                "A payment of {food.largest_1} on the {food.largest_1_day} was larger than your usual {food.avg_payment_usual}.",
                "The largest payment was {food.largest_1}, on the {food.largest_1_day}.",
                "{food} had {food.payments_this_month}, against {food.payments_usual}.",
                "The largest payment was {food.largest_1}, on the {food.largest_1_day}, while a payment averaged {food.avg_payment_usual}.",
                "{food.new_payees_total} went to {food.new_payees_count}.",
                "{food} had {food.payments_this_month} this month, the largest {food.largest_1}.",
                "The largest payment was {food.largest_1}, against {food.avg_payment_usual} a payment.",
                "{food} came to {food.this_month}, up {food.change_pct} from a usual month.",
                "This month {food} came to {food.this_month}, up {food.change_pct} from {food.usual}.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("promptedFormsPass")
    void promptedFormsPass(String template) {
        assertThat(AiInsightChecker.check(one(template), WHY, false)).isEqualTo(AiInsightReason.OK);
    }

    /** The prompt's multi-category forms (rules 4, 6 and 7) pass on a month sheet. */
    static Stream<String> promptedMonthFormsPass() {
        return Stream.of(
                "Spending came to {month.total}, with {food} at {food.this_month}.",
                "{food} rose {food.change_pct} and {fuel} fell {fuel.change_pct}.",
                "{fuel} came to {fuel.this_month}, down {fuel.change_pct} from {fuel.usual}.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("promptedMonthFormsPass")
    void promptedMonthFormsPass(String template) {
        assertThat(AiInsightChecker.check(one(template), MULTI, false)).isEqualTo(AiInsightReason.OK);
    }

    /** The v1 failures the prompt's rules 4-6 name: the checker still rejects them. */
    static Stream<Arguments> measuredMonthFailuresStayRejected() {
        return Stream.of(
                Arguments.of("{food} and {fuel} moved by {food.change_pct} and {fuel.change_pct}.", AiInsightReason.FACT_BINDING),
                Arguments.of("You spent {fuel.this_month} on {fuel}.", AiInsightReason.FACT_BINDING),
                Arguments.of("{fuel} came to {fuel.this_month} this month, {fuel.change_pct} below {fuel.usual}.", AiInsightReason.PERIOD_MISMATCH),
                Arguments.of("{food} and {fuel} came in higher.", AiInsightReason.DIRECTION_MISMATCH));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("measuredMonthFailuresStayRejected")
    void measuredMonthFailuresStayRejected(String template, AiInsightReason reason) {
        assertThat(AiInsightChecker.check(one(template), MULTI, false)).isEqualTo(reason);
    }

    @Test
    void sentenceCountAndTypeAreChecked() {
        assertThat(AiInsightChecker.check(new AiInsightTemplate(List.of(), List.of()), MULTI, false))
                .isEqualTo(AiInsightReason.SCHEMA_MISMATCH);
        AiInsightTemplate.Sentence s = new AiInsightTemplate.Sentence("OBSERVATION", "Spending came to {month.total}.");
        assertThat(AiInsightChecker.check(new AiInsightTemplate(List.of(s, s, s, s, s), List.of()), MULTI, false))
                .isEqualTo(AiInsightReason.SCHEMA_MISMATCH);
        assertThat(AiInsightChecker.check(new AiInsightTemplate(List.of(
                new AiInsightTemplate.Sentence("ADVICE", "Spending came to {month.total}.")), List.of()), MULTI, false))
                .isEqualTo(AiInsightReason.SCHEMA_MISMATCH);
    }

    @Test
    void orderMustBeTheSentRowsOnce() {
        AiInsightTemplate.Sentence s = new AiInsightTemplate.Sentence("OBSERVATION", "Spending came to {month.total}.");
        assertThat(AiInsightChecker.check(new AiInsightTemplate(List.of(s), List.of("row_2", "row_1")), MULTI, true))
                .isEqualTo(AiInsightReason.OK);
        assertThat(AiInsightChecker.check(new AiInsightTemplate(List.of(s), List.of("row_2")), MULTI, true))
                .as("a row left out is appended later, not a failure").isEqualTo(AiInsightReason.OK);
        assertThat(AiInsightChecker.check(new AiInsightTemplate(List.of(s), List.of("row_1", "row_1")), MULTI, true))
                .isEqualTo(AiInsightReason.BAD_ORDER);
        assertThat(AiInsightChecker.check(new AiInsightTemplate(List.of(s), List.of("row_9")), MULTI, true))
                .isEqualTo(AiInsightReason.BAD_ORDER);
        // An order the server did not ask for is ignored, not judged.
        assertThat(AiInsightChecker.check(new AiInsightTemplate(List.of(s), List.of("row_9")), MULTI, false))
                .isEqualTo(AiInsightReason.OK);
    }

    @Test
    void parseReadsTheJsonAndRejectsWhatIsNotIt() {
        AiInsightTemplate.Parsed ok = AiInsightTemplate.parse("""
                ```json
                {"sentences":[{"type":"OBSERVATION","template":"Spending came to {month.total}."}],"order":["row_1"]}
                ```""");
        assertThat(ok.reason()).isNull();
        assertThat(ok.template().sentences()).singleElement()
                .satisfies(s -> assertThat(s.template()).isEqualTo("Spending came to {month.total}."));
        assertThat(ok.template().order()).containsExactly("row_1");

        assertThat(AiInsightTemplate.parse("Food rose a lot this month.").reason()).isEqualTo(AiInsightReason.INVALID_JSON);
        assertThat(AiInsightTemplate.parse("{\"sentences\": \"nope\"}").reason()).isEqualTo(AiInsightReason.SCHEMA_MISMATCH);
        assertThat(AiInsightTemplate.parse("{\"sentences\":[{\"type\":\"OBSERVATION\"}]}").reason())
                .isEqualTo(AiInsightReason.SCHEMA_MISMATCH);
        assertThat(AiInsightTemplate.parse("").reason()).isEqualTo(AiInsightReason.EMPTY);
        assertThat(AiInsightTemplate.parse(null).reason()).isEqualTo(AiInsightReason.EMPTY);
    }

    @Test
    void theReasonListIsTheSpecs() {
        assertThat(AiInsightReason.values()).extracting(Enum::name).containsExactly(
                "OK", "PROVIDER_TIMEOUT", "PROVIDER_ERROR", "RATE_LIMITED", "INVALID_JSON", "SCHEMA_MISMATCH",
                "UNKNOWN_FACT", "LITERAL_NUMBER", "REPEATED_WORDS", "FACT_BINDING", "DIRECTION_MISMATCH", "PERIOD_MISMATCH", "ADVICE",
                "PRODUCT_WORD", "PREDICTION", "BAD_ORDER", "EMPTY", "PERSIST_FAILED", "FREE_ALLOWANCE_USED",
                "FREE_BUDGET_SPENT", "ORG_BUDGET_SPENT", "USER_DAILY_CAP", "CONCURRENCY_LIMIT", "NOT_IN_AUDIENCE");
        assertThat(AiInsightChecker.POLICY_VERSION).isEqualTo("policy-v2");
    }
}
