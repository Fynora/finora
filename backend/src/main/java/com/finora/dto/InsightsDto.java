package com.finora.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record InsightsDto(
        List<String> sentences,
        List<CategoryMover> movers,
        CoverageCaveat coverageCaveat,
        CategoryHighlight biggestCategory,
        MerchantHighlight topMerchant
) {
    public record CategoryMover(String category, BigDecimal current, BigDecimal priorAverage, Double pctChange) {}

    // Structured twins of the "%s was your biggest category at ₹%,.0f." / "Your top merchant ...
    // was \"%s\" at ₹%,.0f." sentences InsightsService already builds -- same values, just not
    // only baked into prose, so a client can render a tappable, iconed row instead of parsing a
    // string. Null when the underlying Optional was empty (no data this month), same "degrade to
    // nothing" shape as every other optional field this DTO returns.
    public record CategoryHighlight(String name, BigDecimal amount) {}
    public record MerchantHighlight(String name, BigDecimal amount) {}

    /**
     * Phase 3 of docs/proposals/statement-continuity-and-coverage-integrity-proposal.md (§0.5/§8)
     * -- populated only when the current reporting month intersects a known coverage gap on any of
     * the user's live accounts, null otherwise. Internal/API-only for now, per §0.5: no UI reads
     * this field yet, but the shape exists now rather than being added later as a breaking change,
     * so a future feature never has to reinvent "does Insights know this month might be incomplete."
     */
    public record CoverageCaveat(String month, List<GapWindow> gaps) {
        public record GapWindow(LocalDate gapStart, LocalDate gapEnd) {}
    }
}
