package com.finora.service;

import com.finora.dto.DashboardSummaryDto;
import com.finora.dto.InsightsDto;
import com.finora.dto.LookAtRowDto;
import com.finora.entity.Category;
import com.finora.people.PeopleDtos;
import com.finora.people.PeopleService;
import com.finora.service.AiInsightFacts.Fact;
import com.finora.service.AiInsightFacts.FactSheet;
import com.finora.service.AiInsightFacts.Period;
import com.finora.service.AiInsightFacts.Row;
import com.finora.service.AiInsightFacts.Subject;
import com.finora.service.AiInsightFacts.Unit;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The month fact sheet (docs/superpowers/specs/2026-10-22-ai-insights-foundation-design.md §4.2): what
 * the month story and the AI ranking are given. Every value is copied from an existing service's own
 * output -- InsightsService's movers and total, the dashboard's money in and out, the "To look at"
 * rows, the people balances -- never added up again here, so the AI can only ever describe figures
 * the app already shows (AiInsightMonthSheetParityIT holds the two side by side).
 *
 * <p>Allowlist only (§4.1): categories by name if built in, by token if the user made them; counts and
 * totals for repeating payments, questions and balances; no payee, person, card or account names.
 */
@Component
public class AiInsightMonthSheetBuilder {

    /** A comparison with the usual month is given only when it holds at least this many months (§4.3). */
    static final int MIN_BASELINE_MONTHS = 2;

    private final InsightsService insightsService;
    private final DashboardService dashboardService;
    private final PeopleService peopleService;
    private final LookAtService lookAtService;

    public AiInsightMonthSheetBuilder(InsightsService insightsService, DashboardService dashboardService,
                                      PeopleService peopleService, LookAtService lookAtService) {
        this.insightsService = insightsService;
        this.dashboardService = dashboardService;
        this.peopleService = peopleService;
        this.lookAtService = lookAtService;
    }

    /** The sheet for {@code month} ({@code yyyy-MM}, or null for the newest reporting month); empty
     *  when that month has nothing reportable to describe. */
    public Optional<FactSheet> build(UUID userId, String month) {
        InsightsService.Built built = insightsService.buildDetailed(userId, month);
        InsightsDto insights = built.dto();
        if (built.monthTotal() == null || insights.reportingMonth() == null) return Optional.empty();
        String sheetMonth = insights.reportingMonth();

        Subjects subjects = new Subjects(built.categories());
        List<Fact> facts = new ArrayList<>();
        facts.add(AiInsightFacts.fact("month", "total", Period.THIS_MONTH, Unit.RUPEES, built.monthTotal(), null));

        if (insights.biggestCategory() != null) {
            Subject s = subjects.forName(insights.biggestCategory().name());
            facts.add(AiInsightFacts.fact(s.id(), "biggest_total", Period.THIS_MONTH, Unit.RUPEES,
                    insights.biggestCategory().amount(), null));
        }
        for (InsightsDto.CategoryMover m : insights.movers()) {
            Subject s = subjects.forName(m.category());
            facts.add(AiInsightFacts.fact(s.id(), "this_month", Period.THIS_MONTH, Unit.RUPEES, m.current(), null));
            boolean comparable = built.baselineMonths().getOrDefault(m.category(), 0) >= MIN_BASELINE_MONTHS
                    && m.pctChange() != null;
            if (comparable) {
                facts.add(AiInsightFacts.fact(s.id(), "usual", Period.USUAL, Unit.RUPEES, m.priorAverage(), null));
                facts.add(AiInsightFacts.fact(s.id(), "change_pct", Period.THIS_MONTH, Unit.PERCENT,
                        BigDecimal.valueOf(m.pctChange()), null));
            }
        }

        if (insights.coverageCaveat() != null) {
            facts.add(AiInsightFacts.fact("month", "coverage_gap", Period.THIS_MONTH, Unit.COUNT, BigDecimal.ONE,
                    "statement gap"));
        }
        if (insights.unlinkedCardBills() != null && insights.unlinkedCardBills().count() > 0) {
            facts.add(AiInsightFacts.fact("month", "card_bills_count", Period.THIS_MONTH, Unit.COUNT,
                    BigDecimal.valueOf(insights.unlinkedCardBills().count()), "card bill"));
            facts.add(AiInsightFacts.fact("month", "card_bills_total", Period.THIS_MONTH, Unit.RUPEES,
                    insights.unlinkedCardBills().total(), null));
        }

        DashboardSummaryDto dashboard = dashboardService.summarize(userId);
        if (dashboard != null && sheetMonth.equals(dashboard.reportingMonth())) {
            facts.add(AiInsightFacts.fact("month", "money_in", Period.THIS_MONTH, Unit.RUPEES, dashboard.monthlyIncome(), null));
            facts.add(AiInsightFacts.fact("month", "money_out", Period.THIS_MONTH, Unit.RUPEES, dashboard.monthlyExpense(), null));
            if (dashboard.expenseDeltaPct() != null) {
                facts.add(AiInsightFacts.fact("month", "money_out_change_pct", Period.THIS_MONTH, Unit.PERCENT,
                        BigDecimal.valueOf(dashboard.expenseDeltaPct()), null));
            }
        }

        List<LookAtRowDto> lookAt = lookAtService.rows(userId, built);
        long toAnswer = lookAt.stream().filter(r -> "new-recurring".equals(r.kind())).count();
        long amountChanges = lookAt.stream().filter(r -> "amount-changed".equals(r.kind())).count();
        if (toAnswer > 0) {
            facts.add(AiInsightFacts.fact("month", "recurring_to_answer", Period.THIS_MONTH, Unit.COUNT,
                    BigDecimal.valueOf(toAnswer), "repeating payment"));
        }
        if (amountChanges > 0) {
            facts.add(AiInsightFacts.fact("month", "amount_changes", Period.THIS_MONTH, Unit.COUNT,
                    BigDecimal.valueOf(amountChanges), "repeating payment"));
        }

        // The same split as mobile's withPeople: family and settled balances are not listed.
        BigDecimal owedToYou = BigDecimal.ZERO;
        BigDecimal youOwe = BigDecimal.ZERO;
        for (PeopleDtos.PersonDto p : peopleService.people(userId)) {
            if (p.family() || p.owedToUser() == null) continue;
            if (p.owedToUser().signum() > 0) owedToYou = owedToYou.add(p.owedToUser());
            else if (p.owedToUser().signum() < 0) youOwe = youOwe.add(p.owedToUser().negate());
        }
        if (owedToYou.signum() > 0) {
            facts.add(AiInsightFacts.fact("month", "owed_to_you", Period.THIS_MONTH, Unit.RUPEES, owedToYou, null));
        }
        if (youOwe.signum() > 0) {
            facts.add(AiInsightFacts.fact("month", "you_owe", Period.THIS_MONTH, Unit.RUPEES, youOwe, null));
        }

        List<Row> rows = new ArrayList<>();
        for (LookAtRowDto r : lookAt) {
            String subjectId = "running-high".equals(r.kind()) ? subjects.forName(r.category()).id() : AiInsightFacts.MONTH.id();
            rows.add(new Row("row_" + (rows.size() + 1), r.key(), r.kind(), subjectId));
        }

        List<String> usualMonths = built.pipeline() == null ? List.of()
                : insightsService.moverBasis(built.pipeline()).priorMonths();
        return Optional.of(new FactSheet(sheetMonth, subjects.used(), facts, rows, usualMonths));
    }

    /**
     * The sheet's subjects: the month, then each category named by a fact or a row, built in or by token
     * (AiInsightFacts.categorySubject). A name that is a built-in category's is built in, even if a user
     * category shares it -- the name is the fixed taxonomy's either way. A name with no category row at
     * all ("Uncategorized") is built in.
     */
    static final class Subjects {
        private final Map<String, Category> byName = new LinkedHashMap<>();
        private final Map<UUID, String> tokens;
        private final Map<String, Subject> used = new LinkedHashMap<>();
        private final java.util.Set<String> ids = new java.util.HashSet<>();

        Subjects(List<Category> categories) {
            for (Category c : categories) {
                Category seen = byName.get(c.getName());
                if (seen == null || (!seen.isSystem() && c.isSystem())) byName.put(c.getName(), c);
            }
            this.tokens = AiInsightFacts.tokensFor(byName.values());
            used.put(AiInsightFacts.MONTH.displayName(), AiInsightFacts.MONTH);
            ids.add(AiInsightFacts.MONTH.id());
        }

        Subject forName(String name) {
            Subject existing = used.get(name);
            if (existing != null) return existing;
            Category c = byName.get(name);
            Subject s = c != null ? AiInsightFacts.categorySubject(c, tokens)
                    : new Subject(AiInsightFacts.slug(name), name, name);
            // Two different names can slug alike ("Food & Dining", "Food Dining"); keep ids unique.
            String id = s.id();
            for (int n = 2; ids.contains(id); n++) id = s.id() + "_" + n;
            if (!id.equals(s.id())) s = new Subject(id, s.displayName(), s.modelName());
            ids.add(id);
            used.put(name, s);
            return s;
        }

        List<Subject> used() {
            return List.copyOf(used.values());
        }
    }
}
