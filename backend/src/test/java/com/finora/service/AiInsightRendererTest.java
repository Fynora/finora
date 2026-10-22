package com.finora.service;

import com.finora.service.AiInsightFacts.FactSheet;
import com.finora.service.AiInsightFacts.Period;
import com.finora.service.AiInsightFacts.Subject;
import com.finora.service.AiInsightFacts.Unit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** AI insights foundation §5.1, §5.3, §8.1: templates become text with the server's own names and
 *  figures; the rules text for "Why?"; the basis line. Every name and amount is invented. */
class AiInsightRendererTest {

    private static AiInsightTemplate sentences(String... templates) {
        return new AiInsightTemplate(java.util.Arrays.stream(templates)
                .map(t -> new AiInsightTemplate.Sentence("OBSERVATION", t)).toList(), List.of());
    }

    @Test
    void rendersNamesAndFigures() {
        assertThat(AiInsightRenderer.render(sentences("{food} came to {food.this_month}, above {food.usual}."),
                AiInsightCheckerTest.MULTI)).isEqualTo("Food came to ₹6,200, above ₹5,000 in a usual month.");
    }

    @Test
    void sentencesAreJoinedWithOneSpace() {
        assertThat(AiInsightRenderer.render(sentences("Spending came to {month.total}.", "{insurance} was {insurance.this_month}."),
                AiInsightCheckerTest.MULTI)).isEqualTo("Spending came to ₹40,000. Insurance was ₹2,000.");
    }

    @Test
    void aUserCategoryRendersWithItsOwnName() {
        FactSheet sheet = new FactSheet("2026-09",
                List.of(AiInsightFacts.MONTH, new Subject("category_1", "Gym with Friend K", "[category-1]")),
                List.of(AiInsightFacts.fact("category_1", "this_month", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal("3000"), null)),
                List.of(), List.of());
        assertThat(AiInsightRenderer.render(sentences("{category_1} came to {category_1.this_month}."), sheet))
                .isEqualTo("Gym with Friend K came to ₹3,000.");
    }

    @Test
    void whyRulesTextShape() {
        FactSheet row = new FactSheet("2026-07", List.of(AiInsightFacts.MONTH, new Subject("dining", "Dining", "Dining")),
                List.of(AiInsightFacts.fact("dining", "payments_this_month", Period.THIS_MONTH, Unit.COUNT, new BigDecimal("14"), "payment"),
                        AiInsightFacts.fact("dining", "payments_usual", Period.USUAL, Unit.COUNT, new BigDecimal("6"), "payment"),
                        AiInsightFacts.fact("dining", "largest_1", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal("2400"), null)),
                List.of(), List.of("2026-04", "2026-05", "2026-06"));
        assertThat(AiInsightRenderer.whyRulesText(row))
                .isEqualTo("14 payments this month against 6 payments in a usual month; the largest was ₹2,400.");
    }

    @Test
    void whyRulesTextWithoutALargestPayment() {
        FactSheet row = new FactSheet("2026-07", List.of(AiInsightFacts.MONTH, new Subject("dining", "Dining", "Dining")),
                List.of(AiInsightFacts.fact("dining", "payments_this_month", Period.THIS_MONTH, Unit.COUNT, BigDecimal.ONE, "payment"),
                        AiInsightFacts.fact("dining", "payments_usual", Period.USUAL, Unit.COUNT, BigDecimal.ONE, "payment")),
                List.of(), List.of("2026-05", "2026-06"));
        assertThat(AiInsightRenderer.whyRulesText(row)).isEqualTo("1 payment this month against 1 payment in a usual month.");
    }

    @Test
    void basisNamesTheBaselineMonths() {
        assertThat(AiInsightRenderer.basisFor(AiInsightCheckerTest.MULTI))
                .isEqualTo("Compared with your usual month (Jun–Aug)");
        FactSheet oneMonth = new FactSheet("2026-09", List.of(AiInsightFacts.MONTH),
                List.of(AiInsightFacts.fact("month", "total", Period.THIS_MONTH, Unit.RUPEES, BigDecimal.TEN, null)),
                List.of(), List.of());
        assertThat(AiInsightRenderer.basisFor(oneMonth)).isEqualTo("This month only");
        FactSheet acrossTheYear = new FactSheet("2026-01", List.of(AiInsightFacts.MONTH, new Subject("food", "Food", "Food")),
                List.of(AiInsightFacts.fact("food", "usual", Period.USUAL, Unit.RUPEES, BigDecimal.TEN, null)),
                List.of(), List.of("2025-11", "2025-12"));
        assertThat(AiInsightRenderer.basisFor(acrossTheYear)).isEqualTo("Compared with your usual month (Nov–Dec)");
    }
}
