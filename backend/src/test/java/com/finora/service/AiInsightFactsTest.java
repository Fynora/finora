package com.finora.service;

import com.finora.entity.Category;
import com.finora.service.AiInsightFacts.Fact;
import com.finora.service.AiInsightFacts.FactSheet;
import com.finora.service.AiInsightFacts.Period;
import com.finora.service.AiInsightFacts.Row;
import com.finora.service.AiInsightFacts.Subject;
import com.finora.service.AiInsightFacts.Unit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** AI insights foundation §4.1: the fact sheet's allowlist, display forms, category tokens and hash. */
class AiInsightFactsTest {

    private static Category category(String name, boolean system, String id) {
        Category c = new Category();
        ReflectionTestUtils.setField(c, "id", UUID.fromString(id));
        c.setName(name);
        c.setSystem(system);
        return c;
    }

    private static final Category FOOD = category("Food", true, "00000000-0000-0000-0000-000000000001");
    private static final Category USER_RENT = category("Friend K rent", false, "00000000-0000-0000-0000-000000000009");
    private static final Category USER_GYM = category("Gym with Sample", false, "00000000-0000-0000-0000-000000000005");

    @Test
    void rupeesUseIndianGrouping() {
        assertThat(AiInsightFacts.displayFor(Unit.RUPEES, Period.THIS_MONTH, new BigDecimal("123456"), null))
                .isEqualTo("₹1,23,456");
        assertThat(AiInsightFacts.displayFor(Unit.RUPEES, Period.THIS_MONTH, new BigDecimal("999.6"), null))
                .isEqualTo("₹1,000");
        assertThat(AiInsightFacts.displayFor(Unit.RUPEES, Period.THIS_MONTH, new BigDecimal("12345678"), null))
                .isEqualTo("₹1,23,45,678");
    }

    @Test
    void usualPeriodCarriesItsWords() {
        assertThat(AiInsightFacts.displayFor(Unit.RUPEES, Period.USUAL, new BigDecimal("5000"), null))
                .isEqualTo("₹5,000 in a usual month");
        assertThat(AiInsightFacts.displayFor(Unit.COUNT, Period.USUAL, new BigDecimal("6"), "payment"))
                .isEqualTo("6 payments in a usual month");
    }

    @Test
    void percentIsWholeAndUnsigned() {
        assertThat(AiInsightFacts.displayFor(Unit.PERCENT, Period.THIS_MONTH, new BigDecimal("14.6"), null))
                .isEqualTo("15%");
        assertThat(AiInsightFacts.displayFor(Unit.PERCENT, Period.THIS_MONTH, new BigDecimal("-14.6"), null))
                .isEqualTo("15%");
    }

    @Test
    void countsAndDays() {
        assertThat(AiInsightFacts.displayFor(Unit.COUNT, Period.THIS_MONTH, BigDecimal.ONE, "payment"))
                .isEqualTo("1 payment");
        assertThat(AiInsightFacts.displayFor(Unit.COUNT, Period.THIS_MONTH, new BigDecimal("6"), "payment"))
                .isEqualTo("6 payments");
        assertThat(AiInsightFacts.displayFor(Unit.DAY, Period.THIS_MONTH, new BigDecimal("3"), null)).isEqualTo("3rd");
        assertThat(AiInsightFacts.displayFor(Unit.DAY, Period.THIS_MONTH, new BigDecimal("11"), null)).isEqualTo("11th");
        assertThat(AiInsightFacts.displayFor(Unit.DAY, Period.THIS_MONTH, new BigDecimal("22"), null)).isEqualTo("22nd");
    }

    @Test
    void aUserCategoryIsNeverNamedToTheModel() {
        Map<UUID, String> tokens = AiInsightFacts.tokensFor(List.of(FOOD, USER_RENT, USER_GYM));
        Subject rent = AiInsightFacts.categorySubject(USER_RENT, tokens);
        Subject gym = AiInsightFacts.categorySubject(USER_GYM, tokens);

        // Numbered by category id: the gym's id is lower, so it is category 1.
        assertThat(gym.modelName()).isEqualTo("[category-1]");
        assertThat(gym.id()).isEqualTo("category_1");
        assertThat(rent.modelName()).isEqualTo("[category-2]");
        assertThat(rent.displayName()).isEqualTo("Friend K rent");

        FactSheet sheet = new FactSheet("2026-10", List.of(rent),
                List.of(AiInsightFacts.fact(rent.id(), "this_month", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal("12000"), null)),
                List.of(new Row("row_1", "CATEGORY_HIGH|Friend K rent|2026-10", "running-high", rent.id())), List.of());
        String json = sheet.modelJson();
        assertThat(json).doesNotContain("Friend K").doesNotContain("rent|").contains("[category-2]").contains("row_1");
    }

    @Test
    void aBuiltInCategoryIsSentByName() {
        Subject food = AiInsightFacts.categorySubject(FOOD, AiInsightFacts.tokensFor(List.of(FOOD)));
        assertThat(food.modelName()).isEqualTo("Food");
        assertThat(food.id()).isEqualTo("food");
        assertThat(AiInsightFacts.categorySubject(category("Food & Dining", true, "00000000-0000-0000-0000-000000000002"),
                Map.of()).id()).isEqualTo("food_dining");
    }

    @Test
    void factIdIsSubjectDotMetricAndDisplayIsRendered() {
        Fact f = AiInsightFacts.fact("food", "usual", Period.USUAL, Unit.RUPEES, new BigDecimal("5000"), null);
        assertThat(f.id()).isEqualTo("food.usual");
        assertThat(f.display()).isEqualTo("₹5,000 in a usual month");
    }

    @Test
    void hashIsStableAcrossFactOrder() {
        Subject food = AiInsightFacts.categorySubject(FOOD, Map.of());
        Fact a = AiInsightFacts.fact("food", "this_month", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal("6200"), null);
        Fact b = AiInsightFacts.fact("food", "usual", Period.USUAL, Unit.RUPEES, new BigDecimal("5000"), null);
        FactSheet one = new FactSheet("2026-10", List.of(food), List.of(a, b), List.of(), List.of());
        FactSheet two = new FactSheet("2026-10", List.of(food), List.of(b, a), List.of(), List.of());
        assertThat(one.hash()).hasSize(64).isEqualTo(two.hash());
    }

    @Test
    void hashChangesWhenAValueChanges() {
        Subject food = AiInsightFacts.categorySubject(FOOD, Map.of());
        FactSheet before = new FactSheet("2026-10", List.of(food),
                List.of(AiInsightFacts.fact("food", "this_month", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal("6200"), null)), List.of(), List.of());
        FactSheet after = new FactSheet("2026-10", List.of(food),
                List.of(AiInsightFacts.fact("food", "this_month", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal("6201"), null)), List.of(), List.of());
        assertThat(before.hash()).isNotEqualTo(after.hash());
    }

    @Test
    void hashIgnoresTheRowsSoGotItDoesNotRegenerate() {
        Subject food = AiInsightFacts.categorySubject(FOOD, Map.of());
        List<Fact> facts = List.of(AiInsightFacts.fact("food", "this_month", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal("6200"), null));
        FactSheet withRow = new FactSheet("2026-10", List.of(food), facts,
                List.of(new Row("row_1", "CATEGORY_HIGH|Food|2026-10", "running-high", "food")), List.of());
        FactSheet without = new FactSheet("2026-10", List.of(food), facts, List.of(), List.of());
        assertThat(withRow.hash()).isEqualTo(without.hash());
    }

    @Test
    void sheetMapRoundTripsForStorage() {
        Subject food = AiInsightFacts.categorySubject(FOOD, Map.of());
        FactSheet sheet = new FactSheet("2026-10", List.of(food),
                List.of(AiInsightFacts.fact("food", "this_month", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal("6200"), null)),
                List.of(), List.of("2026-07", "2026-08", "2026-09"));
        Map<String, Object> stored = sheet.toMap();
        assertThat(stored).containsKeys("month", "subjects", "facts");
        assertThat(FactSheet.fromMap(stored)).isEqualTo(sheet);
    }
}
