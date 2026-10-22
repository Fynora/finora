package com.finora.service;

import com.finora.dto.InsightsDto;
import com.finora.entity.Transaction;
import com.finora.service.AiInsightFacts.Fact;
import com.finora.service.AiInsightFacts.FactSheet;
import com.finora.service.AiInsightFacts.Period;
import com.finora.service.AiInsightFacts.Subject;
import com.finora.service.AiInsightFacts.Unit;
import com.finora.util.CounterpartyIdentity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The "Why?" fact sheet for one running-high category in one month
 * (docs/superpowers/specs/2026-10-22-ai-insights-foundation-design.md §4.2). Its rows are exactly the
 * ones the category's mover was summed from -- InsightsService's own MoverBasis, so shares, loans,
 * refund netting, the card-bill exclusion and the gap-month exclusion all apply -- and its totals are
 * the mover's own figures. Counts, averages, the largest payments (amount and day only), weekend
 * payments and new payees: never a payee's name.
 */
@Component
public class AiInsightRowSheetBuilder {

    private final InsightsService insightsService;

    public AiInsightRowSheetBuilder(InsightsService insightsService) {
        this.insightsService = insightsService;
    }

    /** Empty when {@code category} is not a mover in {@code month}, or its usual month holds fewer
     *  than 2 months (then "Why?" is not offered at all, §4.3). */
    public Optional<FactSheet> build(UUID userId, String month, String category) {
        InsightsService.Built built = insightsService.buildDetailed(userId, month);
        if (built.pipeline() == null) return Optional.empty();
        InsightsDto.CategoryMover mover = built.dto().movers().stream()
                .filter(m -> m.category().equals(category)).findFirst().orElse(null);
        if (mover == null || mover.pctChange() == null
                || built.baselineMonths().getOrDefault(category, 0) < AiInsightMonthSheetBuilder.MIN_BASELINE_MONTHS) {
            return Optional.empty();
        }

        InsightsService.MoverBasis basis = insightsService.moverBasis(built.pipeline());
        String current = basis.currentMonth();
        List<String> usualMonths = basis.usualMonths(category);
        BigDecimal usualCount = BigDecimal.valueOf(usualMonths.size());

        List<Transaction> thisMonth = new ArrayList<>();
        List<Transaction> usual = new ArrayList<>();
        Set<String> usualKeys = new HashSet<>();
        for (Transaction t : basis.moverTxns()) {
            if (!basis.categoryOf(t).equals(category)) continue;
            String m = YearMonth.from(t.getTxnDate()).toString();
            if (usualMonths.contains(m) && t.getCounterpartyKey() != null) usualKeys.add(t.getCounterpartyKey());
            if (basis.refunds().spendAmount(t).signum() <= 0) continue; // a payment, not a refund
            if (m.equals(current)) thisMonth.add(t);
            else if (usualMonths.contains(m)) usual.add(t);
        }

        AiInsightMonthSheetBuilder.Subjects subjects = new AiInsightMonthSheetBuilder.Subjects(built.categories());
        Subject s = subjects.forName(category);
        String id = s.id();
        List<Fact> facts = new ArrayList<>();
        facts.add(AiInsightFacts.fact(id, "this_month", Period.THIS_MONTH, Unit.RUPEES, mover.current(), null));
        facts.add(AiInsightFacts.fact(id, "usual", Period.USUAL, Unit.RUPEES, mover.priorAverage(), null));
        facts.add(AiInsightFacts.fact(id, "change_pct", Period.THIS_MONTH, Unit.PERCENT,
                BigDecimal.valueOf(mover.pctChange()), null));

        facts.add(AiInsightFacts.fact(id, "payments_this_month", Period.THIS_MONTH, Unit.COUNT,
                BigDecimal.valueOf(thisMonth.size()), "payment"));
        facts.add(AiInsightFacts.fact(id, "payments_usual", Period.USUAL, Unit.COUNT,
                perMonth(usual.size(), usualCount), "payment"));

        BigDecimal thisSum = sum(thisMonth, basis);
        BigDecimal usualSum = sum(usual, basis);
        if (!thisMonth.isEmpty()) {
            facts.add(AiInsightFacts.fact(id, "avg_payment_this_month", Period.THIS_MONTH, Unit.RUPEES,
                    thisSum.divide(BigDecimal.valueOf(thisMonth.size()), 2, RoundingMode.HALF_UP), null));
        }
        if (!usual.isEmpty()) {
            facts.add(AiInsightFacts.fact(id, "avg_payment_usual", Period.USUAL, Unit.RUPEES,
                    usualSum.divide(BigDecimal.valueOf(usual.size()), 2, RoundingMode.HALF_UP), null));
        }

        List<Transaction> largest = thisMonth.stream()
                .sorted(Comparator.comparing((Transaction t) -> basis.refunds().spendAmount(t)).reversed()
                        .thenComparing(Transaction::getTxnDate))
                .limit(3).toList();
        for (int i = 0; i < largest.size(); i++) {
            Transaction t = largest.get(i);
            facts.add(AiInsightFacts.fact(id, "largest_" + (i + 1), Period.THIS_MONTH, Unit.RUPEES,
                    basis.refunds().spendAmount(t), null));
            facts.add(AiInsightFacts.fact(id, "largest_" + (i + 1) + "_day", Period.THIS_MONTH, Unit.DAY,
                    BigDecimal.valueOf(t.getTxnDate().getDayOfMonth()), null));
        }

        facts.add(AiInsightFacts.fact(id, "weekend_payments_this_month", Period.THIS_MONTH, Unit.COUNT,
                BigDecimal.valueOf(thisMonth.stream().filter(AiInsightRowSheetBuilder::onAWeekend).count()), "payment"));
        facts.add(AiInsightFacts.fact(id, "weekend_payments_usual", Period.USUAL, Unit.COUNT,
                perMonth(usual.stream().filter(AiInsightRowSheetBuilder::onAWeekend).count(), usualCount), "payment"));

        // New: a payee with no row in this category's usual months. Only a key that names one payee
        // counts -- a gateway's id is many shops (CounterpartyIdentity.identifiesOnePayee).
        List<Transaction> fromNewPayees = thisMonth.stream()
                .filter(t -> t.getCounterpartyKey() != null
                        && CounterpartyIdentity.identifiesOnePayee(t.getCounterpartyKey())
                        && !usualKeys.contains(t.getCounterpartyKey()))
                .toList();
        long newPayees = fromNewPayees.stream().map(Transaction::getCounterpartyKey).distinct().count();
        if (newPayees > 0) {
            facts.add(AiInsightFacts.fact(id, "new_payees_count", Period.THIS_MONTH, Unit.COUNT,
                    BigDecimal.valueOf(newPayees), "new payee"));
            facts.add(AiInsightFacts.fact(id, "new_payees_total", Period.THIS_MONTH, Unit.RUPEES,
                    sum(fromNewPayees, basis), null));
        }

        return Optional.of(new FactSheet(current, subjects.used(), facts, List.of()));
    }

    private static BigDecimal sum(List<Transaction> txns, InsightsService.MoverBasis basis) {
        return txns.stream().map(basis.refunds()::spendAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** A usual-month count: the average over the usual months, to a whole number. */
    private static BigDecimal perMonth(long total, BigDecimal months) {
        return BigDecimal.valueOf(total).divide(months, 0, RoundingMode.HALF_UP);
    }

    private static boolean onAWeekend(Transaction t) {
        DayOfWeek d = t.getTxnDate().getDayOfWeek();
        return d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY;
    }
}
