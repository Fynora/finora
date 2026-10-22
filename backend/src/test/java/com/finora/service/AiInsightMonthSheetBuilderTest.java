package com.finora.service;

import com.finora.dto.DashboardSummaryDto;
import com.finora.dto.InsightsDto;
import com.finora.dto.LookAtRowDto;
import com.finora.entity.Category;
import com.finora.people.PeopleDtos;
import com.finora.people.PeopleService;
import com.finora.service.AiInsightFacts.FactSheet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AI insights foundation §4.2: the month sheet copies figures from the existing services, and only
 *  allowlisted facts reach the model. Every name and amount here is invented. */
class AiInsightMonthSheetBuilderTest {

    private final UUID userId = UUID.randomUUID();
    private InsightsService insightsService;
    private DashboardService dashboardService;
    private PeopleService peopleService;
    private LookAtService lookAtService;
    private AiInsightMonthSheetBuilder builder;

    private static Category category(String name, boolean system) {
        Category c = new Category();
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        c.setName(name);
        c.setSystem(system);
        return c;
    }

    private final Category food = category("Food", true);
    private final Category fuel = category("Fuel", true);
    private final Category userRent = category("Friend K rent", false);

    @BeforeEach
    void setUp() {
        insightsService = mock(InsightsService.class);
        dashboardService = mock(DashboardService.class);
        peopleService = mock(PeopleService.class);
        lookAtService = mock(LookAtService.class);
        builder = new AiInsightMonthSheetBuilder(insightsService, dashboardService, peopleService, lookAtService);
        when(peopleService.people(userId)).thenReturn(List.of());
        when(lookAtService.rows(eq(userId), any(InsightsService.Built.class))).thenReturn(List.of());
        DashboardSummaryDto september = dashboard("2026-09", "40000", "25000", 12.0);
        when(dashboardService.summarize(userId)).thenReturn(september);
    }

    private static DashboardSummaryDto dashboard(String month, String in, String out, Double outDelta) {
        DashboardSummaryDto d = mock(DashboardSummaryDto.class);
        when(d.reportingMonth()).thenReturn(month);
        when(d.monthlyIncome()).thenReturn(new BigDecimal(in));
        when(d.monthlyExpense()).thenReturn(new BigDecimal(out));
        when(d.expenseDeltaPct()).thenReturn(outDelta);
        return d;
    }

    private void given(List<InsightsDto.CategoryMover> movers, Map<String, Integer> baselines) {
        InsightsDto dto = new InsightsDto(List.of(), movers, null,
                new InsightsDto.CategoryHighlight("Food", new BigDecimal("9400")), null, "2026-09", true, null);
        when(insightsService.buildDetailed(userId, "2026-09")).thenReturn(new InsightsService.Built(
                dto, baselines, new BigDecimal("25000"), List.of(food, fuel, userRent)));
    }

    private static InsightsDto.CategoryMover mover(String c, String current, String prior, Double pct) {
        return new InsightsDto.CategoryMover(c, new BigDecimal(current), new BigDecimal(prior), pct);
    }

    @Test
    void noSheetForAMonthWithNothingToDescribe() {
        when(insightsService.buildDetailed(userId, "2026-09")).thenReturn(new InsightsService.Built(
                new InsightsDto(List.of(), List.of(), null, null, null, "2026-09", true, null), Map.of(), null, List.of()));
        assertThat(builder.build(userId, "2026-09")).isEmpty();
    }

    @Test
    void copiesTheMonthTotalAndTheMoversFromInsights() {
        given(List.of(mover("Food", "9400", "6300", 49.2)), Map.of("Food", 3));

        FactSheet sheet = builder.build(userId, "2026-09").orElseThrow();

        assertThat(sheet.month()).isEqualTo("2026-09");
        assertThat(sheet.fact("month.total").value()).isEqualByComparingTo("25000");
        assertThat(sheet.fact("food.biggest_total").value()).isEqualByComparingTo("9400");
        assertThat(sheet.fact("food.this_month").value()).isEqualByComparingTo("9400");
        assertThat(sheet.fact("food.usual").value()).isEqualByComparingTo("6300");
        assertThat(sheet.fact("food.usual").display()).isEqualTo("₹6,300 in a usual month");
        assertThat(sheet.fact("food.change_pct").value()).isEqualByComparingTo("49.2");
    }

    @Test
    void aWeakBaselineGivesNoComparison() {
        given(List.of(mover("Food", "9400", "6300", 49.2), mover("Fuel", "1800", "1000", 80.0)),
                Map.of("Food", 3, "Fuel", 1));

        FactSheet sheet = builder.build(userId, "2026-09").orElseThrow();

        assertThat(sheet.fact("fuel.this_month")).isNotNull();
        assertThat(sheet.fact("fuel.usual")).isNull();
        assertThat(sheet.fact("fuel.change_pct")).isNull();
        assertThat(sheet.fact("food.usual")).isNotNull();
    }

    @Test
    void dashboardFactsOnlyForTheSameMonth() {
        given(List.of(), Map.of());
        FactSheet same = builder.build(userId, "2026-09").orElseThrow();
        assertThat(same.fact("month.money_in").value()).isEqualByComparingTo("40000");
        assertThat(same.fact("month.money_out").value()).isEqualByComparingTo("25000");
        assertThat(same.fact("month.money_out_change_pct").value()).isEqualByComparingTo("12");

        DashboardSummaryDto october = dashboard("2026-10", "1", "1", 1.0);
        when(dashboardService.summarize(userId)).thenReturn(october);
        FactSheet other = builder.build(userId, "2026-09").orElseThrow();
        assertThat(other.fact("month.money_in")).isNull();
        assertThat(other.fact("month.money_out")).isNull();
    }

    @Test
    void noPayeeOrPersonNameAppearsInModelJson() {
        given(List.of(mover("Friend K rent", "12000", "9000", 33.3)), Map.of("Friend K rent", 3));
        when(peopleService.people(userId)).thenReturn(List.of(
                new PeopleDtos.PersonDto("vpa:flatmate.a", "Friend K", new BigDecimal("4000"), LocalDate.parse("2026-08-01"), false)));
        when(lookAtService.rows(eq(userId), any(InsightsService.Built.class))).thenReturn(List.of(
                LookAtRowDto.amountChanged("vpa:flatmate.a", "Rent", new BigDecimal("649"), "2026-09-12", "vpa:flatmate.a|2026-09-12"),
                LookAtRowDto.runningHigh("Friend K rent", new BigDecimal("12000"), new BigDecimal("9000"), "2026-09",
                        "Friend K rent|2026-09", true)));

        FactSheet sheet = builder.build(userId, "2026-09").orElseThrow();
        String json = sheet.modelJson();

        assertThat(json).doesNotContain("flatmate").doesNotContain("Friend K").doesNotContain("vpa:");
        assertThat(json).contains("[category-1]").contains("row_1").contains("row_2");
        // The rows keep their keys for the server, and the user's own name for display.
        assertThat(sheet.rows()).extracting(AiInsightFacts.Row::key)
                .containsExactly("AMOUNT_CHANGED|vpa:flatmate.a|2026-09-12", "CATEGORY_HIGH|Friend K rent|2026-09");
        assertThat(sheet.rows().get(1).subjectId()).isEqualTo("category_1");
        assertThat(sheet.subject("category_1").displayName()).isEqualTo("Friend K rent");
        assertThat(sheet.fact("month.amount_changes").value()).isEqualByComparingTo("1");
    }

    @Test
    void familyBalancesAreLeftOut() {
        given(List.of(), Map.of());
        when(peopleService.people(userId)).thenReturn(List.of(
                new PeopleDtos.PersonDto("vpa:flatmate.a", "Flatmate A", new BigDecimal("4000"), LocalDate.parse("2026-08-01"), false),
                new PeopleDtos.PersonDto("vpa:parent.p", "Parent P", new BigDecimal("50000"), LocalDate.parse("2026-08-01"), true),
                new PeopleDtos.PersonDto("vpa:friend.k", "Friend K", new BigDecimal("-1500"), LocalDate.parse("2026-08-01"), false),
                new PeopleDtos.PersonDto("vpa:sibling.s", "Sibling S", new BigDecimal("-9000"), LocalDate.parse("2026-08-01"), true)));

        FactSheet sheet = builder.build(userId, "2026-09").orElseThrow();

        assertThat(sheet.fact("month.owed_to_you").value()).isEqualByComparingTo("4000");
        assertThat(sheet.fact("month.you_owe").value()).isEqualByComparingTo("1500");
    }

    @Test
    void zeroCountsAreLeftOut() {
        given(List.of(), Map.of());
        FactSheet sheet = builder.build(userId, "2026-09").orElseThrow();
        assertThat(sheet.fact("month.owed_to_you")).isNull();
        assertThat(sheet.fact("month.you_owe")).isNull();
        assertThat(sheet.fact("month.recurring_to_answer")).isNull();
        assertThat(sheet.fact("month.amount_changes")).isNull();
        assertThat(sheet.fact("month.card_bills_count")).isNull();
    }

    @Test
    void countsQuestionsCardBillsAndTheCoverageGap() {
        InsightsDto dto = new InsightsDto(List.of(), List.of(),
                new InsightsDto.CoverageCaveat("2026-09", List.of()), null, null, "2026-09", true,
                new InsightsDto.UnlinkedCardBills("2026-09", new BigDecimal("21400"), 2, "Sample Card", "0001"));
        when(insightsService.buildDetailed(userId, "2026-09")).thenReturn(new InsightsService.Built(
                dto, Map.of(), new BigDecimal("25000"), List.of()));
        when(lookAtService.rows(eq(userId), any(InsightsService.Built.class))).thenReturn(List.of(
                LookAtRowDto.newRecurring(null, "sample gym"), LookAtRowDto.newRecurring(null, "sample tv")));

        FactSheet sheet = builder.build(userId, "2026-09").orElseThrow();

        assertThat(sheet.fact("month.coverage_gap").display()).isEqualTo("1 statement gap");
        assertThat(sheet.fact("month.card_bills_count").display()).isEqualTo("2 card bills");
        assertThat(sheet.fact("month.card_bills_total").value()).isEqualByComparingTo("21400");
        assertThat(sheet.fact("month.recurring_to_answer").display()).isEqualTo("2 repeating payments");
        assertThat(sheet.toString()).doesNotContain("Sample Card").doesNotContain("0001");
    }

    @Test
    void returnsEmptyNotAnErrorWhenInsightsHasNoMonth() {
        when(insightsService.buildDetailed(userId, "2026-09")).thenReturn(new InsightsService.Built(
                new InsightsDto(List.of(), List.of(), null, null, null, null, true, null), Map.of(), null, List.of()));
        assertThat(builder.build(userId, "2026-09")).isEqualTo(Optional.empty());
    }
}
